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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class SportyBetOddsFetcher implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(SportyBetOddsFetcher.class);
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
        playwrightRef.set(Playwright.create());
        try {
            while (true) {
                Playwright pw = playwrightRef.get();
                try (Browser browser = launchBrowser(pw)) {
                    profile = profileManager.getNextProfile();
                    try (BrowserContext context = newContext(browser, profile)) {
                        attachAntiDetection(context, profile);
                        Page page = context.newPage();
//                        attachAntiDetection(page, profile);
                        attachNetworkTaps(page, harvestedHeaders);
                        performInitialNavigationWithRetry(page);

                        String cookieHeader = formatCookies(context.cookies());
                        cookieHeaderRef.set(cookieHeader);
                        page.close();
                        context.close();

                        // create per-sport API clients
                        rebuildClients(pw, cookieHeader, harvestedHeaders, profile);
                        startOrRefreshSchedules();

                        Thread.sleep(Duration.ofMinutes(10).toMillis());
                    } catch (PlaywrightException e) {
                        log.warn("Context block failed: {}", e.getMessage());
                    }
                } catch (Exception e) {
                    log.error("Browser-level error: {}", e.getMessage(), e);
                    backoffWithJitter(1);
                }
            }
        } catch (Exception fatal) {
            log.error("Fatal error in fetcher: ", fatal);
        } finally {
            scheduler.shutdownNow();
            retryExecutor.shutdown();
            apiClients.values().forEach(APIRequestContext::dispose);
            Playwright pw = playwrightRef.getAndSet(null);
            if (pw != null) pw.close();
        }
    }


    // -------------------- Browser/Context helpers --------------------

    private Browser launchBrowser(Playwright pw) {

        log.info("Launching new browser instance (flags: {})",
                scraperConfig.getBROWSER_FlAGS());
        return pw.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(scraperConfig.getBROWSER_FlAGS()));
    }

    private BrowserContext newContext(Browser browser, UserAgentProfile profile) {

        ViewportSize viewportSize = new ViewportSize(profile.getViewport().getWidth(),
                profile.getViewport().getWidth());

        Browser.NewContextOptions opts = new Browser.NewContextOptions()
                .setUserAgent(profile.getUserAgent())
                .setViewportSize(viewportSize)
                .setExtraHTTPHeaders(getAllHeaders(profile));

        return browser.newContext(opts);
    }

    public Map<String, String> getAllHeaders(UserAgentProfile profile) {
        Map<String, String> allHeaders = new HashMap<>();
        Map<String, String> standardHeaders = profile.getHeaders().getStandardHeaders();
        Map<String, String> clientHintsHeaders = profile.getHeaders().getClientHintsHeaders();
        if (standardHeaders != null) {
            allHeaders.putAll(standardHeaders);
        }
        if (clientHintsHeaders != null) {
            allHeaders.putAll(clientHintsHeaders);
        }
        return allHeaders;
    }


    private void attachAntiDetection(BrowserContext context, UserAgentProfile profile) {
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
    }

    private void attachNetworkTaps(Page page, Map<String,String> store) {
        page.onResponse(resp -> {
            String url = resp.url();
            int status = resp.status();

            if ((url.contains("prematch") || url.contains("odds")) && status >= 200 && status < 400) {
                Map<String, String> headers = resp.headers();
                headers.forEach((k,v) -> {
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
                log.info("Navigate attempt {} to {}", attempt + 1, SPORT_PAGE);
                page.navigate(SPORT_PAGE, new Page.NavigateOptions()
                        .setTimeout(45_000)
                        .setWaitUntil(WaitUntilState.NETWORKIDLE));
                // wait for body/critical selectors if needed
                page.waitForSelector("body", new Page.WaitForSelectorOptions().setTimeout(10_000));
                log.info("Initial page load OK");
                return;
            } catch (PlaywrightException e) {
                log.warn("Nav failed ({}): {}", attempt + 1, e.getMessage());
                if (attempt++ >= SportyBetOddsFetcher.CONTEXT_NAV_MAX_RETRIES - 1) throw e;
                backoffWithJitter(attempt);
            }
        }
    }

    private String formatCookies(List<Cookie> cookies) {
        String joined = cookies.stream()
                .map(c -> c.name + "=" + c.value)
                .reduce((a,b) -> a + "; " + b)
                .orElse("");
        log.debug("Cookie header length: {}", joined.length());
        return joined;
    }

    // -------------------- API client & schedulers --------------------

    private Map<String,String> buildHeaders(String cookieHeader, Map<String,String> harvested, UserAgentProfile profile) {
        Map<String,String> h = new HashMap<>();
        h.put("User-Agent", profile.getUserAgent());
        h.put("Referer", SPORT_PAGE);
        h.put("Cookie", cookieHeader);
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/json");
        h.put("X-Requested-With", "XMLHttpRequest");
        harvested.forEach(h::putIfAbsent);
        return h;
    }

    private APIRequestContext getOrCreateClient(String key, Playwright pw, String cookieHeader,
                                                Map<String,String> harvested, UserAgentProfile profile) {
        return apiClients.computeIfAbsent(key, k -> pw.request().newContext(
                new APIRequest.NewContextOptions().setExtraHTTPHeaders(buildHeaders(cookieHeader, harvested, profile))
        ));
    }

    private void startOrRefreshSchedules() {
        // Cancel existing schedules by shutting down & recreating? Here we just start once if not already.
        // If you need refresh, manage Futures and cancel/reschedule explicitly.

        scheduler.scheduleAtFixedRate(() -> safeApiWrapper(this::callFootball), 0, SCHEDULER_PERIOD_SEC, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(() -> safeApiWrapper(this::callBasketball), 5, SCHEDULER_PERIOD_SEC, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(() -> safeApiWrapper(this::callTableTennis), 10, SCHEDULER_PERIOD_SEC, TimeUnit.SECONDS);
    }

    private void safeApiWrapper(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.error("Scheduled API error: {}", e.getMessage(), e);
        }
    }

    private void callFootball() {
        apiCallWithRetry(() -> {
            String url = buildUrl(BASE_URL + "/api/ng/factsCenter/liveOrPrematchEvents",
                    Map.of("sportId","sr:sport:1","_t",String.valueOf(System.currentTimeMillis())));
            APIRequestContext client = getOrCreateClient(KEY_FB, playwrightRef.get(), cookieHeaderRef.get(), harvestedHeaders,
                    profile);
            String body = doGetString(url, client);
            List<String> eventIds = extractEventIds(body);

            if (eventIds == null || eventIds.isEmpty()) {
                log.debug("No eventIds found for sportId {}", 1);
                return;
            }

            // Fan-out with bounded parallelism to avoid bursts

            int parallelism = Math.min(12, Math.max(4, eventIds.size() / 2));
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
                    throw new RuntimeException(e);
                }
                for (Future<Void> f : futures) {
                    try {
                        f.get();
                    } catch (ExecutionException | InterruptedException ee) {
                        Throwable cause = ee.getCause();
                        if (cause instanceof PlaywrightException pe) {
                            // trigger retry flow at the call level (apiCallWithRetry wraps only index call here)
                            log.warn("Detail call PlaywrightException for one eventId: {}", pe.getMessage());
                        } else {
                            log.warn("Detail call error: {}", cause == null ? "unknown" : cause.getMessage());
                        }
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        });
    }



    private void callBasketball() {
        apiCallWithRetry(() -> {
            String url = buildUrl(BASE_URL + "/api/ng/factsCenter/liveOrPrematchEvents",
                    Map.of("sportId","sr:sport:1","_t",String.valueOf(System.currentTimeMillis())));
            APIRequestContext client = getOrCreateClient(KEY_FB,  playwrightRef.get(), cookieHeaderRef.get(), harvestedHeaders,
                    profile);
            String body = doGetString(url, client);
            // extract ids -> detail calls (use same client or create short-lived ones)
            List<String> eventIds = extractEventIds(body);

            if (eventIds == null || eventIds.isEmpty()) {
                log.debug("No eventIds found for sportId {}", 1);
                return;
            }

            // Fan-out with bounded parallelism to avoid bursts
            // You can reuse your existing scheduler or make a small thread pool here
            int parallelism = Math.min(12, Math.max(4, eventIds.size() / 2));
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
                    throw new RuntimeException(e);
                }
                for (Future<Void> f : futures) {
                    try {
                        f.get();
                    } catch (ExecutionException | InterruptedException ee) {
                        Throwable cause = ee.getCause();
                        if (cause instanceof PlaywrightException pe) {
                            // trigger retry flow at the call level (apiCallWithRetry wraps only index call here)
                            log.warn("Detail call PlaywrightException for one eventId: {}", pe.getMessage());
                        } else {
                            log.warn("Detail call error: {}", cause == null ? "unknown" : cause.getMessage());
                        }
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        });
    }

    private void callTableTennis() {
        apiCallWithRetry(() -> {
            String url = buildUrl(BASE_URL + "/api/ng/factsCenter/liveOrPrematchEvents",
                    Map.of("sportId","sr:sport:1","_t",String.valueOf(System.currentTimeMillis())));
            APIRequestContext client = getOrCreateClient(KEY_TT, /*pw*/ playwrightRef.get(), cookieHeaderRef.get(), harvestedHeaders,
                    profile);
            String body = doGetString(url, client);
            // extract ids -> detail calls (use same client or create short-lived ones)
            List<String> eventIds = extractEventIds(body);

            if (eventIds == null || eventIds.isEmpty()) {
                log.debug("No eventIds found for sportId {}", 1);
                return;
            }

            // Fan-out with bounded parallelism to avoid bursts
            // You can reuse your existing scheduler or make a small thread pool here
            int parallelism = Math.min(12, Math.max(4, eventIds.size() / 2));
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
                    throw new RuntimeException(e);
                }
                for (Future<Void> f : futures) {
                    try {
                        f.get();
                    } catch (ExecutionException | InterruptedException ee) {
                        Throwable cause = ee.getCause();
                        if (cause instanceof PlaywrightException pe) {
                            // trigger retry flow at the call level (apiCallWithRetry wraps only index call here)
                            log.warn("Detail call PlaywrightException for one eventId: {}", pe.getMessage());
                        } else {
                            log.warn("Detail call error: {}", cause == null ? "unknown" : cause.getMessage());
                        }
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        });
    }

    private List<String> extractEventIds(String body) {
        return JsonParser.extractEventIds(body);
    }

    /** Second API call: per-event detail JSON -> parse -> normalize -> dispatch */
    private void fetchAndProcessEventDetail(String eventId, APIRequestContext apiClient) {
        apiCallWithRetry(() -> {
            String url = buildEventDetailUrl(eventId);
            String body = doGetString(url, apiClient);

            if (body == null || body.isBlank()) {
                log.warn("Empty detail response for eventId={}", eventId);
                return;
            }

            try {
                SportyEvent domainEvent = parseEventDetail(body);

                // 2) Normalize + push into your pipeline (ArbDetector pool, caches, retry queues, etc.)
                processParsedEvent(domainEvent);
            } catch (Exception ex) {
                log.error("Failed to parse/process eventId={} detail: {}", eventId, ex.getMessage(), ex);
            }
        });
    }

    private SportyEvent parseEventDetail(String detailJson) {
        return JsonParser.deserializeSportyEvent(detailJson, objectMapper);
    }

    /** If Sporty has a different detail endpoint, change here (kept centralized). */
    private String buildEventDetailUrl(String eventId) {

        String base = BASE_URL + "/api/ng/factsCenter/event";
        return buildUrl(base,Map.of(
                        "eventId", eventId,
                        "productId", "1",
                        "_t", String.valueOf(System.currentTimeMillis())
        ));
    }



    private String doGetString(String url, APIRequestContext client) {
        APIResponse res = client.get(url);
        int status = res.status();
        String body = safeBody(res);

        if (status == 401 || status == 403) {
            log.warn("Authorization error {} for {}", status, url);
            throw new PlaywrightException("Auth/rate error " + status);
        }

        if (status < 200 || status >= 300) {
            log.warn("GET {} -> {} {} body: {}", url, status, res.statusText(), snippet(body));
            throw new PlaywrightException("HTTP " + status + " on " + url);
        }
        return body;
    }



    /** URL builder with simple encoding for query params. */
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
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }
        catch (Exception e) {
            throw new RuntimeException("Encoding failed", e); }
    }



    private void processRawEvent(String jsonBody) {
        //parse this json into event
        //normalized the event
        //add event to pool for arbitrage detecting in another thread
        //process the same event in another dependent class in another thread

    }

    private void rebuildClients(Playwright pw, String cookie, Map<String,String> harvested, UserAgentProfile profile) {
        apiClients.values().forEach(APIRequestContext::dispose);
        apiClients.clear();
        apiClients.put(KEY_FB, getOrCreateClient(KEY_FB, pw, cookie, harvested, profile));
        apiClients.put(KEY_BB, getOrCreateClient(KEY_BB, pw, cookie, harvested, profile));
        apiClients.put(KEY_TT, getOrCreateClient(KEY_TT, pw, cookie, harvested, profile));
    }



    private void apiCallWithRetry(Runnable call) {
        int attempt = 0;
        while (true) {
            try {
                call.run();
                return;
            } catch (PlaywrightException e) {
                if (attempt++ >= API_MAX_RETRIES - 1) throw e;
                log.warn("API call failed ({}): {} — retrying", attempt, e.getMessage());
                backoffWithJitter(attempt);
            } catch (RuntimeException e) {
                // If tokens expired or 401/403 loop, you may want to trigger a full refresh cycle
                throw e;
            }
        }
    }

    /** Normalize + route to downstream systems (ArbDetector, caches, retries, etc.). */
    // === Completed method ===
    private void processParsedEvent(SportyEvent event) {
        if (event == null) {
            log.debug("processParsedEvent: event is null, skipping.");
            return;
        }

        try {
            // 1) Normalize (sync)
            NormalizedEvent normalizedEvent = sportyBetService.convertToNormalEvent(event);
            if (normalizedEvent == null) {
                log.warn("convertToNormalEvent returned null for eventId={}", event.getEventId());
                return;
            }

            // 2) Process bet-retry info (async, separate thread)
            CompletableFuture
                    .runAsync(() -> processBetRetryInfo(normalizedEvent), retryExecutor)
                    .exceptionally(ex -> {
                        log.error("processBetRetryInfo failed for eventId={}, err={}",
                                normalizedEvent.getEventId(), ex.getMessage(), ex);
                        return null;
                    });

            // 3) Send to arb detector (sync)
            if (arbDetector != null) {
                arbDetector.addEventToPool(normalizedEvent);
            } else {
                log.warn("arbDetector is null; skipping addEventToPool for eventId={}", normalizedEvent.getEventId());
            }

            log.debug("Processed event: {}", event.getEventId());
        } catch (Exception e) {
            log.error("processParsedEvent failed: {}", e.getMessage(), e);
        }
    }

    private void processBetRetryInfo(NormalizedEvent normalizedEvent) {
        try {

            betLegRetryService.updateFailedBetLeg(normalizedEvent, SCRAPER_BOOKMAKER);
        } catch (Exception e) {
            log.error("BetLegRetry processing failed for eventId={}, err={}",
                    normalizedEvent.getEventId(), e.getMessage(), e);
        }
    }



    private String safeBody(APIResponse res) {
        try {
            return res.text();
        } catch (Exception ignore) {
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
        try {
            Thread.sleep(Math.min(delay + jitter, 20_000L));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
