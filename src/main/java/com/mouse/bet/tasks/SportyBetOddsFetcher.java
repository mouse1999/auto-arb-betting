package com.mouse.bet.tasks;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import com.mouse.bet.config.ScraperConfig;
import com.mouse.bet.manager.ProfileManager;
import com.mouse.bet.model.profile.UserAgentProfile;
import com.mouse.bet.model.sporty.SportyEvent;
import com.mouse.bet.service.BetLegRetryService;
import com.mouse.bet.service.SportyBetService;
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

    // Schedulers
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

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
                        Page page = context.newPage();
                        attachAntiDetection(page, profile);
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
                .setExtraHTTPHeaders(createExtraHeaders(profile));

        return browser.newContext(opts);
    }

    private Map<String, String> createExtraHeaders(UserAgentProfile profile) {
        log.info("Creating headers.....");
        return Map.of(
                "X-Geo-Location", String.format("%f,%f", profile.getGeolocation().getLongitude(), profile.getGeolocation().getLatitude()),
                "X-Time-Zone", profile.getTimeZone(),
                "X-City-Code", profile.getCityCode()
        );
    }

    private void attachAntiDetection(Page page, UserAgentProfile profile) {
        // minimal anti-detect; extend with your full script
        page.addInitScript("""
            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
        """);
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
        return null;
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
        // If you already have a parser, use it here:
        // return sportyBetService.parseEventDetail(detailJson);
        //
        return null;
    }

    /** If Sporty has a different detail endpoint, change here (kept centralized). */
    private String buildEventDetailUrl(String eventId) {
        // Common patterns are:
        //   /api/ng/factsCenter/eventDetail?eventId=...&_t=...
        //   /api/ng/factsCenter/events?eventId=...&_t=...
        // Adjust to the exact one you need or expose via ScraperConfig.
        String base = BASE_URL + "/api/ng/factsCenter/eventDetail";
        return buildUrl(base, Map.of(
                "eventId", eventId,
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
    private void processParsedEvent(Object eventObj) {
        // Example wiring:
        // NormalizedEvent ne = sportyBetService.convertToNormalEvent((SportyEvent) eventObj);
        // arbDetector.addEventToPool(ne);
        // betLegRetryService.updateFailedBetLeg(ne); // if that’s your flow
        //
        // For now, just log:
        log.debug("Processed event: {}", eventObj != null ? eventObj.getClass().getSimpleName() : "null");
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
