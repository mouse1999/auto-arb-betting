package com.mouse.bet.window;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ViewportSize;
import com.microsoft.playwright.options.WaitUntilState;
import com.mouse.bet.config.ScraperConfig;
import com.mouse.bet.entity.Arb;
import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.exception.CaptchaDetectedException;
import com.mouse.bet.exception.LoginException;
import com.mouse.bet.exception.NavigationException;
import com.mouse.bet.exception.PageHealthException;
import com.mouse.bet.interfaces.BettingWindow;
import com.mouse.bet.manager.ArbOrchestrator;
import com.mouse.bet.manager.PageHealthMonitor;
import com.mouse.bet.manager.ProfileManager;
import com.mouse.bet.manager.WindowSyncManager;
import com.mouse.bet.model.profile.UserAgentProfile;
import com.mouse.bet.service.ArbPollingService;
import com.mouse.bet.service.BetLegRetryService;
import com.mouse.bet.tasks.LegTask;
import com.mouse.bet.utils.LoginUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static com.mouse.bet.utils.WindowUtils.attachAntiDetection;

/**
 * SportyBet window handler for live arbitrage betting
 * Manages browser automation, login, and bet placement coordination
 *
 * OPTIMIZED VERSION - Fixed navigation issues, proper context management, and page lifecycle
 */
@Slf4j
@Component
public class SportyWindow implements BettingWindow {

    private static final String EMOJI_INIT = "🚀";
    private static final String EMOJI_LOGIN = "🔐";
    private static final String EMOJI_BET = "🎯";
    private static final String EMOJI_SUCCESS = "✅";
    private static final String EMOJI_ERROR = "❌";
    private static final String EMOJI_WARNING = "⚠️";
    private static final String EMOJI_SYNC = "🔄";
    private static final String EMOJI_POLL = "📊";
    private static final String EMOJI_HEALTH = "💚";

    private static final BookMaker BOOK_MAKER = BookMaker.SPORTY_BET;
    private static final String CONTEXT_FILE = "sporty-context.json";
    private static final String SPORTY_BET_URL = "https://www.sportybet.com/ng";

    private final ProfileManager profileManager;
    private final ScraperConfig scraperConfig;
    private final ArbPollingService arbPollingService;
    private final ArbOrchestrator arbOrchestrator;
    private final BetLegRetryService betRetryService;
    private final WindowSyncManager syncManager;
    private final LoginUtils loginUtils;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext currentContext;
    private UserAgentProfile profile;
    private PageHealthMonitor healthMonitor;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicBoolean isLoggedIn = new AtomicBoolean(false);
    protected final BlockingQueue<LegTask> taskQueue = new LinkedBlockingQueue<>();

    @Value("${sporty.username:}")
    private String sportyUsername;

    @Value("${sporty.password:}")
    private String sportyPassword;

    @Value("${sporty.context.path:./playwright-context}")
    private String contextPath;

    @Value("${sporty.max.retry.attempts:3}")
    private int maxRetryAttempts;

    @Value("${sporty.poll.interval.ms:2000}")
    private long pollIntervalMs;

    @Value("${sporty.bet.timeout.seconds:10}")
    private int betTimeoutSeconds;

    @Value("${sportybet.fetch.enabled.football:false}")
    private boolean fetchFootballEnabled;

    @Value("${sportybet.fetch.enabled.basketball:false}")
    private boolean fetchBasketballEnabled;

    @Value("${sportybet.fetch.enabled.table-tennis:true}")
    private boolean fetchTableTennisEnabled;

    public SportyWindow(ProfileManager profileManager,
                        ScraperConfig scraperConfig,
                        ArbPollingService arbPollingService,
                        ArbOrchestrator arbOrchestrator,
                        BetLegRetryService betRetryService,
                        WindowSyncManager syncManager, LoginUtils loginUtils) {
        this.profileManager = profileManager;
        this.scraperConfig = scraperConfig;
        this.arbPollingService = arbPollingService;
        this.arbOrchestrator = arbOrchestrator;
        this.betRetryService = betRetryService;
        this.syncManager = syncManager;
        this.loginUtils = loginUtils;
    }

    /**
     * Initialize Playwright and browser
     */
    @PostConstruct
    public void init() {
        log.info("{} {} Initializing SportyWindow with Playwright...", EMOJI_INIT, EMOJI_BET);
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(false)
                    .setArgs(scraperConfig.getBROWSER_FlAGS())
                    .setSlowMo(50));

            log.info("{} {} Playwright initialized successfully", EMOJI_SUCCESS, EMOJI_INIT);
            log.info("Registering SportyBet Window for Bet placing");
            arbOrchestrator.registerWorker(BOOK_MAKER, taskQueue);
        } catch (Exception e) {
            log.error("{} {} Failed to initialize Playwright: {}", EMOJI_ERROR, EMOJI_INIT, e.getMessage(), e);
            throw new RuntimeException("Playwright initialization failed", e);
        }
    }

    /**
     * Main entry point - runs the betting window with retry logic
     * OPTIMIZED: Better error categorization and recovery
     */
    public void run() {
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxRetryAttempts) {
            attempt++;
            log.info("{} {} Starting SportyWindow attempt {}/{}",
                    EMOJI_INIT, EMOJI_BET, attempt, maxRetryAttempts);

            try {
                windowEntry();
                log.info("{} {} SportyWindow completed successfully", EMOJI_SUCCESS, EMOJI_BET);
                break; // Success, exit retry loop

            } catch (CaptchaDetectedException e) {
                log.error("{} {} CAPTCHA detected on attempt {}: {}",
                        EMOJI_ERROR, EMOJI_WARNING, attempt, e.getMessage());
                lastException = e;
                handleCaptchaScenario();

            } catch (PageHealthException e) {
                log.error("{} {} Page health check failed on attempt {}: {}",
                        EMOJI_ERROR, EMOJI_HEALTH, attempt, e.getMessage());
                lastException = e;
                if (attempt < maxRetryAttempts) {
                    recreateContext();
                    waitBetweenRetries(attempt);
                }

            } catch (PlaywrightException e) {
                String msg = e.getMessage();

                // Handle specific Playwright object lifecycle errors
                if (msg.contains("Object doesn't exist") ||
                        msg.contains("frame was detached") ||
                        msg.contains("ERR_ABORTED") ||
                        msg.contains("Target closed")) {

                    log.error("{} {} Playwright object lifecycle error on attempt {}: {}",
                            EMOJI_ERROR, EMOJI_WARNING, attempt, msg);
                    lastException = e;

                    if (attempt < maxRetryAttempts) {
                        recreateContext(); // Full cleanup and reset
                        waitBetweenRetries(attempt);
                    }
                } else {
                    // Unexpected Playwright error
                    log.error("{} {} Unexpected Playwright error: {}",
                            EMOJI_ERROR, EMOJI_BET, msg, e);
                    lastException = e;
                    if (attempt < maxRetryAttempts) {
                        recreateContext();
                        waitBetweenRetries(attempt);
                    }
                }

            } catch (Exception e) {
                log.error("{} {} Unexpected error on attempt {}: {}",
                        EMOJI_ERROR, EMOJI_WARNING, attempt, e.getMessage(), e);
                lastException = e;
                if (attempt < maxRetryAttempts) {
                    recreateContext();
                    waitBetweenRetries(attempt);
                }
            }
        }

        if (lastException != null) {
            String errorMsg = String.format("Failed after %d attempts. Last error: %s",
                    maxRetryAttempts, lastException.getMessage());
            log.error("{} {} All retry attempts exhausted for profile {}: {}",
                    EMOJI_ERROR, EMOJI_BET, profile != null ? profile.getId() : "unknown", errorMsg);
        }
    }

    /**
     * Main window entry point - handles login, monitoring, and betting loop
     * OPTIMIZED: Better page lifecycle management, prevents multiple pages
     */
    private void windowEntry() throws Exception {
        Page page = null;

        try {
            // Load or create context
            if (currentContext == null) {
                currentContext = loadOrCreateContext();
            }

            // Verify context is valid
            if (currentContext == null) {
                throw new RuntimeException("Failed to create valid browser context");
            }

            // CRITICAL: Close any existing pages first to prevent multiple pages opening
            for (Page existingPage : currentContext.pages()) {
                if (!existingPage.isClosed()) {
                    log.warn("Closing existing page: {}", existingPage.url());
                    try {
                        existingPage.close();
                    } catch (Exception e) {
                        log.debug("Error closing existing page: {}", e.getMessage());
                    }
                }
            }

            attachAntiDetection(currentContext, profile);

            // Create ONE new page
            page = currentContext.newPage();

            // Verify page was created successfully
            if (page == null || page.isClosed()) {
                throw new RuntimeException("Failed to create valid page");
            }



            log.info("{} {} Page created and health monitor started", EMOJI_SUCCESS, EMOJI_HEALTH);

            // Navigate to SportyBet (has internal retry logic)
            navigateToSportyAndTournament(page);

            // Start health monitoring
            healthMonitor = new PageHealthMonitor(page);
            healthMonitor.start();
            healthMonitor.checkHealth();

            // Handle login with integrated isLoggedIn check
            performLoginIfRequired(page);

            mimicHumanBehavior(page);
            goToLivePage(page);
            moveToEnabledLiveSport(page);

            // Mimic human behavior
            mimicHumanBehavior(page);
            healthMonitor.checkHealth();

            // Register this window with sync manager
            syncManager.registerWindow(BOOK_MAKER, this);
            log.info("{} {} Window registered with sync manager: {}",
                    EMOJI_SUCCESS, EMOJI_SYNC, BOOK_MAKER);

            // Start main betting loop
            isRunning.set(true);
            runBettingLoop(page, BOOK_MAKER);

        } catch (Exception e) {
            log.error("{} {} Error in windowEntry: {}", EMOJI_ERROR, EMOJI_WARNING, e.getMessage(), e);
            throw e;

        } finally {
            isRunning.set(false);

            // Stop health monitor before closing page
            if (healthMonitor != null) {
                try {
                    healthMonitor.stop();
                } catch (Exception e) {
                    log.debug("Error stopping health monitor: {}", e.getMessage());
                }
            }

            // Close the page explicitly
            if (page != null && !page.isClosed()) {
                try {
                    page.close();
                    log.info("Page closed successfully");
                } catch (Exception e) {
                    log.warn("Error closing page: {}", e.getMessage());
                }
            }

            // Save context (if still valid)
            if (currentContext != null) {
                try {
                    saveContext(currentContext);
                    log.info("{} {} Context saved successfully", EMOJI_SUCCESS, EMOJI_INIT);
                } catch (Exception e) {
                    log.warn("Failed to save context: {}", e.getMessage());
                }
            }

            log.info("{} {} Window entry completed", EMOJI_SUCCESS, EMOJI_INIT);
        }
    }

    /**
     * Navigate to SportyBet live betting page
     * OPTIMIZED: Retry logic, better wait states, proper error handling
     */
    private void navigateToSportyAndTournament(Page page) {
        log.info("{} {} Navigating to SportyBet...", EMOJI_INIT, EMOJI_BET);

        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // Check if page is still valid
                if (page.isClosed()) {
                    log.error("{} {} Page is closed, cannot navigate", EMOJI_ERROR, EMOJI_BET);
                    throw new RuntimeException("Page is closed");
                }

                // Navigate with more lenient options
                page.navigate(SPORTY_BET_URL, new Page.NavigateOptions()
                        .setTimeout(60000)
                        .setWaitUntil(WaitUntilState.NETWORKIDLE)); // More lenient than LOAD

//                // Wait for network to be idle (more stable)
//                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions()
//                        .setTimeout(30000));

                log.info("{} {} Successfully navigated to SportyBet", EMOJI_SUCCESS, EMOJI_BET);
                return; // SUCCESS - exit method

            } catch (PlaywrightException e) {
                String errorMsg = e.getMessage();

                if (errorMsg.contains("Object doesn't exist") ||
                        errorMsg.contains("frame was detached") ||
                        errorMsg.contains("ERR_ABORTED") ||
                        errorMsg.contains("Timeout")) {

                    log.warn("{} {} Navigation attempt {}/{} failed: {}",
                            EMOJI_WARNING, EMOJI_BET, attempt, maxAttempts, errorMsg);

                    if (attempt < maxAttempts) {
                        try {
                            Thread.sleep(2000 * attempt); // Exponential backoff
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Navigation interrupted", ie);
                        }
                        continue; // Retry
                    }
                }

                // Either not a recoverable error, or we're out of attempts
                log.error("{} {} Failed to navigate after {} attempts: {}",
                        EMOJI_ERROR, EMOJI_BET, maxAttempts, errorMsg);
                throw new RuntimeException("Navigation failed after retries", e);
            }
        }
    }

    /**
     * Integrated login method that checks login status and performs login only if needed
     */
    private void performLoginIfRequired(Page page) throws LoginException {
        if (isLoggedIn.get()) {
            log.info("{} {} Already logged in (cached state)", EMOJI_SUCCESS, EMOJI_LOGIN);
            return;
        }

        // Check current page login status
        if (checkLoginStatus(page)) {
            isLoggedIn.set(true);
            log.info("{} {} Already logged in (page verification)", EMOJI_SUCCESS, EMOJI_LOGIN);
            return;
        }

        log.info("{} {} Not logged in, performing login...", EMOJI_WARNING, EMOJI_LOGIN);
        performLogin(page);
        healthMonitor.checkHealth();
        saveContext(currentContext);

        // Verify login was successful
        if (checkLoginStatus(page)) {
            isLoggedIn.set(true);
            log.info("{} {} Login successful, context saved", EMOJI_SUCCESS, EMOJI_LOGIN);
        } else {
            throw new LoginException("Login verification failed after login attempt");
        }
    }

    /**
     * Perform login with integrated status checking
     */
    private void performLogin(Page page) throws LoginException {
        log.info("{} {} Performing login...", EMOJI_LOGIN, EMOJI_INIT);

        try {
            page.waitForLoadState(LoadState.NETWORKIDLE);

            boolean loginSuccess = loginUtils.performLogin(page, sportyUsername, sportyPassword);

            if (!loginSuccess) {
                log.error("{} {} Login failed – universal login returned false", EMOJI_ERROR, EMOJI_LOGIN);
                throw new LoginException("Universal login failed");
            }

            // Extra safety: wait for network + small delay
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(2500);

            log.info("{} {} Verifying login status...", EMOJI_LOGIN, EMOJI_HEALTH);

            if (!checkLoginStatus(page)) {
                log.error("{} {} Login verification failed – no user indicators found", EMOJI_ERROR, EMOJI_LOGIN);
                throw new LoginException("Login verification failed – user not detected");
            }

            log.info("{} {} Login successful and verified!", EMOJI_SUCCESS, EMOJI_LOGIN);

        } catch (Exception e) {
            log.error("{} {} Unexpected login error: {}", EMOJI_ERROR, EMOJI_LOGIN, e.getMessage());
            throw new LoginException("Login failed: " + e.getMessage(), e);
        }
    }

    /**
     * Check if logged in - extracted as reusable method
     */
    private boolean checkLoginStatus(Page page) {
        try {
            // Delegate to LoginUtils which has proper login detection
            boolean loggedIn = loginUtils.isLoggedIn(page);

            if (loggedIn) {
                log.info("Login status: LOGGED IN");
            } else {
                log.info("Login status: NOT LOGGED IN");
            }

            return loggedIn;

        } catch (Exception e) {
            log.error("Error checking login status: {}", e.getMessage());
            return false; // Assume not logged in on error
        }
    }

    /**
     * Main betting loop - polls for arbs and places bets
     */
    private void runBettingLoop(Page page, BookMaker bookmaker) throws Exception {
        log.info("{} {} Starting betting loop for {}", EMOJI_INIT, EMOJI_POLL, bookmaker);

        while (isRunning.get()) {
            try {
                // Check if paused
                if (isPaused.get()) {
                    log.debug("Window paused, waiting...");
                    Thread.sleep(1000);
                    continue;
                }

                // Health check
                healthMonitor.checkHealth();

                // Poll for available Leg task
                LegTask task = taskQueue.poll();
                if (task == null) {
                    Thread.sleep(100); // Small delay to prevent busy waiting
                    continue;
                }

                log.info("arb opportunity retrieved for placing bet | ArbId: {} | Profit: {}% | Bookmaker: {}",
                        task.getArbId(), task.getLeg().getProfitPercent(), BOOK_MAKER);

                // Get the leg for this bookmaker
                BetLeg myLeg = task.getLeg();
                if (myLeg == null) {
                    log.warn("{} {} No leg found for bookmaker {} in arb {}",
                            EMOJI_WARNING, EMOJI_BET, bookmaker, task.getArbId());
                    continue;
                }

                // Signal that this window is healthy and ready to accept bets
                syncManager.markReady(task.getArbId(), bookmaker);

                // Wait for partner window to be ready
                boolean partnersReady = syncManager.waitForPartnersReady(
                        task.getArbId(),
                        bookmaker,
                        Duration.ofSeconds(betTimeoutSeconds)
                );

                if (!partnersReady) {
                    log.warn("{} {} Partners not ready within timeout | ArbId: {}",
                            EMOJI_WARNING, EMOJI_SYNC, task.getArbId());
                    arbPollingService.releaseArb(task.getArb());
                    continue;
                }

                log.info("{} {} All partners ready, proceeding with bet placement | ArbId: {}",
                        EMOJI_SUCCESS, EMOJI_SYNC, task.getArbId());

                // Process the bet placement
                processBetPlacement(page, task, myLeg);

                // Mimic human delay between bets
                randomHumanDelay(2000, 5000);

            } catch (InterruptedException e) {
                log.info("{} Betting loop interrupted", EMOJI_WARNING);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("{} {} Error in betting loop: {}",
                        EMOJI_ERROR, EMOJI_BET, e.getMessage(), e);
                Thread.sleep(5000); // Wait before continuing
            }
        }

        log.info("{} {} Betting loop stopped", EMOJI_SUCCESS, EMOJI_POLL);
    }

    /**
     * Process bet placement - extracted from main loop for better readability
     */
    private void processBetPlacement(Page page, LegTask task, BetLeg myLeg) {
        // Navigate to the game
        boolean gameAvailable = navigateToGameOnSporty(page, task.getArb(), myLeg);

        if (!gameAvailable) {
            log.warn("{} {} Game not available | ArbId: {} | EventId: {}",
                    EMOJI_WARNING, EMOJI_BET, task.getArbId(), myLeg.getEventId());

            // Notify sync manager of failure
            syncManager.notifyBetFailure(task.getArbId(), BOOK_MAKER, "Game not available");

            // Check if partner already placed bet
            if (syncManager.hasPartnerPlacedBet(task.getArbId(), BOOK_MAKER)) {
                log.error("{} {} Partner already placed bet but game unavailable here! | ArbId: {}",
                        EMOJI_ERROR, EMOJI_WARNING, task.getArbId());
                // Trigger retry service for partner's leg
                betRetryService.addFailedBetLegForRetry(myLeg, BOOK_MAKER);
            }

            arbPollingService.releaseArb(task.getArb());
            return;
        }

        healthMonitor.checkHealth();

        // Verify odds are still good
        boolean oddsValid = verifyOdds(page, myLeg);
        if (!oddsValid) {
            log.warn("{} {} Odds changed unfavorably | ArbId: {}",
                    EMOJI_WARNING, EMOJI_BET, task.getArbId());
            syncManager.notifyBetFailure(task.getArbId(), BOOK_MAKER, "Odds changed");
            arbPollingService.releaseArb(task.getArb());
            return;
        }

        // Place the bet
        boolean betPlaced = placeBet(page, task.getArb(), myLeg);

        if (betPlaced) {
            log.info("{} {} Bet placed successfully | ArbId: {} | Stake: {} | Odds: {}",
                    EMOJI_SUCCESS, EMOJI_BET, task.getArbId(),
                    myLeg.getStake(), myLeg.getOdds());

            // Notify sync manager
            syncManager.notifyBetPlaced(task.getArbId(), BOOK_MAKER);

            // Wait for confirmation
            waitForBetConfirmation(page);

            // Update leg status
            myLeg.markAsPlaced(extractBetId(page), myLeg.getOdds());

        } else {
            log.error("{} {} Bet placement failed | ArbId: {}",
                    EMOJI_ERROR, EMOJI_BET, task.getArbId());

            syncManager.notifyBetFailure(task.getArbId(), BOOK_MAKER, "Placement failed");

            // Check if partner placed bet
            if (syncManager.hasPartnerPlacedBet(task.getArbId(), BOOK_MAKER)) {
                log.error("{} {} Partner placed bet but we failed! Scheduling retry | ArbId: {}",
                        EMOJI_ERROR, EMOJI_WARNING, task.getArbId());
                betRetryService.addFailedBetLegForRetry(myLeg, BOOK_MAKER);
            }
        }

        // Release arb
        arbPollingService.releaseArb(task.getArb());
    }

    /**
     * Navigate to live betting page
     */
    private void goToLivePage(Page page) {
        final long timeout = 20_000; // 20 seconds max for everything

        // Step 1: Try the fastest and most reliable selectors first (with fallback chain)
        try {
            // Priority 1: Unique ID - fastest & most stable
            Locator liveBettingLink = page.locator("#header_nav_liveBetting");
            if (liveBettingLink.isVisible(new Locator.IsVisibleOptions().setTimeout(5000))) {
                liveBettingLink.click(new Locator.ClickOptions().setTimeout(10000));
                log.info("Clicked 'Live Betting' using ID selector");
            } else {
                throw new Exception("ID locator not visible");
            }
        } catch (Exception e) {
            log.info("ID selector failed, trying fallback...");

            // Priority 2: Text-based CSS selector (very reliable)
            try {
                Locator liveBettingLink = page.locator("a:has-text('Live Betting')");
                liveBettingLink.click(new Locator.ClickOptions().setTimeout(10000));
                log.info("Clicked 'Live Betting' using text selector");
            } catch (Exception e2) {
                log.info("Text selector failed, trying accessibility role...");

                // Priority 3: Playwright's recommended auto-healing selector (best for accessibility)
                try {
                    page.getByRole(AriaRole.LINK,
                                    new Page.GetByRoleOptions().setName("Live Betting").setExact(true))
                            .click(new Locator.ClickOptions().setTimeout(10000));
                    log.info("Clicked 'Live Betting' using getByRole (accessibility)");
                } catch (Exception e3) {
                    throw new NavigationException("Failed to click 'Live Betting' tab using all fallback strategies", e3);
                }
            }
        }

        // Step 2: Wait for navigation to Live Betting page with flexible pattern
        try {
            page.waitForURL(url -> url.toString().contains("/sport/live/"),
                    new Page.WaitForURLOptions().setTimeout(timeout));

            // Optional: Extra safety - ensure the live page content is actually loaded
            page.waitForLoadState(LoadState.LOAD,
                    new Page.WaitForLoadStateOptions().setTimeout(15000));

            log.info("Successfully navigated to Live Betting page: " + page.url());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load Live Betting page after clicking. Current URL: " + page.url(), e);
        }
    }

    /**
     * Main method: Enter Multi View then switch to the correct live sport
     */
    private void moveToEnabledLiveSport(Page page) throws InterruptedException {
        // === 1. Click "Multi View" ===
        page.locator("""
                        "a:has-text('Multi View')",
                      "span[data-cms-key='multi_view']",
                      "[data-cms-key='multi_view']",
                      "a >> text=Multi View",
                      "span:has-text('Multi View')"
                       
                       """)
                .first()
                .click(new Locator.ClickOptions().setTimeout(15_000));

        log.info("Entered Multi View");

        // === 2. Wait for Football Multi View (default landing page) ===
        page.waitForURL("**/football/live_list/**", new Page.WaitForURLOptions().setTimeout(15_000));
        randomHumanDelay(2000, 4500);

        // === 3. Confirm matches are visible ===
        page.waitForSelector(".match-row, .live-match, .m-content-row",
                new Page.WaitForSelectorOptions().setTimeout(10_000));

        randomHumanDelay(1500, 3000);
        log.info("Multi View fully loaded: {}", page.url());

        // === 4. Choose target sport ===
        if (fetchBasketballEnabled) {
            switchToLiveSport(page, "Basketball");
        } else if (fetchTableTennisEnabled) {
            switchToLiveSport(page, "Table Tennis");
        } else {
            log.info("Staying on Football Multi View (default)");
            randomHumanDelay(2000, 4000);
        }
    }

    /**
     * Universal sport switcher – works for EVERY sport on SportyBet
     */
    private void switchToLiveSport(Page page, String sportInput) throws InterruptedException {
        String sport = sportInput.trim();
        String displayName;
        String urlSegment;

        switch (sport.toLowerCase()) {
            case "football" -> {
                displayName = "Football";
                urlSegment = "football";
            }
            case "basketball" -> {
                displayName = "Basketball";
                urlSegment = "basketball";
            }
            case "table tennis", "table-tennis", "tt", "tabletennis" -> {
                displayName = "Table Tennis";
                urlSegment = "tableTennis";
            }
            case "tennis" -> {
                displayName = "Tennis";
                urlSegment = "tennis";
            }
            default -> {
                displayName = sport.substring(0, 1).toUpperCase() + sport.substring(1).toLowerCase();
                urlSegment = displayName.toLowerCase().replace(" ", "-");
            }
        }

        log.info("Switching to live sport: {} → {}", displayName, urlSegment);

        boolean isVisibleSport = Set.of("Football", "Basketball", "Tennis", "vFootball", "eFootball")
                .contains(displayName);

        if (isVisibleSport) {
            clickVisibleSportTab(page, displayName, urlSegment);
        } else {
            clickSportViaMoreSportsDropdown(page, displayName, urlSegment);
        }

        // Final confirmation
        page.waitForSelector(".match-row, .m-content-row, .live-match",
                new Page.WaitForSelectorOptions().setTimeout(12_000));

        randomHumanDelay(2200, 4200);
        log.info("{} Multi View ready! URL: {}", displayName, page.url());
    }

    /**
     * Click visible sport tab (Football, Basketball, Tennis, etc.)
     */
    private void clickVisibleSportTab(Page page, String displayName, String urlSegment) throws InterruptedException {
        page.locator("""
        .sport-name-item >> .text:has-text('%s'),
        .sport-name >> text='%s',
        div.m-overview >> text='%s' >> visible=true
        """.formatted(displayName, displayName, displayName))
                .first()
                .click(new Locator.ClickOptions()
                        .setTimeout(12_000)
                        .setForce(true));

        page.waitForURL("**/" + urlSegment + "/live_list/**",
                new Page.WaitForURLOptions().setTimeout(15_000));
    }

    /**
     * Click sport via More Sports dropdown (Table Tennis, etc.)
     */
    private void clickSportViaMoreSportsDropdown(Page page, String displayName, String urlSegment) throws InterruptedException {
        // Open dropdown
        page.locator(".select-title, text='More Sports', .select-title__label")
                .first()
                .click(new Locator.ClickOptions().setTimeout(10_000));

        randomHumanDelay(800, 1600);

        // Wait for list
        page.waitForSelector(".select-list >> visible=true",
                new Page.WaitForSelectorOptions().setTimeout(8000));

        // Click exact sport
        page.locator(".select-item")
                .filter(new Locator.FilterOptions().setHasText(displayName))
                .first()
                .click(new Locator.ClickOptions()
                        .setTimeout(10_000)
                        .setForce(true));

        // Confirm navigation
        page.waitForURL("**/" + urlSegment + "/live_list/**",
                new Page.WaitForURLOptions().setTimeout(15_000));
    }

    /**
     * Navigate to specific game/event
     * OPTIMIZED: Better selector strategies, proper error handling
     */
    private boolean navigateToGameOnSporty(Page page, Arb arb, BetLeg leg) {
        String home = leg.getHomeTeam().trim();
        String away = leg.getAwayTeam().trim();
        String fullMatch = home + " vs " + away;

        log.info("{} Navigating to: {} | EventId: {}", EMOJI_BET, fullMatch, leg.getEventId());

        try {
            randomHumanDelay(2000, 3000);

            if (tryDirectNavigation(page, home, away, leg)) return true;
            if (tryEventIdNavigation(page, leg)) return true;

        } catch (Exception e) {
            log.error("{} Navigation crashed: {}", EMOJI_ERROR, e.toString());
        }

        log.warn("{} All navigation methods failed for: {}", EMOJI_WARNING, fullMatch);
        return false;
    }

    /**
     * METHOD 1: Direct click in Multi View / Live List (FASTEST & MOST RELIABLE)
     */
    private boolean tryDirectNavigation(Page page, String home, String away, BetLeg leg) {
        String homeEscaped = Pattern.quote(home);
        String awayEscaped = Pattern.quote(away);

        String[] selectors = {
                // Most reliable: title attribute with both teams
                String.format(".teams[title*='%s'][title*='%s' i]", home, away),
                // Structural selectors (more reliable than text)
                String.format(".home-team:text-is('%s') + * .away-team:text-is('%s')", home, away),
                // Flexible text matching
                String.format(".teams:has-text('%s'):has-text('%s')", home, away),
                // Single team fallback
                String.format(".teams[title*='%s' i]", home),
                ".teams:has-text(" + homeEscaped + "):has-text(" + awayEscaped + ") >> nth=0"
        };

        for (String selector : selectors) {
            try {
                Locator match = page.locator(selector).first();

                if (match.count() > 0 && match.isVisible()) {
                    log.info("{} Found via: {}", EMOJI_SUCCESS, selector);

                    // Ensure element is actionable
                    match.scrollIntoViewIfNeeded();
                    randomHumanDelay(900, 1800);

                    match.click(new Locator.ClickOptions().setTimeout(10_000));

                    // More robust URL waiting
                    page.waitForURL(url ->
                                    url.contains("/event/") ||
                                            url.contains("/match/") ||
                                            url.contains("/games/"),
                            new Page.WaitForURLOptions()
                                    .setTimeout(15000)
                                    .setWaitUntil(WaitUntilState.LOAD)
                    );

                    log.info("{} Navigated to: {}", EMOJI_SUCCESS, page.url());
                    return true;
                }
            } catch (PlaywrightException e) {
                log.debug("Selector failed: {} - {}", selector, e.getMessage());
            }
        }
        return false;
    }

    /**
     * METHOD 2: Direct URL with Event ID (Nuclear Option – 100% if ID is valid)
     */
    private boolean tryEventIdNavigation(Page page, BetLeg leg) {
        String eventId = leg.getEventId();
        if (eventId == null || eventId.isBlank()) {
            log.debug("Invalid eventId: {}", eventId);
            return false;
        }

        String url = "https://www.sportybet.com/ng/sport/" + leg.getNavigationLink();

        try {
            log.info("{} Trying direct URL: {}", EMOJI_WARNING, url);

            Response response = page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(20_000)
                    .setWaitUntil(WaitUntilState.LOAD));

            // Check HTTP status
            if (response != null && !response.ok()) {
                log.debug("URL returned status: {}", response.status());
            }

            randomHumanDelay(2000, 3500);

            // More robust verification
            boolean hasMatchContent = page.locator(
                    ".teams, .home-team, .away-team, .match-details"
            ).count() > 0;

            boolean correctUrl = page.url().contains(eventId) &&
                    (page.url().contains("/event/") || page.url().contains("/match/"));

            if (hasMatchContent && correctUrl) {
                log.info("{} Event ID navigation SUCCESS → {}", EMOJI_SUCCESS, page.url());
                return true;
            }

        } catch (PlaywrightException e) {
            log.debug("URL failed: {} - {}", url, e.getMessage());
        }

        return false;
    }

    /**
     * Verify odds are still valid
     */
    private boolean verifyOdds(Page page, BetLeg leg) {
        try {
            // Locate odds element for the specific outcome
            String oddsSelector = String.format(
                    "//button[contains(@class, 'odd') and contains(text(), '%s')]",
                    leg.getOdds().toString()
            );

            Locator oddsElement = page.locator(oddsSelector);
            if (oddsElement.count() > 0) {
                String displayedOdds = oddsElement.first().textContent().trim();
                double displayed = Double.parseDouble(displayedOdds);
                double expected = leg.getOdds().doubleValue();

                // Allow 2% tolerance
                double tolerance = expected * 0.02;
                boolean valid = Math.abs(displayed - expected) <= tolerance;

                if (valid) {
                    log.info("{} {} Odds verified | Expected: {} | Displayed: {}",
                            EMOJI_SUCCESS, EMOJI_BET, expected, displayed);
                } else {
                    log.warn("{} {} Odds mismatch | Expected: {} | Displayed: {}",
                            EMOJI_WARNING, EMOJI_BET, expected, displayed);
                }

                return valid;
            }

            return false;

        } catch (Exception e) {
            log.error("{} {} Error verifying odds: {}", EMOJI_ERROR, EMOJI_BET, e.getMessage());
            return false;
        }
    }

    /**
     * Place bet on the page
     */
    private boolean placeBet(Page page, Arb arb, BetLeg leg) {
        log.info("{} {} Placing bet | Stake: {} | Odds: {}",
                EMOJI_BET, EMOJI_BET, leg.getStake(), leg.getOdds());

        try {
            // Click on odds to add to betslip
            String oddsSelector = String.format(
                    "//button[contains(@class, 'odd') and contains(text(), '%s')]",
                    leg.getOdds().toString()
            );
            page.locator(oddsSelector).first().click();
            Thread.sleep(500);

            // Enter stake amount
            Locator stakeInput = page.locator("input[placeholder*='stake'], input[name*='amount']");
            stakeInput.first().fill(leg.getStake().toString());
            Thread.sleep(300);

            // Click place bet button
            Locator placeBetBtn = page.locator("button:has-text('Place Bet'), button:has-text('Bet Now')");
            placeBetBtn.first().click();

            // Wait for confirmation
            Thread.sleep(2000);

            log.info("{} {} Bet placement initiated", EMOJI_SUCCESS, EMOJI_BET);
            return true;

        } catch (Exception e) {
            log.error("{} {} Failed to place bet: {}", EMOJI_ERROR, EMOJI_BET, e.getMessage());
            return false;
        }
    }

    /**
     * Wait for bet confirmation
     */
    private void waitForBetConfirmation(Page page) {
        try {
            Locator confirmation = page.locator(
                    "//div[contains(text(), 'Bet placed') or contains(text(), 'Success')]"
            );
            confirmation.waitFor(new Locator.WaitForOptions().setTimeout(5000));
            log.info("{} {} Bet confirmed", EMOJI_SUCCESS, EMOJI_BET);
        } catch (Exception e) {
            log.warn("{} {} Confirmation not detected: {}", EMOJI_WARNING, EMOJI_BET, e.getMessage());
        }
    }

    /**
     * Extract bet ID from confirmation
     */
    private String extractBetId(Page page) {
        try {
            Locator betIdElement = page.locator("//span[contains(text(), 'Bet ID')]/following-sibling::span");
            if (betIdElement.count() > 0) {
                return betIdElement.first().textContent().trim();
            }
        } catch (Exception e) {
            log.warn("Could not extract bet ID: {}", e.getMessage());
        }
        return "BET_" + System.currentTimeMillis();
    }

    /**
     * Mimic human behavior
     */
    private void mimicHumanBehavior(Page page) {
        try {
            log.debug("Mimicking human behavior...");

            // Random scroll
            page.evaluate("window.scrollTo(0, Math.random() * 500)");
            randomHumanDelay(500, 1500);

            // Mouse movement
            page.mouse().move(ThreadLocalRandom.current().nextInt(100, 600),
                    ThreadLocalRandom.current().nextInt(100, 600));
            randomHumanDelay(200, 700);

        } catch (Exception e) {
            log.warn("Error mimicking human behavior: {}", e.getMessage());
        }
    }

    /**
     * Load or create browser context
     * OPTIMIZED: Better validation and error handling
     */
    private BrowserContext loadOrCreateContext() {
        profile = profileManager.getNextProfile();
        Path contextFilePath = Paths.get(contextPath, CONTEXT_FILE);

        // Try to load existing context
        if (Files.exists(contextFilePath)) {
            try {
                log.info("Loading existing browser context from: {}", contextFilePath);

                ViewportSize viewportSize = new ViewportSize(
                        profile.getViewport().getWidth(),
                        profile.getViewport().getHeight()
                );

                BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                        .setUserAgent(profile.getUserAgent())
                        .setViewportSize(viewportSize)
                        .setExtraHTTPHeaders(getAllHeaders(profile))
                        .setStorageStatePath(contextFilePath));

                // Validate the loaded context
                if (context != null) {
                    log.info("Existing context loaded successfully");
                    return context;
                }

            } catch (Exception e) {
                log.warn("Failed to load existing context: {}", e.getMessage());

                // Delete corrupted context file
                try {
                    Files.deleteIfExists(contextFilePath);
                    log.info("Deleted corrupted context file");
                } catch (Exception deleteEx) {
                    log.warn("Could not delete context file: {}", deleteEx.getMessage());
                }
            }
        }

        log.info("Creating new browser context");
        return newContext(browser, profile);
    }

    /**
     * Create new browser context
     */
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

    /**
     * Get all headers from profile
     */
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

    /**
     * Save browser context
     */
    private void saveContext(BrowserContext context) {
        if (context == null) return;

        try {
            Path contextDir = Paths.get(contextPath);
            if (!Files.exists(contextDir)) {
                Files.createDirectories(contextDir);
            }

            Path contextFilePath = contextDir.resolve(CONTEXT_FILE);
            context.storageState(new BrowserContext.StorageStateOptions()
                    .setPath(contextFilePath));
            log.info("Browser context saved to: {}", contextFilePath);
        } catch (Exception e) {
            log.error("Failed to save context: {}", e.getMessage(), e);
        }
    }

    /**
     * Recreate browser context
     * OPTIMIZED: Proper cleanup of all resources before recreating
     */
    private void recreateContext() {
        log.info("Recreating browser context...");
        isLoggedIn.set(false); // Reset login state

        // Stop health monitor first to prevent access to closed page
        if (healthMonitor != null) {
            try {
                healthMonitor.stop();
            } catch (Exception e) {
                log.warn("Error stopping health monitor: {}", e.getMessage());
            }
            healthMonitor = null;
        }

        // Close existing context properly
        if (currentContext != null) {
            try {
                // Close all pages first
                for (Page page : currentContext.pages()) {
                    try {
                        if (!page.isClosed()) {
                            page.close();
                        }
                    } catch (Exception e) {
                        log.debug("Error closing page: {}", e.getMessage());
                    }
                }

                // Then close context
                currentContext.close();
                log.info("Old context closed successfully");

            } catch (Exception e) {
                log.warn("Error closing context: {}", e.getMessage());
            } finally {
                currentContext = null;
            }
        }

        // Wait a bit before creating new context
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Handle CAPTCHA scenario
     */
    private void handleCaptchaScenario() {
        log.warn("{} {} CAPTCHA detected - implementing manual resolution wait",
                EMOJI_WARNING, EMOJI_ERROR);

        isPaused.set(true);

        try {
            Thread.sleep(60000); // Wait 1 minute
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        isPaused.set(false);
        recreateContext();
    }

    /**
     * Wait between retry attempts
     * OPTIMIZED: Exponential backoff with max cap
     */
    private void waitBetweenRetries(int attempt) {
        try {
            long waitTime = (long) Math.min(5000 * Math.pow(2, attempt - 1), 30000);
            log.info("Waiting {}ms before retry...", waitTime);
            Thread.sleep(waitTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * HUMAN-LIKE DELAY (anti-detection)
     */
    private void randomHumanDelay(long minMs, long maxMs) {
        try {
            long delay = minMs + ThreadLocalRandom.current().nextLong(maxMs - minMs + 1);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Validate that page is still usable
     */
    private boolean isPageValid(Page page) {
        try {
            return page != null && !page.isClosed() && page.url() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if context is healthy
     */
    private boolean isContextHealthy() {
        try {
            return currentContext != null &&
                    !currentContext.pages().isEmpty() &&
                    currentContext.pages().stream().anyMatch(p -> !p.isClosed());
        } catch (Exception e) {
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC CONTROL METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    public void pause() {
        isPaused.set(true);
        log.info("{} Window paused", EMOJI_WARNING);
    }

    public void resume() {
        isPaused.set(false);
        log.info("{} {} Window resumed", EMOJI_SUCCESS, EMOJI_SYNC);
    }

    public void stop() {
        isRunning.set(false);
        log.info("{} Window stopped", EMOJI_WARNING);
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public boolean isPaused() {
        return isPaused.get();
    }

    public void shutdown() {
        stop();
        if (healthMonitor != null) {
            healthMonitor.stop();
        }
        if (currentContext != null) {
            try {
                // Close all pages first
                for (Page page : currentContext.pages()) {
                    if (!page.isClosed()) {
                        page.close();
                    }
                }
                currentContext.close();
            } catch (Exception e) {
                log.warn("Error during shutdown: {}", e.getMessage());
            }
        }
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        log.info("{} {} SportyWindow shutdown complete", EMOJI_SUCCESS, EMOJI_INIT);
    }
}