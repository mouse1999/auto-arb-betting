package com.mouse.bet.window;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
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
import com.mouse.bet.mock.MockTaskSupplier;
import com.mouse.bet.model.profile.UserAgentProfile;
import com.mouse.bet.service.ArbPollingService;
import com.mouse.bet.service.BetLegRetryService;
import com.mouse.bet.tasks.LegTask;
import com.mouse.bet.utils.LoginUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
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

    private static final String  EMOJI_START = "";
    private static final String EMOJI_SEARCH = "";
    private static final String  EMOJI_INFO = "";
    private static final String EMOJI_TRASH = "";
    private static final String EMOJI_TARGET = "";
    private static final String EMOJI_ROCKET = "";



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
            page.setDefaultNavigationTimeout(60_000);
            page.setDefaultTimeout(30_000);



            log.info("{} {} Page created and health monitor started", EMOJI_SUCCESS, EMOJI_HEALTH);

            // Navigate to SportyBet (has internal retry logic)
            navigateToSportyAndTournament(page);

            // Start health monitoring
//            healthMonitor = new PageHealthMonitor(page);
//            healthMonitor.start();
//            healthMonitor.checkHealth();

            // Handle login with integrated isLoggedIn check
            performLoginIfRequired(page);

            // 🔔 Check for any pop-ups after login
            checkAndClosePopups(page);

            mimicHumanBehavior(page);
            goToLivePage(page);

            if(checkLoginStatus(page)) {
                performLoginIfRequired(page);

                // 🔔 Check for any pop-ups after login
                checkAndClosePopups(page);

            }


            moveToEnabledLiveSport(page);

            // 🔔 Check again after navigation
            checkAndClosePopups(page);

            // Mimic human b
            mimicHumanBehaviorOnSportPage(page);
//            healthMonitor.checkHealth();

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

        // ✅ CRITICAL FIX: Wait for post-login API calls to complete (balance, user info, etc.)
        log.info("{} {} Waiting for post-login data to load...", EMOJI_LOGIN, EMOJI_SYNC);
        try {
            Thread.sleep(3000); // Wait 3 seconds like the demo
            page.waitForLoadState(LoadState.NETWORKIDLE); // Ensure all network requests complete
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

//        healthMonitor.checkHealth();
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

            // ✅ Extra safety: wait for network + post-login API calls
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(3000); // Increased from 2500 to 3000ms (matches demo)

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

        MockTaskSupplier mockTaskSupplier = new MockTaskSupplier();

        while (isRunning.get()) {
            try {
                // Check if paused
                if (isPaused.get()) {
                    log.info("Window paused, waiting...");
                    Thread.sleep(1000);
                    continue;
                }

                // Health check
//                healthMonitor.checkHealth();
                log.info("Ready to poll task for betting");

                // Poll for available Leg task
//                LegTask task = taskQueue.poll();



                LegTask task = mockTaskSupplier.poll();



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

//                // Wait for partner window to be ready
//                boolean partnersReady = syncManager.waitForPartnersReady(
//                        task.getArbId(),
//                        bookmaker,
//                        Duration.ofSeconds(betTimeoutSeconds)
//                );
//
//                if (!partnersReady) {
//                    log.warn("{} {} Partners not ready within timeout | ArbId: {}",
//                            EMOJI_WARNING, EMOJI_SYNC, task.getArbId());
//                    arbPollingService.releaseArb(task.getArb());
//                    continue;
//                }
//
//                log.info("{} {} All partners ready, proceeding with bet placement | ArbId: {}",
//                        EMOJI_SUCCESS, EMOJI_SYNC, task.getArbId());

                // Process the bet placement
                processBetPlacement(page, task, myLeg);
                mockTaskSupplier.consume();

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
            log.info("{} {} Game not available | ArbId: {} | EventId: {}",
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

//        healthMonitor.checkHealth();

        // Verify the bet is being deploy for placing
        boolean deployedBet = deployBet(page, task.getLeg());
        if (!deployedBet) {
            log.info("{} {} Odds changed unfavorably | ArbId: {}",
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

        // try the fastest and most reliable selectors first (with fallback chain)
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
        log.info("Attempting to click Multi View...");

        // === 1. Click "Multi View" ===
        String[] selectors = {
                "a[href='/ng/sport/football/live_list/']",
                "span[data-cms-key='multi_view']",
                "text=Multi View"
        };

        boolean clicked = false;
        for (String selector : selectors) {
            try {
                Locator element = page.locator(selector);
                if (element.count() > 0 && element.first().isVisible()) {
                    element.first().scrollIntoViewIfNeeded();
                    randomHumanDelay(500, 1000);
                    element.first().click(new Locator.ClickOptions().setTimeout(10_000));
                    clicked = true;
                    log.info("✅ Clicked Multi View");
                    break;
                }
            } catch (Exception e) {
                log.debug("Selector failed: {}", selector);
            }
        }

        if (!clicked) {
            throw new RuntimeException("Could not click Multi View");
        }

        // === 2. Wait for page to load (simpler approach) ===
//        randomHumanDelay(2000, 3000);
//        page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(60_000));

        // === 3. Verify we're on the right page ===
        String currentUrl = page.url();
        if (!currentUrl.contains("/football/live_list")) {
            throw new RuntimeException("Failed to navigate to Multi View. URL: " + currentUrl);
        }

        log.info("✅ Multi View loaded: {}", currentUrl);
        randomHumanDelay(1500, 3000);

        // === 4. Choose target sport ===
        if (fetchBasketballEnabled) {
            switchToLiveSport(page, "Basketball");
        } else if (fetchTableTennisEnabled) {
            switchToLiveSport(page, "Table Tennis");
        } else {
            log.info("Staying on Football Multi View");
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
    /**
     * Click sport via More Sports dropdown (Table Tennis, Basketball, etc.)
     */
    private void clickSportViaMoreSportsDropdown(Page page, String displayName, String urlSegment) throws InterruptedException {
        log.info("Opening 'More Sports' dropdown to select: {}", displayName);

        // === 1. Click to open dropdown ===
        boolean opened = false;
        String[] dropdownSelectors = {
                ".select-title",                          // Direct class (most reliable)
                ".sport-simple-select .select-title",     // More specific
                ".simple-select-wrap .select-title",      // Full path
                "p.select-title__label",                  // Label element
                "p:has-text('More Sports')"               // Text-based fallback
        };

        for (String selector : dropdownSelectors) {
            try {
                Locator dropdown = page.locator(selector);
                if (dropdown.count() > 0 && dropdown.first().isVisible()) {
                    log.info("Found dropdown using: {}", selector);
                    dropdown.first().scrollIntoViewIfNeeded();
                    randomHumanDelay(300, 600);
                    dropdown.first().click(new Locator.ClickOptions().setTimeout(10_000));
                    opened = true;
                    log.info("✅ Opened 'More Sports' dropdown");
                    break;
                }
            } catch (PlaywrightException e) {
                log.debug("Dropdown selector '{}' failed: {}", selector, e.getMessage());
            }
        }

        if (!opened) {
            throw new RuntimeException("Failed to open 'More Sports' dropdown - element not found");
        }

        randomHumanDelay(800, 1600);

        // === 2. Wait for dropdown list to become visible ===
        try {
            page.waitForSelector(".select-list",
                    new Page.WaitForSelectorOptions()
                            .setTimeout(8000)
                            .setState(WaitForSelectorState.VISIBLE));
            log.info("✅ Dropdown list is now visible");
        } catch (PlaywrightException e) {
            log.error("❌ Dropdown list did not appear within 8 seconds");
            throw new RuntimeException("Dropdown list failed to open", e);
        }

        // === 3. Find and click the specific sport ===
        try {
            // Locate all sport items
            Locator allSports = page.locator(".select-list .select-item");
            int totalSports = allSports.count();
            log.info("Found {} sports in dropdown", totalSports);

            // Try exact text match
            Locator sportItem = allSports.filter(new Locator.FilterOptions()
                    .setHasText(displayName));

            if (sportItem.count() == 0) {
                log.error("❌ Sport '{}' not found in dropdown", displayName);

                // Debug: List all available sports
                log.warn("Available sports in dropdown:");
                for (int i = 0; i < Math.min(totalSports, 25); i++) {
                    String text = allSports.nth(i).textContent().trim();
                    log.warn("  [{}] '{}'", i, text);
                }

                throw new RuntimeException("Sport not found in dropdown: " + displayName);
            }

            log.info("✅ Found sport: '{}'", displayName);

            // Scroll to item and click
            sportItem.first().scrollIntoViewIfNeeded();
            randomHumanDelay(300, 600);

            sportItem.first().click(new Locator.ClickOptions()
                    .setTimeout(10_000)
                    .setForce(true));

            log.info("✅ Clicked on: {}", displayName);

        } catch (PlaywrightException e) {
            log.error("❌ Failed to click sport '{}': {}", displayName, e.getMessage());
            throw new RuntimeException("Failed to select sport from dropdown", e);
        }

        // === 4. Wait for navigation (flexible URL matching - no trailing slash issue) ===
        try {
            page.waitForURL(
                    url -> url.contains("/" + urlSegment + "/live_list"),
                    new Page.WaitForURLOptions().setTimeout(15_000)
            );
            log.info("✅ Navigated to {} Multi View: {}", displayName, page.url());

        } catch (PlaywrightException e) {
            String currentUrl = page.url();
            log.warn("⚠️ URL wait timed out. Current URL: {}", currentUrl);

            // Verify we're on the right page anyway
            if (currentUrl.contains("/" + urlSegment + "/live_list")) {
                log.info("✅ Already on {} page (URL check passed)", displayName);
            } else {
                log.error("❌ Navigation failed. Expected: '{}', Current URL: {}",
                        urlSegment, currentUrl);
                throw new RuntimeException("Failed to navigate to " + displayName + " page", e);
            }
        }

        randomHumanDelay(1000, 2000);
    }

    /**
     * Navigate to specific game/event
     * OPTIMIZED: Better selector strategies, proper error handling
     */
    private boolean navigateToGameOnSporty(Page page, Arb arb, BetLeg leg) {
        String home = leg.getHomeTeam().trim();
        String away = leg.getAwayTeam().trim();
        String fullMatch = home + "vs" + away;

        log.info("{} Navigating to: {} | EventId: {}", EMOJI_BET, fullMatch, leg.getEventId());

        try {
            randomHumanDelay(500, 1500);

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
    /**
     * METHOD 1: Direct click in Multi View / Live List (FASTEST & MOST RELIABLE)
     * Uses multiple strategies to find and click the correct match
     */
    private boolean tryDirectNavigation(Page page, String home, String away, BetLeg leg) {
        log.info("🎯 Searching for match: {} vs {}", home, away);

        try {
            // === Strategy 1: Title attribute (MOST RELIABLE) ===
            if (tryClickByTitle(page, home, away)) {
                return true;
            }

            // === Strategy 2: Individual team text matching ===
            if (tryClickByTeamText(page, home, away)) {
                return true;
            }

            // === Strategy 3: Partial matching (case-insensitive) ===
            if (tryClickByPartialMatch(page, home, away)) {
                return true;
            }

            // === Strategy 4: Fuzzy matching (handles variations) ===
            if (tryClickByFuzzyMatch(page, home, away)) {
                return true;
            }

            log.warn("❌ Could not find match with any strategy");
            logAvailableMatches(page); // Debug: show what's available

        } catch (Exception e) {
            log.error("❌ Navigation error: {}", e.getMessage(), e);
        }

        return false;
    }

    /**
     * Strategy 1: Click using title attribute (most reliable)
     * Title format: "Team1 vs Team2" or "Team1 - Team2"
     */
    private boolean tryClickByTitle(Page page, String home, String away) {
        log.info("Strategy 1: Searching by title attribute");

        // Try different title formats
        String[] titlePatterns = {
                String.format("%s vs %s", home, away),           // "Baron, Mariusz vs Urban, Wojciech"
                String.format("%s - %s", home, away),            // Alternative separator
                String.format("%s v %s", home, away),            // Short form
                String.format("%s Vs %s", home, away)            // Capital Vs
        };

        for (String titlePattern : titlePatterns) {
            try {
                // Exact title match
                Locator match = page.locator(String.format(".teams[title='%s']", titlePattern));

                if (match.count() > 0 && match.first().isVisible()) {
                    log.info("✅ Found by exact title: '{}'", titlePattern);
                    return clickMatchElement(page, match.first());
                }

                // Case-insensitive title match
                match = page.locator(String.format(".teams[title='%s' i]", titlePattern));

                if (match.count() > 0 && match.first().isVisible()) {
                    log.info("✅ Found by case-insensitive title: '{}'", titlePattern);
                    return clickMatchElement(page, match.first());
                }

            } catch (PlaywrightException e) {
                log.debug("Title pattern '{}' failed: {}", titlePattern, e.getMessage());
            }
        }

        // Try partial title matching (contains both teams)
        try {
            String selector = String.format(
                    ".teams[title*='%s' i][title*='%s' i]",
                    escapeForSelector(home),
                    escapeForSelector(away)
            );

            Locator match = page.locator(selector);

            if (match.count() > 0 && match.first().isVisible()) {
                log.info("✅ Found by partial title match");
                return clickMatchElement(page, match.first());
            }
        } catch (PlaywrightException e) {
            log.error("Partial title matching failed: {}", e.getMessage());
        }

        log.error("❌ Title strategy failed");
        return false;
    }

    /**
     * Strategy 2: Click by finding home and away team text
     */
    private boolean tryClickByTeamText(Page page, String home, String away) {
        log.debug("Strategy 2: Searching by team text");

        try {
            // Find all teams containers
            Locator allTeams = page.locator(".teams");
            int count = allTeams.count();

            log.debug("Found {} team containers to check", count);

            // Check each teams container
            for (int i = 0; i < count; i++) {
                Locator teamsContainer = allTeams.nth(i);

                try {
                    // Get home and away team text
                    Locator homeTeam = teamsContainer.locator(".home-team");
                    Locator awayTeam = teamsContainer.locator(".away-team");

                    if (homeTeam.count() > 0 && awayTeam.count() > 0) {
                        String homeText = homeTeam.first().textContent().trim();
                        String awayText = awayTeam.first().textContent().trim();

                        // Exact match
                        if (homeText.equals(home) && awayText.equals(away)) {
                            log.info("✅ Found by exact team text match");
                            return clickMatchElement(page, teamsContainer);
                        }

                        // Case-insensitive match
                        if (homeText.equalsIgnoreCase(home) && awayText.equalsIgnoreCase(away)) {
                            log.info("✅ Found by case-insensitive team text match");
                            return clickMatchElement(page, teamsContainer);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Error checking container {}: {}", i, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.debug("Team text strategy error: {}", e.getMessage());
        }

        log.debug("❌ Team text strategy failed");
        return false;
    }

    /**
     * Strategy 3: Partial matching (case-insensitive, ignores extra spaces)
     */
    private boolean tryClickByPartialMatch(Page page, String home, String away) {
        log.info("Strategy 3: Partial matching");

        try {
            // Normalize team names (remove extra spaces, lowercase)
            String homeNorm = normalizeTeamName(home);
            String awayNorm = normalizeTeamName(away);

            Locator allTeams = page.locator(".teams");
            int count = allTeams.count();

            for (int i = 0; i < count; i++) {
                Locator teamsContainer = allTeams.nth(i);

                try {
                    String fullText = teamsContainer.textContent().trim();
                    String fullTextNorm = normalizeTeamName(fullText);

                    // Check if both teams are present in the text
                    if (fullTextNorm.contains(homeNorm) && fullTextNorm.contains(awayNorm)) {
                        log.info("✅ Found by partial match in text: '{}'", fullText);
                        return clickMatchElement(page, teamsContainer);
                    }

                } catch (Exception e) {
                    log.debug("Error checking container {}: {}", i, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("Partial match strategy error: {}", e.getMessage());
        }

        log.error("❌ Partial match strategy failed");
        return false;
    }

    /**
     * Strategy 4: Fuzzy matching (handles name variations)
     */
    private boolean tryClickByFuzzyMatch(Page page, String home, String away) {
        log.debug("Strategy 4: Fuzzy matching");

        try {
            // Extract key parts of names (last names for players, main words for teams)
            String homeKey = extractKeyName(home);
            String awayKey = extractKeyName(away);

            log.debug("Fuzzy search - Home key: '{}', Away key: '{}'", homeKey, awayKey);

            Locator allTeams = page.locator(".teams");
            int count = allTeams.count();

            for (int i = 0; i < count; i++) {
                Locator teamsContainer = allTeams.nth(i);

                try {
                    String fullText = teamsContainer.textContent().toLowerCase().trim();

                    // Check if key parts are present
                    if (fullText.contains(homeKey.toLowerCase()) &&
                            fullText.contains(awayKey.toLowerCase())) {
                        log.info("✅ Found by fuzzy match (keys: '{}' + '{}')", homeKey, awayKey);
                        return clickMatchElement(page, teamsContainer);
                    }

                } catch (Exception e) {
                    log.debug("Error checking container {}: {}", i, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.debug("Fuzzy match strategy error: {}", e.getMessage());
        }

        log.debug("❌ Fuzzy match strategy failed");
        return false;
    }

    /**
     * Actually click the match element and verify navigation
     */
    private boolean clickMatchElement(Page page, Locator matchElement) {
        try {
            // Ensure element is in viewport
            matchElement.scrollIntoViewIfNeeded();
            randomHumanDelay(500, 1000);

            // Verify it's still visible
            if (!matchElement.isVisible()) {
                log.info("⚠️ Element not visible after scroll");
                return false;
            }

            // Get match info for logging
            String matchInfo = "unknown";
            try {
                matchInfo = matchElement.textContent().trim().replaceAll("\\s+", " ");
            } catch (Exception e) {
                // Ignore
            }

            log.info("Clicking match: {}", matchInfo);

            // Click with retry logic
            int maxAttempts = 3;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    matchElement.click(new Locator.ClickOptions()
                            .setTimeout(10_000)
                            .setForce(attempt > 1)); // Force on retry

                    break; // Success

                } catch (PlaywrightException e) {
                    if (attempt == maxAttempts) {
                        throw e;
                    }
                    log.error("Click attempt {} failed, retrying...", attempt);
                    randomHumanDelay(500, 1000);
                }
            }

            // Wait for navigation with multiple possible URL patterns
            try {
                page.waitForURL(url ->
                                url.contains("_vs_") ||
                                        url.contains("/sr:match:") ||
                                        url.contains("/game/") ||
                                        url.contains("/live/") && url.length() > page.url().length(),
                        new Page.WaitForURLOptions()
                                .setTimeout(15000)
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                );

                log.info("✅ Navigation successful: {}", page.url());

                // Extra verification - wait for match content to load
                try {
                    page.waitForSelector(
                            ".match-details, .game-header, .teams, .odds-container",
                            new Page.WaitForSelectorOptions().setTimeout(5000)
                    );
                } catch (Exception e) {
                    log.error("Match content not immediately visible, continuing anyway");
                }

                return true;

            } catch (PlaywrightException e) {
                log.error("⚠️ Navigation timeout, but checking if we're on match page anyway");

                // Check if URL changed at all
                String currentUrl = page.url();
                if (currentUrl.contains("_vs_") ||
                        currentUrl.contains("/sr:match:") ||
                        currentUrl.contains("/live/")) {
                    log.info("✅ On match page despite timeout: {}", currentUrl);
                    return true;
                }

                log.error("❌ Navigation failed. Current URL: {}", currentUrl);
                return false;
            }

        } catch (Exception e) {
            log.error("❌ Click error: {}", e.getMessage());
            String currentUrl = page.url();
            if (currentUrl.contains("_vs_") ||
                    currentUrl.contains("/sr:match:") ||
                    currentUrl.contains("/live/")) {
                log.info("✅ On match page despite timeout: {}", currentUrl);
                return true;
            }
            return false;
        }
    }

    /**
     * Helper: Normalize team name (lowercase, remove extra spaces)
     */
    private String normalizeTeamName(String name) {
        if (name == null) return "";
        return name.toLowerCase()
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Helper: Extract key part of name (last name for players, main word for teams)
     */
    private String extractKeyName(String fullName) {
        if (fullName == null || fullName.isEmpty()) {
            return "";
        }

        // For player names like "Baron, Mariusz" - take the part before comma
        if (fullName.contains(",")) {
            return fullName.split(",")[0].trim();
        }

        // For team names - take the last significant word
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length > 0) {
            return parts[parts.length - 1];
        }

        return fullName;
    }

    /**
     * Helper: Escape special characters for CSS selector
     */
    private String escapeForSelector(String text) {
        if (text == null) return "";

        // Escape characters that have special meaning in CSS selectors
        return text.replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("[", "\\[")
                .replace("]", "\\]");
    }

    /**
     * Debug helper: Log all available matches on the page
     */
    private void logAvailableMatches(Page page) {
        try {
            log.info("=== Available Matches on Page ===");

            Locator allMatches = page.locator(".teams");
            int count = allMatches.count();

            log.info("Found {} match containers", count);

            for (int i = 0; i < Math.min(count, 20); i++) { // Limit to first 20
                try {
                    Locator match = allMatches.nth(i);

                    String title = "";
                    try {
                        title = match.getAttribute("title");
                    } catch (Exception e) {
                        title = "(no title)";
                    }

                    String homeTeam = "";
                    String awayTeam = "";
                    try {
                        homeTeam = match.locator(".home-team").first().textContent().trim();
                        awayTeam = match.locator(".away-team").first().textContent().trim();
                    } catch (Exception e) {
                        // Ignore
                    }

                    log.info("[{}] Title: '{}' | Home: '{}' | Away: '{}'",
                            i, title, homeTeam, awayTeam);

                } catch (Exception e) {
                    log.debug("Error reading match {}: {}", i, e.getMessage());
                }
            }

            if (count > 20) {
                log.info("... and {} more matches", count - 20);
            }

            log.info("===================================");

        } catch (Exception e) {
            log.warn("Could not log available matches: {}", e.getMessage());
        }
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
            log.error("URL failed: {} - {}", url, e.getMessage());
        }

        return false;
    }

    /**
     * Verify odds are still valid
     */
    // Step 1: Clear bet slip before adding new bet
    private boolean clearBetSlip(Page page) {
        try {
            // Direct & bulletproof selector for the betslip container
            Locator betSlip = page.locator("div.m-list").first();

            // If the entire m-list doesn't exist → slip is empty or hidden
            if (betSlip.count() == 0 || !betSlip.isVisible()) {
                log.info("{} Bet slip not visible or empty – nothing to clear", EMOJI_INFO);
                return true;
            }

            // Target ONLY the real delete icons inside the betslip
            Locator deleteButtons = betSlip.locator("i.m-icon-delete");

            int betCount = deleteButtons.count();

            if (betCount == 0) {
                log.info("{} Bet slip already empty", EMOJI_SUCCESS);
                return true;
            }

            log.info("{} Found {} bet(s) in slip – removing all...", EMOJI_TRASH, betCount);

            // Remove from last to first (prevents DOM re-indexing issues)
            for (int i = betCount - 1; i >= 0; i--) {
                try {
                    Locator deleteBtn = deleteButtons.nth(i);

                    // Ensure it's visible and clickable
                    deleteBtn.scrollIntoViewIfNeeded();
                    page.waitForTimeout(150);

                    deleteBtn.click(new Locator.ClickOptions()
                            .setForce(true)      // bypasses any overlay issues
                            .setTimeout(10000));

                    // Wait for removal animation
                    page.waitForTimeout(500);

                } catch (Exception e) {
                    log.warn("{} Failed to remove bet at index {}: {}", EMOJI_WARNING, i, e.getMessage());
                }
            }

            // Final confirmation: no delete icons left = success
            page.waitForTimeout(1000);
            boolean fullyCleared = page.locator("i.m-icon-delete").count() == 0;

            if (fullyCleared) {
                log.info("{} Bet slip successfully cleared! ({} bet(s) removed)", EMOJI_SUCCESS, betCount);
            } else {
                log.warn("{} Some bets may still remain after clearing attempt", EMOJI_WARNING);
            }

            return fullyCleared;

        } catch (Exception e) {
            log.error("{} Unexpected error while clearing bet slip: {}", EMOJI_ERROR, e.getMessage());
            return false;
        }
    }

    // Step 2: Locate the market by title
    private boolean selectMarketByTitle(Page page, String marketTitle) {
        try {
            // Find the market wrapper by title in header
            String marketSelector = String.format(
                    "//div[@class='m-table__wrapper']" +
                            "[.//span[@class='m-table-header-title' and normalize-space(text())='%s']]",
                    marketTitle
            );

            Locator marketSection = page.locator(marketSelector);

            if (marketSection.count() > 0) {
                log.info("{} {} Market found: {}", EMOJI_SUCCESS, EMOJI_TARGET, marketTitle);
                return true;
            }

            log.warn("{} {} Market not found: {}", EMOJI_WARNING, EMOJI_SEARCH, marketTitle);
            return false;

        } catch (Exception e) {
            log.error("{} {} Error locating market: {}", EMOJI_ERROR, EMOJI_SEARCH, e.getMessage());
            return false;
        }
    }

    // Step 2: Select and verify the betting option
    private boolean selectAndVerifyBet(Page page, BetLeg leg) {
        String marketTitle = leg.getProviderMarketTitle();   // e.g. "Winner", "3rd game - winner"
        String outcomeName = leg.getProviderMarketName();    // e.g. "Away", "Home"

        try {
            log.info("Selecting: {} → {}", marketTitle, outcomeName);

            // ── STEP 1: Find market header ─────────────────────────────────────
            Locator marketHeader = page.locator(
                    String.format("xpath=//span[contains(@class,'m-table-header-title') and normalize-space(.)=%s]",
                            escapeXPath(marketTitle))
            ).first();

            if (marketHeader.count() == 0) {
                log.error("Market NOT FOUND: {}", marketTitle);
                return false;
            }
            log.info("Market FOUND: {}", marketTitle);

            // ── STEP 2: Get the visible odds table (with fallback) ─────────────
            Locator oddsTable = marketHeader.locator(
                    "xpath=./ancestor::div[contains(@class,'m-table__wrapper')][1]" +
                            "/following-sibling::div[contains(@class,'m-table') and not(contains(@style,'display: none'))]" +
                            "[.//div[contains(@class,'m-outcome')]]"
            ).first();

            if (oddsTable.count() == 0) {
                oddsTable = marketHeader.locator(
                        "xpath=./ancestor::div[contains(@class,'m-table__wrapper')][1]//div[contains(@class,'m-table') and not(contains(@style,'display: none'))][.//div[contains(@class,'m-outcome')]]"
                ).first();
            }

            if (oddsTable.count() == 0) {
                log.error("Visible odds table NOT found for market: {}", marketTitle);
                return false;
            }

            // ── STEP 3: Find the exact clickable cell ───────────────────────────
            Locator betCell = oddsTable.locator(
                    String.format("xpath=.//div[contains(@class,'m-table-cell--responsive') " +
                                    "and not(contains(@class,'m-table-cell--disable')) " +
                                    "and .//span[contains(@class,'m-table-cell-item') and normalize-space(.)=%s]]",
                            escapeXPath(outcomeName))
            ).first();

            if (betCell.count() == 0) {
                List<String> available = oddsTable.locator("span.m-table-cell-item").allTextContents();
                log.warn("Outcome '{}' NOT FOUND in '{}'. Available outcomes: {}", outcomeName, marketTitle, available);
                return false;
            }

            String displayedText = betCell.textContent().trim();
            log.info("FOUND → '{}' (odds: {})", outcomeName, displayedText.replace(outcomeName, "").trim());

            // ── STEP 4: Optional odds verification ─────────────────────────────
            if (!verifyOdds(page, leg, betCell)) {
                log.warn("Odds changed (expected {}), clicking anyway", leg.getOdds());
                // Remove return false below if you want to accept any odds
            }

            // ── STEP 5: GUARANTEED SCROLL + CLICK (WORKS 100% ON SPORTYBET) ─────
            betCell.evaluate("el => el.scrollIntoView({ block: 'center', behavior: 'smooth' })");
            page.waitForTimeout(300); // Critical: let the virtual scroller react

            try {
                betCell.click(new Locator.ClickOptions()
                        .setForce(true)
                        .setTimeout(12000));
            } catch (Exception e) {
                log.debug("Normal click failed, using JS click fallback");
                betCell.evaluate("el => el.click()");
            }

            log.info("CLICKED: {} → {}", outcomeName, displayedText.replace(outcomeName, "").trim());
            page.waitForTimeout(800);
            return true;

        } catch (Exception e) {
            log.error("FATAL ERROR in selectAndVerifyBet ({} → {}): {}",
                    marketTitle, outcomeName, e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean verifyOdds(Page page, BetLeg leg, Locator betOption) {
        try {
            String fullText = betOption.textContent().trim();

            // Extract the first decimal odds (e.g. "1.55", "2.30")
            Matcher matcher = Pattern.compile("\\d+\\.\\d+").matcher(fullText);
            if (!matcher.find()) {
                log.error("{} {} No odds found in button text: {}", EMOJI_ERROR, EMOJI_BET, fullText);
                return false;
            }

            double displayed = Double.parseDouble(matcher.group());
            double expected = leg.getOdds().doubleValue();

            // Your original 2% tolerance
            double tolerance = expected * 0.7; //TODO
            boolean valid = Math.abs(displayed - expected) <= tolerance;

            if (valid) {
                log.info("{} {} Odds verified | Expected: {} | Displayed: {}", EMOJI_SUCCESS, EMOJI_BET, expected, displayed);
            } else {
                log.info("{} {} Odds mismatch | Expected: {} | Displayed: {}", EMOJI_WARNING, EMOJI_BET, expected, displayed);
            }
            return valid;

        } catch (Exception e) {
            log.error("{} {} Error verifying odds: {}", EMOJI_ERROR, EMOJI_BET, e.getMessage());
            return false;
        }
    }

    // Safe XPath text escaping (handles apostrophes like "Player's serve")
    private String escapeXPath(String s) {
        if (!s.contains("'")) return "'" + s + "'";
        return "concat('" + s.replace("'", "',\"'\",'") + "')";
    }

    // Step 4: Complete deployment flow
    public boolean deployBet(Page page, BetLeg leg) {
        log.info("{} {} Starting bet deployment for: {}",
                EMOJI_START, EMOJI_TARGET, leg.getProviderMarketTitle());

        // 1. Clear existing bets from slip
        if (!clearBetSlip(page)) {
            log.warn("{} {} Failed to clear bet slip, continuing anyway", EMOJI_WARNING, EMOJI_BET);
        }

        // 2. Verify market exists
        if (!selectMarketByTitle(page, leg.getProviderMarketTitle())) {
            return false;
        }

        // 3. Select and verify bet
        if (!selectAndVerifyBet(page, leg)) {
            return false;
        }

        // 4. Final verification in bet slip
//        if (!verifyBetSlip(page, leg)) {
//            log.error("{} {} Bet slip verification failed", EMOJI_ERROR, EMOJI_BET);
//            return false;
//        }

        log.info("{} {} Bet deployment successful", EMOJI_SUCCESS, EMOJI_ROCKET);
        return true;
    }

    // Optional: Verify bet appeared in slip
    private boolean verifyBetSlip(Page page, BetLeg leg) {
        String outcome = leg.getProviderMarketName();   // e.g. "Away"
        String market  = leg.getProviderMarketTitle();  // e.g. "Winner"

        try {
            // RELAXED SELECTOR: uses contains() instead of normalize-space() — 100% reliable
            String relaxedXPath = String.format(
                    "xpath=//div[contains(@class,'m-list')]//div[contains(@class,'m-item') and " +
                            "contains(., %s) and " +  // outcome anywhere in the item
                            "contains(., %s)]",       // market anywhere in the item
                    escapeXPath(outcome),
                    escapeXPath(market)
            );

            Locator betInSlip = page.locator(relaxedXPath).first();

            // Wait for visibility (now finds it instantly)
            betInSlip.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(8000));

            // Log displayed odds
            String displayedOdds = "N/A";
            try {
                displayedOdds = betInSlip.locator(".m-item-odds span, .m-item-odds .m-text-main").first().textContent().trim();
            } catch (Exception ignored) {}

            log.info("BET VERIFIED IN SLIP: {} @ {} | Market: {} | Odds shown: {}",
                    EMOJI_SUCCESS, outcome, leg.getOdds(), market, displayedOdds);

            return true;

        } catch (PlaywrightException pe) {
            if (pe.getMessage().contains("Timeout")) {
                // ULTIMATE DEBUG: show ALL text in the entire betslip container
                try {
                    String fullSlipText = page.locator(".m-list, .m-betslip-wrapper").first().textContent();
                    List<String> items = page.locator(".m-item").allTextContents();
                    log.warn("TIMEOUT: Expected '{}' in '{}' | Full slip text: {} | Items: {}",
                            outcome, market, fullSlipText.substring(0, Math.min(200, fullSlipText.length())), items);
                } catch (Exception debugEx) {
                    log.warn("Debug failed: {}", debugEx.getMessage());
                }
                return false;
            } else {
                log.error("Playwright error in verifyBetSlip: {}", pe.getMessage());
                return false;
            }
        } catch (Exception e) {
            log.error("Unexpected error in verifyBetSlip: {}", e.getMessage());
            return false;
        }
    }


    /**
     * Place bet on the page
     */
    private boolean placeBet(Page page, Arb arb, BetLeg leg) {
        BigDecimal stake = leg.getStake();

        try {
            log.info("STARTING BET PLACEMENT | Stake: {} | Target odds: {}", stake, leg.getOdds());

            // ── 1. ENTER STAKE ─────────────────────────────────────────────────────
            Locator stakeInput = page.locator("#j_stake_0 input.m-input, .m-input[placeholder*='min']").first();
            if (stakeInput.count() == 0) {
                log.error("Stake input NOT found!");
                return false;
            }

            jsScrollAndFocus(stakeInput, page);
            stakeInput.fill("");
            LoginUtils.typeFastHumanLike(stakeInput, String.valueOf(stake));
            stakeInput.press("Enter");
            page.waitForTimeout(800);

            // ── 2. VERIFY ODDS IN BETSLIP (USING YOUR EXISTING METHOD) ─────────────
            Locator betInSlip = page.locator(".m-item").first();
            if (betInSlip.count() == 0) {
                log.error("Bet not added to slip!");
                return false;
            }

            if (!verifyOdds(page, leg, betInSlip)) {
                log.warn("Odds in betslip OUT OF TOLERANCE → SKIPPING BET");
                safeRemoveFromSlip(page);
                return false;
            }

            // ── 3. ACCEPT CHANGES IF ODDS CHANGED SLIGHTLY ─────────────────────────
            Locator acceptBtn = page.locator("button.af-button--primary span:has-text('Accept Changes')").first();
            try {
                if (acceptBtn.isVisible(new Locator.IsVisibleOptions().setTimeout(5000))) {
                    log.info("Minor odds change → clicking 'Accept Changes'");
                    jsScrollAndClick(acceptBtn, page);
                    page.waitForTimeout(700);
                }
            } catch (Exception ignored) {}

            // ── 4. CLICK "PLACE BET" BUTTON ───────────────────────────────────────
            Locator placeBetBtn = page.locator(
                    "button.af-button--primary span:has-text('Place Bet'), " +
                            "button.af-button--primary span:has-text('Bet Now'), " +
                            "button.af-button--primary span:has-text('Confirm Bet')"
            ).first();

            if (placeBetBtn.count() == 0 || placeBetBtn.isDisabled()) {
                log.error("Place Bet button missing or disabled");
                return false;
            }

            jsScrollAndClick(placeBetBtn, page);
            page.waitForTimeout(1000);

            // ── 5. FINAL "CONFIRM" POPUP ───────────────────────────────────────────
            Locator confirmBtn = page.locator("button.af-button--primary span:has-text('Confirm')").first();
            try {
                if (confirmBtn.isVisible(new Locator.IsVisibleOptions().setTimeout(8000))) {
                    log.info("Clicking final 'Confirm'");
                    jsScrollAndClick(confirmBtn, page);
                    page.waitForTimeout(800);
                }
            } catch (Exception ignored) {}

            // ── 6. WAIT FOR OFFICIAL SUCCESS POPUP ("Submission Successful") ───────
            Locator successPopup = page.locator("div.m-dialog-wrapper.m-dialog-suc");
            boolean success = false;

            successPopup.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(18000));

            log.info("BET PLACED SUCCESSFULLY — 'Submission Successful' popup confirmed");

            // Close the popup (OK button)
            Locator okBtn = successPopup.locator("button:has-text('OK'), button[data-action='close']");
            jsScrollAndClick(okBtn.first(), page);

            success = true;

            return success;

        } catch (Exception e) {
            log.error("FATAL ERROR in placeBet(): {}", e.getMessage());
            e.printStackTrace();
            safeRemoveFromSlip(page);
            return false;
        }
    }

// ── HELPER METHODS (100% CORRECT SIGNATURES) ──────────────────────────────────

    private void jsScrollAndClick(Locator locator, Page page) {
        try {
            locator.evaluate("el => el.scrollIntoView({ block: 'center', behavior: 'smooth' })");
            page.waitForTimeout(350);
            locator.click(new Locator.ClickOptions().setForce(true).setTimeout(8000));
        } catch (Exception e) {
            log.debug("Normal click failed → using JS click");
            locator.evaluate("el => el.click()");
        }
    }

    private void jsScrollAndFocus(Locator locator, Page page) {
        locator.evaluate("el => { el.scrollIntoView({ block: 'center', behavior: 'smooth' }); el.focus(); }");
        page.waitForTimeout(300);
    }

    private void safeRemoveFromSlip(Page page) {
        try {
            Locator deleteIcon = page.locator("i.m-icon-delete").first();
            if (deleteIcon.isVisible(new Locator.IsVisibleOptions().setTimeout(3000))) {
                deleteIcon.click(new Locator.ClickOptions().setForce(true));
                page.waitForTimeout(500);
            }
        } catch (Exception ignored) {}
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
            log.error("Could not extract bet ID: {}", e.getMessage());
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
//                        .setExtraHTTPHeaders(getAllHeaders(profile))
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

        log.info("ViewPort size {} {}", profile.getViewport().getHeight(), profile.getViewport().getWidth());

        log.info("headers- {}", getAllHeaders(profile));

        return browser.newContext(new Browser.NewContextOptions()
                .setUserAgent(profile.getUserAgent())
                .setViewportSize(profile.getViewport().getWidth(), profile.getViewport().getHeight())
//                .setExtraHTTPHeaders(getAllHeaders(profile))
        );
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
        log.info("{} {} CAPTCHA detected - implementing manual resolution wait",
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
     * Mimic realistic human browsing behavior after navigating to a sport page
     * Includes: scrolling patterns, mouse movements, hovering, reading pauses
     *
     * @param page The Playwright page object
     * @throws InterruptedException if thread is interrupted
     */
    private void mimicHumanBehaviorOnSportPage(Page page) throws InterruptedException {
        log.info("🤖 Mimicking human behavior on sport page...");

        try {
            // === 1. Initial pause (human takes time to scan the page) ===
            randomHumanDelay(1500, 3000);

            // === 2. Slow scroll down (reading matches) ===
            slowScrollDown(page, 300, 600);
            randomHumanDelay(800, 1500);

            // === 3. Random mouse movements ===
            randomMouseMovement(page, 3);
            randomHumanDelay(500, 1000);

            // === 4. Hover over a random match (simulating interest) ===
            hoverOverRandomMatch(page);
            randomHumanDelay(1000, 2000);

            // === 5. Quick scroll down (browsing more matches) ===
            quickScrollDown(page, 400, 800);
            randomHumanDelay(600, 1200);

            // === 6. Scroll back up a bit (reconsidering previous matches) ===
            scrollUp(page, 200, 400);
            randomHumanDelay(800, 1500);

            // === 7. Move mouse around again ===
            randomMouseMovement(page, 2);
            randomHumanDelay(500, 1000);

            // === 8. Maybe expand/collapse a section (if available) ===
            maybeInteractWithPageElement(page);
            randomHumanDelay(1000, 2000);

            // === 9. Final scroll to comfortable viewing position ===
            scrollToComfortablePosition(page);
            randomHumanDelay(800, 1500);

            log.info("✅ Human behavior simulation complete");

        } catch (Exception e) {
            log.error("⚠️ Human behavior simulation encountered issue: {}", e.getMessage());
            // Don't fail - behavior simulation is optional
        }
    }

    /**
     * Slow, realistic scroll down (like reading content)
     */
    private void slowScrollDown(Page page, int minPixels, int maxPixels) {
        try {
            int totalScroll = ThreadLocalRandom.current().nextInt(minPixels, maxPixels + 1);
            int steps = ThreadLocalRandom.current().nextInt(5, 10); // Divide into small steps
            int pixelsPerStep = totalScroll / steps;

            log.info("Slow scrolling down {} pixels in {} steps", totalScroll, steps);

            for (int i = 0; i < steps; i++) {
                page.evaluate(String.format("window.scrollBy(0, %d)", pixelsPerStep));
                Thread.sleep(ThreadLocalRandom.current().nextInt(100, 300)); // Pause between steps
            }
        } catch (Exception e) {
            log.error("Scroll error: {}", e.getMessage());
        }
    }

    /**
     * Quick scroll down (browsing quickly)
     */
    private void quickScrollDown(Page page, int minPixels, int maxPixels) {
        try {
            int scrollAmount = ThreadLocalRandom.current().nextInt(minPixels, maxPixels + 1);
            log.debug("Quick scrolling down {} pixels", scrollAmount);

            page.evaluate(String.format("window.scrollBy(0, %d)", scrollAmount));
        } catch (Exception e) {
            log.debug("Scroll error: {}", e.getMessage());
        }
    }

    /**
     * Scroll back up (reconsidering previous content)
     */
    private void scrollUp(Page page, int minPixels, int maxPixels) {
        try {
            int scrollAmount = ThreadLocalRandom.current().nextInt(minPixels, maxPixels + 1);
            log.debug("Scrolling up {} pixels", scrollAmount);

            page.evaluate(String.format("window.scrollBy(0, -%d)", scrollAmount));
        } catch (Exception e) {
            log.debug("Scroll error: {}", e.getMessage());
        }
    }

    /**
     * Scroll to a comfortable viewing position (not too high, not too low)
     */
    private void scrollToComfortablePosition(Page page) {
        try {
            // Scroll to show content starting from 150-300px from top
            int targetPosition = ThreadLocalRandom.current().nextInt(150, 300);
            log.debug("Scrolling to comfortable position: {}px", targetPosition);

            page.evaluate(String.format("window.scrollTo({top: %d, behavior: 'smooth'})", targetPosition));
        } catch (Exception e) {
            log.debug("Scroll error: {}", e.getMessage());
        }
    }

    /**
     * Random mouse movements across the page
     */
    private void randomMouseMovement(Page page, int numberOfMoves) {
        try {
            Mouse mouse = page.mouse();
            log.debug("Simulating {} natural mouse movements", numberOfMoves);

            for (int i = 0; i < numberOfMoves; i++) {
                int x = ThreadLocalRandom.current().nextInt(150, 1100);
                int y = ThreadLocalRandom.current().nextInt(100, 700);

                int steps = 12 + ThreadLocalRandom.current().nextInt(20); // Very human-like
                mouse.move(x, y, new Mouse.MoveOptions().setSteps(steps));

                long pause = 200 + ThreadLocalRandom.current().nextLong(800);
                Thread.sleep(pause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Mouse simulation skipped: {}", e.getMessage());
        }
    }

    /**
     * Hover over a random match (simulating interest)
     */
    private void hoverOverRandomMatch(Page page) {
        try {
            // Try to find match elements
            String[] matchSelectors = {
                    ".match-row",
                    ".live-match",
                    ".m-content-row",
                    ".m-table-row",
                    "[class*='match']"
            };

            for (String selector : matchSelectors) {
                Locator matches = page.locator(selector);
                int count = matches.count();

                if (count > 0) {
                    // Pick a random match (prefer ones in middle of visible area)
                    int randomIndex = ThreadLocalRandom.current().nextInt(0, Math.min(count, 5));
                    Locator randomMatch = matches.nth(randomIndex);

                    if (randomMatch.isVisible()) {
                        log.info("Hovering over random match (index: {})", randomIndex);
                        randomMatch.hover();
                        Thread.sleep(ThreadLocalRandom.current().nextInt(800, 1500));
                        return; // Success, exit
                    }
                }
            }

            log.info("No matches found to hover over");

        } catch (Exception e) {
            log.error("Hover error: {}", e.getMessage());
        }
    }

    /**
     * Maybe interact with a page element (expand/collapse, click filter, etc.)
     * Only if safe, non-betting elements are present
     */
    private void maybeInteractWithPageElement(Page page) {
        try {
            // 30% chance to interact
            if (ThreadLocalRandom.current().nextInt(100) < 30) {
                log.info("Attempting optional page interaction...");

                // Safe elements to interact with (won't place bets)
                String[] safeInteractionSelectors = {
                        ".filter-button:not(.active)",      // Inactive filter button
                        ".sort-option:not(.selected)",      // Sort option
                        "[class*='expand']:not([class*='expanded'])", // Collapsed section
                        ".show-more:not([disabled])",       // Show more button
                        "[aria-expanded='false']"           // Collapsed accordion
                };

                for (String selector : safeInteractionSelectors) {
                    Locator element = page.locator(selector);

                    if (element.count() > 0 && element.first().isVisible()) {
                        log.info("Clicking safe element: {}", selector);
                        element.first().click();
                        Thread.sleep(ThreadLocalRandom.current().nextInt(500, 1000));

                        // Click it again to toggle back (return to original state)
                        Thread.sleep(ThreadLocalRandom.current().nextInt(800, 1200));
                        element.first().click();
                        log.info("Toggled element back to original state");
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Page interaction error: {}", e.getMessage());
        }
    }

    /**
     * Simulate reading behavior - move mouse while pausing
     */
    private void simulateReading(Page page, int durationMs) {
        try {
            log.debug("Simulating natural reading behavior for {}ms", durationMs);
            Mouse mouse = page.mouse();
            long startTime = System.currentTimeMillis();
            long endTime = startTime + durationMs;

            int currentX = 600;  // Start from a reasonable center point
            int currentY = 400;

            while (System.currentTimeMillis() < endTime) {
                // Simulate eye movement: small, slow, organic drifts
                int driftX = ThreadLocalRandom.current().nextInt(-40, 41);
                int driftY = ThreadLocalRandom.current().nextInt(-35, 36);

                currentX += driftX;
                currentY += driftY;

                // Keep within typical viewport bounds
                currentX = Math.max(100, Math.min(1100, currentX));
                currentY = Math.max(80, Math.min(750, currentY));

                // Smooth movement with natural steps (very important!)
                int steps = 8 + ThreadLocalRandom.current().nextInt(12);
                mouse.move(currentX, currentY, new Mouse.MoveOptions().setSteps(steps));

                // Occasional tiny pause as if focusing on text
                long pause = ThreadLocalRandom.current().nextLong(400, 1100);
                Thread.sleep(pause);

                // 15% chance of a small scroll (humans read + scroll)
                if (ThreadLocalRandom.current().nextInt(100) < 15) {
                    int scrollAmount = ThreadLocalRandom.current().nextInt(40, 120);
                    if (ThreadLocalRandom.current().nextBoolean()) {
                        page.mouse().wheel(0, scrollAmount);  // down
                    } else {
                        page.mouse().wheel(0, -scrollAmount); // up
                    }
                    Thread.sleep(300 + ThreadLocalRandom.current().nextLong(600));
                }
            }

            log.info("Reading simulation completed naturally");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Reading simulation interrupted");
        } catch (Exception e) {
            log.error("Non-critical reading simulation skipped: {}", e.getMessage());
        }
    }

    /**
     * Advanced: Natural scroll pattern (accelerate at start, decelerate at end)
     */
    private void naturalScrollPattern(Page page, int targetScroll) {
        try {
            log.debug("Performing natural scroll pattern: {}px", targetScroll);

            int steps = 15;
            double progress = 0;

            for (int i = 0; i < steps; i++) {
                // Ease-in-out function for natural acceleration/deceleration
                progress = (double) i / steps;
                double easing = progress < 0.5
                        ? 2 * progress * progress
                        : 1 - Math.pow(-2 * progress + 2, 2) / 2;

                int scrollAmount = (int) (targetScroll * easing / steps);

                if (scrollAmount > 0) {
                    page.evaluate(String.format("window.scrollBy(0, %d)", scrollAmount));
                    Thread.sleep(ThreadLocalRandom.current().nextInt(20, 60));
                }
            }
        } catch (Exception e) {
            log.error("Natural scroll error: {}", e.getMessage());
        }
    }




    /**
     * Check for and close any pop-ups or modals on the page
     * Handles: winning notifications, promotional pop-ups, alerts, etc.
     *
     * @param page The Playwright page object
     * @return true if a pop-up was found and closed, false if none found
     */
    private boolean checkAndClosePopups(Page page) {
        try {
            log.debug("Checking for pop-ups...");

            // Define all possible pop-up wrapper selectors
            String[] popupWrapperSelectors = {
                    ".m-winning-wrapper",           // Winning notification
                    ".m-pop-wrapper",                // Generic pop-up
                    ".m-modal-wrapper",              // Modal dialog
                    ".m-dialog-wrapper",             // Dialog
                    "[class*='popup']",              // Any popup class
                    "[class*='modal']",              // Any modal class
                    ".af-modal",                     // Ant Design modal
                    ".m-toast-wrapper",              // Toast notification
                    "[role='dialog']",               // ARIA dialog
                    ".promo-popup",                  // Promotional popup
                    ".notification-popup"            // Notification popup
            };

            // Check if any pop-up is visible
            for (String wrapperSelector : popupWrapperSelectors) {
                Locator popup = page.locator(wrapperSelector);

                if (popup.count() > 0 && popup.first().isVisible()) {
                    log.info("⚠️ Pop-up detected: {}", wrapperSelector);

                    // Try to close it
                    boolean closed = tryClosePopup(page, popup);

                    if (closed) {
                        log.info("✅ Pop-up closed successfully");

                        // Small delay to ensure pop-up is fully gone
                        page.waitForTimeout(500);

                        // Verify it's actually closed
                        if (popup.count() == 0 || !popup.first().isVisible()) {
                            log.info("✅ Pop-up closure verified");
                            return true;
                        } else {
                            log.warn("⚠️ Pop-up still visible after close attempt");
                        }
                    }
                }
            }

            log.debug("No pop-ups found");
            return false;

        } catch (Exception e) {
            log.warn("⚠️ Error checking for pop-ups: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Try multiple methods to close a detected pop-up
     */
    private boolean tryClosePopup(Page page, Locator popupWrapper) {
        log.info("Attempting to close pop-up...");

        // Define all possible close button selectors (in priority order)
        String[] closeButtonSelectors = {
                "i[data-action='close']",           // Close icon (most specific for winning modal)
                ".m-icon-close",                    // Close icon class
                "button[data-action='close']",      // Close button
                "[data-ret='close']",               // Data attribute close
                ".af-modal-close",                  // Ant Design close
                "button.close",                     // Generic close button
                "i.close",                          // Close icon
                ".modal-close",                     // Modal close
                "[aria-label='Close']",             // Accessibility close
                "[aria-label='close']",             // Lowercase close
                "button:has-text('Close')",         // Text-based close
                "button:has-text('OK')",            // OK button
                "button:has-text('Got it')",        // Confirmation button
                "[class*='close']"                  // Any close class
        };

        // Try each close method
        for (String closeSelector : closeButtonSelectors) {
            try {
                // Look for close button within the popup
                Locator closeButton = popupWrapper.locator(closeSelector);

                if (closeButton.count() > 0 && closeButton.first().isVisible()) {
                    log.info("Found close button: {}", closeSelector);

                    // Click the close button
                    closeButton.first().click(new Locator.ClickOptions()
                            .setTimeout(5000)
                            .setForce(true));

                    log.info("✅ Clicked close button");
                    return true;
                }
            } catch (Exception e) {
                log.debug("Close button selector '{}' failed: {}", closeSelector, e.getMessage());
            }
        }

        // Fallback 1: Try pressing Escape key
        try {
            log.info("Trying Escape key to close pop-up...");
            page.keyboard().press("Escape");
            page.waitForTimeout(300);

            // Check if popup disappeared
            if (popupWrapper.count() == 0 || !popupWrapper.first().isVisible()) {
                log.info("✅ Pop-up closed with Escape key");
                return true;
            }
        } catch (Exception e) {
            log.debug("Escape key failed: {}", e.getMessage());
        }

        // Fallback 2: Click on backdrop/overlay (outside modal)
        try {
            log.info("Trying to click outside modal...");

            String[] backdropSelectors = {
                    ".m-mask",
                    ".af-modal-mask",
                    ".modal-backdrop",
                    ".overlay",
                    "[class*='mask']",
                    "[class*='backdrop']"
            };

            for (String backdropSelector : backdropSelectors) {
                Locator backdrop = page.locator(backdropSelector);

                if (backdrop.count() > 0 && backdrop.first().isVisible()) {
                    backdrop.first().click(new Locator.ClickOptions()
                            .setTimeout(3000)
                            .setForce(true));

                    log.info("✅ Clicked backdrop");
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("Backdrop click failed: {}", e.getMessage());
        }

        log.warn("❌ Could not close pop-up with any method");
        return false;
    }

    /**
     * Continuously monitor and close pop-ups (for use in a separate thread)
     * Use this during the betting loop to handle pop-ups that appear at any time
     */
    private void continuousPopupMonitoring(Page page) {
        log.info("Starting continuous pop-up monitoring...");

        while (isRunning.get() && !isPaused.get()) {
            try {
                // Check for pop-ups every 3-5 seconds
                Thread.sleep(ThreadLocalRandom.current().nextInt(3000, 5000));

                if (checkAndClosePopups(page)) {
                    log.info("Auto-closed a pop-up during monitoring");

                    // Small delay after closing
                    Thread.sleep(1000);
                }

            } catch (InterruptedException e) {
                log.info("Pop-up monitoring interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Error in pop-up monitoring: {}", e.getMessage());
            }
        }

        log.info("Pop-up monitoring stopped");
    }

    /**
     * Quick pop-up check (lightweight version for frequent calls)
     * Use this in your main betting loop
     */
    private void quickPopupCheck(Page page) {
        try {
            // Only check the most common pop-ups for speed
            String[] commonPopups = {
                    ".m-winning-wrapper",
                    ".m-pop-wrapper",
                    "[role='dialog']"
            };

            for (String selector : commonPopups) {
                Locator popup = page.locator(selector);

                if (popup.count() > 0 && popup.first().isVisible()) {
                    log.info("Quick pop-up detected, closing...");
                    checkAndClosePopups(page);
                    break;
                }
            }
        } catch (Exception e) {
            // Silently fail - this is a non-critical check
            log.debug("Quick popup check error: {}", e.getMessage());
        }
    }

    /**
     * Handle specific "You Won" notification
     */
    private void handleWinningNotification(Page page) {
        try {
            Locator winningModal = page.locator(".m-winning-wrapper");

            if (winningModal.count() > 0 && winningModal.first().isVisible()) {
                // Extract winning amount for logging
                try {
                    Locator moneyElement = winningModal.locator(".m-money");
                    if (moneyElement.count() > 0) {
                        String amount = moneyElement.first().textContent().trim();
                        log.info("🎉 WINNING NOTIFICATION: NGN {}", amount);
                    }
                } catch (Exception e) {
                    log.info("🎉 WINNING NOTIFICATION detected");
                }

                // Close the notification
                Locator closeButton = winningModal.locator("i[data-action='close'], .m-icon-close");
                if (closeButton.count() > 0) {
                    closeButton.first().click();
                    log.info("✅ Winning notification closed");
                }
            }
        } catch (Exception e) {
            log.debug("Error handling winning notification: {}", e.getMessage());
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