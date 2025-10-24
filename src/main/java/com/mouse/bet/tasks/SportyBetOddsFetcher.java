package com.mouse.bet.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import com.mouse.bet.config.ScraperConfig;
import com.mouse.bet.detector.ArbDetector;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.manager.ProfileManager;
import com.mouse.bet.model.NormalizedEvent;
import com.mouse.bet.model.profile.UserAgentProfile;
import com.mouse.bet.model.sporty.SportyEvent;
import com.mouse.bet.service.BetLegRetryService;
import com.mouse.bet.service.SportyBetService;
import com.mouse.bet.utils.JsonParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Component
public class SportyBetOddsFetcher implements Runnable {
    private final ScraperConfig scraperConfig;
    private final ProfileManager profileManager;
    private final SportyBetService sportyBetService;
    private final BetLegRetryService betLegRetryService;
    private final ArbDetector arbDetector;
    private final ObjectMapper objectMapper;

    private final AtomicReference<Playwright> playwrightRef = new AtomicReference<>();

    private static final String BASE_URL = "https://www.sportybet.com";
    private static final String SPORT_PAGE = BASE_URL + "/ng";
    private static final int CONTEXT_NAV_MAX_RETRIES = 3;
    private static final int API_MAX_RETRIES = 2; // Reduced for faster failure
    private static final long SCHEDULER_PERIOD_SEC = 3;
    private static final String KEY_FB = "sport:1";
    private static final String KEY_BB = "sport:2";
    private static final String KEY_TT = "sport:20";
    private static final BookMaker SCRAPER_BOOKMAKER = BookMaker.SPORTY_BET;

    // Thread pools - sized for high concurrency
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private final ExecutorService eventDetailExecutor = Executors.newFixedThreadPool(30); // Large pool for event details
    private final ExecutorService processingExecutor = Executors.newFixedThreadPool(10); // For parsing/normalizing
    private final ExecutorService retryExecutor = Executors.newFixedThreadPool(5);

    // Thread-local API clients (solves the "Cannot find command" issue)
    private final ThreadLocal<Map<String, APIRequestContext>> threadLocalClients =
            ThreadLocal.withInitial(HashMap::new);
    private volatile boolean clientsNeedRefresh = false;

    // Tracking
    private final AtomicInteger activeDetailFetches = new AtomicInteger(0);
    private final Map<String, Long> lastFetchTime = new ConcurrentHashMap<>();

    // Collected headers/tokens after successful nav
    private final Map<String, String> harvestedHeaders = new ConcurrentHashMap<>();
    private final AtomicReference<String> cookieHeaderRef = new AtomicReference<>("");

    private volatile UserAgentProfile profile;
    private final AtomicBoolean schedulesStarted = new AtomicBoolean();
    private final AtomicBoolean isRunning = new AtomicBoolean(true);

    @Override
    public void run() {
        log.info("=== Starting SportyBetOddsFetcher (3-sec cadence mode) ===");
        log.info("Bookmaker: {}, Base URL: {}", SCRAPER_BOOKMAKER, BASE_URL);

        playwrightRef.set(Playwright.create());
        log.info("Playwright instance created");

        try {
            // Initial setup cycle
            performInitialSetup();

            // Start schedulers
            startSchedulers();

            // Keep running and refresh session periodically
            while (isRunning.get()) {
                Thread.sleep(Duration.ofMinutes(10).toMillis());
                log.info("Active detail fetches: {}", activeDetailFetches.get());

                // Optional: Refresh session if needed
                if (shouldRefreshSession()) {
                    log.info("Refreshing session...");
                    performInitialSetup();
                }
            }

        } catch (Exception fatal) {
            log.error("Fatal error in fetcher", fatal);
        } finally {
            cleanup();
        }
    }

    private void performInitialSetup() {
        log.info("=== Performing initial setup ===");

        Playwright pw = playwrightRef.get();
        try (Browser browser = launchBrowser(pw)) {
            profile = profileManager.getNextProfile();
            log.info("Profile: UA={}", profile.getUserAgent().substring(0, 50));

            try (BrowserContext context = newContext(browser, profile)) {
                attachAntiDetection(context, profile);

                Page page = context.newPage();
                Map<String, String> captured = new HashMap<>();

                attachNetworkTaps(page, captured);
                performInitialNavigationWithRetry(page);

                String cookieHeader = formatCookies(context.cookies());
                cookieHeaderRef.set(cookieHeader);

                harvestedHeaders.clear();
                harvestedHeaders.putAll(captured);

                log.info("Setup complete - harvested {} headers, cookies: {} bytes",
                        captured.size(), cookieHeader.length());

                page.close();
            }
        } catch (Exception e) {
            log.error("Setup failed: {}", e.getMessage(), e);
            throw new RuntimeException("Initial setup failed", e);
        }

        // Mark clients for refresh
        clientsNeedRefresh = true;
    }

    private void startSchedulers() {
        if (!schedulesStarted.compareAndSet(false, true)) {
            log.info("Schedulers already started");
            return;
        }

        log.info("Starting schedulers with {}s cadence", SCHEDULER_PERIOD_SEC);

        // Stagger the start times to avoid simultaneous hits
        scheduler.scheduleAtFixedRate(
                () -> safeWrapper("Football", this::callFootball),
                0, SCHEDULER_PERIOD_SEC, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(
                () -> safeWrapper("Basketball", this::callBasketball),
                1, SCHEDULER_PERIOD_SEC, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(
                () -> safeWrapper("TableTennis", this::callTableTennis),
                2, SCHEDULER_PERIOD_SEC, TimeUnit.SECONDS);
    }

    private void safeWrapper(String sportName, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.error("{} fetch error: {}", sportName, e.getMessage());
        }
    }

    // ==================== ASYNC SPORT FETCH METHODS ====================

    private void callFootball() {
        fetchSportEvents("Football", "sr:sport:1", KEY_FB);
    }

    private void callBasketball() {
        fetchSportEvents("Basketball", "sr:sport:2", KEY_BB);
    }

    private void callTableTennis() {
        fetchSportEvents("Table Tennis", "sr:sport:20", KEY_TT);
    }

    /**
     * Generic async sport fetch - returns immediately after dispatching tasks
     */
    private void fetchSportEvents(String sportName, String sportId, String clientKey) {
        long start = System.currentTimeMillis();

        try {
            // Quick check: don't overwhelm if too many active fetches
            int active = activeDetailFetches.get();
            if (active > 200) {
                log.warn("{} skipped - too many active fetches: {}", sportName, active);
                return;
            }

            String url = buildUrl(BASE_URL + "/api/ng/factsCenter/liveOrPrematchEvents",
                    Map.of("sportId", sportId, "_t", String.valueOf(System.currentTimeMillis())));

            String body = safeApiGet(url, clientKey);

            if (body == null || body.isEmpty()) {
                log.warn("{} - empty response", sportName);
                return;
            }

            List<String> eventIds = extractEventIds(body);

            if (eventIds == null || eventIds.isEmpty()) {
                log.debug("{} - no events", sportName);
                return;
            }

            // ASYNC: Dispatch all event detail fetches immediately
            int dispatched = 0;
            for (String eventId : eventIds) {
                // Skip if recently fetched (within last 2 seconds)
                Long lastFetch = lastFetchTime.get(eventId);
                if (lastFetch != null && (System.currentTimeMillis() - lastFetch) < 2000) {
                    continue;
                }

                lastFetchTime.put(eventId, System.currentTimeMillis());
                activeDetailFetches.incrementAndGet();
                dispatched++;

                // Submit to executor - don't wait for result
                eventDetailExecutor.submit(() -> {
                    try {
                        fetchAndProcessEventDetailAsync(eventId, clientKey);
                    } finally {
                        activeDetailFetches.decrementAndGet();
                    }
                });
            }

            long duration = System.currentTimeMillis() - start;
            log.info("{}: dispatched {}/{} events in {}ms [active: {}]",
                    sportName, dispatched, eventIds.size(), duration, activeDetailFetches.get());

        } catch (Exception e) {
            log.error("{} fetch failed: {}", sportName, e.getMessage());
        }
    }

    /**
     * ASYNC event detail fetch - uses thread-local client
     */
    private void fetchAndProcessEventDetailAsync(String eventId, String clientKey) {
        try {
            String url = buildEventDetailUrl(eventId);
            String body = safeApiGet(url, clientKey);

            if (body == null || body.isBlank()) {
                log.debug("Empty detail for eventId={}", eventId);
                return;
            }

            // ASYNC: Submit parsing/processing to separate executor
            processingExecutor.submit(() -> {
                try {
                    SportyEvent domainEvent = parseEventDetail(body);
                    if (domainEvent != null) {
                        processParsedEvent(domainEvent);
                    }
                } catch (Exception ex) {
                    log.debug("Process failed for eventId={}: {}", eventId, ex.getMessage());
                }
            });

        } catch (Exception e) {
            log.debug("Detail fetch failed for eventId={}: {}", eventId, e.getMessage());
        } finally {
            // Cleanup thread-local client after use
            cleanupThreadLocalClients();
        }
    }

    // ==================== THREAD-LOCAL CLIENT MANAGEMENT ====================

    private APIRequestContext getThreadLocalClient(String clientKey) {
        Map<String, APIRequestContext> localClients = threadLocalClients.get();

        if (clientsNeedRefresh) {
            localClients.values().forEach(c -> {
                try { c.dispose(); } catch (Exception ignored) {}
            });
            localClients.clear();
            clientsNeedRefresh = false;
        }

        if (!localClients.containsKey(clientKey)) {
            Playwright pw = playwrightRef.get();
            if (pw == null) {
                throw new IllegalStateException("Playwright not available");
            }

            APIRequestContext newClient = pw.request().newContext(
                    new APIRequest.NewContextOptions()
                            .setExtraHTTPHeaders(buildHeaders(cookieHeaderRef.get(), harvestedHeaders, profile))
                            .setTimeout(15_000) // 15 second timeout
            );

            localClients.put(clientKey, newClient);
        }

        return localClients.get(clientKey);
    }

    private void cleanupThreadLocalClients() {
        Map<String, APIRequestContext> localClients = threadLocalClients.get();
        if (localClients != null && !localClients.isEmpty()) {
            localClients.values().forEach(c -> {
                try { c.dispose(); } catch (Exception ignored) {}
            });
            localClients.clear();
        }
        threadLocalClients.remove();
    }

    /**
     * Thread-safe API GET with automatic retry on auth errors
     */
    private String safeApiGet(String url, String clientKey) {
        return safeApiGet(url, clientKey, 0);
    }

    private String safeApiGet(String url, String clientKey, int authRetryCount) {
        if (authRetryCount > 1) {
            throw new PlaywrightException("Max auth retries exceeded");
        }

        try {
            APIRequestContext client = getThreadLocalClient(clientKey);
            APIResponse res = client.get(url);
            int status = res.status();

            if (status == 401 || status == 403) {
                log.debug("Auth {} for {}, retrying", status, url);
                clientsNeedRefresh = true;
                Thread.sleep(300);
                return safeApiGet(url, clientKey, authRetryCount + 1);
            }

            String body = safeBody(res);

            if (status < 200 || status >= 300) {
                throw new PlaywrightException("HTTP " + status + " on " + url);
            }

            return body;

        } catch (PlaywrightException e) {
            if (e.getMessage().contains("Cannot find command") && authRetryCount == 0) {
                log.debug("Command error, retrying");
                cleanupThreadLocalClients();
                return safeApiGet(url, clientKey, authRetryCount + 1);
            }
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted", e);
        }
    }

    // ==================== PROCESSING & NORMALIZATION ====================

    private SportyEvent parseEventDetail(String detailJson) {
        return JsonParser.deserializeSportyEvent(detailJson, objectMapper);
    }

    private void processParsedEvent(SportyEvent event) {
        if (event == null) return;

        try {
            NormalizedEvent normalizedEvent = sportyBetService.convertToNormalEvent(event);

            if (normalizedEvent == null) {
                return;
            }

            // ASYNC: Bet retry processing
            CompletableFuture.runAsync(
                    () -> processBetRetryInfo(normalizedEvent),
                    retryExecutor
            ).exceptionally(ex -> {
                log.debug("Retry processing failed for {}: {}",
                        normalizedEvent.getEventId(), ex.getMessage());
                return null;
            });

            // Sync: Add to arb detector
            if (arbDetector != null) {
                arbDetector.addEventToPool(normalizedEvent);
            }

        } catch (Exception e) {
            log.debug("Process event failed for {}: {}",
                    event.getEventId(), e.getMessage());
        }
    }

    private void processBetRetryInfo(NormalizedEvent normalizedEvent) {
        try {
            betLegRetryService.updateFailedBetLeg(normalizedEvent, SCRAPER_BOOKMAKER);
        } catch (Exception e) {
            log.debug("Bet retry failed for {}: {}",
                    normalizedEvent.getEventId(), e.getMessage());
        }
    }

    // ==================== BROWSER SETUP METHODS ====================

    private Browser launchBrowser(Playwright pw) {
        return pw.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(scraperConfig.getBROWSER_FlAGS()));
    }

    private BrowserContext newContext(Browser browser, UserAgentProfile profile) {
        ViewportSize viewportSize = new ViewportSize(
                profile.getViewport().getWidth(),
                profile.getViewport().getHeight()
        );

        return browser.newContext(new Browser.NewContextOptions()
                .setUserAgent(profile.getUserAgent())
                .setViewportSize(viewportSize)
                .setExtraHTTPHeaders(getAllHeaders(profile)));
    }

    public Map<String, String> getAllHeaders(UserAgentProfile profile) {
        Map<String, String> allHeaders = new HashMap<>();

        Map<String, String> standardHeaders = profile.getHeaders().getStandardHeaders();
        Map<String, String> clientHintsHeaders = profile.getHeaders().getClientHintsHeaders();

        if (standardHeaders != null) allHeaders.putAll(standardHeaders);
        if (clientHintsHeaders != null) allHeaders.putAll(clientHintsHeaders);

        return allHeaders;
    }

    private void attachAntiDetection(BrowserContext context, UserAgentProfile profile) {
        log.info("Injecting anti-detection stealth script");
        log.debug("Profile details - Platform: {}, Languages: {}, Hardware: {} cores",
                profile.getPlatform(),
                profile.getLanguages(),
                profile.getHardwareConcurrency());

        String stealthScript = String.format("""
        // === Profile Configuration ===
        const profile = %s;

        // === Remove Automation Indicators ===
        Object.defineProperty(navigator, 'webdriver', {
            get: () => undefined,
            configurable: true
        });
        
        delete navigator.__proto__.webdriver;
        window.chrome = { runtime: {} };

        // === Override Client Hints ===
        Object.defineProperty(navigator, 'userAgentData', {
            get: () => ({
                brands: %s,
                mobile: %s,
                platform: "%s",
                platformVersion: "%s",
                architecture: "%s",
                bitness: "%s",
                model: "%s",
                uaFullVersion: "%s",
                getHighEntropyValues: function(hints) {
                    return new Promise((resolve) => {
                        const result = {};
                        if (hints.includes('architecture')) result.architecture = profile.clientHints.architecture;
                        if (hints.includes('bitness')) result.bitness = profile.clientHints.bitness;
                        if (hints.includes('model')) result.model = profile.clientHints.model;
                        if (hints.includes('platformVersion')) result.platformVersion = profile.clientHints.platformVersion;
                        if (hints.includes('uaFullVersion')) result.uaFullVersion = profile.clientHints.uaFullVersion;
                        if (hints.includes('fullVersionList')) {
                            result.fullVersionList = profile.clientHints.brands.map(brand => ({
                                brand: brand.brand,
                                version: brand.version + ".0.0.0"
                            }));
                        }
                        resolve(result);
                    });
                }
            }),
            configurable: true
        });

        // === Canvas Fingerprinting Protection ===
        const originalGetContext = HTMLCanvasElement.prototype.getContext;
        HTMLCanvasElement.prototype.getContext = function(contextType, ...args) {
            if (contextType === '2d') {
                const context = originalGetContext.call(this, contextType, ...args);
                if (context) {
                    // Set fill style from profile
                    context.fillStyle = profile.canvasFillStyle;
                    
                    // Override toDataURL to add noise
                    const originalToDataURL = context.canvas.toDataURL;
                    context.canvas.toDataURL = function(type, quality) {
                        const imageData = context.getImageData(0, 0, this.width, this.height);
                        // Add minimal random noise to fingerprint
                        for (let i = 0; i < imageData.data.length; i += 10) {
                            imageData.data[i] = imageData.data[i] + Math.floor(Math.random() * 2);
                        }
                        context.putImageData(imageData, 0, 0);
                        return originalToDataURL.call(this, type, quality);
                    };
                    
                    // Override getImageData
                    const originalGetImageData = context.getImageData;
                    context.getImageData = function(...args) {
                        const imageData = originalGetImageData.call(this, ...args);
                        // Add slight variation
                        for (let i = 0; i < imageData.data.length; i += 100) {
                            imageData.data[i] = imageData.data[i] ^ 1;
                        }
                        return imageData;
                    };
                }
                return context;
            }
            return originalGetContext.call(this, contextType, ...args);
        };

        // === WebGL Fingerprinting Protection ===
        const getParameterProxy = function(originalFunction) {
            return function(parameter) {
                // Return WebGL values from profile
                if (parameter === 37445) { // UNMASKED_VENDOR_WEBGL
                    return profile.webglVendor;
                }
                if (parameter === 37446) { // UNMASKED_RENDERER_WEBGL
                    return profile.webglRenderer;
                }
                if (parameter === 7936) { // VENDOR
                    return profile.webgl.vendor;
                }
                if (parameter === 7937) { // RENDERER
                    return profile.webgl.renderer;
                }
                if (parameter === 7938) { // VERSION
                    return profile.webgl.version;
                }
                return originalFunction.call(this, parameter);
            };
        };

        const getExtensionProxy = function(originalFunction) {
            return function(extensionName) {
                const result = originalFunction.call(this, extensionName);
                if (result && typeof result.getParameter === 'function') {
                    result.getParameter = getParameterProxy(result.getParameter);
                }
                return result;
            };
        };

        Object.defineProperty(WebGLRenderingContext.prototype, 'getParameter', {
            value: getParameterProxy(WebGLRenderingContext.prototype.getParameter),
            configurable: true
        });

        Object.defineProperty(WebGLRenderingContext.prototype, 'getExtension', {
            value: getExtensionProxy(WebGLRenderingContext.prototype.getExtension),
            configurable: true
        });

        // === Audio Context Fingerprinting ===
        const originalCreateOscillator = OfflineAudioContext.prototype.createOscillator;
        OfflineAudioContext.prototype.createOscillator = function() {
            const oscillator = originalCreateOscillator.call(this);
            // Use frequency from profile
            oscillator.frequency.value = profile.audioFrequency;
            
            const originalStart = oscillator.start;
            oscillator.start = function(when) {
                return originalStart.call(this, when + (Math.random() * 0.0001));
            };
            return oscillator;
        };

        // === Hardware Concurrency & Device Memory ===
        Object.defineProperty(navigator, 'hardwareConcurrency', {
            get: () => profile.hardwareConcurrency,
            configurable: true
        });

        Object.defineProperty(navigator, 'deviceMemory', {
            get: () => profile.deviceMemory,
            configurable: true
        });

        // === Platform & Language Spoofing ===
        Object.defineProperty(navigator, 'platform', {
            get: () => profile.platform,
            configurable: true
        });

        Object.defineProperty(navigator, 'language', {
            get: () => profile.languages[0],
            configurable: true
        });

        Object.defineProperty(navigator, 'languages', {
            get: () => profile.languages,
            configurable: true
        });

        // === Plugin Spoofing ===
        Object.defineProperty(navigator, 'plugins', {
            get: () => profile.plugins,
            configurable: true
        });

        Object.defineProperty(navigator, 'mimeTypes', {
            get: () => profile.plugins.map(plugin => ({
                type: 'application/' + plugin.name.toLowerCase().replace(/\\s+/g, ''),
                suffixes: plugin.name.toLowerCase().includes('pdf') ? 'pdf' : 'exe',
                description: plugin.description,
                enabledPlugin: plugin
            })),
            configurable: true
        });

        // === Screen Resolution Spoofing ===
        Object.defineProperty(screen, 'width', {
            get: () => profile.viewport.width,
            configurable: true
        });

        Object.defineProperty(screen, 'height', {
            get: () => profile.viewport.height,
            configurable: true
        });

        Object.defineProperty(screen, 'availWidth', {
            get: () => profile.screen.availWidth,
            configurable: true
        });

        Object.defineProperty(screen, 'availHeight', {
            get: () => profile.screen.availHeight,
            configurable: true
        });

        Object.defineProperty(screen, 'colorDepth', {
            get: () => profile.screen.colorDepth,
            configurable: true
        });

        Object.defineProperty(screen, 'pixelDepth', {
            get: () => profile.screen.pixelDepth,
            configurable: true
        });

        // === Connection API Spoofing ===
        if ('connection' in navigator) {
            Object.defineProperty(navigator.connection, 'downlink', {
                get: () => profile.connection.downlink,
                configurable: true
            });

            Object.defineProperty(navigator.connection, 'effectiveType', {
                get: () => profile.connection.effectiveType,
                configurable: true
            });

            Object.defineProperty(navigator.connection, 'rtt', {
                get: () => profile.connection.rtt,
                configurable: true
            });
        }

        // === Battery API Spoofing ===
        if ('getBattery' in navigator) {
            const originalGetBattery = navigator.getBattery;
            navigator.getBattery = function() {
                return Promise.resolve(profile.battery);
            };
        }

        // === Timezone Spoofing ===
        const originalGetTimezoneOffset = Date.prototype.getTimezoneOffset;
        Date.prototype.getTimezoneOffset = function() {
            // Calculate offset based on profile timezone
            const now = new Date();
            const tzString = now.toLocaleString('en-US', { timeZone: profile.timeZone });
            const localDate = new Date(tzString);
            const diff = (now.getTime() - localDate.getTime()) / 60000;
            return Math.round(diff);
        };

        // === Geolocation Spoofing ===
        if ('geolocation' in navigator) {
            const originalGetCurrentPosition = navigator.geolocation.getCurrentPosition;
            navigator.geolocation.getCurrentPosition = function(success, error, options) {
                if (success) {
                    success({
                        coords: {
                            latitude: profile.geolocation.latitude,
                            longitude: profile.geolocation.longitude,
                            accuracy: profile.geolocation.accuracy,
                            altitude: null,
                            altitudeAccuracy: null,
                            heading: null,
                            speed: null
                        },
                        timestamp: Date.now()
                    });
                }
            };
        }

        // === Permissions API Spoofing ===
        const originalPermissionsQuery = navigator.permissions.query;
        navigator.permissions.query = function(parameters) {
            if (parameters.name === 'notifications') {
                return Promise.resolve({ state: profile.permissions.notifications });
            }
            if (parameters.name === 'geolocation') {
                return Promise.resolve({ state: profile.permissions.geolocation });
            }
            return originalPermissionsQuery.call(this, parameters);
        };

        // === Media Devices Spoofing ===
        if ('mediaDevices' in navigator) {
            const originalEnumerateDevices = navigator.mediaDevices.enumerateDevices;
            navigator.mediaDevices.enumerateDevices = function() {
                return Promise.resolve(profile.mediaDevices);
            };
        }

        // === Font Detection Protection ===
        if (window.queryLocalFonts) {
            window.queryLocalFonts = function() {
                return Promise.resolve(profile.fonts.map(font => ({ 
                    family: font,
                    fullName: font,
                    postscriptName: font.replace(/\\\\s+/g, '')
                })));
            };
        }

        // === Storage Quota Spoofing ===
        if ('storage' in navigator && 'estimate' in navigator.storage) {
            const originalEstimate = navigator.storage.estimate;
            navigator.storage.estimate = function() {
                return Promise.resolve({
                    quota: profile.storage.quota,
                    usage: profile.storage.usage,
                    usageDetails: {}
                });
            };
        }

        // === Notification API Spoofing ===
        if ('Notification' in window) {
            Object.defineProperty(Notification, 'permission', {
                get: () => profile.permissions.notifications,
                configurable: true
            });
        }

        // Final cleanup
        delete window.$cdc_;
        delete window._Selenium_IDE_Recorder;
    """,
                new Gson().toJson(profile), // Full profile object
                new Gson().toJson(profile.getClientHints().getBrands()),
                profile.getClientHints().getMobile(),
                profile.getClientHints().getPlatform(),
                profile.getClientHints().getPlatformVersion(),
                profile.getClientHints().getArchitecture(),
                profile.getClientHints().getBitness(),
                profile.getClientHints().getModel(),
                profile.getClientHints().getUaFullVersion()
        );

        context.addInitScript(stealthScript);
        log.info("Stealth script injected successfully (script length: {} chars)", stealthScript.length());
    }
    private void attachNetworkTaps(Page page, Map<String, String> store) {
        page.onResponse(resp -> {
            String url = resp.url();
            int status = resp.status();

            if ((url.contains("prematch") || url.contains("odds")) && status >= 200 && status < 400) {
                Map<String, String> headers = resp.headers();
                headers.forEach((k, v) -> {
                    String key = k.toLowerCase(Locale.ROOT);
                    if (key.equals("authorization") || key.equals("x-csrf-token") || key.equals("set-cookie")) {
                        store.put(key, v);
                    }
                });
            }
        });
    }

    private void performInitialNavigationWithRetry(Page page) {
        int attempt = 0;
        while (true) {
            try {
                page.navigate(SPORT_PAGE, new Page.NavigateOptions()
                        .setTimeout(45_000)
                        .setWaitUntil(WaitUntilState.NETWORKIDLE));

                page.waitForSelector("body", new Page.WaitForSelectorOptions().setTimeout(10_000));
                return;

            } catch (PlaywrightException e) {
                if (attempt++ >= CONTEXT_NAV_MAX_RETRIES - 1) {
                    throw e;
                }
                backoffWithJitter(attempt);
            }
        }
    }

    private String formatCookies(List<Cookie> cookies) {
        return cookies.stream()
                .map(c -> c.name + "=" + c.value)
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    // ==================== UTILITY METHODS ====================

    private Map<String, String> buildHeaders(String cookieHeader, Map<String, String> harvested, UserAgentProfile profile) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", profile.getUserAgent());
        h.put("Referer", SPORT_PAGE);
        h.put("Cookie", cookieHeader);
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/json");
        h.put("X-Requested-With", "XMLHttpRequest");
        if (harvested != null) h.putAll(harvested);
        return h;
    }

    private List<String> extractEventIds(String body) {
        return JsonParser.extractEventIds(body);
    }

    private String buildEventDetailUrl(String eventId) {
        String base = BASE_URL + "/api/ng/factsCenter/event";
        return buildUrl(base, Map.of(
                "eventId", eventId,
                "productId", "1",
                "_t", String.valueOf(System.currentTimeMillis())
        ));
    }

    private String buildUrl(String base, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(base);
        if (params != null && !params.isEmpty()) {
            sb.append("?");
            sb.append(params.entrySet().stream()
                    .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .collect(Collectors.joining("&")));
        }
        return sb.toString();
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String safeBody(APIResponse res) {
        try {
            return res.text();
        } catch (Exception e) {
            return null;
        }
    }

    private void backoffWithJitter(int attempt) {
        long delay = 200L + ThreadLocalRandom.current().nextLong(50, 150);
        try {
            Thread.sleep(Math.min(delay, 500L));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean shouldRefreshSession() {
        // Refresh every 10 minutes if needed
        return false; // Implement your logic
    }

    // ==================== CLEANUP ====================

    private void cleanup() {
        log.info("=== Shutting down SportyBetOddsFetcher ===");

        isRunning.set(false);

        scheduler.shutdownNow();
        eventDetailExecutor.shutdownNow();
        processingExecutor.shutdownNow();
        retryExecutor.shutdownNow();

        threadLocalClients.remove();

        Playwright pw = playwrightRef.getAndSet(null);
        if (pw != null) {
            pw.close();
        }

        log.info("=== Shutdown complete ===");
    }
}