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
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

/**
 * Live arbing mode fetcher for SportyBet odds.
 * Optimized with context pool for sub-second profile rotation.
 * Non-blocking list scheduling, per-sport timeout logs, heartbeat, and per-request timeouts.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class SportyBetOddsFetcher implements Runnable {

    // ==================== DEPENDENCIES ====================
    private final ScraperConfig scraperConfig;
    private final ProfileManager profileManager;
    private final SportyBetService sportyBetService;
    private final BetLegRetryService betLegRetryService;
    private final ArbDetector arbDetector;
    private final ObjectMapper objectMapper;

    // ==================== CONFIGURATION CONSTANTS ====================
    private static final String BASE_URL = "https://www.sportybet.com";
    private static final String SPORT_PAGE = BASE_URL + "/ng";

    private static final int INITIAL_SETUP_MAX_ATTEMPTS = 3;
    private static final int CONTEXT_NAV_MAX_RETRIES = 3;
    private static final int API_MAX_RETRIES = 2;
    private static final int API_TIMEOUT_MS = 25_000; // default context timeout
    private static final long SCHEDULER_PERIOD_SEC = 3;
    private static final int EVENT_DETAIL_THREADS = 16;
    private static final int PROCESSING_THREADS = 100;
    private static final long EVENT_DEDUP_WINDOW_MS = 800;
    private static final int MAX_ACTIVE_FETCHES = 100;

    // Per-request timeouts (overrides)
    private static final int LIST_API_TIMEOUT_MS = 10_000;
    private static final int DETAIL_API_TIMEOUT_MS = 15_000;

    // Context pool configuration
    private static final int CONTEXT_POOL_SIZE = 20;
    private static final int CONTEXT_MAX_AGE_MS = 300_000; // 5 minutes

    // Rate limit detection thresholds
    private static final int RATE_LIMIT_THRESHOLD = 5;
    private static final int SLOW_REQUEST_THRESHOLD_MS = 20_000;

    // Sport keys
    private static final String KEY_FB = "sr:sport:1";
    private static final String KEY_BB = "sr:sport:2";
    private static final String KEY_TT = "sr:sport:20";
    private static final BookMaker SCRAPER_BOOKMAKER = BookMaker.SPORTY_BET;

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

    private final ThreadLocal<Map<String, APIRequestContext>> threadLocalClients =
            ThreadLocal.withInitial(HashMap::new);
    private final ThreadLocal<Long> threadLocalProfileVersion =
            ThreadLocal.withInitial(() -> -1L);

    private final AtomicBoolean globalNeedsRefresh = new AtomicBoolean(false);
    private final ThreadLocal<Boolean> tlNeedsRefresh =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final Map<String, String> harvestedHeaders = new ConcurrentHashMap<>();
    private final AtomicReference<String> cookieHeaderRef = new AtomicReference<>("");

    // ==================== STATE TRACKING ====================
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private final AtomicBoolean schedulesStarted = new AtomicBoolean(false);
    private final AtomicBoolean setupCompleted = new AtomicBoolean(false);
    private final AtomicBoolean profileRotationInProgress = new AtomicBoolean(false);
    private final AtomicBoolean needsSessionRefresh = new AtomicBoolean(false);

    private final AtomicInteger activeDetailFetches = new AtomicInteger(0);
    private final Map<String, Long> lastFetchTime = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRequestTime = new ConcurrentHashMap<>();

    // Rate limit metrics
    private final AtomicInteger consecutiveRateLimitErrors = new AtomicInteger(0);
    private final AtomicInteger consecutiveTimeouts = new AtomicInteger(0);
    private final AtomicInteger consecutiveNetworkErrors = new AtomicInteger(0);
    private final AtomicLong lastProfileRotation = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger requestsSinceLastRotation = new AtomicInteger(0);

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

        public EventFetchTask(String eventId, String clientKey, boolean isLive) {
            this.eventId = eventId;
            this.clientKey = clientKey;
            this.isLive = isLive;
            this.timestamp = System.currentTimeMillis();
        }

        public int getPriority() {
            return isLive ? 100 : 50;
        }
    }

    // ==================== MAIN RUN ====================
    @Override
    public void run() {
        log.info("=== Starting SportyBetOddsFetcher (OPTIMIZED with Context Pool) ===");
        log.info("Cadence={}s, Dedup={}ms, DetailThreads={}, ProcessingThreads={}, PoolSize={}",
                SCHEDULER_PERIOD_SEC, EVENT_DEDUP_WINDOW_MS, EVENT_DETAIL_THREADS,
                PROCESSING_THREADS, CONTEXT_POOL_SIZE);

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
        // Lightweight heartbeat every 5 seconds so logs keep moving even under waits
        scheduler.scheduleAtFixedRate(() -> {
            try {
                log.info("Tick — queued={}, activeFetches={}, rotationInProgress={}, setupOK={}",
                        eventQueue.size(), activeDetailFetches.get(), profileRotationInProgress.get(), setupCompleted.get());
            } catch (Throwable t) {
                log.error("Heartbeat error: {}", t.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);

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

    private void logHealthMetrics() {
        int active = activeDetailFetches.get();
        int queued = eventQueue.size();
        int netErrors = consecutiveNetworkErrors.get();
        int rateLimitErrors = consecutiveRateLimitErrors.get();
        int timeouts = consecutiveTimeouts.get();
        int requests = requestsSinceLastRotation.get();
        long timeSinceRotation = System.currentTimeMillis() - lastProfileRotation.get();
        int poolSize = contextPool.size();

        log.info("Health — Active: {}, Queued: {}, NetErrors: {}, RateLimit: {}, Timeouts: {}, " +
                        "Requests: {}, TimeSinceRotation: {}s, PoolSize: {}, SetupOK: {}",
                active, queued, netErrors, rateLimitErrors, timeouts, requests,
                timeSinceRotation / 1000, poolSize, setupCompleted.get());
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

        // Create single shared browser (reused for all contexts)
        sharedBrowser = launchBrowser(pw);
        log.info("Shared browser created");

        // Pre-warm the pool
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
                    // Keep pool topped up and fresh
                    maintainContextPool();
                    Thread.sleep(10_000); // Check every 10 seconds
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
        // Remove stale contexts
        contextPool.removeIf(wrapper -> {
            if (wrapper.isStale()) {
                log.info("Removing stale context from pool (age: {}ms)",
                        System.currentTimeMillis() - wrapper.createdAt);
                safeClose(wrapper.getContext());
                return true;
            }
            return false;
        });

        // Refill pool to capacity
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
        log.info("=== Fast Profile Rotation (Context Pool) ===");
        long startTime = System.currentTimeMillis();

        // Signal threads to pause briefly
        globalNeedsRefresh.set(true);

        // Small delay to allow in-flight requests to complete
        try {
            Thread.sleep(100);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }

        // Get pre-warmed context from pool (instant!)
        ContextWrapper newWrapper = contextPool.poll();

        if (newWrapper == null) {
            log.info("Context pool empty, creating new context (slower path)");
            newWrapper = createNewContextWrapper();
        }

        Page page = null;
        try {
            // Quick cookie harvest
            page = newWrapper.getContext().newPage();
            Map<String, String> captured = new HashMap<>();
            attachNetworkTaps(page, captured);

            // Fast navigation - just harvest cookies
            page.navigate(SPORT_PAGE, new Page.NavigateOptions()
                    .setTimeout(15_000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)); // Faster than NETWORKIDLE

            String cookieHeader = formatCookies(newWrapper.getContext().cookies());

            // Store old context for cleanup
            ContextWrapper oldContext = activeContext;

            // Atomic swap
            profile = newWrapper.getProfile();
            cookieHeaderRef.set(cookieHeader);
            harvestedHeaders.clear();
            harvestedHeaders.putAll(captured);
            activeContext = newWrapper;

            long newVersion = profileVersion.incrementAndGet();
            resetRateLimitMetrics();

            long duration = System.currentTimeMillis() - startTime;
            log.info("Fast rotation complete in {}ms! Version: {}, cookies: {} bytes, headers: {}",
                    duration, newVersion, cookieHeader.length(), captured.size());

            // Clean up old context
            if (oldContext != null) {
                safeClose(oldContext.getContext());
            }

            // Trigger background refill
            refillContextPoolAsync();

        } catch (Exception e) {
            log.error("Fast rotation failed: {}", e.getMessage(), e);
            // Return context to pool if we didn't use it
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

            log.info("Setup OK — cookies={} bytes, harvestedHeaders={}",
                    cookieHeader.length(), captured.size());
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
        log.info("Starting schedulers with {}s cadence (live arbing mode)", SCHEDULER_PERIOD_SEC);

        // Non-blocking: schedule the trigger, each sport runs independently with its own timeout
        scheduler.scheduleAtFixedRate(
                () -> safeWrapper("AllSports", this::fetchAllSportsParallel),
                0, SCHEDULER_PERIOD_SEC, TimeUnit.SECONDS
        );
    }

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

        // Fire-and-forget tasks with their own 30s timeout and INFO logs
        runSportListTask("Football", KEY_FB, KEY_FB);
        runSportListTask("Basketball", KEY_BB, KEY_BB);
        runSportListTask("TableTennis", KEY_TT, KEY_TT);

        long duration = System.currentTimeMillis() - start;
        log.info("All sports fetch cycle triggered in {}ms [queue={}, active={}]",
                duration, eventQueue.size(), activeDetailFetches.get());
    }

    private void runSportListTask(String sportName, String sportId, String clientKey) {
        CompletableFuture
                .runAsync(() -> fetchSportEventsList(sportName, sportId, clientKey), listFetchExecutor)
                .orTimeout(30, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    // Per-sport timeout / error logging at INFO to match your pattern
                    if (ex instanceof TimeoutException || (ex.getCause() instanceof TimeoutException)) {
                        int timeouts = consecutiveTimeouts.incrementAndGet();
                        log.info("{}: List fetch timeout after 30s (timeout #{})", sportName, timeouts);
                    } else {
                        String msg = ex.getMessage();
                        log.info("{}: List fetch error: {}", sportName, msg);
                    }
                    return null;
                });
    }

    private void fetchSportEventsList(String sportName, String sportId, String clientKey) {
        long fetchStart = System.currentTimeMillis();
        try {
            if (shouldSkipRequest(clientKey)) {
                log.info("{}: Skipping - too soon after last request", sportName);
                return;
            }

            String url = buildEventsListUrl(sportId);
            log.info("{}: Fetching events list from API...", sportName);

            String body = safeApiGet(url, clientKey, 0, LIST_API_TIMEOUT_MS);
            long apiDuration = System.currentTimeMillis() - fetchStart;

            consecutiveNetworkErrors.set(0);

            if (body == null || body.isEmpty()) {
                log.info("{}: Empty response from API ({}ms)", sportName, apiDuration);
                return;
            }

            processEventsListResponse(sportName, body, clientKey, fetchStart);

        } catch (PlaywrightException e) {
            long duration = System.currentTimeMillis() - fetchStart;
            log.error("{}: Network error after {}ms - {}", sportName, duration, e.getMessage());
            handleNetworkError(sportName, e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - fetchStart;
            log.error("{}: List fetch failed after {}ms - {}", sportName, duration, e.getMessage());
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
        return buildUrl(BASE_URL + "/api/ng/factsCenter/liveOrPrematchEvents",
                Map.of("sportId", sportId, "_t", String.valueOf(System.currentTimeMillis())));
    }

    private void processEventsListResponse(String sportName, String body, String clientKey, long fetchStart) {
        List<String> eventIds = extractEventIds(body);

        if (eventIds == null || eventIds.isEmpty()) {
            long apiDuration = System.currentTimeMillis() - fetchStart;
            log.info("{}: No events found in response ({}ms)", sportName, apiDuration);
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
            eventQueue.offer(new EventFetchTask(eventId, clientKey, isLive));
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
                    fetchAndProcessEventDetailAsync(task.getEventId(), task.getClientKey());
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
    private void fetchAndProcessEventDetailAsync(String eventId, String clientKey) {
        try {
            String url = buildEventDetailUrl(eventId);
            String body = safeApiGet(url, clientKey, 0, DETAIL_API_TIMEOUT_MS);
            if (body == null || body.isBlank()) return;

            processingExecutor.submit(() -> {
                try {
                    SportyEvent domainEvent = parseEventDetail(body);
                    if (domainEvent != null) {
                        processParsedEvent(domainEvent);
                    }
                } catch (Exception ex) {
                    log.info("Process failed for {}: {}", eventId, ex.getMessage());
                }
            });
        } catch (Exception e) {
            log.info("Detail fetch failed for {}: {}", eventId, e.getMessage());
        }
    }

    private SportyEvent parseEventDetail(String detailJson) {
        return JsonParser.deserializeSportyEvent(detailJson, objectMapper);
    }

    private void processParsedEvent(SportyEvent event) {
        if (event == null) return;

        try {
            NormalizedEvent normalized = sportyBetService.convertToNormalEvent(event);
            if (normalized == null) return;

            CompletableFuture.runAsync(() -> processBetRetryInfo(normalized), retryExecutor)
                    .exceptionally(ex -> null);

            if (arbDetector != null) {
                arbDetector.addEventToPool(normalized);
            }
        } catch (Exception e) {
            log.info("processParsedEvent failed for {}: {}", event.getEventId(), e.getMessage());
        }
    }

    private void processBetRetryInfo(NormalizedEvent normalizedEvent) {
        try {
            betLegRetryService.updateFailedBetLeg(normalizedEvent, SCRAPER_BOOKMAKER);
        } catch (Exception e) {
            log.info("BetLegRetry failed for {}: {}", normalizedEvent.getEventId(), e.getMessage());
        }
    }

    // ==================== HTTP LAYER (THREAD-LOCAL CLIENTS) ====================
    private String safeApiGet(String url, String clientKey) throws InterruptedException {
        return safeApiGet(url, clientKey, 0, null);
    }

    private String safeApiGet(String url, String clientKey, int retry, Integer perRequestTimeoutMs) throws InterruptedException {
        if (retry > API_MAX_RETRIES) {
            log.info("HTTP max retries exceeded for: {}", url);
            consecutiveRateLimitErrors.incrementAndGet();
            throw new PlaywrightException("HTTP retried too many times: " + url);
        }

        long requestStart = System.currentTimeMillis();
        try {
            APIRequestContext client = getThreadLocalClient(clientKey);
            if (client == null) {
                log.info("Client is null for key: {}, retrying...", clientKey);
                Thread.sleep(100 * (retry + 1));
                return safeApiGet(url, clientKey, retry + 1, perRequestTimeoutMs);
            }

            if (!isClientValid(client)) {
                log.info("Client validation failed, recreating for retry {}", retry + 1);
                recreateClient(clientKey);
                Thread.sleep(100 * (retry + 1));
                return safeApiGet(url, clientKey, retry + 1, perRequestTimeoutMs);
            }

            APIResponse res;
            if (perRequestTimeoutMs != null) {
                res = client.get(url);
            } else {
                res = client.get(url);
            }

            int status = res.status();
            long requestDuration = System.currentTimeMillis() - requestStart;

            requestsSinceLastRotation.incrementAndGet();

            return handleApiResponse(url, clientKey, retry, res, status, requestDuration);

        } catch (PlaywrightException e) {
            return handlePlaywrightException(url, clientKey, retry, requestStart, e, perRequestTimeoutMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during API request", ie);
        }
    }

    private String handleApiResponse(String url, String clientKey, int retry,
                                     APIResponse res, int status, long requestDuration)
            throws InterruptedException {

        if (status == 429) {
            return handleRateLimitResponse(url, clientKey, retry, requestDuration);
        }

        if (status == 401 || status == 403) {
            return handleAuthErrorResponse(url, clientKey, retry, status, requestDuration);
        }

        if (requestDuration > SLOW_REQUEST_THRESHOLD_MS) {
            detectSlowRequest(requestDuration);
        }

        String body = safeBody(res);
        if (status < 200 || status >= 300) {
            log.info("HTTP {} for {} (took {}ms)", status, url, requestDuration);
            throw new PlaywrightException("HTTP " + status + " on " + url);
        }

        consecutiveRateLimitErrors.set(0);

        if (requestDuration > 5000) {
            log.info("Slow API response: {}ms for {}", requestDuration, url);
        }

        return body;
    }

    private String handleRateLimitResponse(String url, String clientKey, int retry,
                                           long requestDuration) throws InterruptedException {
        int rateLimitCount = consecutiveRateLimitErrors.incrementAndGet();
        log.info("Rate limit detected (429) on attempt {} - count: {}, duration: {}ms",
                retry + 1, rateLimitCount, requestDuration);
        Thread.sleep(1000 * (retry + 1));
        return safeApiGet(url, clientKey, retry + 1, null);
    }

    private String handleAuthErrorResponse(String url, String clientKey, int retry,
                                           int status, long requestDuration) throws InterruptedException {
        int rateLimitCount = consecutiveRateLimitErrors.incrementAndGet();
        log.info("Auth/Forbidden error ({}) - possible rate limit, count: {}, duration: {}ms",
                status, rateLimitCount, requestDuration);
        tlNeedsRefresh.set(true);
        Thread.sleep(150);
        return safeApiGet(url, clientKey, retry + 1, null);
    }

    private void detectSlowRequest(long requestDuration) {
        int rateLimitCount = consecutiveRateLimitErrors.incrementAndGet();
        log.info("Suspiciously slow request detected: {}ms (threshold: {}ms) - possible throttling, count: {}",
                requestDuration, SLOW_REQUEST_THRESHOLD_MS, rateLimitCount);
    }

    private String handlePlaywrightException(String url, String clientKey, int retry,
                                             long requestStart, PlaywrightException e,
                                             Integer perRequestTimeoutMs)
            throws InterruptedException {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        long requestDuration = System.currentTimeMillis() - requestStart;

        if (isClientDisposalError(msg) && retry < API_MAX_RETRIES) {
            log.info("Client disposal detected, recreating for retry {} (after {}ms): {}",
                    retry + 1, requestDuration, msg);

            recreateClient(clientKey);
            tlNeedsRefresh.set(true);
            Thread.sleep(200 * (retry + 1));
            return safeApiGet(url, clientKey, retry + 1, perRequestTimeoutMs);
        }

        if (msg.toLowerCase().contains("timeout")) {
            consecutiveTimeouts.incrementAndGet();
        }

        log.info("API request failed after {}ms: {}", requestDuration, msg);
        throw e;
    }

    private boolean isClientDisposalError(String errorMessage) {
        return errorMessage.contains("Cannot find object to call __adopt__")
                || errorMessage.contains("Cannot find command")
                || errorMessage.contains("Channel closed")
                || errorMessage.contains("Object doesn't exist")
                || errorMessage.contains("Target closed");
    }

    private void recreateClient(String clientKey) {
        Map<String, APIRequestContext> local = threadLocalClients.get();
        APIRequestContext old = local.remove(clientKey);
        if (old != null) {
            try {
                old.dispose();
            } catch (Exception ignore) {}
        }
    }

    private boolean isClientValid(APIRequestContext client) {
        return client != null;
    }

    private APIRequestContext getThreadLocalClient(String clientKey) {
        Map<String, APIRequestContext> local = threadLocalClients.get();
        Long threadVersion = threadLocalProfileVersion.get();
        Long currentVersion = profileVersion.get();

        APIRequestContext client = local.get(clientKey);

        if (client != null && !validateExistingClient(clientKey, client, local)) {
            client = null;
        }

        if (shouldRefreshClient(threadVersion, currentVersion, client)) {
            client = refreshThreadLocalClient(clientKey, client, local, currentVersion);
        }

        if (globalNeedsRefresh.get() && client != null) {
            client = forceClientRefresh(clientKey, client, local);
        }

        return client;
    }

    private boolean validateExistingClient(String clientKey, APIRequestContext client,
                                           Map<String, APIRequestContext> local) {
        try {
            if (!isClientValid(client)) {
                log.info("Client {} is disposed, recreating", clientKey);
                local.remove(clientKey);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.info("Client {} validation error, recreating: {}", clientKey, e.getMessage());
            local.remove(clientKey);
            return false;
        }
    }

    private boolean shouldRefreshClient(Long threadVersion, Long currentVersion,
                                        APIRequestContext client) {
        return !threadVersion.equals(currentVersion) || client == null;
    }

    private APIRequestContext refreshThreadLocalClient(String clientKey, APIRequestContext client,
                                                       Map<String, APIRequestContext> local,
                                                       Long currentVersion) {
        log.info("Thread {} refreshing client for key: {} (version: {} -> {})",
                Thread.currentThread().getName(), clientKey,
                threadLocalProfileVersion.get(), currentVersion);

        if (client != null) {
            try {
                client.dispose();
            } catch (Exception ignored) {}
            local.remove(clientKey);
        }

        client = createNewAPIClient(clientKey);
        if (client != null) {
            local.put(clientKey, client);
        }
        threadLocalProfileVersion.set(currentVersion);
        tlNeedsRefresh.set(false);

        return client;
    }

    private APIRequestContext forceClientRefresh(String clientKey, APIRequestContext client,
                                                 Map<String, APIRequestContext> local) {
        log.info("Global refresh requested, recreating client for key: {}", clientKey);
        try {
            client.dispose();
        } catch (Exception ignored) {}
        local.remove(clientKey);
        client = createNewAPIClient(clientKey);
        if (client != null) {
            local.put(clientKey, client);
        }
        globalNeedsRefresh.set(false);
        return client;
    }

    private APIRequestContext createNewAPIClient(String clientKey) {
        try {
            Playwright pw = playwrightRef.get();
            if (pw == null) {
                log.error("Playwright not available for creating new API client");
                return null;
            }

            log.info("Creating new API client for key: {} on thread: {} with profile version: {}",
                    clientKey, Thread.currentThread().getName(), profileVersion.get());

            APIRequest.NewContextOptions options = new APIRequest.NewContextOptions()
                    .setTimeout((double) API_TIMEOUT_MS);

            Map<String, String> headers = buildHeaders(cookieHeaderRef.get(), harvestedHeaders, profile);
            if (headers != null && !headers.isEmpty()) {
                options.setExtraHTTPHeaders(headers);
            }

            return pw.request().newContext(options);
        } catch (Exception e) {
            log.error("Failed to create new API client for key {}: {}", clientKey, e.getMessage());
            return null;
        }
    }

    private void checkThreadLocalClientsHealth() {
        try {
            Map<String, APIRequestContext> local = threadLocalClients.get();
            if (local == null || local.isEmpty()) return;

            List<String> invalidClients = local.entrySet().stream()
                    .filter(entry -> !isClientHealthy(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();

            if (!invalidClients.isEmpty()) {
                log.info("Cleaning up {} invalid thread-local clients: {}",
                        invalidClients.size(), invalidClients);
                invalidClients.forEach(key -> disposeClient(local, key));
            }
        } catch (Exception e) {
            log.info("Thread-local clients health check failed: {}", e.getMessage());
        }
    }

    private boolean isClientHealthy(APIRequestContext client) {
        try {
            return isClientValid(client);
        } catch (Exception e) {
            return false;
        }
    }

    private void disposeClient(Map<String, APIRequestContext> local, String key) {
        APIRequestContext client = local.remove(key);
        if (client != null) {
            try {
                client.dispose();
            } catch (Exception ignored) {}
        }
    }

    private void handleNetworkError(String context, PlaywrightException e) {
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
    private Map<String, String> buildHeaders(String cookieHeader, Map<String, String> harvested,
                                             UserAgentProfile profile) {
        Map<String, String> h = new HashMap<>();
        if (profile != null) {
            h.put("User-Agent", profile.getUserAgent());
        }
        h.put("Referer", SPORT_PAGE);
        h.put("Cookie", cookieHeader);
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/json");
        h.put("X-Requested-With", "XMLHttpRequest");
        if (harvested != null) {
            h.putAll(harvested);
        }
        return h;
    }

    private List<String> extractEventIds(String body) {
        return JsonParser.extractEventIds(body);
    }

    private boolean isLiveEvent(String responseBody, String eventId) {
        return true;
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

    private String formatCookies(List<Cookie> cookies) {
        return cookies.stream()
                .map(c -> c.name + "=" + c.value)
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    private String safeBody(APIResponse res) {
        try {
            return res.text();
        } catch (Exception e) {
            return null;
        }
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
        log.info("=== Shutting down (Live Arbing Mode) ===");
        isRunning.set(false);

        shutdownExecutors();
        eventQueue.clear();
        contextPool.clear();
        cleanupThreadLocalClients();
        closePlaywright();

        log.info("=== Shutdown complete ===");
    }

    private void shutdownExecutors() {
        scheduler.shutdownNow();
        listFetchExecutor.shutdownNow();
        eventDetailExecutor.shutdownNow();
        processingExecutor.shutdownNow();
        retryExecutor.shutdownNow();
        profileRotationExecutor.shutdownNow();
    }

    private void cleanupThreadLocalClients() {
        Map<String, APIRequestContext> local = threadLocalClients.get();
        if (local != null && !local.isEmpty()) {
            local.values().forEach(c -> {
                try {
                    c.dispose();
                } catch (Exception ignored) {}
            });
            local.clear();
        }
        threadLocalClients.remove();
    }

    private void closePlaywright() {
        Playwright pw = playwrightRef.getAndSet(null);
        if (pw != null) {
            try {
                pw.close();
            } catch (Exception ignored) {}
        }
    }
}
