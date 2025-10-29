package com.mouse.bet.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import com.microsoft.playwright.options.Cookie;
import com.mouse.bet.config.ScraperConfig;
import com.mouse.bet.detector.ArbDetector;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.interceptor.HeadersInterceptor;
import com.mouse.bet.interceptor.SimpleHttpLoggingInterceptor;
import com.mouse.bet.manager.ProfileManager;
import com.mouse.bet.model.NormalizedEvent;
import com.mouse.bet.model.bet9ja.Bet9jaEvent;
import com.mouse.bet.model.profile.UserAgentProfile;
import com.mouse.bet.service.Bet9jaService;
import com.mouse.bet.service.BetLegRetryService;
import com.mouse.bet.utils.JsonParser;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.Request;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

/**
 * Live arbing mode fetcher for Bet9ja odds.
 * Optimized with OkHttp client and context pool for sub-second profile rotation.
 * Includes adaptive cadence and stale data rejection for live arbitrage.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class Bet9jaOddsFetcher implements Runnable {

    // ==================== DEPENDENCIES ====================
    private final ScraperConfig scraperConfig;
    private final ProfileManager profileManager;
    private final Bet9jaService bet9jaService;
    private final BetLegRetryService betLegRetryService;
    private final ArbDetector arbDetector;
    private final ObjectMapper objectMapper;

    // ==================== CONFIGURATION CONSTANTS ====================
    private static final String BASE_URL = "https://sports.bet9ja.com";
    private static final String SPORT_PAGE = "https://sports.bet9ja.com";

    private static final int INITIAL_SETUP_MAX_ATTEMPTS = 3;
    private static final int CONTEXT_NAV_MAX_RETRIES = 3;
    private static final int API_MAX_RETRIES = 2;
    private static final int API_TIMEOUT_MS = 5_000;  // ✅ Reduced from 25s
    private static final long MIN_SCHEDULER_PERIOD_SEC = 2;
    private static final long MAX_SCHEDULER_PERIOD_SEC = 15;
    private static final int EVENT_DETAIL_THREADS = 100;
    private static final int PROCESSING_THREADS = 50;
    private static final long EVENT_DEDUP_WINDOW_MS = 800;
    private static final int MAX_ACTIVE_FETCHES = 100;
    private static final long STALE_DATA_THRESHOLD_MS = 5_000;  // ✅ Reject data older than 5s

    // Per-request timeouts - AGGRESSIVE for live arb
    private static final int LIST_API_TIMEOUT_MS = 3_000;  // ✅ Reduced from 10s
    private static final int DETAIL_API_TIMEOUT_MS = 4_000;  // ✅ Reduced from 15s

    // Context pool configuration
    private static final int CONTEXT_POOL_SIZE = 20;
    private static final int CONTEXT_MAX_AGE_MS = 300_000;

    // Rate limit detection thresholds
    private static final int RATE_LIMIT_THRESHOLD = 5;
    private static final int SLOW_REQUEST_THRESHOLD_MS = 5_000;  // ✅ Reduced from 20s

    // Sport keys
    private static final String KEY_FB = "3000001";
    private static final String KEY_BB = "3000002";
    private static final String KEY_TT = "3000020";
    private static final BookMaker SCRAPER_BOOKMAKER = BookMaker.BET9JA;

    // Response time tracking for adaptive cadence
    private static final int RESPONSE_TIME_WINDOW = 10;
    private final Queue<Long> recentResponseTimes = new ConcurrentLinkedQueue<>();

    // ==================== THREAD POOLS ====================
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
    private final ExecutorService listFetchExecutor = Executors.newFixedThreadPool(4);
    private final ExecutorService eventDetailExecutor = Executors.newFixedThreadPool(EVENT_DETAIL_THREADS);
    private final ExecutorService processingExecutor = Executors.newFixedThreadPool(PROCESSING_THREADS);
    private final ExecutorService retryExecutor = Executors.newFixedThreadPool(5);
    private final ExecutorService profileRotationExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService poolWarmerExecutor = Executors.newSingleThreadExecutor();

    // ==================== SESSION MANAGEMENT ====================
    private final AtomicReference<Playwright> playwrightRef = new AtomicReference<>();
    private volatile Browser sharedBrowser;
    private volatile UserAgentProfile profile;
    private final AtomicLong profileVersion = new AtomicLong(0);

    // Context pool for fast rotation
    private final BlockingQueue<ContextWrapper> contextPool = new LinkedBlockingQueue<>(CONTEXT_POOL_SIZE);
    private volatile ContextWrapper activeContext;

    // OkHttp client management
    private final ThreadLocal<OkHttpClient> threadLocalClients = ThreadLocal.withInitial(this::createNewOkHttpClient);
    private final ThreadLocal<Long> threadLocalProfileVersion = ThreadLocal.withInitial(() -> -1L);
    private volatile OkHttpClient sharedOkHttpClient;

    private final AtomicBoolean globalNeedsRefresh = new AtomicBoolean(false);
    private final ThreadLocal<Boolean> tlNeedsRefresh = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final Map<String, String> harvestedHeaders = new ConcurrentHashMap<>();
    private final AtomicReference<String> cookieHeaderRef = new AtomicReference<>("");

    // ==================== STATE TRACKING ====================
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private final AtomicBoolean schedulesStarted = new AtomicBoolean(false);
    private final AtomicBoolean setupCompleted = new AtomicBoolean(false);
    private final AtomicBoolean profileRotationInProgress = new AtomicBoolean(false);
    private final AtomicBoolean needsSessionRefresh = new AtomicBoolean(false);

    // ✅ Track in-progress fetches per sport to prevent overlap
    private final AtomicBoolean footballFetchInProgress = new AtomicBoolean(false);
    private final AtomicBoolean basketballFetchInProgress = new AtomicBoolean(false);
    private final AtomicBoolean tableTennisFetchInProgress = new AtomicBoolean(false);

    private final AtomicInteger activeDetailFetches = new AtomicInteger(0);
    private final Map<String, Long> lastFetchTime = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRequestTime = new ConcurrentHashMap<>();
    private final Map<String, Long> requestStartTimes = new ConcurrentHashMap<>();  // ✅ Track request start times

    // Rate limit metrics
    private final AtomicInteger consecutiveRateLimitErrors = new AtomicInteger(0);
    private final AtomicInteger consecutiveTimeouts = new AtomicInteger(0);
    private final AtomicInteger consecutiveNetworkErrors = new AtomicInteger(0);
    private final AtomicLong lastProfileRotation = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger requestsSinceLastRotation = new AtomicInteger(0);

    // ✅ Adaptive cadence
    private final AtomicLong dynamicCadenceSec = new AtomicLong(MIN_SCHEDULER_PERIOD_SEC);
    private volatile ScheduledFuture<?> activeFetchSchedule;

    private final PriorityBlockingQueue<EventFetchTask> eventQueue = new PriorityBlockingQueue<>(
            1000,
            Comparator.comparingInt(EventFetchTask::getPriority).reversed()
    );

    // ==================== CONTEXT WRAPPER ====================
    private static class ContextWrapper {
        @Getter
        private final BrowserContext context;
        @Getter
        private final UserAgentProfile profile;
        private final long createdAt;

        public ContextWrapper(BrowserContext context, UserAgentProfile profile) {
            this.context = context;
            this.profile = profile;
            this.createdAt = System.currentTimeMillis();
        }

        public boolean isStale() {
            return System.currentTimeMillis() - createdAt > CONTEXT_MAX_AGE_MS;
        }
    }

    // ==================== TASK MODEL ====================
    private static class EventFetchTask {
        @Getter private final String eventId;
        @Getter private final String clientKey;
        private final boolean isLive;
        @Getter private final long timestamp;
        @Getter private final long requestSentTime;  // ✅ Track when request was sent

        public EventFetchTask(String eventId, String clientKey, boolean isLive, long requestSentTime) {
            this.eventId = eventId;
            this.clientKey = clientKey;
            this.isLive = isLive;
            this.timestamp = System.currentTimeMillis();
            this.requestSentTime = requestSentTime;
        }

        public int getPriority() {
            return isLive ? 100 : 50;
        }
    }

    // ==================== MAIN RUN ====================
    @Override
    public void run() {
        log.info("=== Starting Bet9jaOddsFetcher (Live Arb Optimized) ===");
        log.info("InitialCadence={}s, Dedup={}ms, StaleThreshold={}ms, DetailThreads={}, ProcessingThreads={}",
                MIN_SCHEDULER_PERIOD_SEC, EVENT_DEDUP_WINDOW_MS, STALE_DATA_THRESHOLD_MS,
                EVENT_DETAIL_THREADS, PROCESSING_THREADS);

        playwrightRef.set(Playwright.create());

        try {
            performInitialSetupWithRetry();
            initializeContextPool();
            startPoolWarmer();
            startSchedulers();
            startQueueProcessor();
            runHealthMonitor();
        } catch (RuntimeException setupEx) {
            log.error("Could not complete initial setup, entering recovery mode", setupEx);
            runRecoveryMode();
        } catch (Exception fatal) {
            log.error("Fatal error in main loop", fatal);
        } finally {
            cleanup();
        }
    }

    private void runHealthMonitor() throws InterruptedException {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                log.info("Tick — queued={}, activeFetches={}, rotationInProgress={}, setupOK={}",
                        eventQueue.size(), activeDetailFetches.get(), profileRotationInProgress.get(), setupCompleted.get());
            } catch (Throwable t) {
                log.error("Heartbeat error: {}", t.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);

        // Start adaptive cadence monitor
        scheduler.scheduleAtFixedRate(this::adjustCadenceBasedOnResponseTime,
                10, 10, TimeUnit.SECONDS);

        while (isRunning.get()) {
            Thread.sleep(Duration.ofSeconds(30).toMillis());

            logHealthMetrics();
            checkThreadLocalClientsHealth();

            if (shouldRotateProfile()) {
                log.info("Rate limit detected! Triggering fast profile rotation...");
                triggerProfileRotation();
            }

            if (needsSessionRefresh.get()) {
                log.info("Triggering streaming session refresh...");
                performStreamingSessionRefresh();
            }

            cleanupStaleFetchTimes();
        }
    }

    //Adaptive cadence adjustment
    private void adjustCadenceBasedOnResponseTime() {
        long avgResponseTimeMs = calculateAverageResponseTime();

        long newCadenceSec;
        if (avgResponseTimeMs < 2000) {
            // Fast responses, use minimum cadence
            newCadenceSec = MIN_SCHEDULER_PERIOD_SEC;
        } else if (avgResponseTimeMs < 5000) {
            // Moderate responses, use 5s cadence
            newCadenceSec = 5;
        } else {
            // Slow responses (5s+), increase cadence to prevent pileup
            newCadenceSec = (avgResponseTimeMs / 1000) + 2;
            newCadenceSec = Math.min(newCadenceSec, MAX_SCHEDULER_PERIOD_SEC);
        }

        long oldCadence = dynamicCadenceSec.getAndSet(newCadenceSec);
        if (oldCadence != newCadenceSec) {
            log.warn("⚠️ Cadence adjusted: {}s → {}s (avg response: {}ms)",
                    oldCadence, newCadenceSec, avgResponseTimeMs);

            // Restart scheduler with new cadence
            if (activeFetchSchedule != null) {
                activeFetchSchedule.cancel(false);
            }
            activeFetchSchedule = scheduler.scheduleAtFixedRate(
                    () -> safeWrapper("AllSports", this::fetchAllSportsParallel),
                    0, newCadenceSec, TimeUnit.SECONDS
            );
        }
    }

    // Calculate average response time from recent requests
    private long calculateAverageResponseTime() {
        if (recentResponseTimes.isEmpty()) {
            return 0;
        }

        long sum = 0;
        int count = 0;
        for (Long time : recentResponseTimes) {
            sum += time;
            count++;
        }

        return count > 0 ? sum / count : 0;
    }

    // Track response time
    private void recordResponseTime(long responseTimeMs) {
        recentResponseTimes.offer(responseTimeMs);

        // Keep only recent N response times
        while (recentResponseTimes.size() > RESPONSE_TIME_WINDOW) {
            recentResponseTimes.poll();
        }
    }

    private void logHealthMetrics() {
        int active = activeDetailFetches.get();
        int queued = eventQueue.size();
        int netErrors = consecutiveNetworkErrors.get();
        int rateLimitErrors = consecutiveRateLimitErrors.get();
        int timeouts = consecutiveTimeouts.get();
        int requests = requestsSinceLastRotation.get();
        long timeSinceRotation = System.currentTimeMillis() - lastProfileRotation.get();
        int poolSize = contextPool.size();
        long avgResponseTime = calculateAverageResponseTime();
        long currentCadence = dynamicCadenceSec.get();

        log.info("Health — Active: {}, Queued: {}, NetErrors: {}, RateLimit: {}, Timeouts: {}, " +
                        "Requests: {}, TimeSinceRotation: {}s, PoolSize: {}, AvgResponse: {}ms, Cadence: {}s, SetupOK: {}",
                active, queued, netErrors, rateLimitErrors, timeouts, requests,
                timeSinceRotation / 1000, poolSize, avgResponseTime, currentCadence, setupCompleted.get());

        // ✅ CRITICAL ALERT for slow responses
        if (avgResponseTime > 5000) {
            log.error("❌❌❌ CRITICAL: Average response time {}ms - LIVE ARB INEFFECTIVE! ❌❌❌",
                    avgResponseTime);
        } else if (avgResponseTime > 3000) {
            log.warn("⚠️ WARNING: Average response time {}ms - approaching live arb threshold",
                    avgResponseTime);
        }
    }

    private void cleanupStaleFetchTimes() {
        if (lastFetchTime.size() > 10_000) {
            long cutoff = System.currentTimeMillis() - 60_000;
            lastFetchTime.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
    }

    private void runRecoveryMode() {
        while (isRunning.get()) {
            try {
                Thread.sleep(30_000);
                log.info("Attempting setup recovery...");
                performInitialSetupWithRetry();

                if (setupCompleted.get()) {
                    log.info("Setup recovery successful, starting schedulers...");
                    initializeContextPool();
                    startPoolWarmer();
                    startSchedulers();
                    startQueueProcessor();
                    runHealthMonitor();
                    break;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception retryEx) {
                log.error("Setup recovery failed: {}", retryEx.getMessage());
            }
        }
    }

    // ==================== CONTEXT POOL MANAGEMENT ====================
    private void initializeContextPool() {
        log.info("=== Initializing context pool (size: {}) ===", CONTEXT_POOL_SIZE);

        Playwright pw = playwrightRef.get();
        if (pw == null) {
            throw new IllegalStateException("Playwright not initialized");
        }

        sharedBrowser = launchBrowser(pw);
        log.info("Shared browser created");

        for (int i = 0; i < CONTEXT_POOL_SIZE; i++) {
            try {
                ContextWrapper wrapper = createNewContextWrapper();
                contextPool.offer(wrapper);
                log.info("Pre-warmed context {}/{}", i + 1, CONTEXT_POOL_SIZE);
            } catch (Exception e) {
                log.error("Failed to pre-warm context: {}", e.getMessage());
            }
        }

        log.info("Context pool initialized with {} contexts", contextPool.size());
    }

    private ContextWrapper createNewContextWrapper() {
        log.info("Creating new context wrapper");
        UserAgentProfile newProfile = profileManager.getNextProfile();
        log.info("Creating context with profile: Platform={}, UA={}...",
                newProfile.getPlatform(),
                newProfile.getUserAgent().substring(0, Math.min(50, newProfile.getUserAgent().length())));

        BrowserContext context = newContext(sharedBrowser, newProfile);
        attachAntiDetection(context, newProfile);

        return new ContextWrapper(context, newProfile);
    }

    private void startPoolWarmer() {
        log.info("Starting context pool warmer");
        poolWarmerExecutor.submit(() -> {
            while (isRunning.get()) {
                try {
                    maintainContextPool();
                    Thread.sleep(10_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Pool warmer error: {}", e.getMessage());
                }
            }
        });
    }

    private void maintainContextPool() {
        contextPool.removeIf(wrapper -> {
            if (wrapper.isStale()) {
                log.info("Removing stale context from pool (age: {}ms)",
                        System.currentTimeMillis() - wrapper.createdAt);
                safeClose(wrapper.getContext());
                return true;
            }
            return false;
        });

        while (contextPool.size() < CONTEXT_POOL_SIZE) {
            try {
                ContextWrapper wrapper = createNewContextWrapper();
                contextPool.offer(wrapper);
                log.info("Pool refilled: {}/{}", contextPool.size(), CONTEXT_POOL_SIZE);
            } catch (Exception e) {
                log.error("Failed to refill pool: {}", e.getMessage());
                break;
            }
        }
    }

    private void refillContextPoolAsync() {
        poolWarmerExecutor.submit(() -> {
            try {
                if (contextPool.size() < CONTEXT_POOL_SIZE) {
                    ContextWrapper wrapper = createNewContextWrapper();
                    contextPool.offer(wrapper);
                    log.info("Pool async refill: {}/{}", contextPool.size(), CONTEXT_POOL_SIZE);
                }
            } catch (Exception e) {
                log.error("Pool async refill failed: {}", e.getMessage());
            }
        });
    }

    // ==================== OKHTTP CLIENT MANAGEMENT ====================
    private OkHttpClient createNewOkHttpClient() {
        log.info("Creating new OkHttp client for thread: {}", Thread.currentThread().getName());

        ConnectionPool connectionPool = new ConnectionPool(50, 5, TimeUnit.MINUTES);

        return new OkHttpClient.Builder()
                .connectionPool(connectionPool)
                .connectTimeout(API_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(API_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(API_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .addInterceptor(new SimpleHttpLoggingInterceptor())
                .addInterceptor(new HeadersInterceptor())
                .build();
    }

    private class HeadersInterceptor implements Interceptor {
        @Override
        public okhttp3.Response intercept(Chain chain) throws IOException {
            Request original = chain.request();
            String cookies = cookieHeaderRef.get();

            if (cookies.isEmpty()) {
                log.warn("⚠️ WARNING: No cookies set for request to {}", original.url());
            }

            // ✅ EXACT headers from Network tab
            Request.Builder builder = original.newBuilder()
                    // SportyBet custom headers
                    .header("operid", "2")
                    .header("platform", "web")
                    .header("clientid", "web")

                    // Accept headers
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en")  // Changed from "en-US,en;q=0.9" to match exactly
                    .header("Accept-Encoding", "gzip, deflate, br, zstd")

                    // Content-Type
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")

                    // Referer (NO Origin - it's not in the real request)
                    .header("Referer", SPORT_PAGE)

                    // sec-ch-ua headers
                    .header("sec-ch-ua", profile.getHeaders().getClientHintsHeaders().get("Sec-CH-UA"))
                    .header("sec-ch-ua-mobile", profile.getHeaders().getClientHintsHeaders().get("Sec-CH-UA-Mobile"))
                    .header("sec-ch-ua-platform", profile.getHeaders().getClientHintsHeaders().get("Sec-CH-UA-Platform"))

                    // sec-fetch headers (CORRECT for API XHR)
                    .header("sec-fetch-dest", "empty")
                    .header("sec-fetch-mode", "same-origin")
                    .header("sec-fetch-site", "same-origin")

                    // Priority
                    .header("Priority", "u=1, i")

                    // User Agent
                    .header("User-Agent", profile != null ? profile.getUserAgent() :
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0")

                    // Cookies
                    .header("Cookie", cookies);

            // Only add authorization/token headers from harvested
            harvestedHeaders.forEach((key, value) -> {
                String lowerKey = key.toLowerCase();
                if (lowerKey.equals("authorization") ||
                        lowerKey.equals("x-csrf-token") ||
                        lowerKey.equals("x-api-key") ||
                        (lowerKey.startsWith("x-") && lowerKey.contains("token"))) {
                    builder.header(key, value);
                }
            });

            Request builtRequest = builder.build();

            // Log first request only
            if (log.isInfoEnabled() && requestsSinceLastRotation.get() == 0) {
                log.info("📤 FINAL REQUEST HEADERS:");
                Headers finalHeaders = builtRequest.headers();
                for (int i = 0; i < finalHeaders.size(); i++) {
                    log.info("   {} = {}", finalHeaders.name(i),
                            finalHeaders.value(i).length() > 80 ?
                                    finalHeaders.value(i).substring(0, 80) + "..." :
                                    finalHeaders.value(i));
                }
            }

            return chain.proceed(builtRequest);
        }
    }

    private OkHttpClient getThreadLocalClient() {
        Long threadVersion = threadLocalProfileVersion.get();
        Long currentVersion = profileVersion.get();

        if (!threadVersion.equals(currentVersion) || tlNeedsRefresh.get() || globalNeedsRefresh.get()) {
            log.info("Thread {} refreshing OkHttp client (version: {} -> {})",
                    Thread.currentThread().getName(), threadVersion, currentVersion);

            OkHttpClient oldClient = threadLocalClients.get();
            if (oldClient != null) {
                shutdownOkHttpClient(oldClient);
            }

            OkHttpClient newClient = createNewOkHttpClient();
            threadLocalClients.set(newClient);
            threadLocalProfileVersion.set(currentVersion);
            tlNeedsRefresh.set(false);
        }

        return threadLocalClients.get();
    }

    private void shutdownOkHttpClient(OkHttpClient client) {
        try {
            if (client.connectionPool() != null) {
                client.connectionPool().evictAll();
            }
            if (client.dispatcher() != null) {
                client.dispatcher().executorService().shutdown();
            }
            if (client.cache() != null) {
                client.cache().close();
            }
        } catch (Exception e) {
            log.debug("Error shutting down OkHttp client: {}", e.getMessage());
        }
    }

    // ==================== OPTIMIZED PROFILE ROTATION ====================
    private boolean shouldRotateProfile() {
        int rateLimitErrors = consecutiveRateLimitErrors.get();
        int timeouts = consecutiveTimeouts.get();
        long timeSinceLastRotation = System.currentTimeMillis() - lastProfileRotation.get();
        int requests = requestsSinceLastRotation.get();

        if (rateLimitErrors >= RATE_LIMIT_THRESHOLD) {
            log.info("Rate limit threshold reached: {} consecutive errors", rateLimitErrors);
            return true;
        }

        if (timeouts >= 3) {
            log.info("Timeout threshold reached: {} consecutive timeouts", timeouts);
            return true;
        }

        if (timeSinceLastRotation > 300_000 && requests > 100) {
            log.info("Proactive profile rotation: {}s elapsed, {} requests made",
                    timeSinceLastRotation / 1000, requests);
            return true;
        }

        if (requests > 500) {
            log.info("Proactive profile rotation: {} requests threshold reached", requests);
            return true;
        }

        return false;
    }

    private void triggerProfileRotation() {
        if (profileRotationInProgress.compareAndSet(false, true)) {
            log.info("=== Triggering Fast Profile Rotation ===");
            profileRotationExecutor.submit(() -> {
                try {
                    performFastProfileRotation();
                } catch (Exception e) {
                    log.error("Profile rotation failed: {}", e.getMessage(), e);
                } finally {
                    profileRotationInProgress.set(false);
                }
            });
        } else {
            log.info("Profile rotation already in progress, skipping trigger");
        }
    }

    private void performFastProfileRotation() {
        log.info("=== Fast Profile Rotation (Context Pool + OkHttp) ===");
        long startTime = System.currentTimeMillis();

        globalNeedsRefresh.set(true);

        try {
            Thread.sleep(100);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }

        ContextWrapper newWrapper = contextPool.poll();

        if (newWrapper == null) {
            log.info("Context pool empty, creating new context (slower path)");
            newWrapper = createNewContextWrapper();
        }

        Page page = null;
        try {
            page = newWrapper.getContext().newPage();
            Map<String, String> captured = new HashMap<>();
            attachNetworkTaps(page, captured);

            page.navigate(SPORT_PAGE, new Page.NavigateOptions()
                    .setTimeout(15_000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            String cookieHeader = formatCookies(newWrapper.getContext().cookies());

            ContextWrapper oldContext = activeContext;

            // Atomic swap
            profile = newWrapper.getProfile();
            cookieHeaderRef.set(cookieHeader);
            harvestedHeaders.clear();
            harvestedHeaders.putAll(captured);
            activeContext = newWrapper;

            long newVersion = profileVersion.incrementAndGet();
            resetRateLimitMetrics();

            // Force all threads to recreate OkHttp clients on next request
            globalNeedsRefresh.set(true);

            long duration = System.currentTimeMillis() - startTime;
            log.info("Fast rotation complete in {}ms! Version: {}, cookies: {} bytes, headers: {}",
                    duration, newVersion, cookieHeader.length(), captured.size());

            if (oldContext != null) {
                safeClose(oldContext.getContext());
            }

            refillContextPoolAsync();

        } catch (Exception e) {
            log.error("Fast rotation failed: {}", e.getMessage(), e);
            if (page == null && newWrapper != null) {
                contextPool.offer(newWrapper);
            }
        } finally {
            if (page != null) {
                safeClose(page);
            }
            globalNeedsRefresh.set(false);
        }
    }

    private void resetRateLimitMetrics() {
        consecutiveRateLimitErrors.set(0);
        consecutiveTimeouts.set(0);
        consecutiveNetworkErrors.set(0);
        requestsSinceLastRotation.set(0);
        lastProfileRotation.set(System.currentTimeMillis());
    }

    // ==================== INITIAL SETUP WITH RETRY ====================
    private void performInitialSetupWithRetry() {
        int attempt = 0;
        Exception lastException = null;

        while (attempt < INITIAL_SETUP_MAX_ATTEMPTS) {
            attempt++;
            try {
                log.info("=== Initial setup attempt {}/{} ===", attempt, INITIAL_SETUP_MAX_ATTEMPTS);
                performInitialSetup();
                setupCompleted.set(true);
                log.info("=== Initial setup completed successfully on attempt {} ===", attempt);
                return;
            } catch (Exception e) {
                lastException = e;
                log.error("Initial setup attempt {}/{} failed: {}",
                        attempt, INITIAL_SETUP_MAX_ATTEMPTS, e.getMessage());

                if (attempt < INITIAL_SETUP_MAX_ATTEMPTS) {
                    backoffBeforeRetry(attempt);
                }
            }
        }

        log.error("Initial setup failed after {} attempts", INITIAL_SETUP_MAX_ATTEMPTS);
        throw new RuntimeException(
                "Initial setup failed after " + INITIAL_SETUP_MAX_ATTEMPTS + " attempts",
                lastException
        );
    }

    private void backoffBeforeRetry(int attempt) {
        try {
            long backoffMs = 2000L * attempt;
            log.info("Waiting {}ms before retry...", backoffMs);
            Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Setup interrupted during backoff", ie);
        }
    }

    private void performInitialSetup() {
        log.info("=== Performing initial setup (harvest headers/cookies) ===");

        Playwright pw = playwrightRef.get();
        if (pw == null) {
            throw new IllegalStateException("Playwright not initialized");
        }

        Browser browser = null;
        BrowserContext context = null;
        Page page = null;

        try {
            profile = profileManager.getNextProfile();
            log.info("Using profile: UA={}, Platform={}",
                    profile.getUserAgent().substring(0, Math.min(50, profile.getUserAgent().length())),
                    profile.getPlatform());

            browser = launchBrowser(pw);
            context = newContext(browser, profile);
            attachAntiDetection(context, profile);
            page = context.newPage();

            Map<String, String> captured = new HashMap<>();
            attachNetworkTaps(page, captured);
            performInitialNavigationWithRetry(page);

            String cookieHeader = formatCookies(context.cookies());
            cookieHeaderRef.set(cookieHeader);
            harvestedHeaders.clear();
            harvestedHeaders.putAll(captured);

            log.info("=== CAPTURED HEADERS SUMMARY ===");
            log.info("Cookies: {} bytes", cookieHeader.length());
            log.info("Headers count: {}", harvestedHeaders.size());

            boolean hasOperid = harvestedHeaders.containsKey("operid");
            boolean hasPlatform = harvestedHeaders.containsKey("platform");
            boolean hasSecChUa = harvestedHeaders.containsKey("sec-ch-ua");
            boolean hasSecFetchMode = harvestedHeaders.containsKey("sec-fetch-mode");

            log.info("Has operid: {}", hasOperid);
            log.info("Has platform: {}", hasPlatform);
            log.info("Has sec-ch-ua: {}", hasSecChUa);
            log.info("Has sec-fetch-mode: {}", hasSecFetchMode);

            if (!hasOperid || !hasPlatform) {
                log.error("❌ CRITICAL: Missing operid or platform headers!");
            }
            if (!hasSecChUa || !hasSecFetchMode) {
                log.info("⚠️ WARNING: Missing sec-ch-ua or sec-fetch headers!");
            }

            log.info("All captured headers: {}", harvestedHeaders.keySet());
            log.info("================================");
            // Create initial shared OkHttp client
            sharedOkHttpClient = createNewOkHttpClient();
            log.info("Shared OkHttp client created");

        } finally {
            safeClose(page);
            safeClose(context);
            safeClose(browser);
        }

        globalNeedsRefresh.set(true);
        profileVersion.incrementAndGet();
        lastProfileRotation.set(System.currentTimeMillis());
    }

    // ==================== SCHEDULERS & PARALLEL LIST FETCH ====================
    private void startSchedulers() {
        if (!schedulesStarted.compareAndSet(false, true)) {
            log.info("Schedulers already started");
            return;
        }
        log.info("Starting ADAPTIVE schedulers with initial cadence {}s (live arbing mode)", MIN_SCHEDULER_PERIOD_SEC);

        activeFetchSchedule = scheduler.scheduleAtFixedRate(
                () -> safeWrapper("AllSports", this::fetchAllSportsParallel),
                0, MIN_SCHEDULER_PERIOD_SEC, TimeUnit.SECONDS
        );
    }

    //Prevent overlapping requests
    private void fetchAllSportsParallel() {
        if (!setupCompleted.get()) {
            log.info("Skipping fetch - setup not completed");
            return;
        }

        if (profileRotationInProgress.get()) {
            log.info("Skipping fetch - profile rotation in progress");
            return;
        }

        long start = System.currentTimeMillis();

        // Only start new fetch if previous completed
        if (footballFetchInProgress.compareAndSet(false, true)) {
            runSportListTaskWithFlag("Football", KEY_FB, KEY_FB, footballFetchInProgress);
        } else {
            log.warn("⚠️ Skipping Football fetch - previous request still in progress");
        }

        if (basketballFetchInProgress.compareAndSet(false, true)) {
            runSportListTaskWithFlag("Basketball", KEY_BB, KEY_BB, basketballFetchInProgress);
        } else {
            log.warn("⚠️ Skipping Basketball fetch - previous request still in progress");
        }

        if (tableTennisFetchInProgress.compareAndSet(false, true)) {
            runSportListTaskWithFlag("TableTennis", KEY_TT, KEY_TT, tableTennisFetchInProgress);
        } else {
            log.warn("⚠️ Skipping TableTennis fetch - previous request still in progress");
        }

        long duration = System.currentTimeMillis() - start;
        log.info("All sports fetch cycle triggered in {}ms [queue={}, active={}]",
                duration, eventQueue.size(), activeDetailFetches.get());
    }


    private void runSportListTaskWithFlag(String sportName, String sportId,
                                          String clientKey, AtomicBoolean inProgressFlag) {
        CompletableFuture
                .runAsync(() -> {
                    try {
                        fetchSportEventsList(sportName, sportId, clientKey);
                    } finally {
                        inProgressFlag.set(false);
                    }
                }, listFetchExecutor)
                .orTimeout(8, TimeUnit.SECONDS)  // Fail fast if taking too long
                .exceptionally(ex -> {
                    inProgressFlag.set(false);  // Reset flag on failure
                    if (ex instanceof TimeoutException || (ex.getCause() instanceof TimeoutException)) {
                        int timeouts = consecutiveTimeouts.incrementAndGet();
                        log.warn("⚠️ {}: List fetch timeout after 8s - SLOW NETWORK! (timeout #{})",
                                sportName, timeouts);
                    } else {
                        String msg = ex.getMessage();
                        log.warn("⚠️ {}: List fetch error: {}", sportName, msg);
                    }
                    return null;
                });
    }

    // ✅ Track request start time and response time
    private void fetchSportEventsList(String sportName, String sportId, String clientKey) {
        long fetchStart = System.currentTimeMillis();
        requestStartTimes.put(clientKey, fetchStart);

        try {
            if (shouldSkipRequest(clientKey)) {
                log.info("{}: Skipping - too soon after last request", sportName);
                return;
            }

            String url = buildEventsListUrl(sportId);
            log.info("{}: Fetching events list from API...", sportName);

            String body = safeApiGet(url, clientKey, 0, LIST_API_TIMEOUT_MS);
            long apiDuration = System.currentTimeMillis() - fetchStart;

            //Record response time for adaptive cadence
            recordResponseTime(apiDuration);

            // ✅ ALERT if too slow
            if (apiDuration > 5000) {
                log.error("❌ CRITICAL: {} response took {}ms - TOO SLOW FOR LIVE ARB!",
                        sportName, apiDuration);
            } else if (apiDuration > 3000) {
                log.warn("⚠️ WARNING: {} response took {}ms - approaching threshold",
                        sportName, apiDuration);
            } else {
                log.info("✅ {}: Response received in {}ms", sportName, apiDuration);
            }

            consecutiveNetworkErrors.set(0);

            if (body == null || body.isEmpty()) {
                log.info("{}: Empty response from API ({}ms)", sportName, apiDuration);
                return;
            }

            processEventsListResponse(sportName, body, clientKey, fetchStart);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - fetchStart;
            log.error("{}: List fetch failed after {}ms - {}", sportName, duration, e.getMessage());
            handleNetworkError(sportName, e);
        } finally {
            requestStartTimes.remove(clientKey);
        }
    }

    private boolean shouldSkipRequest(String clientKey) {
        Long lastReq = lastRequestTime.get(clientKey);
        long now = System.currentTimeMillis();
        if (lastReq != null && (now - lastReq) < 100) {
            return true;
        }
        lastRequestTime.put(clientKey, now);
        return false;
    }

    private String buildEventsListUrl(String sportId) {
        return buildUrl(BASE_URL + "/desktop/feapi/PalimpsestLiveAjax/GetLiveEventsV3",
                Map.of("SID", sportId, "v_cache_version", "1.295.4.219"));
    }

    private void processEventsListResponse(String sportName, String body, String clientKey, long fetchStart) {
        List<String> eventIds = extractEventIds(body);

        if (eventIds == null || eventIds.isEmpty()) {
            long apiDuration = System.currentTimeMillis() - fetchStart;
            log.info("{}: No events found in response ({}ms)", sportName, apiDuration);
            log.debug("{}: Response preview: {}", sportName,
                    body.length() > 200 ? body.substring(0, 200) : body);
            return;
        }
        log.info("Extracted {} live events for {}", eventIds.size(), sportName);

        int queued = 0, skipped = 0;
        for (String eventId : eventIds) {
            if (isEventRecentlyFetched(eventId)) {
                skipped++;
                continue;
            }

            lastFetchTime.put(eventId, System.currentTimeMillis());
            boolean isLive = isLiveEvent(body, eventId);
            // Pass request sent time to task
            eventQueue.offer(new EventFetchTask(eventId, clientKey, isLive, fetchStart));
            queued++;
        }

        if (queued > 0) {
            long totalDuration = System.currentTimeMillis() - fetchStart;
            log.info("{}: Completed in {}ms - queued {}/{} events (skipped: {})",
                    sportName, totalDuration, queued, eventIds.size(), skipped);
        }
    }

    private boolean isEventRecentlyFetched(String eventId) {
        Long last = lastFetchTime.get(eventId);
        return last != null && (System.currentTimeMillis() - last) < EVENT_DEDUP_WINDOW_MS;
    }

    // ==================== QUEUE PROCESSOR ====================
    private void startQueueProcessor() {
        for (int i = 0; i < EVENT_DETAIL_THREADS; i++) {
            eventDetailExecutor.submit(this::processEventQueue);
        }
    }

    private void processEventQueue() {
        while (isRunning.get()) {
            try {
                if (!setupCompleted.get()) {
                    Thread.sleep(1000);
                    continue;
                }

                if (profileRotationInProgress.get()) {
                    Thread.sleep(500);
                    continue;
                }

                if (activeDetailFetches.get() > MAX_ACTIVE_FETCHES) {
                    Thread.sleep(100);
                    continue;
                }

                EventFetchTask task = eventQueue.poll(1, TimeUnit.SECONDS);
                if (task == null) continue;

                if (isTaskStale(task)) {
                    long age = System.currentTimeMillis() - task.getTimestamp();
                    log.info("Dropping stale task: eventId={}, age={}ms", task.getEventId(), age);
                    continue;
                }

                activeDetailFetches.incrementAndGet();
                try {
                    fetchAndProcessEventDetailAsync(task);
                } finally {
                    activeDetailFetches.decrementAndGet();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                log.error("Queue processor error: {}", ex.getMessage());
            }
        }
    }

    private boolean isTaskStale(EventFetchTask task) {
        long age = System.currentTimeMillis() - task.getTimestamp();
        long maxAge = (task.getPriority() > 50) ? 5_000 : 30_000;
        return age > maxAge;
    }

    // ==================== STREAMING SESSION REFRESH ====================
    private void performStreamingSessionRefresh() {
        log.info("=== Streaming session refresh started ===");
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                performFastProfileRotation();
                needsSessionRefresh.set(false);
            } catch (Exception e) {
                log.error("Streaming refresh failed: {}", e.getMessage());
                needsSessionRefresh.set(false);
            }
        });
    }

    // ==================== DETAIL FETCH & PROCESS ====================
    //accepts task with timestamp
    private void fetchAndProcessEventDetailAsync(EventFetchTask task) {
        try {
            String url = buildEventDetailUrl(task.getEventId());
            long detailFetchStart = System.currentTimeMillis();

            String body = safeApiGet(url, task.getClientKey(), 0, DETAIL_API_TIMEOUT_MS);
            if (body == null || body.isBlank()) return;

            long detailFetchDuration = System.currentTimeMillis() - detailFetchStart;
            recordResponseTime(detailFetchDuration);

            processingExecutor.submit(() -> {
                try {
                    // Check data age before parsing
                    long dataAge = System.currentTimeMillis() - task.getRequestSentTime();

                    if (dataAge > STALE_DATA_THRESHOLD_MS) {
                        log.warn("⚠️ REJECTING STALE DATA: Event {} is {}ms old (threshold: {}ms)",
                                task.getEventId(), dataAge, STALE_DATA_THRESHOLD_MS);
                        return;  // Don't process stale odds
                    }

                    Bet9jaEvent domainEvent = parseEventDetail(body);
                    if (domainEvent != null) {
                        processParsedEvent(domainEvent, dataAge);
                    }
                } catch (Exception ex) {
                    log.info("Process failed for {}: {}", task.getEventId(), ex.getMessage());
                }
            });
        } catch (Exception e) {
            log.info("Detail fetch failed for {}: {}", task.getEventId(), e.getMessage());
        }
    }

    private Bet9jaEvent parseEventDetail(String detailJson) {
        return JsonParser.deserializeBet9jaEvent(detailJson, objectMapper);
    }

    // includes data age
    private void processParsedEvent(Bet9jaEvent event, long dataAge) {
        if (event == null) return;

        try {
            NormalizedEvent normalized = bet9jaService.convertToNormalEvent(event);
            if (normalized == null) return;

            // ✅ Add metadata about data freshness
//            normalized.setFetchTimestamp(System.currentTimeMillis() - dataAge);

            if (dataAge > 3000) {
                log.debug("Processing event {} with data age: {}ms", event.getEventHeader().getId(), dataAge);
            }

            CompletableFuture.runAsync(() -> processBetRetryInfo(normalized), retryExecutor)
                    .exceptionally(ex -> null);

            if (arbDetector != null) {
                // Only add fresh data to arb detection
                arbDetector.addEventToPool(normalized);
            }
        } catch (Exception e) {
            log.info("processParsedEvent failed for {}: {}", event.getEventHeader().getId(), e.getMessage());
        }
    }

    private void processBetRetryInfo(NormalizedEvent normalizedEvent) {
        try {
            betLegRetryService.updateFailedBetLeg(normalizedEvent, SCRAPER_BOOKMAKER);
        } catch (Exception e) {
            log.info("BetLegRetry failed for {}: {}", normalizedEvent.getEventId(), e.getMessage());
        }
    }

    // ==================== HTTP LAYER (OKHTTP) ====================
    private String safeApiGet(String url, String clientKey, int retry, Integer perRequestTimeoutMs) {
        if (retry > API_MAX_RETRIES) {
            log.info("HTTP max retries exceeded for: {}", url);
            consecutiveRateLimitErrors.incrementAndGet();
            throw new RuntimeException("HTTP retried too many times: " + url);
        }

        long requestStart = System.currentTimeMillis();
        try {
            OkHttpClient client = getThreadLocalClient();

            // Apply per-request timeout if specified
            if (perRequestTimeoutMs != null) {
                client = client.newBuilder()
                        .readTimeout(perRequestTimeoutMs, TimeUnit.MILLISECONDS)
                        .build();
            }

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            try (okhttp3.Response response = client.newCall(request).execute()) {
                int status = response.code();
                long requestDuration = System.currentTimeMillis() - requestStart;

                requestsSinceLastRotation.incrementAndGet();

                return handleOkHttpResponse(url, clientKey, retry, response, status, requestDuration, perRequestTimeoutMs);
            }

        } catch (IOException e) {
            return handleIOException(url, clientKey, retry, requestStart, e, perRequestTimeoutMs);
        } catch (Exception e) {
            long requestDuration = System.currentTimeMillis() - requestStart;
            log.info("API request failed after {}ms: {}", requestDuration, e.getMessage());
            throw new RuntimeException("API request failed: " + e.getMessage(), e);
        }
    }

    private String handleOkHttpResponse(String url, String clientKey, int retry,
                                        okhttp3.Response response, int status,
                                        long requestDuration, Integer perRequestTimeoutMs) {
        try {
            if (status == 429) {
                return handleRateLimitResponse(url, clientKey, retry, requestDuration, perRequestTimeoutMs);
            }

            if (status == 401 || status == 403) {
                return handleAuthErrorResponse(url, clientKey, retry, status, requestDuration, perRequestTimeoutMs);
            }

            if (requestDuration > SLOW_REQUEST_THRESHOLD_MS) {
                detectSlowRequest(requestDuration);
            }

            if (status < 200 || status >= 300) {
                log.info("HTTP {} for {} (took {}ms)", status, url, requestDuration);
                throw new RuntimeException("HTTP " + status + " on " + url);
            }

            consecutiveRateLimitErrors.set(0);

            if (requestDuration > 5000) {
                log.info("Slow API response: {}ms for {}", requestDuration, url);
            }


            ResponseBody body = response.body();
            if (body == null) {
                return null;
            }

            log.info("Response body {}", body.string());

            String contentEncoding = response.header("Content-Encoding");
            long contentLength = body.contentLength();

            log.info("Response headers - Content-Encoding: {}, Content-Length: {}",
                    contentEncoding, contentLength);

            // If Content-Encoding is null or not "gzip", it's already decompressed
            if (contentEncoding == null || !contentEncoding.equalsIgnoreCase("gzip")) {
                log.info("Response is NOT gzip encoded, reading directly");
                String responseText = body.string();
                log.info("Read {} bytes directly", responseText.length());
                return responseText;
            } else {
                log.info("Response is still GZIP encoded! Manual decompression needed");
                byte[] gzipBytes = body.bytes();
                return JsonParser.decompressGzipToJson(gzipBytes);
            }

        } catch (IOException e) {
            log.error("Failed to read response body: {}", e.getMessage());
            return null;
        }
    }

    private String handleRateLimitResponse(String url, String clientKey, int retry,
                                           long requestDuration, Integer perRequestTimeoutMs) {
        int rateLimitCount = consecutiveRateLimitErrors.incrementAndGet();
        log.info("Rate limit detected (429) on attempt {} - count: {}, duration: {}ms",
                retry + 1, rateLimitCount, requestDuration);

        try {
            Thread.sleep(1000 * (retry + 1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during rate limit backoff", ie);
        }

        return safeApiGet(url, clientKey, retry + 1, perRequestTimeoutMs);
    }

    private String handleAuthErrorResponse(String url, String clientKey, int retry,
                                           int status, long requestDuration,
                                           Integer perRequestTimeoutMs) {
        int rateLimitCount = consecutiveRateLimitErrors.incrementAndGet();
        log.info("Auth/Forbidden error ({}) - possible rate limit, count: {}, duration: {}ms",
                status, rateLimitCount, requestDuration);

        tlNeedsRefresh.set(true);

        try {
            Thread.sleep(150);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during auth error backoff", ie);
        }

        return safeApiGet(url, clientKey, retry + 1, perRequestTimeoutMs);
    }

    private void detectSlowRequest(long requestDuration) {
        int rateLimitCount = consecutiveRateLimitErrors.incrementAndGet();
        log.info("Suspiciously slow request detected: {}ms (threshold: {}ms) - possible throttling, count: {}",
                requestDuration, SLOW_REQUEST_THRESHOLD_MS, rateLimitCount);
    }

    private String handleIOException(String url, String clientKey, int retry,
                                     long requestStart, IOException e,
                                     Integer perRequestTimeoutMs) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        long requestDuration = System.currentTimeMillis() - requestStart;

        if (msg.toLowerCase().contains("timeout")) {
            consecutiveTimeouts.incrementAndGet();
            log.info("Request timeout after {}ms: {}", requestDuration, msg);
        } else if (msg.toLowerCase().contains("connection")) {
            consecutiveNetworkErrors.incrementAndGet();
            log.info("Connection error after {}ms: {}", requestDuration, msg);
        } else {
            log.info("IO error after {}ms: {}", requestDuration, msg);
        }

        if (retry < API_MAX_RETRIES) {
            log.info("Retrying request (attempt {}/{})", retry + 1, API_MAX_RETRIES);
            tlNeedsRefresh.set(true);

            try {
                Thread.sleep(200 * (retry + 1));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during retry backoff", ie);
            }

            return safeApiGet(url, clientKey, retry + 1, perRequestTimeoutMs);
        }

        throw new RuntimeException("API request failed after retries: " + msg, e);
    }

    private void handleNetworkError(String context, Exception e) {
        int n = consecutiveNetworkErrors.incrementAndGet();
        String msg = e.getMessage() == null ? "" : e.getMessage();

        if (msg.contains("429") || msg.contains("Too Many Requests")) {
            consecutiveRateLimitErrors.incrementAndGet();
            log.info("{} rate limit error (#{}): {}", context, n, msg);
        } else if (msg.toLowerCase().contains("timeout")) {
            consecutiveTimeouts.incrementAndGet();
            log.info("{} timeout error (#{}): {}", context, n, msg);
        } else {
            log.info("{} network error (#{}): {}", context, n, msg);
        }

        if (n >= 3) {
            needsSessionRefresh.set(true);
        }
    }

    private void checkThreadLocalClientsHealth() {
        try {
            OkHttpClient client = threadLocalClients.get();
            if (client != null) {
                ConnectionPool pool = client.connectionPool();
                if (pool != null) {
                    int idle = pool.idleConnectionCount();
                    if (idle > 0) {
                        log.debug("Thread {} has {} idle connections",
                                Thread.currentThread().getName(), idle);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Thread-local client health check failed: {}", e.getMessage());
        }
    }

    // ==================== BROWSER CONTEXT BOOTSTRAP ====================
    private Browser launchBrowser(Playwright pw) {
        List<String> args = new ArrayList<>(scraperConfig.getBROWSER_FlAGS());

        if (!args.contains("--disable-dev-shm-usage")) {
            args.add("--disable-dev-shm-usage");
        }
        if (!args.contains("--no-sandbox")) {
            args.add("--no-sandbox");
        }

        return pw.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setTimeout(120_000)
                .setArgs(args));
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

    private Map<String, String> getAllHeaders(UserAgentProfile profile) {
        Map<String, String> all = new HashMap<>();
        if (profile.getHeaders().getStandardHeaders() != null) {
            all.putAll(profile.getHeaders().getStandardHeaders());
        }
        if (profile.getHeaders().getClientHintsHeaders() != null) {
            all.putAll(profile.getHeaders().getClientHintsHeaders());
        }
        return all;
    }

    private void attachAntiDetection(BrowserContext context, UserAgentProfile profile) {
        log.info("Injecting anti-detection stealth script");
        String stealthScript = buildStealthScript(profile);
        context.addInitScript(stealthScript);
        log.info("Stealth script injected successfully");
    }

    private String buildStealthScript(UserAgentProfile profile) {
        return String.format("""
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
                    context.fillStyle = profile.canvasFillStyle;
                    
                    const originalToDataURL = context.canvas.toDataURL;
                    context.canvas.toDataURL = function(type, quality) {
                        const imageData = context.getImageData(0, 0, this.width, this.height);
                        for (let i = 0; i < imageData.data.length; i += 10) {
                            imageData.data[i] = imageData.data[i] + Math.floor(Math.random() * 2);
                        }
                        context.putImageData(imageData, 0, 0);
                        return originalToDataURL.call(this, type, quality);
                    };
                    
                    const originalGetImageData = context.getImageData;
                    context.getImageData = function(...args) {
                        const imageData = originalGetImageData.call(this, ...args);
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
                if (parameter === 37445) return profile.webglVendor;
                if (parameter === 37446) return profile.webglRenderer;
                if (parameter === 7936) return profile.webgl.vendor;
                if (parameter === 7937) return profile.webgl.renderer;
                if (parameter === 7938) return profile.webgl.version;
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
            oscillator.frequency.value = profile.audioFrequency;
            
            const originalStart = oscillator.start;
            oscillator.start = function(when) {
                return originalStart.call(this, when + (Math.random() * 0.0001));
            };
            return oscillator;
        };

        // === Hardware & Device Properties ===
        Object.defineProperty(navigator, 'hardwareConcurrency', {
            get: () => profile.hardwareConcurrency,
            configurable: true
        });

        Object.defineProperty(navigator, 'deviceMemory', {
            get: () => profile.deviceMemory,
            configurable: true
        });

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

        // === Screen Properties ===
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

        // === Network Connection Spoofing ===
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
            navigator.getBattery = function() {
                return Promise.resolve(profile.battery);
            };
        }

        // === Timezone Spoofing ===
        Date.prototype.getTimezoneOffset = function() {
            const now = new Date();
            const tzString = now.toLocaleString('en-US', { timeZone: profile.timeZone });
            const localDate = new Date(tzString);
            const diff = (now.getTime() - localDate.getTime()) / 60000;
            return Math.round(diff);
        };

        // === Geolocation Spoofing ===
        if ('geolocation' in navigator) {
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
                    postscriptName: font.replace(/\\s+/g, '')
                })));
            };
        }

        // === Storage Quota Spoofing ===
        if ('storage' in navigator && 'estimate' in navigator.storage) {
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

        // === Final Cleanup ===
        delete window.$cdc_;
        delete window._Selenium_IDE_Recorder;
    """,
                new Gson().toJson(profile),
                new Gson().toJson(profile.getClientHints().getBrands()),
                profile.getClientHints().getMobile(),
                profile.getClientHints().getPlatform(),
                profile.getClientHints().getPlatformVersion(),
                profile.getClientHints().getArchitecture(),
                profile.getClientHints().getBitness(),
                profile.getClientHints().getModel(),
                profile.getClientHints().getUaFullVersion()
        );
    }

    private void attachNetworkTaps(Page page, Map<String, String> store) {
        page.onResponse(resp -> {
            String url = resp.url();
            int status = resp.status();
            if ((url.contains("/feapi/PalimpsestLiveAjax/")) && status >= 200 && status < 400) {
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
                log.info("Navigation attempt {} to {}", attempt + 1, SPORT_PAGE);
                page.navigate(SPORT_PAGE, new Page.NavigateOptions()
                        .setTimeout(60_000)
                        .setWaitUntil(WaitUntilState.NETWORKIDLE));
                page.waitForSelector("body", new Page.WaitForSelectorOptions()
                        .setTimeout(30_000));
                log.info("Navigation successful on attempt {}", attempt + 1);
                return;
            } catch (PlaywrightException e) {
                log.error("Navigation attempt {} failed: {}", attempt + 1, e.getMessage());
                if (attempt++ >= CONTEXT_NAV_MAX_RETRIES - 1) {
                    log.error("All navigation attempts failed");
                    throw e;
                }
                backoffWithJitter(attempt);
            }
        }
    }

    // ==================== UTILITY METHODS ====================
    private List<String> extractEventIds(String body) {
        return JsonParser.extractBet9jaEventIdsAsStrings(body);
    }

    private boolean isLiveEvent(String responseBody, String eventId) {
        return true;
    }

    private String buildEventDetailUrl(String eventId) {
        String base = BASE_URL + "/desktop/feapi/PalimpsestLiveAjax/GetLiveEventV2";
        return buildUrl(base, Map.of(
                "EVENTID", eventId,
                "ISMKT", "1",
                "v_cache_version","1.295.4.219"
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

    private String formatCookies(List<Cookie> cookies) {
        return cookies.stream()
                .map(c -> c.name + "=" + c.value)
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    private void safeClose(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {}
    }

    private void safeWrapper(String name, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            log.error("{} error: {}", name, t.getMessage());
        }
    }

    private void backoffWithJitter(int attempt) {
        long base = 250L;
        long jitter = ThreadLocalRandom.current().nextLong(50, 150);
        long total = Math.min(base * (1L << Math.min(attempt, 3)) + jitter, 2_000L);
        try {
            Thread.sleep(total);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== SHUTDOWN ====================
    public void shutdown() {
        log.info("Shutdown requested");
        isRunning.set(false);
    }

    private void cleanup() {
        log.info("=== Shutting down (OkHttp + Live Arbing Mode) ===");
        isRunning.set(false);

        shutdownExecutors();
        eventQueue.clear();
        cleanupContextPool();
        cleanupThreadLocalClients();
        cleanupSharedOkHttpClient();
        closePlaywright();

        log.info("=== Shutdown complete ===");
    }

    private void shutdownExecutors() {
        log.info("Shutting down executors...");
        scheduler.shutdownNow();
        listFetchExecutor.shutdownNow();
        eventDetailExecutor.shutdownNow();
        processingExecutor.shutdownNow();
        retryExecutor.shutdownNow();
        profileRotationExecutor.shutdownNow();
        poolWarmerExecutor.shutdownNow();

        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
            listFetchExecutor.awaitTermination(5, TimeUnit.SECONDS);
            eventDetailExecutor.awaitTermination(5, TimeUnit.SECONDS);
            processingExecutor.awaitTermination(5, TimeUnit.SECONDS);
            retryExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.warn("Timeout waiting for executors to terminate");
            Thread.currentThread().interrupt();
        }
    }

    private void cleanupContextPool() {
        log.info("Cleaning up context pool...");
        contextPool.forEach(wrapper -> {
            try {
                if (wrapper != null && wrapper.getContext() != null) {
                    wrapper.getContext().close();
                }
            } catch (Exception e) {
                log.debug("Error closing context: {}", e.getMessage());
            }
        });
        contextPool.clear();

        if (activeContext != null && activeContext.getContext() != null) {
            try {
                activeContext.getContext().close();
            } catch (Exception e) {
                log.debug("Error closing active context: {}", e.getMessage());
            }
        }
    }

    private void cleanupThreadLocalClients() {
        log.info("Cleaning up thread-local OkHttp clients...");
        OkHttpClient client = threadLocalClients.get();
        if (client != null) {
            shutdownOkHttpClient(client);
        }
        threadLocalClients.remove();
        threadLocalProfileVersion.remove();
        tlNeedsRefresh.remove();
    }

    private void cleanupSharedOkHttpClient() {
        log.info("Cleaning up shared OkHttp client...");
        if (sharedOkHttpClient != null) {
            shutdownOkHttpClient(sharedOkHttpClient);
            sharedOkHttpClient = null;
        }
    }

    private void closePlaywright() {
        log.info("Closing Playwright...");

        if (sharedBrowser != null) {
            try {
                sharedBrowser.close();
                sharedBrowser = null;
            } catch (Exception e) {
                log.debug("Error closing shared browser: {}", e.getMessage());
            }
        }

        Playwright pw = playwrightRef.getAndSet(null);
        if (pw != null) {
            try {
                pw.close();
            } catch (Exception e) {
                log.debug("Error closing Playwright: {}", e.getMessage());
            }
        }
    }
}