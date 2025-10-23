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
    private final Map<String, APIRequestContext> apiClients = new ConcurrentHashMap<>();
    private final AtomicReference<Playwright> playwrightRef = new AtomicReference<>();

    private static final String BASE_URL   = "https://www.sportybet.com";
    private static final String SPORT_PAGE = BASE_URL + "/ng";
    private static final int CONTEXT_NAV_MAX_RETRIES = 3;
    private static final int API_MAX_RETRIES         = 3;
    private static final long SCHEDULER_PERIOD_SEC   = 45;
    private static final String KEY_FB  = "sport:1";
    private static final String KEY_BB  = "sport:2";
    private static final String KEY_TT  = "sport:20";
    private static final BookMaker SCRAPER_BOOKMAKER = BookMaker.SPORTY_BET;

    // Schedulers
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
    private final ExecutorService retryExecutor = Executors.newFixedThreadPool(2);

    // Collected headers/tokens after a successful nav
    private final Map<String, String> harvestedHeaders = new ConcurrentHashMap<>();
    private final AtomicReference<String> cookieHeaderRef = new AtomicReference<>("");

    private UserAgentProfile profile;


    @Override
    public void run() {
        log.info("=== Starting SportyBetOddsFetcher ===");
        log.info("Bookmaker: {}, Base URL: {}", SCRAPER_BOOKMAKER, BASE_URL);
        log.info("Initializing playwright ...");

        playwrightRef.set(Playwright.create());
        log.info("Playwright instance created successfully");

        try {
            int cycleCount = 0;
            while (true) {
                cycleCount++;
                log.info("=== Starting fetch cycle #{} ===", cycleCount);

                Playwright pw = playwrightRef.get();
                try (Browser browser = launchBrowser(pw)) {
                    log.info("Browser launched successfully for cycle #{}", cycleCount);

                    profile = profileManager.getNextProfile();
                    log.info("Retrieved profile: UserAgent={}, Viewport={}x{}",
                            profile.getUserAgent(),
                            profile.getViewport().getWidth(),
                            profile.getViewport().getHeight());

                    try (BrowserContext context = newContext(browser, profile)) {
                        log.info("Browser context created with custom headers");

                        attachAntiDetection(context, profile);
                        log.info("Anti-detection scripts injected into context");

                        Page page = context.newPage();
                        log.info("New page created, attaching network listeners");

                        attachNetworkTaps(page, harvestedHeaders);
                        log.info("Network response listeners attached");

                        performInitialNavigationWithRetry(page);
                        log.info("Initial navigation completed successfully");

                        String cookieHeader = formatCookies(context.cookies());
                        cookieHeaderRef.set(cookieHeader);
                        log.info("Cookies formatted and stored (length: {})", cookieHeader.length());

                        log.info("Harvested headers count: {}", harvestedHeaders.size());
                        harvestedHeaders.forEach((k, v) ->
                                log.debug("Harvested header: {} = {}", k, v.substring(0, Math.min(50, v.length())))
                        );

                        page.close();
                        log.debug("Page closed");
                        context.close();
                        log.debug("Browser context closed");

                        rebuildClients(pw, cookieHeader, harvestedHeaders, profile);
                        log.info("API clients rebuilt: {}", apiClients.keySet());

                        startOrRefreshSchedules();
                        log.info("Schedulers started/refreshed - Period: {}s", SCHEDULER_PERIOD_SEC);

                        long sleepDuration = Duration.ofMinutes(10).toMillis();
                        log.info("Sleeping for {} minutes before next cycle", sleepDuration / 60000);
                        Thread.sleep(sleepDuration);

                    } catch (PlaywrightException e) {
                        log.warn("Context block failed in cycle #{}: {}", cycleCount, e.getMessage());
                        log.debug("Context error details", e);
                    }
                } catch (Exception e) {
                    log.error("Browser-level error in cycle #{}: {}", cycleCount, e.getMessage(), e);
                    backoffWithJitter(1);
                }
            }
        } catch (Exception fatal) {
            log.error("Fatal error in fetcher - shutting down", fatal);
        } finally {
            log.info("=== Shutting down SportyBetOddsFetcher ===");

            log.info("Shutting down scheduler...");
            scheduler.shutdownNow();

            log.info("Shutting down retry executor...");
            retryExecutor.shutdown();

            log.info("Disposing {} API clients...", apiClients.size());
            apiClients.values().forEach(APIRequestContext::dispose);

            Playwright pw = playwrightRef.getAndSet(null);
            if (pw != null) {
                log.info("Closing Playwright instance");
                pw.close();
            }

            log.info("=== SportyBetOddsFetcher shutdown complete ===");
        }
    }


    // -------------------- Browser/Context helpers --------------------

    private Browser launchBrowser(Playwright pw) {
        log.info("Launching new browser instance (headless=true, flags: {})",
                scraperConfig.getBROWSER_FlAGS());

        Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(scraperConfig.getBROWSER_FlAGS()));

        log.info("Browser launched successfully");
        return browser;
    }

    private BrowserContext newContext(Browser browser, UserAgentProfile profile) {
        log.debug("Creating new browser context with profile settings");

        ViewportSize viewportSize = new ViewportSize(profile.getViewport().getWidth(),
                profile.getViewport().getWidth());

        Browser.NewContextOptions opts = new Browser.NewContextOptions()
                .setUserAgent(profile.getUserAgent())
                .setViewportSize(viewportSize)
                .setExtraHTTPHeaders(getAllHeaders(profile));

        BrowserContext context = browser.newContext(opts);
        log.debug("Browser context created with viewport {}x{}",
                viewportSize.width, viewportSize.height);

        return context;
    }

    public Map<String, String> getAllHeaders(UserAgentProfile profile) {
        log.debug("Assembling headers from profile");

        Map<String, String> allHeaders = new HashMap<>();
        Map<String, String> standardHeaders = profile.getHeaders().getStandardHeaders();
        Map<String, String> clientHintsHeaders = profile.getHeaders().getClientHintsHeaders();

        if (standardHeaders != null) {
            allHeaders.putAll(standardHeaders);
            log.debug("Added {} standard headers", standardHeaders.size());
        }
        if (clientHintsHeaders != null) {
            allHeaders.putAll(clientHintsHeaders);
            log.debug("Added {} client hints headers", clientHintsHeaders.size());
        }

        log.debug("Total headers assembled: {}", allHeaders.size());
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

    private void attachNetworkTaps(Page page, Map<String,String> store) {
        log.debug("Attaching network response listeners for header harvesting");

        page.onResponse(resp -> {
            String url = resp.url();
            int status = resp.status();

            if ((url.contains("prematch") || url.contains("odds")) && status >= 200 && status < 400) {
                log.debug("Capturing headers from response: {} (status: {})", url, status);

                Map<String, String> headers = resp.headers();
                headers.forEach((k,v) -> {
                    String key = k.toLowerCase(Locale.ROOT);
                    if (key.equals("authorization") || key.equals("x-csrf-token") || key.equals("set-cookie")) {
                        store.put(key, v);
                        log.debug("Harvested header: {}", key);
                    }
                });
            }
        });

        log.debug("Network listeners attached");
    }

    private void performInitialNavigationWithRetry(Page page) {
        log.info("Starting initial navigation to {}", SPORT_PAGE);

        int attempt = 0;
        while (true) {
            try {
                log.info("Navigate attempt {} to {}", attempt + 1, SPORT_PAGE);

                page.navigate(SPORT_PAGE, new Page.NavigateOptions()
                        .setTimeout(45_000)
                        .setWaitUntil(WaitUntilState.NETWORKIDLE));

                log.debug("Navigation completed, waiting for body selector");
                page.waitForSelector("body", new Page.WaitForSelectorOptions().setTimeout(10_000));

                log.info("Initial page load successful on attempt {}", attempt + 1);
                return;

            } catch (PlaywrightException e) {
                log.warn("Nav attempt {} failed: {}", attempt + 1, e.getMessage());

                if (attempt++ >= SportyBetOddsFetcher.CONTEXT_NAV_MAX_RETRIES - 1) {
                    log.error("Navigation failed after {} attempts", CONTEXT_NAV_MAX_RETRIES);
                    throw e;
                }

                log.info("Retrying navigation after backoff...");
                backoffWithJitter(attempt);
            }
        }
    }

    private String formatCookies(List<Cookie> cookies) {
        log.debug("Formatting {} cookies", cookies.size());

        String joined = cookies.stream()
                .map(c -> c.name + "=" + c.value)
                .reduce((a,b) -> a + "; " + b)
                .orElse("");

        log.debug("Cookie header formatted: length={}, cookies={}", joined.length(), cookies.size());
        return joined;
    }

    // -------------------- API client & schedulers --------------------

    private Map<String,String> buildHeaders(String cookieHeader, Map<String,String> harvested, UserAgentProfile profile) {
        log.debug("Building API request headers");

        Map<String,String> h = new HashMap<>();
        h.put("User-Agent", profile.getUserAgent());
        h.put("Referer", SPORT_PAGE);
        h.put("Cookie", cookieHeader);
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/json");
        h.put("X-Requested-With", "XMLHttpRequest");
        harvested.forEach(h::putIfAbsent);

        log.debug("Built headers: {} total (including {} harvested)", h.size(), harvested.size());
        return h;
    }

    private APIRequestContext getOrCreateClient(String key, Playwright pw, String cookieHeader,
                                                Map<String,String> harvested, UserAgentProfile profile) {
        log.debug("Getting or creating API client for key: {}", key);

        return apiClients.computeIfAbsent(key, k -> {
            log.info("Creating new API client for key: {}", k);
            return pw.request().newContext(
                    new APIRequest.NewContextOptions().setExtraHTTPHeaders(buildHeaders(cookieHeader, harvested, profile))
            );
        });
    }

    private void startOrRefreshSchedules() {
        log.info("Starting/refreshing scheduled tasks");
        log.info("Schedule period: {}s, Tasks: Football, Basketball, TableTennis", SCHEDULER_PERIOD_SEC);

        scheduler.scheduleAtFixedRate(() -> safeApiWrapper(this::callFootball), 0, SCHEDULER_PERIOD_SEC, TimeUnit.SECONDS);
        log.info("Football scheduler started (initial delay: 0s, period: {}s)", SCHEDULER_PERIOD_SEC);

        scheduler.scheduleAtFixedRate(() -> safeApiWrapper(this::callBasketball), 5, SCHEDULER_PERIOD_SEC, TimeUnit.SECONDS);
        log.info("Basketball scheduler started (initial delay: 5s, period: {}s)", SCHEDULER_PERIOD_SEC);

        scheduler.scheduleAtFixedRate(() -> safeApiWrapper(this::callTableTennis), 10, SCHEDULER_PERIOD_SEC, TimeUnit.SECONDS);
        log.info("Table Tennis scheduler started (initial delay: 10s, period: {}s)", SCHEDULER_PERIOD_SEC);
    }

    private void safeApiWrapper(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.error("Scheduled API error: {}", e.getMessage(), e);
        }
    }

    private void callFootball() {
        log.info("=== Starting Football fetch ===");
        long startTime = System.currentTimeMillis();

        apiCallWithRetry(() -> {
            String url = buildUrl(BASE_URL + "/api/ng/factsCenter/liveOrPrematchEvents",
                    Map.of("sportId","sr:sport:1","_t",String.valueOf(System.currentTimeMillis())));

            log.debug("Football API URL: {}", url);

            APIRequestContext client = getOrCreateClient(KEY_FB, playwrightRef.get(), cookieHeaderRef.get(), harvestedHeaders,
                    profile);

            String body = doGetString(url, client);
            log.debug("Football response received: {} chars", body != null ? body.length() : 0);

            List<String> eventIds = extractEventIds(body);

            if (eventIds == null || eventIds.isEmpty()) {
                log.info("No football events found");
                return;
            }

            log.info("Found {} football events", eventIds.size());

            int parallelism = Math.min(12, Math.max(4, eventIds.size() / 2));
            log.info("Processing football events with parallelism: {}", parallelism);

            ExecutorService pool = Executors.newFixedThreadPool(parallelism);
            try {
                List<Callable<Void>> tasks = eventIds.stream().map(eventId -> (Callable<Void>) () -> {
                    fetchAndProcessEventDetail(eventId, client);
                    return null;
                }).toList();

                List<Future<Void>> futures = null;
                try {
                    futures = pool.invokeAll(tasks);
                } catch (InterruptedException e) {
                    log.error("Football task execution interrupted", e);
                    throw new RuntimeException(e);
                }

                int successCount = 0;
                int failureCount = 0;

                for (Future<Void> f : futures) {
                    try {
                        f.get();
                        successCount++;
                    } catch (ExecutionException | InterruptedException ee) {
                        failureCount++;
                        Throwable cause = ee.getCause();
                        if (cause instanceof PlaywrightException pe) {
                            log.warn("Football detail call PlaywrightException: {}", pe.getMessage());
                        } else {
                            log.warn("Football detail call error: {}", cause == null ? "unknown" : cause.getMessage());
                        }
                    }
                }

                log.info("Football batch complete: {} successful, {} failed", successCount, failureCount);

            } finally {
                pool.shutdownNow();
            }
        });

        long duration = System.currentTimeMillis() - startTime;
        log.info("=== Football fetch complete (duration: {}ms) ===", duration);
    }



    private void callBasketball() {
        log.info("=== Starting Basketball fetch ===");
        long startTime = System.currentTimeMillis();

        apiCallWithRetry(() -> {
            String url = buildUrl(BASE_URL + "/api/ng/factsCenter/liveOrPrematchEvents",
                    Map.of("sportId","sr:sport:2","_t",String.valueOf(System.currentTimeMillis())));

            log.debug("Basketball API URL: {}", url);

            APIRequestContext client = getOrCreateClient(KEY_BB,  playwrightRef.get(), cookieHeaderRef.get(), harvestedHeaders,
                    profile);

            String body = doGetString(url, client);
            log.debug("Basketball response received: {} chars", body != null ? body.length() : 0);

            List<String> eventIds = extractEventIds(body);

            if (eventIds == null || eventIds.isEmpty()) {
                log.info("No basketball events found");
                return;
            }

            log.info("Found {} basketball events", eventIds.size());

            int parallelism = Math.min(12, Math.max(4, eventIds.size() / 2));
            log.info("Processing basketball events with parallelism: {}", parallelism);

            ExecutorService pool = Executors.newFixedThreadPool(parallelism);
            try {
                List<Callable<Void>> tasks = eventIds.stream().map(eventId -> (Callable<Void>) () -> {
                    fetchAndProcessEventDetail(eventId, client);
                    return null;
                }).toList();

                List<Future<Void>> futures = null;
                try {
                    futures = pool.invokeAll(tasks);
                } catch (InterruptedException e) {
                    log.error("Basketball task execution interrupted", e);
                    throw new RuntimeException(e);
                }

                int successCount = 0;
                int failureCount = 0;

                for (Future<Void> f : futures) {
                    try {
                        f.get();
                        successCount++;
                    } catch (ExecutionException | InterruptedException ee) {
                        failureCount++;
                        Throwable cause = ee.getCause();
                        if (cause instanceof PlaywrightException pe) {
                            log.warn("Basketball detail call PlaywrightException: {}", pe.getMessage());
                        } else {
                            log.warn("Basketball detail call error: {}", cause == null ? "unknown" : cause.getMessage());
                        }
                    }
                }

                log.info("Basketball batch complete: {} successful, {} failed", successCount, failureCount);

            } finally {
                pool.shutdownNow();
            }
        });

        long duration = System.currentTimeMillis() - startTime;
        log.info("=== Basketball fetch complete (duration: {}ms) ===", duration);
    }

    private void callTableTennis() {
        log.info("=== Starting Table Tennis fetch ===");
        long startTime = System.currentTimeMillis();

        apiCallWithRetry(() -> {
            String url = buildUrl(BASE_URL + "/api/ng/factsCenter/liveOrPrematchEvents",
                    Map.of("sportId","sr:sport:20","_t",String.valueOf(System.currentTimeMillis())));

            log.debug("Table Tennis API URL: {}", url);

            APIRequestContext client = getOrCreateClient(KEY_TT, playwrightRef.get(), cookieHeaderRef.get(), harvestedHeaders,
                    profile);

            String body = doGetString(url, client);
            log.debug("Table Tennis response received: {} chars", body != null ? body.length() : 0);

            List<String> eventIds = extractEventIds(body);

            if (eventIds == null || eventIds.isEmpty()) {
                log.info("No table tennis events found");
                return;
            }

            log.info("Found {} table tennis events", eventIds.size());

            int parallelism = Math.min(12, Math.max(4, eventIds.size() / 2));
            log.info("Processing table tennis events with parallelism: {}", parallelism);

            ExecutorService pool = Executors.newFixedThreadPool(parallelism);
            try {
                List<Callable<Void>> tasks = eventIds.stream().map(eventId -> (Callable<Void>) () -> {
                    fetchAndProcessEventDetail(eventId, client);
                    return null;
                }).toList();

                List<Future<Void>> futures = null;
                try {
                    futures = pool.invokeAll(tasks);
                } catch (InterruptedException e) {
                    log.error("Table Tennis task execution interrupted", e);
                    throw new RuntimeException(e);
                }

                int successCount = 0;
                int failureCount = 0;

                for (Future<Void> f : futures) {
                    try {
                        f.get();
                        successCount++;
                    } catch (ExecutionException | InterruptedException ee) {
                        failureCount++;
                        Throwable cause = ee.getCause();
                        if (cause instanceof PlaywrightException pe) {
                            log.warn("Table Tennis detail call PlaywrightException: {}", pe.getMessage());
                        } else {
                            log.warn("Table Tennis detail call error: {}", cause == null ? "unknown" : cause.getMessage());
                        }
                    }
                }

                log.info("Table Tennis batch complete: {} successful, {} failed", successCount, failureCount);

            } finally {
                pool.shutdownNow();
            }
        });

        long duration = System.currentTimeMillis() - startTime;
        log.info("=== Table Tennis fetch complete (duration: {}ms) ===", duration);
    }

    private List<String> extractEventIds(String body) {
        log.debug("Extracting event IDs from response body");

        List<String> eventIds = JsonParser.extractEventIds(body);

        if (eventIds != null) {
            log.debug("Extracted {} event IDs", eventIds.size());
        } else {
            log.debug("No event IDs extracted (null result)");
        }

        return eventIds;
    }

    /** Second API call: per-event detail JSON -> parse -> normalize -> dispatch */
    private void fetchAndProcessEventDetail(String eventId, APIRequestContext apiClient) {
        log.debug("Fetching detail for eventId: {}", eventId);

        apiCallWithRetry(() -> {
            String url = buildEventDetailUrl(eventId);
            log.debug("Detail URL for {}: {}", eventId, url);

            String body = doGetString(url, apiClient);

            if (body == null || body.isBlank()) {
                log.warn("Empty detail response for eventId={}", eventId);
                return;
            }

            log.debug("Received detail body for eventId={}: {} chars", eventId, body.length());

            try {
                SportyEvent domainEvent = parseEventDetail(body);
                log.debug("Successfully parsed SportyEvent for eventId={}", eventId);

                processParsedEvent(domainEvent);
                log.debug("Successfully processed event: {}", domainEvent.getEventId());

            } catch (Exception ex) {
                log.error("Failed to parse/process eventId={} detail: {}", eventId, ex.getMessage(), ex);
            }
        });
    }

    private SportyEvent parseEventDetail(String detailJson) {
        log.debug("Parsing event detail JSON (length: {})", detailJson.length());

        SportyEvent event = JsonParser.deserializeSportyEvent(detailJson, objectMapper);

        if (event != null) {
            log.debug("Parsed SportyEvent: eventId={}", event.getEventId());
        } else {
            log.warn("Failed to parse SportyEvent - result is null");
        }

        return event;
    }

    /** If Sporty has a different detail endpoint, change here (kept centralized). */
    private String buildEventDetailUrl(String eventId) {
        log.trace("Building detail URL for eventId: {}", eventId);

        String base = BASE_URL + "/api/ng/factsCenter/event";
        String url = buildUrl(base, Map.of(
                "eventId", eventId,
                "productId", "1",
                "_t", String.valueOf(System.currentTimeMillis())
        ));

        log.trace("Built detail URL: {}", url);
        return url;
    }



    private String doGetString(String url, APIRequestContext client) {
        log.debug("Executing GET request: {}", url);

        APIResponse res = client.get(url);
        int status = res.status();
        String body = safeBody(res);

        log.debug("Response status: {} for URL: {}", status, url);

        if (status == 401 || status == 403) {
            log.warn("Authorization error {} for {}", status, url);
            throw new PlaywrightException("Auth/rate error " + status);
        }

        if (status < 200 || status >= 300) {
            log.warn("GET {} -> {} {} body: {}", url, status, res.statusText(), snippet(body));
            throw new PlaywrightException("HTTP " + status + " on " + url);
        }

        log.debug("GET successful: {} chars returned", body != null ? body.length() : 0);
        return body;
    }



    /** URL builder with simple encoding for query params. */
    private String buildUrl(String base, Map<String, String> params) {
        log.trace("Building URL - base: {}, params: {}", base, params != null ? params.size() : 0);

        StringBuilder sb = new StringBuilder(base);
        if (params != null && !params.isEmpty()) {
            sb.append("?");
            sb.append(params.entrySet().stream()
                    .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .collect(Collectors.joining("&")));
        }

        String url = sb.toString();
        log.trace("Built URL: {}", url);
        return url;
    }

    private String encode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }
        catch (Exception e) {
            log.error("URL encoding failed for string: {}", s, e);
            throw new RuntimeException("Encoding failed", e);
        }
    }



    private void rebuildClients(Playwright pw, String cookie, Map<String,String> harvested, UserAgentProfile profile) {
        log.info("Rebuilding API clients");
        log.debug("Disposing {} existing clients", apiClients.size());

        apiClients.values().forEach(APIRequestContext::dispose);
        apiClients.clear();

        log.debug("Creating fresh API clients for all sports");
        apiClients.put(KEY_FB, getOrCreateClient(KEY_FB, pw, cookie, harvested, profile));
        apiClients.put(KEY_BB, getOrCreateClient(KEY_BB, pw, cookie, harvested, profile));
        apiClients.put(KEY_TT, getOrCreateClient(KEY_TT, pw, cookie, harvested, profile));

        log.info("API clients rebuilt successfully: {}", apiClients.keySet());
    }



    private void apiCallWithRetry(Runnable call) {
        log.debug("Starting API call with retry logic (max retries: {})", API_MAX_RETRIES);

        int attempt = 0;
        while (true) {
            try {
                call.run();
                log.debug("API call succeeded on attempt {}", attempt + 1);
                return;
            } catch (PlaywrightException e) {
                attempt++;
                log.warn("API call failed (attempt {}): {}", attempt, e.getMessage());

                if (attempt >= API_MAX_RETRIES) {
                    log.error("API call failed after {} attempts", API_MAX_RETRIES);
                    throw e;
                }

                log.info("Retrying API call after backoff (attempt {} of {})", attempt, API_MAX_RETRIES);
                backoffWithJitter(attempt);
            } catch (RuntimeException e) {
                log.error("Non-retryable RuntimeException in API call: {}", e.getMessage(), e);
                throw e;
            }
        }
    }

    /** Normalize + route to downstream systems (ArbDetector, caches, retries, etc.). */
    private void processParsedEvent(SportyEvent event) {
        if (event == null) {
            log.debug("processParsedEvent: event is null, skipping.");
            return;
        }

        log.info("Processing parsed event: eventId={}", event.getEventId());

        try {
            // 1) Normalize (sync)
            log.info("Converting SportyEvent to NormalizedEvent for eventId={}", event.getEventId());
            NormalizedEvent normalizedEvent = sportyBetService.convertToNormalEvent(event);

            if (normalizedEvent == null) {
                log.info("convertToNormalEvent returned null for eventId={}", event.getEventId());
                return;
            }

            log.info("Normalized event created successfully for eventId={}", normalizedEvent.getEventId());

            // 2) Process bet-retry info (async, separate thread)
            log.info("Submitting bet retry processing to executor for eventId={}", normalizedEvent.getEventId());
            CompletableFuture
                    .runAsync(() -> processBetRetryInfo(normalizedEvent), retryExecutor)
                    .exceptionally(ex -> {
                        log.error("processBetRetryInfo failed for eventId={}, err={}",
                                normalizedEvent.getEventId(), ex.getMessage(), ex);
                        return null;
                    });

            // 3) Send to arb detector (sync)
            if (arbDetector != null) {
                log.debug("Adding event to arb detector pool: eventId={}", normalizedEvent.getEventId());
                arbDetector.addEventToPool(normalizedEvent);
                log.debug("Event added to arb detector successfully");
            } else {
                log.warn("arbDetector is null; skipping addEventToPool for eventId={}", normalizedEvent.getEventId());
            }

            log.info("Successfully processed event: eventId={}", event.getEventId());

        } catch (Exception e) {
            log.error("processParsedEvent failed for eventId={}: {}",
                    event.getEventId(), e.getMessage(), e);
        }
    }

    private void processBetRetryInfo(NormalizedEvent normalizedEvent) {
        log.debug("Processing bet retry info for eventId={}, bookmaker={}",
                normalizedEvent.getEventId(), SCRAPER_BOOKMAKER);

        try {
            betLegRetryService.updateFailedBetLeg(normalizedEvent, SCRAPER_BOOKMAKER);
            log.debug("Bet retry info updated successfully for eventId={}", normalizedEvent.getEventId());
        } catch (Exception e) {
            log.error("BetLegRetry processing failed for eventId={}, err={}",
                    normalizedEvent.getEventId(), e.getMessage(), e);
        }
    }



    private String safeBody(APIResponse res) {
        try {
            return res.text();
        } catch (Exception e) {
            log.warn("Failed to extract response body: {}", e.getMessage());
            return null;
        }
    }

    private String snippet(String s) {
        if (s == null) return "null";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    // -------------------- Backoff --------------------

    private void backoffWithJitter(int attempt) {
        long base = 2_000L; // 2s
        long delay = (long) (base * Math.pow(2, Math.max(1, attempt)));
        long jitter = ThreadLocalRandom.current().nextLong(500, 1500);
        long totalDelay = Math.min(delay + jitter, 20_000L);

        log.info("Backing off for {}ms (attempt: {}, base delay: {}ms, jitter: {}ms)",
                totalDelay, attempt, delay, jitter);

        try {
            Thread.sleep(totalDelay);
            log.debug("Backoff complete");
        } catch (InterruptedException ie) {
            log.warn("Backoff interrupted");
            Thread.currentThread().interrupt();
        }
    }
}