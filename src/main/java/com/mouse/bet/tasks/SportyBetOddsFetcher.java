package com.mouse.bet.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
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

    // Playwright
    private final AtomicReference<Playwright> playwrightRef = new AtomicReference<>();

    // Schedulers / Executors
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
    private final ExecutorService retryExecutor = Executors.newFixedThreadPool(2);

    // Runtime state
    private final AtomicReference<String> cookieHeaderRef = new AtomicReference<>("");
    private final AtomicBoolean schedulesStarted = new AtomicBoolean(false);
    private UserAgentProfile profile;

    // --- API context pools ---
    // Per-sport pool of contexts (immutable after swap)
    private final AtomicReference<Map<String, List<APIRequestContext>>> ctxPoolsRef =
            new AtomicReference<>(Map.of());

    // Per-sport round-robin index
    private final Map<String, AtomicInteger> rrCounters = new ConcurrentHashMap<>();

    // Per-sport simple lock for pool maintenance
    private final Map<String, Object> poolLocks = new ConcurrentHashMap<>();

    // Constants
    private static final String BASE_URL   = "https://www.sportybet.com";
    private static final String SPORT_PAGE = BASE_URL + "/ng";
    private static final int CONTEXT_NAV_MAX_RETRIES = 3;
    private static final int API_MAX_RETRIES         = 3;
    private static final long SCHEDULER_PERIOD_SEC   = 45;
    private static final BookMaker SCRAPER_BOOKMAKER = BookMaker.SPORTY_BET;
    private static final int CTX_POOL_SIZE           = 6;  // pool size per sport
    private static final long POOL_DISPOSE_GRACE_MS  = 15_000; // let in-flight calls finish

    // Sport keys
    private static final String KEY_FB  = "sport:1";
    private static final String KEY_BB  = "sport:2";
    private static final String KEY_TT  = "sport:20";

    @Override
    public void run() {
        log.info("=== Starting SportyBetOddsFetcher ===");
        log.info("Bookmaker: {}, Base URL: {}", SCRAPER_BOOKMAKER, BASE_URL);

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

                    Map<String, String> captured = new HashMap<>();

                    try (BrowserContext context = newContext(browser, profile)) {
                        attachAntiDetection(context, profile);
                        Page page = context.newPage();

                        attachNetworkTaps(page, captured);

                        performInitialNavigationWithRetry(page);

                        String cookieHeader = formatCookies(context.cookies());
                        cookieHeaderRef.set(cookieHeader);
                        log.info("Captured cookies (length={} chars)", cookieHeader.length());
                    } catch (PlaywrightException e) {
                        log.warn("Context block failed in cycle #{}: {}", cycleCount, e.toString());
                        continue; // try next cycle
                    }

                    // Build fresh pools and atomically swap
                    rebuildPoolsAtomically(pw, cookieHeaderRef.get(), captured, profile);

                    // Start schedulers once
                    if (schedulesStarted.compareAndSet(false, true)) {
                        startSchedulersOnce();
                    }

                    // Sleep until next refresh cycle
                    long sleepMs = Duration.ofMinutes(10).toMillis();
                    log.info("Sleeping for {} minutes before next cycle", sleepMs / 60000);
                    Thread.sleep(sleepMs);

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
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

            log.info("Disposing API client pools...");
            Map<String, List<APIRequestContext>> pools = ctxPoolsRef.getAndSet(Map.of());
            disposePools(pools);

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

        return pw.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(scraperConfig.getBROWSER_FlAGS()));
    }

    private BrowserContext newContext(Browser browser, UserAgentProfile profile) {
        int w = profile.getViewport().getWidth();
        int h = profile.getViewport().getHeight(); // ✅ correct height

        Browser.NewContextOptions opts = new Browser.NewContextOptions()
                .setUserAgent(profile.getUserAgent())
                .setViewportSize(w, h)
                .setExtraHTTPHeaders(getAllHeaders(profile));

        return browser.newContext(opts);
    }

    private Map<String, String> getAllHeaders(UserAgentProfile profile) {
        Map<String, String> all = new HashMap<>();
        Map<String, String> std = profile.getHeaders().getStandardHeaders();
        Map<String, String> hints = profile.getHeaders().getClientHintsHeaders();

        if (std != null) all.putAll(std);
        if (hints != null) all.putAll(hints);

        return all;
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
        page.onResponse(resp -> {
            String url = resp.url();
            int status = resp.status();

            if ((url.contains("prematch") || url.contains("odds")) && status >= 200 && status < 400) {
                Map<String, String> headers = resp.headers();
                headers.forEach((k, v) -> {
                    String key = k.toLowerCase(Locale.ROOT);
                    // Only carry forward request-useful headers
                    if (key.equals("authorization") || key.equals("x-csrf-token")) {
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
                if (attempt++ >= CONTEXT_NAV_MAX_RETRIES - 1) throw e;
                backoffWithJitter(attempt);
            }
        }
    }

    private String formatCookies(List<Cookie> cookies) {
        return cookies.stream()
                .map(c -> c.name + "=" + c.value)
                .collect(Collectors.joining("; "));
    }

    // -------------------- API client pools --------------------

    private void rebuildPoolsAtomically(Playwright pw, String cookie, Map<String,String> harvested, UserAgentProfile profile) {
        log.info("Rebuilding API client pools (atomic swap)");

        Map<String, List<APIRequestContext>> fresh = new HashMap<>();
        fresh.put(KEY_FB, buildPoolForKey(pw, cookie, harvested, profile));
        fresh.put(KEY_BB, buildPoolForKey(pw, cookie, harvested, profile));
        fresh.put(KEY_TT, buildPoolForKey(pw, cookie, harvested, profile));

        // Init counters/locks if missing
        for (String key : fresh.keySet()) {
            rrCounters.computeIfAbsent(key, k -> new AtomicInteger());
            poolLocks.computeIfAbsent(key, k -> new Object());
        }

        Map<String, List<APIRequestContext>> old = ctxPoolsRef.getAndSet(
                Map.copyOf(fresh) // make it immutable snapshot
        );

        // Dispose old pools *after* short grace period to let in-flight calls finish
        disposePoolsAsync(old);
        log.info("API client pools swapped successfully");
    }

    private List<APIRequestContext> buildPoolForKey(Playwright pw, String cookie, Map<String,String> harvested, UserAgentProfile profile) {
        List<APIRequestContext> pool = new ArrayList<>(CTX_POOL_SIZE);
        for (int i = 0; i < CTX_POOL_SIZE; i++) {
            pool.add(
                    pw.request().newContext(
                            new APIRequest.NewContextOptions()
                                    .setExtraHTTPHeaders(buildHeaders(cookie, harvested, profile))
                    )
            );
        }
        return Collections.unmodifiableList(pool);
    }

    private void disposePools(Map<String, List<APIRequestContext>> pools) {
        if (pools == null || pools.isEmpty()) return;
        pools.values().forEach(list -> list.forEach(APIRequestContext::dispose));
    }

    private void disposePoolsAsync(Map<String, List<APIRequestContext>> old) {
        if (old == null || old.isEmpty()) return;
        scheduler.schedule(() -> {
            try {
                disposePools(old);
                log.info("Disposed old API client pools");
            } catch (Exception e) {
                log.warn("Failed to dispose old pools: {}", e.toString());
            }
        }, POOL_DISPOSE_GRACE_MS, TimeUnit.MILLISECONDS);
    }

    private Map<String,String> buildHeaders(String cookieHeader, Map<String,String> harvested, UserAgentProfile profile) {
        Map<String,String> h = new HashMap<>();
        h.put("User-Agent", profile.getUserAgent());
        h.put("Referer", SPORT_PAGE);
        h.put("Cookie", cookieHeader);
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/json");
        h.put("X-Requested-With", "XMLHttpRequest");

        if (harvested != null) {
            harvested.forEach((k,v) -> {
                String key = k.toLowerCase(Locale.ROOT);
                if (key.equals("authorization") || key.equals("x-csrf-token")) {
                    h.put(key, v);
                }
            });
        }
        return h;
    }

    private APIRequestContext pickCtx(String key) {
        List<APIRequestContext> pool = ctxPoolsRef.get().get(key);
        if (pool == null || pool.isEmpty()) throw new IllegalStateException("No API context pool for key " + key);
        AtomicInteger ctr = rrCounters.get(key);
        int idx = Math.abs(ctr.getAndIncrement() % pool.size());
        return pool.get(idx);
    }

    private void refreshOneCtxInPool(String key, APIRequestContext toReplace) {
        synchronized (poolLocks.get(key)) {
            Map<String, List<APIRequestContext>> snapshot = ctxPoolsRef.get();
            List<APIRequestContext> pool = snapshot.get(key);
            if (pool == null || pool.isEmpty()) return;

            // Create a mutable copy, replace the specific instance, then swap
            List<APIRequestContext> newPool = new ArrayList<>(pool);
            int idx = newPool.indexOf(toReplace);
            if (idx < 0) return; // not found (already rotated)
            try {
                toReplace.dispose();
            } catch (Exception ignored) {}

            APIRequestContext fresh = playwrightRef.get().request().newContext(
                    new APIRequest.NewContextOptions().setExtraHTTPHeaders(
                            buildHeaders(cookieHeaderRef.get(), Map.of(), profile)
                    )
            );
            newPool.set(idx, fresh);

            Map<String, List<APIRequestContext>> newSnapshot = new HashMap<>(snapshot);
            newSnapshot.put(key, Collections.unmodifiableList(newPool));
            ctxPoolsRef.set(Collections.unmodifiableMap(newSnapshot));
        }
    }

    // -------------------- Schedulers --------------------

    private void startSchedulersOnce() {
        log.info("Starting scheduled tasks (period: {}s)", SCHEDULER_PERIOD_SEC);
        scheduler.scheduleAtFixedRate(() -> safeApiWrapper(this::callFootball),     0,  SCHEDULER_PERIOD_SEC, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(() -> safeApiWrapper(this::callBasketball),   5,  SCHEDULER_PERIOD_SEC, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(() -> safeApiWrapper(this::callTableTennis), 10, SCHEDULER_PERIOD_SEC, TimeUnit.SECONDS);
    }

    private void safeApiWrapper(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.error("Scheduled API error: {}", e.getMessage(), e);
        }
    }

    // -------------------- Callers --------------------

    private void callFootball() {
        log.info("=== Starting Football fetch ===");
        long startTime = System.currentTimeMillis();

        apiCallWithRetry(() -> {
            String url = buildUrl(BASE_URL + "/api/ng/factsCenter/liveOrPrematchEvents",
                    Map.of("sportId","sr:sport:1","_t",String.valueOf(System.currentTimeMillis())));

            String body = safeApiGet(url, KEY_FB);
            List<String> eventIds = extractEventIds(body);
            if (eventIds == null || eventIds.isEmpty()) {
                log.info("No football events found");
                return;
            }

            int parallelism = Math.min(12, Math.max(4, eventIds.size() / 2));
            ExecutorService pool = Executors.newFixedThreadPool(parallelism);

            try {
                List<Callable<Void>> tasks = eventIds.stream().map(eventId -> (Callable<Void>) () -> {
                    fetchAndProcessEventDetail(eventId, KEY_FB);
                    return null;
                }).toList();

                List<Future<Void>> futures = pool.invokeAll(tasks);
                int success = 0, fail = 0;
                for (Future<Void> f : futures) {
                    try { f.get(); success++; }
                    catch (Exception ex) { fail++; log.warn("Football detail call error: {}", ex.getMessage()); }
                }
                log.info("Football batch complete: {} successful, {} failed", success, fail);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } finally {
                pool.shutdownNow();
            }
        });

        log.info("=== Football fetch complete ({}ms) ===", (System.currentTimeMillis() - startTime));
    }

    private void callBasketball() {
        log.info("=== Starting Basketball fetch ===");
        long startTime = System.currentTimeMillis();

        apiCallWithRetry(() -> {
            String url = buildUrl(BASE_URL + "/api/ng/factsCenter/liveOrPrematchEvents",
                    Map.of("sportId","sr:sport:2","_t",String.valueOf(System.currentTimeMillis())));

            String body = safeApiGet(url, KEY_BB);
            List<String> eventIds = extractEventIds(body);
            if (eventIds == null || eventIds.isEmpty()) {
                log.info("No basketball events found");
                return;
            }

            int parallelism = Math.min(12, Math.max(4, eventIds.size() / 2));
            ExecutorService pool = Executors.newFixedThreadPool(parallelism);

            try {
                List<Callable<Void>> tasks = eventIds.stream().map(eventId -> (Callable<Void>) () -> {
                    fetchAndProcessEventDetail(eventId, KEY_BB);
                    return null;
                }).toList();

                List<Future<Void>> futures = pool.invokeAll(tasks);
                int success = 0, fail = 0;
                for (Future<Void> f : futures) {
                    try { f.get(); success++; }
                    catch (Exception ex) { fail++; log.warn("Basketball detail call error: {}", ex.getMessage()); }
                }
                log.info("Basketball batch complete: {} successful, {} failed", success, fail);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } finally {
                pool.shutdownNow();
            }
        });

        log.info("=== Basketball fetch complete ({}ms) ===", (System.currentTimeMillis() - startTime));
    }

    private void callTableTennis() {
        log.info("=== Starting Table Tennis fetch ===");
        long startTime = System.currentTimeMillis();

        apiCallWithRetry(() -> {
            String url = buildUrl(BASE_URL + "/api/ng/factsCenter/liveOrPrematchEvents",
                    Map.of("sportId","sr:sport:20","_t",String.valueOf(System.currentTimeMillis())));

            String body = safeApiGet(url, KEY_TT);
            List<String> eventIds = extractEventIds(body);
            if (eventIds == null || eventIds.isEmpty()) {
                log.info("No table tennis events found");
                return;
            }

            int parallelism = Math.min(12, Math.max(4, eventIds.size() / 2));
            ExecutorService pool = Executors.newFixedThreadPool(parallelism);

            try {
                List<Callable<Void>> tasks = eventIds.stream().map(eventId -> (Callable<Void>) () -> {
                    fetchAndProcessEventDetail(eventId, KEY_TT);
                    return null;
                }).toList();

                List<Future<Void>> futures = pool.invokeAll(tasks);
                int success = 0, fail = 0;
                for (Future<Void> f : futures) {
                    try { f.get(); success++; }
                    catch (Exception ex) { fail++; log.warn("Table Tennis detail call error: {}", ex.getMessage()); }
                }
                log.info("Table Tennis batch complete: {} successful, {} failed", success, fail);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } finally {
                pool.shutdownNow();
            }
        });

        log.info("=== Table Tennis fetch complete ({}ms) ===", (System.currentTimeMillis() - startTime));
    }

    // -------------------- Details --------------------

    private List<String> extractEventIds(String body) {
        return JsonParser.extractEventIds(body);
    }

    private void fetchAndProcessEventDetail(String eventId, String clientKey) {
        apiCallWithRetry(() -> {
            String url = buildEventDetailUrl(eventId);
            String body = safeApiGet(url, clientKey);

            if (body == null || body.isBlank()) {
                log.warn("Empty detail response for eventId={}", eventId);
                return;
            }

            try {
                SportyEvent domainEvent = parseEventDetail(body);
                processParsedEvent(domainEvent);
            } catch (Exception ex) {
                log.error("Failed to parse/process eventId={} detail: {}", eventId, ex.getMessage(), ex);
            }
        });
    }

    private SportyEvent parseEventDetail(String detailJson) {
        return JsonParser.deserializeSportyEvent(detailJson, objectMapper);
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
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { throw new RuntimeException("Encoding failed", e); }
    }

    // -------------------- HTTP with Pool + Recovery --------------------

    private String safeApiGet(String url, String clientKey) {
        APIRequestContext ctx = pickCtx(clientKey);
        try {
            APIResponse res = ctx.get(url);
            int status = res.status();

            if (status == 401 || status == 403) {
                log.warn("Auth {} for {}, refreshing one ctx in {} and retrying once", status, url, clientKey);
                refreshOneCtxInPool(clientKey, ctx);
                APIRequestContext ctx2 = pickCtx(clientKey);
                res = ctx2.get(url);
                status = res.status();
            }

            String body = safeBody(res);
            if (status < 200 || status >= 300) {
                log.warn("GET {} -> {} {} body: {}", url, status, res.statusText(), snippet(body));
                throw new PlaywrightException("HTTP " + status + " on " + url);
            }
            return body;

        } catch (PlaywrightException e) {
            String msg = String.valueOf(e.getMessage());
            // Transport recovery: "Cannot find command to respond"
            if (msg.contains("Cannot find command to respond")) {
                log.warn("Transport error on {}, rotating one ctx for {} and retrying once", url, clientKey);
                refreshOneCtxInPool(clientKey, ctx);
                APIRequestContext ctx2 = pickCtx(clientKey);
                APIResponse res2 = ctx2.get(url);
                int status2 = res2.status();
                String body2 = safeBody(res2);
                if (status2 >= 200 && status2 < 300) return body2;
                throw new PlaywrightException("HTTP " + status2 + " on retry for " + url);
            }
            log.error("Playwright error during API call to {}: {}", url, e.getMessage());
            throw e;
        }
    }

    private String safeBody(APIResponse res) {
        try { return res.text(); }
        catch (Exception e) { log.warn("Failed to extract response body: {}", e.getMessage()); return null; }
    }

    // -------------------- Normalize/Dispatch --------------------

    private void processParsedEvent(SportyEvent event) {
        if (event == null) return;

        try {
            NormalizedEvent normalizedEvent = sportyBetService.convertToNormalEvent(event);
            if (normalizedEvent == null) return;

            CompletableFuture
                    .runAsync(() -> processBetRetryInfo(normalizedEvent), retryExecutor)
                    .exceptionally(ex -> { log.error("processBetRetryInfo failed: {}", ex.getMessage(), ex); return null; });

            if (arbDetector != null) {
                arbDetector.addEventToPool(normalizedEvent);
            }
        } catch (Exception e) {
            log.error("processParsedEvent failed for eventId={}: {}", event.getEventId(), e.getMessage(), e);
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

    // -------------------- Retry / Backoff --------------------

    private void apiCallWithRetry(Runnable call) {
        int attempt = 0;
        while (true) {
            try {
                call.run();
                return;
            } catch (PlaywrightException e) {
                attempt++;
                log.warn("API call failed (attempt {}): {}", attempt, e.getMessage());
                if (attempt >= API_MAX_RETRIES) throw e;
                backoffWithJitter(attempt);
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }

    private void backoffWithJitter(int attempt) {
        long base = 2_000L; // 2s
        long delay = (long) (base * Math.pow(2, Math.max(1, attempt)));
        long jitter = ThreadLocalRandom.current().nextLong(500, 1500);
        long total = Math.min(delay + jitter, 20_000L);
        try { Thread.sleep(total); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private String snippet(String s) {
        if (s == null) return "null";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
