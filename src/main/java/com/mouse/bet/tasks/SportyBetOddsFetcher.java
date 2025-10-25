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

    // ==================== CONSTANTS (LIVE ARBING MODE) ====================
    private static final String BASE_URL   = "https://www.sportybet.com";
    private static final String SPORT_PAGE = BASE_URL + "/ng";

    private static final int  INITIAL_SETUP_MAX_ATTEMPTS = 3;
    private static final int  CONTEXT_NAV_MAX_RETRIES = 3;
    private static final int  API_MAX_RETRIES         = 2;
    private static final int  API_TIMEOUT_MS          = 25_000;
    private static final long SCHEDULER_PERIOD_SEC    = 2;
    private static final int  EVENT_DETAIL_THREADS    = 50;
    private static final int  PROCESSING_THREADS      = 100;
    private static final long EVENT_DEDUP_WINDOW_MS   = 500;
    private static final int  MAX_ACTIVE_FETCHES      = 300;

    // Rate limit detection
    private static final int  RATE_LIMIT_THRESHOLD    = 5;  // consecutive failures
    private static final int  SLOW_REQUEST_THRESHOLD_MS = 20_000; // 20s is suspicious

    private static final String KEY_FB = "sr:sport:1";
    private static final String KEY_BB = "sr:sport:2";
    private static final String KEY_TT = "sr:sport:20";
    private static final BookMaker SCRAPER_BOOKMAKER = BookMaker.SPORTY_BET;

    // ==================== THREADING ====================
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private final ExecutorService listFetchExecutor = Executors.newFixedThreadPool(3);
    private final ExecutorService eventDetailExecutor = Executors.newFixedThreadPool(EVENT_DETAIL_THREADS);
    private final ExecutorService processingExecutor  = Executors.newFixedThreadPool(PROCESSING_THREADS);
    private final ExecutorService retryExecutor = Executors.newFixedThreadPool(5);
    private final ExecutorService profileRotationExecutor = Executors.newSingleThreadExecutor();

    // ==================== SESSION / HTTP ====================
    private final AtomicReference<Playwright> playwrightRef = new AtomicReference<>();
    private volatile UserAgentProfile profile;
    private final AtomicLong profileVersion = new AtomicLong(0); // Track profile changes

    private final ThreadLocal<Map<String, APIRequestContext>> threadLocalClients =
            ThreadLocal.withInitial(HashMap::new);
    private final ThreadLocal<Long> threadLocalProfileVersion = ThreadLocal.withInitial(() -> -1L);

    private final AtomicBoolean globalNeedsRefresh = new AtomicBoolean(false);
    private final ThreadLocal<Boolean> tlNeedsRefresh = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final Map<String, String> harvestedHeaders = new ConcurrentHashMap<>();
    private final AtomicReference<String> cookieHeaderRef = new AtomicReference<>("");

    // ==================== STATE / METRICS ====================
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private final AtomicBoolean schedulesStarted = new AtomicBoolean(false);
    private final AtomicBoolean setupCompleted = new AtomicBoolean(false);
    private final AtomicBoolean profileRotationInProgress = new AtomicBoolean(false);

    private final AtomicInteger activeDetailFetches = new AtomicInteger(0);
    private final Map<String, Long> lastFetchTime   = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRequestTime = new ConcurrentHashMap<>();

    // Rate limit detection
    private final AtomicInteger consecutiveRateLimitErrors = new AtomicInteger(0);
    private final AtomicInteger consecutiveTimeouts = new AtomicInteger(0);
    private final AtomicInteger consecutiveNetworkErrors = new AtomicInteger(0);
    private final AtomicLong lastProfileRotation = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger requestsSinceLastRotation = new AtomicInteger(0);

    private final AtomicBoolean needsSessionRefresh      = new AtomicBoolean(false);

    private final PriorityBlockingQueue<EventFetchTask> eventQueue = new PriorityBlockingQueue<>(
            1000,
            Comparator.comparingInt(EventFetchTask::getPriority).reversed()
    );

    // ==================== TASK MODEL ====================
    private static class EventFetchTask {
        @Getter
        private final String eventId;
        @Getter
        private final String clientKey;
        private final boolean isLive;
        @Getter
        private final long timestamp;

        public EventFetchTask(String eventId, String clientKey, boolean isLive) {
            this.eventId = eventId;
            this.clientKey = clientKey;
            this.isLive = isLive;
            this.timestamp = System.currentTimeMillis();
        }
        public int getPriority() { return isLive ? 100 : 50; }
    }

    // ==================== MAIN RUN ====================
    @Override
    public void run() {
        log.info("=== Starting SportyBetOddsFetcher (LIVE ARBING MODE with Profile Rotation) ===");
        log.info("Cadence={}s, Dedup={}ms, DetailThreads={}, ProcessingThreads={}",
                SCHEDULER_PERIOD_SEC, EVENT_DEDUP_WINDOW_MS, EVENT_DETAIL_THREADS, PROCESSING_THREADS);

        playwrightRef.set(Playwright.create());

        try {
            performInitialSetupWithRetry();

            startSchedulers();
            startQueueProcessor();

            // Health monitor
            while (isRunning.get()) {
                Thread.sleep(Duration.ofSeconds(30).toMillis());
                int active = activeDetailFetches.get();
                int queued = eventQueue.size();
                int netErrors = consecutiveNetworkErrors.get();
                int rateLimitErrors = consecutiveRateLimitErrors.get();
                int timeouts = consecutiveTimeouts.get();
                int requests = requestsSinceLastRotation.get();
                long timeSinceRotation = System.currentTimeMillis() - lastProfileRotation.get();

                log.info("Health — Active: {}, Queued: {}, NetErrors: {}, RateLimit: {}, Timeouts: {}, Requests: {}, TimeSinceRotation: {}s, SetupOK: {}",
                        active, queued, netErrors, rateLimitErrors, timeouts, requests,
                        timeSinceRotation / 1000, setupCompleted.get());

                // Trigger profile rotation if needed
                if (shouldRotateProfile()) {
                    log.info("Rate limit detected! Triggering profile rotation...");
                    triggerProfileRotation();
                }

                if (needsSessionRefresh.get()) {
                    log.info("Triggering streaming session refresh...");
                    performStreamingSessionRefresh();
                }

                if (lastFetchTime.size() > 10_000) {
                    long cutoff = System.currentTimeMillis() - 60_000;
                    lastFetchTime.entrySet().removeIf(e -> e.getValue() < cutoff);
                }
            }
        } catch (RuntimeException setupEx) {
            log.error("Could not complete initial setup, entering recovery mode", setupEx);

            while (isRunning.get()) {
                try {
                    Thread.sleep(30_000);
                    log.info("Attempting setup recovery...");
                    performInitialSetupWithRetry();

                    if (setupCompleted.get()) {
                        log.info("Setup recovery successful, starting schedulers...");
                        startSchedulers();
                        startQueueProcessor();

                        while (isRunning.get()) {
                            Thread.sleep(Duration.ofSeconds(30).toMillis());
                            int active = activeDetailFetches.get();
                            int queued = eventQueue.size();
                            int errors = consecutiveNetworkErrors.get();
                            log.info("Health — Active: {}, Queued: {}, NetErrors: {}", active, queued, errors);

                            if (shouldRotateProfile()) {
                                triggerProfileRotation();
                            }

                            if (needsSessionRefresh.get()) {
                                performStreamingSessionRefresh();
                            }
                        }
                        break;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception retryEx) {
                    log.error("Setup recovery failed: {}", retryEx.getMessage());
                }
            }
        } catch (Exception fatal) {
            log.error("Fatal error in main loop", fatal);
        } finally {
            cleanup();
        }
    }

    // ==================== RATE LIMIT DETECTION & PROFILE ROTATION ====================
    private boolean shouldRotateProfile() {
        int rateLimitErrors = consecutiveRateLimitErrors.get();
        int timeouts = consecutiveTimeouts.get();
        long timeSinceLastRotation = System.currentTimeMillis() - lastProfileRotation.get();
        int requests = requestsSinceLastRotation.get();

        // Rotate if:
        // 1. Too many rate limit errors (429, 403, slow requests)
        if (rateLimitErrors >= RATE_LIMIT_THRESHOLD) {
            log.info("Rate limit threshold reached: {} consecutive errors", rateLimitErrors);
            return true;
        }

        // 2. Too many consecutive timeouts
        if (timeouts >= 3) {
            log.info("Timeout threshold reached: {} consecutive timeouts", timeouts);
            return true;
        }

        // 3. Proactive rotation: every 5 minutes or 500 requests (whichever comes first)
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
            log.info("=== Starting Profile Rotation ===");
            profileRotationExecutor.submit(() -> {
                try {
                    performProfileRotation();
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

    private void performProfileRotation() {
        log.info("Executing profile rotation...");
        Playwright pw = playwrightRef.get();
        if (pw == null) {
            log.error("Playwright not available for profile rotation");
            return;
        }

        Browser browser = null;
        BrowserContext context = null;
        Page page = null;

        try {
            // Get new profile
            UserAgentProfile newProfile = profileManager.getNextProfile();
            log.info("Rotating to new profile: Platform={}, UA={}...",
                    newProfile.getPlatform(),
                    newProfile.getUserAgent().substring(0, Math.min(50, newProfile.getUserAgent().length())));

            // Create new browser session with new profile
            browser = launchBrowser(pw);
            context = newContext(browser, newProfile);
            attachAntiDetection(context, newProfile);
            page = context.newPage();

            // Harvest new headers/cookies
            Map<String, String> captured = new HashMap<>();
            attachNetworkTaps(page, captured);
            performInitialNavigationWithRetry(page);

            String cookieHeader = formatCookies(context.cookies());

            // Atomic swap of profile and headers
            profile = newProfile;
            cookieHeaderRef.set(cookieHeader);
            harvestedHeaders.clear();
            harvestedHeaders.putAll(captured);

            // Increment profile version to invalidate all thread-local clients
            long newVersion = profileVersion.incrementAndGet();
            globalNeedsRefresh.set(true);

            // Reset counters
            consecutiveRateLimitErrors.set(0);
            consecutiveTimeouts.set(0);
            consecutiveNetworkErrors.set(0);
            requestsSinceLastRotation.set(0);
            lastProfileRotation.set(System.currentTimeMillis());

            log.info("Profile rotation complete! New version: {}, cookies: {} bytes, headers: {}",
                    newVersion, cookieHeader.length(), captured.size());

        } catch (Exception e) {
            log.error("Profile rotation failed: {}", e.getMessage(), e);
            // Don't throw - let the system continue with old profile
        } finally {
            safeClose(page);
            safeClose(context);
            safeClose(browser);
        }
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
                    try {
                        long backoffMs = 2000L * attempt;
                        log.info("Waiting {}ms before retry...", backoffMs);
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Setup interrupted during backoff", ie);
                    }
                }
            }
        }

        log.error("Initial setup failed after {} attempts", INITIAL_SETUP_MAX_ATTEMPTS);
        throw new RuntimeException(
                "Initial setup failed after " + INITIAL_SETUP_MAX_ATTEMPTS + " attempts",
                lastException
        );
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

        List<CompletableFuture<Void>> futures = List.of(
                CompletableFuture.runAsync(() -> fetchSportEventsList("Football", KEY_FB, KEY_FB), listFetchExecutor),
                CompletableFuture.runAsync(() -> fetchSportEventsList("Basketball", KEY_BB, KEY_BB), listFetchExecutor),
                CompletableFuture.runAsync(() -> fetchSportEventsList("TableTennis", KEY_TT, KEY_TT), listFetchExecutor)
        );

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(30, TimeUnit.SECONDS)
                    .join();
            consecutiveTimeouts.set(0); // Reset on success
        } catch (CompletionException | CancellationException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof TimeoutException) {
                int timeouts = consecutiveTimeouts.incrementAndGet();
                log.info("Parallel fetch timeout after 30s (timeout #{}) - some sports may still be fetching", timeouts);
            } else {
                log.info("Parallel fetch error: {}", cause != null ? cause.getMessage() : ex.getMessage());
            }
        }

        long duration = System.currentTimeMillis() - start;
        log.info("All sports fetch cycle completed in {}ms [queue={}, active={}]",
                duration, eventQueue.size(), activeDetailFetches.get());
    }

    private void fetchSportEventsList(String sportName, String sportId, String clientKey) {
        long fetchStart = System.currentTimeMillis();
        try {
            Long lastReq = lastRequestTime.get(clientKey);
            long now = System.currentTimeMillis();
            if (lastReq != null && (now - lastReq) < 100) {
                log.info("{}: Skipping - too soon after last request", sportName);
                return;
            }
            lastRequestTime.put(clientKey, now);

            String url = buildUrl(BASE_URL + "/api/ng/factsCenter/liveOrPrematchEvents",
                    Map.of("sportId", sportId, "_t", String.valueOf(System.currentTimeMillis())));

            log.info("{}: Fetching events list from API...", sportName);
            String body = safeApiGet(url, clientKey);
            long apiDuration = System.currentTimeMillis() - fetchStart;

            consecutiveNetworkErrors.set(0);
            if (body == null || body.isEmpty()) {
                log.info("{}: Empty response from API ({}ms)", sportName, apiDuration);
                return;
            }

            List<String> eventIds = extractEventIds(body);
            if (eventIds == null || eventIds.isEmpty()) {
                log.info("{}: No events found in response ({}ms)", sportName, apiDuration);
                return;
            }

            int queued = 0, skipped = 0;
            for (String eventId : eventIds) {
                Long last = lastFetchTime.get(eventId);
                if (last != null && (System.currentTimeMillis() - last) < EVENT_DEDUP_WINDOW_MS) {
                    skipped++;
                    continue;
                }
                lastFetchTime.put(eventId, System.currentTimeMillis());

                boolean isLive = isLiveEvent(body, eventId);
                eventQueue.offer(new EventFetchTask(eventId, clientKey, isLive));
                queued++;
            }

            long totalDuration = System.currentTimeMillis() - fetchStart;
            if (queued > 0 || log.isDebugEnabled()) {
                log.info("{}: Completed in {}ms - queued {}/{} events (skipped: {})",
                        sportName, totalDuration, queued, eventIds.size(), skipped);
            }

        } catch (PlaywrightException e) {
            long duration = System.currentTimeMillis() - fetchStart;
            log.info("{}: Network error after {}ms - {}", sportName, duration, e.getMessage());
            handleNetworkError(sportName, e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - fetchStart;
            log.error("{}: List fetch failed after {}ms - {}", sportName, duration, e.getMessage());
        }
    }

    // ==================== QUEUE PROCESSOR ====================
    private void startQueueProcessor() {
        for (int i = 0; i < EVENT_DETAIL_THREADS; i++) {
            eventDetailExecutor.submit(() -> {
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

                        long age = System.currentTimeMillis() - task.getTimestamp();
                        long maxAge = (task.getPriority() > 50) ? 5_000 : 30_000;
                        if (age > maxAge) {
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
            });
        }
    }

    // ==================== STREAMING SESSION REFRESH (ZERO DOWNTIME) ====================
    private void performStreamingSessionRefresh() {
        log.info("=== Streaming session refresh started ===");
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                performProfileRotation();
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
            String body = safeApiGet(url, clientKey);
            if (body == null || body.isBlank()) return;

            processingExecutor.submit(() -> {
                try {
                    SportyEvent domainEvent = parseEventDetail(body);
                    if (domainEvent != null) processParsedEvent(domainEvent);
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

    // ==================== HTTP LAYER (THREAD-LOCAL CLIENTS, SAFE REFRESH) ====================
    private String safeApiGet(String url, String clientKey) {
        return safeApiGet(url, clientKey, 0);
    }

    private String safeApiGet(String url, String clientKey, int retry) {
        if (retry > 2) {
            log.info("HTTP max retries exceeded for: {}", url);
            consecutiveRateLimitErrors.incrementAndGet();
            throw new PlaywrightException("HTTP retried too many times: " + url);
        }

        long requestStart = System.currentTimeMillis();
        try {
            APIRequestContext client = getThreadLocalClient(clientKey);
            APIResponse res = client.get(url);
            int status = res.status();
            long requestDuration = System.currentTimeMillis() - requestStart;

            requestsSinceLastRotation.incrementAndGet();

            // Detect rate limiting
            if (status == 429) {
                int rateLimitCount = consecutiveRateLimitErrors.incrementAndGet();
                log.info("Rate limit detected (429) on attempt {} - count: {}, duration: {}ms",
                        retry + 1, rateLimitCount, requestDuration);
                Thread.sleep(1000 * (retry + 1)); // Exponential backoff
                return safeApiGet(url, clientKey, retry + 1);
            }

            if (status == 401 || status == 403) {
                int rateLimitCount = consecutiveRateLimitErrors.incrementAndGet();
                log.info("Auth/Forbidden error ({}) - possible rate limit, count: {}, duration: {}ms",
                        status, rateLimitCount, requestDuration);
                tlNeedsRefresh.set(true);
                Thread.sleep(150);
                return safeApiGet(url, clientKey, retry + 1);
            }

            // Detect suspiciously slow requests (likely throttled)
            if (requestDuration > SLOW_REQUEST_THRESHOLD_MS) {
                int rateLimitCount = consecutiveRateLimitErrors.incrementAndGet();
                log.info("Suspiciously slow request detected: {}ms (threshold: {}ms) - possible throttling, count: {}",
                        requestDuration, SLOW_REQUEST_THRESHOLD_MS, rateLimitCount);
            }

            String body = safeBody(res);
            if (status < 200 || status >= 300) {
                log.info("HTTP {} for {} (took {}ms)", status, url, requestDuration);
                throw new PlaywrightException("HTTP " + status + " on " + url);
            }

            // Success - reset rate limit counter
            consecutiveRateLimitErrors.set(0);

            if (requestDuration > 5000) {
                log.info("Slow API response: {}ms for {}", requestDuration, url);
            }

            return body;

        } catch (PlaywrightException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            long requestDuration = System.currentTimeMillis() - requestStart;

            if ((msg.contains("Cannot find object to call __adopt__")
                    || msg.contains("Cannot find command")
                    || msg.contains("Channel closed"))
                    && retry < 2) {

                log.info("Client disposal needed, recreating for retry {} (after {}ms)",
                        retry + 1, requestDuration);
                Map<String, APIRequestContext> local = threadLocalClients.get();
                APIRequestContext old = local.remove(clientKey);
                if (old != null) { try { old.dispose(); } catch (Exception ignore) {} }
                tlNeedsRefresh.set(false);
                return safeApiGet(url, clientKey, retry + 1);
            }

            if (msg.toLowerCase().contains("timeout")) {
                consecutiveTimeouts.incrementAndGet();
            }

            log.info("API request failed after {}ms: {}", requestDuration, msg);
            throw e;

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during API request", ie);
        }
    }

    private APIRequestContext getThreadLocalClient(String clientKey) {
        Map<String, APIRequestContext> local = threadLocalClients.get();
        Long threadVersion = threadLocalProfileVersion.get();
        Long currentVersion = profileVersion.get();

        // Check if this thread needs to refresh due to profile rotation
        if (!threadVersion.equals(currentVersion)) {
            log.info("Thread {} detected profile version change ({} -> {}), refreshing clients",
                    Thread.currentThread().getName(), threadVersion, currentVersion);
            local.values().forEach(c -> { try { c.dispose(); } catch (Exception ignored) {} });
            local.clear();
            threadLocalProfileVersion.set(currentVersion);
            tlNeedsRefresh.set(false);
        }

        if (globalNeedsRefresh.get()) {
            tlNeedsRefresh.set(true);
        }

        if (tlNeedsRefresh.get()) {
            log.info("Refreshing thread-local API clients for thread: {}",
                    Thread.currentThread().getName());
            local.values().forEach(c -> { try { c.dispose(); } catch (Exception ignored) {} });
            local.clear();
            tlNeedsRefresh.set(false);
            globalNeedsRefresh.set(false);
        }

        APIRequestContext client = local.get(clientKey);
        if (client == null) {
            Playwright pw = playwrightRef.get();
            if (pw == null) throw new IllegalStateException("Playwright not available");

            log.info("Creating new API client for key: {} on thread: {} with profile version: {}",
                    clientKey, Thread.currentThread().getName(), currentVersion);
            client = pw.request().newContext(
                    new APIRequest.NewContextOptions()
                            .setExtraHTTPHeaders(buildHeaders(cookieHeaderRef.get(), harvestedHeaders, profile))
                            .setTimeout(API_TIMEOUT_MS)
            );
            local.put(clientKey, client);
        }
        return client;
    }

    private void handleNetworkError(String context, PlaywrightException e) {
        int n = consecutiveNetworkErrors.incrementAndGet();
        String msg = e.getMessage() == null ? "" : e.getMessage();

        // Check if it's a rate limit related error
        if (msg.contains("429") || msg.contains("Too Many Requests")) {
            consecutiveRateLimitErrors.incrementAndGet();
            log.info("{} rate limit error (#{}): {}", context, n, msg);
        } else if (msg.toLowerCase().contains("timeout")) {
            consecutiveTimeouts.incrementAndGet();
            log.info("{} timeout error (#{}): {}", context, n, msg);
        } else {
            log.info("{} network error (#{}): {}", context, n, msg);
        }

        if (n >= 3) needsSessionRefresh.set(true);
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

    public Map<String, String> getAllHeaders(UserAgentProfile profile) {
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
        log.info("Profile details - Platform: {}, Languages: {}, Hardware: {} cores",
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
                    postscriptName: font.replace(/\\s+/g, '')
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

    // ==================== HELPERS ====================
    private Map<String, String> buildHeaders(String cookieHeader, Map<String, String> harvested, UserAgentProfile profile) {
        Map<String, String> h = new HashMap<>();
        if (profile != null) {
            h.put("User-Agent", profile.getUserAgent());
        }
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

        scheduler.shutdownNow();
        listFetchExecutor.shutdownNow();
        eventDetailExecutor.shutdownNow();
        processingExecutor.shutdownNow();
        retryExecutor.shutdownNow();
        profileRotationExecutor.shutdownNow();

        eventQueue.clear();

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

        Playwright pw = playwrightRef.getAndSet(null);
        if (pw != null) {
            try {
                pw.close();
            } catch (Exception ignored) {}
        }

        log.info("=== Shutdown complete ===");
    }
}