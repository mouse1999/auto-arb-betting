package com.mouse.bet.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mouse.bet.detector.ArbDetector;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.interceptor.SimpleHttpLoggingInterceptor;
import com.mouse.bet.model.NormalizedEvent;
import com.mouse.bet.model.msport.MSportEvent;
import com.mouse.bet.service.BetLegRetryService;
import com.mouse.bet.service.MSportService;
import com.mouse.bet.service.ScraperCycleSyncService;
import com.mouse.bet.utils.DecompressionUtil;
import com.mouse.bet.utils.JsonParser;
import com.mouse.bet.window.MSportWindow;
import com.mouse.bet.window.SportyWindow;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
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
 * Pure OkHttp fetcher for MSport odds - NO PLAYWRIGHT.
 * Uses multiple OkHttp clients with connection pooling for maximum speed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MSportOddsFetcher implements Runnable {

    // ==================== DEPENDENCIES ====================
    private final MSportService mSportService;
    private final BetLegRetryService betLegRetryService;
    private final ArbDetector arbDetector;
    private final ObjectMapper objectMapper;
    private final ScraperCycleSyncService cycleSync;
    private final MSportWindow mSportWindow;
    private final SportyWindow sportyWindow;

    private final String scraperId = "MSport-" + (Math.random() < 0.5 ? "A" : "B");

    // ==================== CONFIGURATION CONSTANTS ====================
    private static final String SPORT_PAGE = "https://www.msport.com";
    private static final int API_MAX_RETRIES = 2;
    private static final int API_TIMEOUT_MS = 5_000;
    private static final long MIN_SCHEDULER_PERIOD_SEC = 2;
    private static final long MAX_SCHEDULER_PERIOD_SEC = 15;
    private static final int EVENT_DETAIL_THREADS = 100;
    private static final int PROCESSING_THREADS = 50;
    private static final long EVENT_DEDUP_WINDOW_MS = 800;
    private static final int MAX_ACTIVE_FETCHES = 100;
    private static final long STALE_DATA_THRESHOLD_MS = 5_000;
    private static final int LIST_API_TIMEOUT_MS = 3_000;
    private static final int DETAIL_API_TIMEOUT_MS = 4_000;
    private static final int RATE_LIMIT_THRESHOLD = 5;
    private static final int SLOW_REQUEST_THRESHOLD_MS = 5_000;

    // ✅ Multiple OkHttp clients for better parallelism
    private static final int HTTP_CLIENT_POOL_SIZE = 10;

    @Value("${msport.fetch.enabled.football:false}")
    private boolean fetchFootballEnabled;

    @Value("${msport.fetch.enabled.basketball:false}")
    private boolean fetchBasketballEnabled;

    @Value("${msport.fetch.enabled.table-tennis:true}")
    private boolean fetchTableTennisEnabled;

    // Sport keys
    private static final String KEY_FB = "sr:sport:1";
    private static final String KEY_BB = "sr:sport:2";
    private static final String KEY_TT = "sr:sport:20";
    private static final BookMaker SCRAPER_BOOKMAKER = BookMaker.M_SPORT;

    // Response time tracking
    private static final int RESPONSE_TIME_WINDOW = 10;
    private final Queue<Long> recentResponseTimes = new ConcurrentLinkedQueue<>();

    // ==================== THREAD POOLS ====================
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
    private final ExecutorService listFetchExecutor = Executors.newFixedThreadPool(4);
    private final ExecutorService eventDetailExecutor = Executors.newFixedThreadPool(EVENT_DETAIL_THREADS);
    private final ExecutorService processingExecutor = Executors.newFixedThreadPool(PROCESSING_THREADS);
    private final ExecutorService retryExecutor = Executors.newFixedThreadPool(5);

    // ==================== HTTP CLIENT POOL ====================
    private final List<OkHttpClient> httpClientPool = new CopyOnWriteArrayList<>();
    private final AtomicInteger clientRoundRobin = new AtomicInteger(0);

    // ==================== STATE TRACKING ====================
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private final AtomicBoolean schedulesStarted = new AtomicBoolean(false);
    private final AtomicBoolean setupCompleted = new AtomicBoolean(false);

    private final AtomicBoolean footballFetchInProgress = new AtomicBoolean(false);
    private final AtomicBoolean basketballFetchInProgress = new AtomicBoolean(false);
    private final AtomicBoolean tableTennisFetchInProgress = new AtomicBoolean(false);

    private final AtomicInteger activeDetailFetches = new AtomicInteger(0);
    private final Map<String, Long> lastFetchTime = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRequestTime = new ConcurrentHashMap<>();

    // Rate limit metrics
    private final AtomicInteger consecutiveRateLimitErrors = new AtomicInteger(0);
    private final AtomicInteger consecutiveTimeouts = new AtomicInteger(0);
    private final AtomicInteger consecutiveNetworkErrors = new AtomicInteger(0);
    private final AtomicLong lastMetricsReset = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger requestsSinceLastReset = new AtomicInteger(0);

    private final AtomicLong dynamicCadenceSec = new AtomicLong(MIN_SCHEDULER_PERIOD_SEC);
    private volatile ScheduledFuture<?> activeFetchSchedule;

    private final PriorityBlockingQueue<EventFetchTask> eventQueue = new PriorityBlockingQueue<>(
            1000,
            Comparator.comparingInt(EventFetchTask::getPriority).reversed()
    );

    // ==================== TASK MODEL ====================
    private static class EventFetchTask {
        @Getter private final String eventId;
        @Getter private final String clientKey;
        private final boolean isLive;
        @Getter private final long timestamp;
        @Getter private final long requestSentTime;

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
        log.info("=== MSportOddsFetcher - Pure OkHttp Mode ===");
        log.info("InitialCadence={}s, Dedup={}ms, StaleThreshold={}ms, DetailThreads={}, ProcessingThreads={}",
                MIN_SCHEDULER_PERIOD_SEC, EVENT_DEDUP_WINDOW_MS, STALE_DATA_THRESHOLD_MS,
                EVENT_DETAIL_THREADS, PROCESSING_THREADS);

        try {
            setupCompleted.set(true);
            startSchedulers();
            startQueueProcessor();
            runHealthMonitor();
        } catch (Exception fatal) {
            log.error("Fatal error in main loop", fatal);
        } finally {
            cleanup();
        }
    }

    @PostConstruct
    public void init() {
        log.info("=== Initializing MSportOddsFetcher (Pure OkHttp) ===");
        try {
            initializeHttpClientPool();
            log.info("✅ HTTP client pool initialized with {} clients", HTTP_CLIENT_POOL_SIZE);
        } catch (Exception e) {
            log.error("Failed to initialize HTTP clients: {}", e.getMessage(), e);
            throw new RuntimeException("HTTP client initialization failed", e);
        }
    }

    // ==================== HTTP CLIENT POOL ====================
    private void initializeHttpClientPool() {
        log.info("Creating HTTP client pool with {} clients", HTTP_CLIENT_POOL_SIZE);

        for (int i = 0; i < HTTP_CLIENT_POOL_SIZE; i++) {
            OkHttpClient client = createOptimizedOkHttpClient();
            httpClientPool.add(client);
            log.info("Created HTTP client {}/{}", i + 1, HTTP_CLIENT_POOL_SIZE);
        }
    }

    private OkHttpClient createOptimizedOkHttpClient() {
        ConnectionPool connectionPool = new ConnectionPool(
                50,            // max idle connections per client
                5,             // keep-alive duration
                TimeUnit.MINUTES
        );

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

    private OkHttpClient getNextClient() {
        int index = Math.abs(clientRoundRobin.getAndIncrement() % HTTP_CLIENT_POOL_SIZE);
        return httpClientPool.get(index);
    }

    // ✅ MSport-specific headers
    private class HeadersInterceptor implements Interceptor {
        @Override
        public okhttp3.Response intercept(Chain chain) throws IOException {
            Request original = chain.request();

            Request.Builder builder = original.newBuilder()
                    .header("apilevel", "2")
                    .header("devmem", "8")
                    .header("network", "4g")
                    .header("operid", "2")
                    .header("schemechoose", "2")
                    .header("Clientid", "WEB")
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("Referer", SPORT_PAGE)
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Priority", "u=1, i")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");

            return chain.proceed(builder.build());
        }
    }

    // ==================== HEALTH MONITOR ====================
    private void runHealthMonitor() throws InterruptedException {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                boolean windowRunning = (sportyWindow.isWindowUpAndRunning() && mSportWindow.isWindowUpAndRunning());
                log.info("Tick — queued={}, activeFetches={}, setupOK={}, windowStatus={}",
                        eventQueue.size(), activeDetailFetches.get(), setupCompleted.get(),
                        windowRunning ? "UP" : "DOWN");

                if (!windowRunning) {
                    log.error("❌ MSport window is DOWN - scraper will pause fetching");
                }
            } catch (Throwable t) {
                log.error("Heartbeat error: {}", t.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(this::adjustCadenceBasedOnResponseTime,
                10, 10, TimeUnit.SECONDS);

        while (isRunning.get()) {
            Thread.sleep(Duration.ofSeconds(30).toMillis());
            logHealthMetrics();
            cleanupStaleFetchTimes();
        }
    }

    private void adjustCadenceBasedOnResponseTime() {
        long avgResponseTimeMs = calculateAverageResponseTime();

        long newCadenceSec;
        if (avgResponseTimeMs < 2000) {
            newCadenceSec = MIN_SCHEDULER_PERIOD_SEC;
        } else if (avgResponseTimeMs < 5000) {
            newCadenceSec = 5;
        } else {
            newCadenceSec = (avgResponseTimeMs / 1000) + 2;
            newCadenceSec = Math.min(newCadenceSec, MAX_SCHEDULER_PERIOD_SEC);
        }

        long oldCadence = dynamicCadenceSec.getAndSet(newCadenceSec);
        if (oldCadence != newCadenceSec) {
            log.warn("⚠️ Cadence adjusted: {}s → {}s (avg response: {}ms)",
                    oldCadence, newCadenceSec, avgResponseTimeMs);

            if (activeFetchSchedule != null) {
                activeFetchSchedule.cancel(false);
            }
            activeFetchSchedule = scheduler.scheduleAtFixedRate(
                    () -> safeWrapper("AllSports", this::fetchAllSportsParallel),
                    0, newCadenceSec, TimeUnit.SECONDS
            );
        }
    }

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

    private void recordResponseTime(long responseTimeMs) {
        recentResponseTimes.offer(responseTimeMs);

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
        int requests = requestsSinceLastReset.get();
        long timeSinceReset = System.currentTimeMillis() - lastMetricsReset.get();
        long avgResponseTime = calculateAverageResponseTime();
        long currentCadence = dynamicCadenceSec.get();
        boolean windowRunning = mSportWindow.isWindowUpAndRunning();

        int totalIdle = 0;
        int totalConn = 0;
        for (OkHttpClient client : httpClientPool) {
            if (client.connectionPool() != null) {
                totalIdle += client.connectionPool().idleConnectionCount();
                totalConn += client.connectionPool().connectionCount();
            }
        }

        log.info("Health — Active: {}, Queued: {}, NetErrors: {}, RateLimit: {}, Timeouts: {}, " +
                        "Requests: {}, TimeSinceReset: {}s, AvgResponse: {}ms, " +
                        "Cadence: {}s, TotalConnections: {}/{} idle, ClientPoolSize: {}, WindowStatus: {}",
                active, queued, netErrors, rateLimitErrors, timeouts, requests,
                timeSinceReset / 1000, avgResponseTime, currentCadence,
                totalIdle, totalConn, httpClientPool.size(), windowRunning ? "UP" : "DOWN");

        if (!windowRunning) {
            log.error("❌❌❌ CRITICAL: MSport window is DOWN - scraper cannot fetch events! ❌❌❌");
        }

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

    // ==================== SCHEDULERS & PARALLEL LIST FETCH ====================
    private void startSchedulers() {
        if (!schedulesStarted.compareAndSet(false, true)) {
            log.info("Schedulers already started");
            return;
        }

        log.info("Starting ADAPTIVE schedulers with initial cadence {}s (live arbing mode)",
                MIN_SCHEDULER_PERIOD_SEC);

        activeFetchSchedule = scheduler.scheduleAtFixedRate(
                () -> safeWrapper("AllSports", this::fetchAllSportsParallel),
                0, MIN_SCHEDULER_PERIOD_SEC, TimeUnit.SECONDS
        );
    }

    private void fetchAllSportsParallel() {
        if (!setupCompleted.get()) {
            log.info("Skipping fetch - setup not completed");
            return;
        }

        if (!(sportyWindow.isWindowUpAndRunning() && mSportWindow.isWindowUpAndRunning())) {
            log.warn("⚠️ MSport window is NOT running - skipping fetch cycle");
            return;
        }

        boolean partnerReady = cycleSync.waitForPartner(scraperId, Duration.ofSeconds(90));

        if (!partnerReady) {
            log.info("{} skipping fetch — partner not ready", scraperId);
            return;
        }

        long start = System.currentTimeMillis();

        if (fetchFootballEnabled) {
            if (footballFetchInProgress.compareAndSet(false, true)) {
                runSportListTaskWithFlag("Football", KEY_FB, KEY_FB, footballFetchInProgress);
            } else {
                log.warn("Skipping Football fetch - previous request still in progress");
            }
        }

        if (fetchBasketballEnabled) {
            if (basketballFetchInProgress.compareAndSet(false, true)) {
                runSportListTaskWithFlag("Basketball", KEY_BB, KEY_BB, basketballFetchInProgress);
            }
        }

        if (fetchTableTennisEnabled) {
            if (tableTennisFetchInProgress.compareAndSet(false, true)) {
                runSportListTaskWithFlag("TableTennis", KEY_TT, KEY_TT, tableTennisFetchInProgress);
            }
        }

        long duration = System.currentTimeMillis() - start;
        log.info("All sports fetch cycle triggered in {}ms [queue={}, active={}] | FB={} | BB={} | TT={}",
                duration, eventQueue.size(), activeDetailFetches.get(),
                fetchFootballEnabled ? "ON" : "OFF",
                fetchBasketballEnabled ? "ON" : "OFF",
                fetchTableTennisEnabled ? "ON" : "OFF"
        );
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
                .orTimeout(8, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    inProgressFlag.set(false);
                    if (ex instanceof TimeoutException || (ex.getCause() instanceof TimeoutException)) {
                        int timeouts = consecutiveTimeouts.incrementAndGet();
                        log.warn("⚠️ {}: List fetch timeout after 8s - SLOW NETWORK! (timeout #{})",
                                sportName, timeouts);
                    } else {
                        log.warn("⚠️ {}: List fetch error: {}", sportName, ex.getMessage());
                    }
                    return null;
                });
    }

    private void fetchSportEventsList(String sportName, String sportId, String clientKey) {
        long fetchStart = System.currentTimeMillis();

        try {
            if (!(sportyWindow.isWindowUpAndRunning() && mSportWindow.isWindowUpAndRunning())) {
                log.warn("⚠️ MSport window went down - aborting {} fetch", sportName);
                return;
            }

            if (shouldSkipRequest(clientKey)) {
                log.info("{}: Skipping - too soon after last request", sportName);
                return;
            }

            String url = buildEventsListUrl(sportId);
            log.info("{}: Fetching events list from API...", sportName);

            String body = safeApiGet(url, clientKey, 0, LIST_API_TIMEOUT_MS);

            long apiDuration = System.currentTimeMillis() - fetchStart;
            recordResponseTime(apiDuration);

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
        return buildUrl(SPORT_PAGE + "/api/ng/facts-center/query/frontend/live-matches",
                Map.of("sportId", sportId));
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
            boolean isLive = true;
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

    // ==================== DETAIL FETCH & PROCESS ====================
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
                    long dataAge = System.currentTimeMillis() - task.getRequestSentTime();

                    if (dataAge > STALE_DATA_THRESHOLD_MS) {
                        log.warn("⚠️ REJECTING STALE DATA: Event {} is {}ms old (threshold: {}ms)",
                                task.getEventId(), dataAge, STALE_DATA_THRESHOLD_MS);
                        return;
                    }

                    MSportEvent domainEvent = parseEventDetail(body);
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

    private MSportEvent parseEventDetail(String detailJson) {
        return JsonParser.deserializeMSportEvent(detailJson, objectMapper);
    }

    private void processParsedEvent(MSportEvent event, long dataAge) {
        if (event == null) return;

        try {
            NormalizedEvent normalized = mSportService.convertToNormalEvent(event);
            if (normalized == null) return;

            log.info("✅ Successfully normalized event {}", event.getEventId());

            if (dataAge > 3000) {
                log.info("Processing event {} with data age: {}ms", event.getEventId(), dataAge);
            }

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

    // ==================== HTTP LAYER (OKHTTP) ====================
    private String safeApiGet(String url, String clientKey, int retry, Integer perRequestTimeoutMs) {
        if (retry > API_MAX_RETRIES) {
            log.info("HTTP max retries exceeded for: {}", url);
            consecutiveRateLimitErrors.incrementAndGet();
            throw new RuntimeException("HTTP retried too many times: " + url);
        }

        long requestStart = System.currentTimeMillis();
        try {
            OkHttpClient client = getNextClient();

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

                requestsSinceLastReset.incrementAndGet();

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

            ResponseBody body = response.body();
            if (body == null) {
                return null;
            }

            return DecompressionUtil.decompressResponse(response);

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
            Thread.sleep(1000L * (retry + 1));
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

            try {
                Thread.sleep(200L * (retry + 1));
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
    }

    // ==================== UTILITY METHODS ====================
    private List<String> extractEventIds(String body) {
        return JsonParser.extractEventIds(body);
    }

    private String buildEventDetailUrl(String eventId) {
        String base = SPORT_PAGE + "/api/ng/facts-center/query/frontend/match/detail";
        return buildUrl(base, Map.of("eventId", eventId));
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

    private void safeWrapper(String name, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            log.error("{} error: {}", name, t.getMessage());
        }
    }

    // ==================== SHUTDOWN ====================
    public void shutdown() {
        log.info("Shutdown requested");
        isRunning.set(false);
    }

    private void cleanup() {
        log.info("=== Shutting down (Pure OkHttp Mode) ===");
        isRunning.set(false);

        shutdownExecutors();
        eventQueue.clear();
        cleanupHttpClientPool();

        log.info("=== Shutdown complete ===");
    }

    private void shutdownExecutors() {
        log.info("Shutting down executors...");
        scheduler.shutdownNow();
        listFetchExecutor.shutdownNow();
        eventDetailExecutor.shutdownNow();
        processingExecutor.shutdownNow();
        retryExecutor.shutdownNow();

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

    private void cleanupHttpClientPool() {
        log.info("Cleaning up HTTP client pool...");
        for (OkHttpClient client : httpClientPool) {
            shutdownOkHttpClient(client);
        }
        httpClientPool.clear();
    }

    private void shutdownOkHttpClient(OkHttpClient client) {
        try {
            if (client.connectionPool() != null) {
                client.connectionPool().evictAll();
            }
            if (client.dispatcher() != null) {
                ExecutorService executor = client.dispatcher().executorService();
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            if (client.cache() != null) {
                client.cache().close();
            }
        } catch (Exception e) {
            log.info("Error shutting down OkHttp client: {}", e.getMessage());
        }
    }
}