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
import com.mouse.bet.logservice.BettingFlowLogger;
import com.mouse.bet.manager.ArbOrchestrator;
import com.mouse.bet.manager.PageHealthMonitor;
import com.mouse.bet.manager.ProfileManager;
import com.mouse.bet.manager.WindowSyncManager;
import com.mouse.bet.model.profile.UserAgentProfile;
import com.mouse.bet.service.ArbPollingService;
import com.mouse.bet.service.BetLegRetryService;
import com.mouse.bet.service.BettingMetricsService;
import com.mouse.bet.tasks.LegTask;
import com.mouse.bet.utils.SportyLoginUtils;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static com.mouse.bet.utils.WindowUtils.attachAntiDetection;

/**
 * SportyBet window handler for live arbitrage betting
 * Manages browser automation, login, and bet placement coordination
 *
 * OPTIMIZED VERSION - Fixed navigation issues, proper context management, and page lifecycle
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class SportyWindow implements BettingWindow, Runnable {

    private static final String EMOJI_INIT = "🚀";
    private static final String EMOJI_LOGIN = "🔐";
    private static final String EMOJI_BET = "🎯";
    private static final String EMOJI_SUCCESS = "✅";
    private static final String EMOJI_ERROR = "❌";
    private static final String EMOJI_WARNING = "⚠️";
    private static final String EMOJI_SYNC = "🔄";
    private static final String EMOJI_POLL = "📊";
    private static final String EMOJI_HEALTH = "💚";
    private static final String EMOJI_SHUTDOWN = "🛑";

    private static final String  EMOJI_START = "";
    private static final String EMOJI_SEARCH = "";
    private static final String  EMOJI_INFO = "";
    private static final String EMOJI_TRASH = "";
    private static final String EMOJI_TARGET = "";
    private static final String EMOJI_ROCKET = "";
    private static final String  EMOJI_NAVIGATION = "";
    private static final String EMOJI_CLOCK = "";


    private static final double ODDS_TOLERANCE_PERCENT = 50.0;
    private static final int RETRY_MAX_ATTEMPTS = 3;
    private static final long RETRY_TIMEOUT_MS = 10_000;
    private static final long RETRY_DELAY_MS = 1000;



    private static final BookMaker BOOK_MAKER = BookMaker.SPORTY_BET;
    final int MAX_DURATION_MS = 15_000; // duration for placing bet after initial failure
    private static final String CONTEXT_FILE = "sporty-context.json";
    private static final String SPORTY_BET_URL = "https://www.sportybet.com/ng";

    private final ProfileManager profileManager;
    private final ScraperConfig scraperConfig;
    private final ArbPollingService arbPollingService;
    private final ArbOrchestrator arbOrchestrator;
//    private final BetLegRetryService betRetryService;
    private final WindowSyncManager syncManager;
    private final SportyLoginUtils sportyLoginUtils;
    private final BettingMetricsService bettingMetricsService;
    private final BettingFlowLogger flowLogger;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext currentContext;
    private UserAgentProfile profile;
    private PageHealthMonitor healthMonitor;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isWindowUpAndRunning = new AtomicBoolean(false);

    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicBoolean isLoggedIn = new AtomicBoolean(false);
    @Getter
    private final BlockingQueue<LegTask> taskQueue = new LinkedBlockingQueue<>();

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

    @Value("${fetch.enabled.football:false}")
    private boolean fetchFootballEnabled;

    @Value("${fetch.enabled.basketball:false}")
    private boolean fetchBasketballEnabled;

    @Value("${fetch.enabled.table-tennis:true}")
    private boolean fetchTableTennisEnabled;


    /**
     * Initialize Playwright and browser
     */
    @PostConstruct
    public void init() {
        log.info("{} {} Initializing SportyWindow with Playwright...", EMOJI_INIT, EMOJI_BET);
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
//                    .setArgs(scraperConfig.getBROWSER_FlAGS())
                    .setSlowMo(0));

            log.info("{} {} Playwright initialized successfully", EMOJI_SUCCESS, EMOJI_INIT);
            log.info("Registering SportyBet Window for Bet placing");
            arbOrchestrator.registerWorker(BOOK_MAKER, taskQueue);

            randomHumanDelay(1000, 3000);
            log.info("safe starting arb orchestrator in {} window", BOOK_MAKER);
            arbOrchestrator.start();
        } catch (Exception e) {
            log.error("{} {} Failed to initialize Playwright: {}", EMOJI_ERROR, EMOJI_INIT, e.getMessage(), e);
            throw new RuntimeException("Playwright initialization failed", e);
        }
    }


    // ===================================================================
// USE THIS METHOD IN BOTH SportyWindow AND MSportWindow
// Replace your existing processBetPlacement method with this one
// ===================================================================

    private void processBetPlacement(Page page, LegTask task, BetLeg myLeg) {
        String arbId = task.getArbId();
        BigDecimal myOdds = myLeg.getOdds();

        flowLogger.logBetPlacementStart(arbId, BOOK_MAKER, myOdds);

        try {
            // ========================================
            // STEP 1: REGISTER INTENT (with odds)
            // ========================================
            boolean intentRegistered = syncManager.registerIntent(arbId, BOOK_MAKER, myOdds.doubleValue());
            if (!intentRegistered) {
                flowLogger.logArbCancelledDuringIntent(arbId, BOOK_MAKER);
                return;
            }

            // ========================================
            // STEP 2: NAVIGATE TO BET PAGE (slow operation)
            // ========================================
            flowLogger.logNavigationStart(arbId, BOOK_MAKER);

            // Use the appropriate navigation method based on BOOK_MAKER
            boolean gameAvailable;
            if (BOOK_MAKER == BookMaker.SPORTY_BET) {
                gameAvailable = navigateToGameOnSporty(page, task.getArb(), myLeg);
            } else {
                gameAvailable = navigateToGameOnSporty(page, task.getArb(), myLeg);
            }

            if (!gameAvailable) {
                flowLogger.logGameNotAvailable(arbId, BOOK_MAKER);
                syncManager.notifyBetFailure(arbId, BOOK_MAKER, "Game not available");
                syncManager.skipArbAndSync(arbId);
                bettingMetricsService.recordArbFailure();
                return;
            }

            // ========================================
            // STEP 3: MARK READY
            // ========================================
            boolean markedReady = syncManager.markReady(arbId, BOOK_MAKER);

            if (!markedReady) {
                flowLogger.logPartnerTimeout(arbId, BOOK_MAKER);
                arbPollingService.killArb(task.getArb());
                return;
            }

            flowLogger.logMarkedReady(arbId, BOOK_MAKER);

            // ========================================
            // STEP 4: WAIT FOR PARTNER TO BE READY
            // ========================================
            boolean partnersReady = syncManager.waitForPartnersReadyOrTimeout(
                    arbId,
                    BOOK_MAKER,
                    Duration.ofSeconds(betTimeoutSeconds)
            );

            if (!partnersReady) {
                flowLogger.logPartnersNotReady(arbId, BOOK_MAKER);
                arbPollingService.killArb(task.getArb());

                // Clear partner's task queue
                arbOrchestrator.getWorkerQueues().forEach((bookmaker, queue) -> {
                    if (bookmaker.equals(BOOK_MAKER)) {
                        log.debug("⏭️ Skipping {} queue (current window: {})", bookmaker, BOOK_MAKER);
                        return;
                    }

                    int clearedCount = queue.size();
                    LegTask legTask = queue.peek();

                    if (legTask != null) {
                        Phaser phaser = legTask.getBarrier();
                        phaser.arriveAndAwaitAdvance();
                    }

                    log.info("✅ Cleared worker queue | Bookmaker: {} | TasksRemoved: {} from {} window",
                            bookmaker, clearedCount, BOOK_MAKER);
                });
                return;
            }

            flowLogger.logBothPartnersReady(arbId, BOOK_MAKER);

            // ========================================
            // STEP 5: SIMULTANEOUS BETTING (NO WAITING)
            // ========================================
            log.info("🚀 SIMULTANEOUS BETTING | ArbId: {} | Bookmaker: {}", arbId, BOOK_MAKER);

            // Verify bet deployment
            boolean deployedBet = deployBet(page, task.getLeg());
            flowLogger.logBetDeploymentCheck(arbId, BOOK_MAKER, deployedBet);

            if (!deployedBet) {
                log.warn("❌ Odds not available | ArbId: {} | Bookmaker: {}", arbId, BOOK_MAKER);
                syncManager.notifyBetFailure(arbId, BOOK_MAKER, "Odds changed");
                arbPollingService.killArb(task.getArb());

                Phaser phaser = task.getBarrier();
                phaser.arriveAndAwaitAdvance();
                return;
            }

            // Place the bet (BOTH WINDOWS DO THIS SIMULTANEOUSLY)
            boolean betPlaced = placeBet(page, task.getArb(), myLeg);

            if (betPlaced) {
                log.info("✅ Bet PLACED | ArbId: {} | Bookmaker: {} | Stake: {} | Odds: {}",
                        arbId, BOOK_MAKER, myLeg.getStake(), myLeg.getOdds());

                // Notify success immediately
                syncManager.notifyBetPlaced(arbId, BOOK_MAKER);

                waitForBetConfirmation(page);
                flowLogger.logBetConfirmationWait(arbId, BOOK_MAKER);

                randomHumanDelay(2000, 3000);

                // Spend amount based on bookmaker
                if (BOOK_MAKER == BookMaker.SPORTY_BET) {
                    sportyLoginUtils.spendAmount(BOOK_MAKER, myLeg.getStake(), arbId);
                    checkAndClosePopups(page);
                } else {
                    sportyLoginUtils.spendAmount(BOOK_MAKER, myLeg.getStake(), arbId);
                    checkAndClosePopups(page);
                }

                flowLogger.logStakeSpent(arbId, BOOK_MAKER, myLeg.getStake());

                String betId = extractBetId(page);
                flowLogger.logBetIdExtracted(arbId, BOOK_MAKER, betId);
                myLeg.markAsPlaced(betId, myLeg.getOdds());

                // ========================================
                // STEP 6: WAIT FOR PARTNER & HANDLE ROLLBACK IF NEEDED
                // ========================================
                handlePartnerResultAndRollback(page, arbId, betId, myLeg);

                bettingMetricsService.recordArbSuccess();

            } else {
                log.error("❌ Bet placement FAILED | ArbId: {} | Bookmaker: {}", arbId, BOOK_MAKER);

                // Notify failure immediately
                syncManager.notifyBetFailure(arbId, BOOK_MAKER, "Placement failed");

                // ========================================
                // STEP 6: WAIT FOR PARTNER TO SEE IF THEY SUCCEEDED
                // ========================================
                log.info("⏳ Waiting to check if partner succeeded | ArbId: {} | Bookmaker: {}", arbId, BOOK_MAKER);

                WindowSyncManager.PartnerBetResult partnerResult = syncManager.waitForPartnerBetCompletion(
                        arbId,
                        BOOK_MAKER,
                        Duration.ofSeconds(betTimeoutSeconds + 5)
                );

                if (partnerResult.isSuccess()) {
                    log.warn("⚠️ Partner SUCCEEDED but I FAILED - partner will rollback | ArbId: {} | Bookmaker: {}",
                            arbId, BOOK_MAKER);
                    flowLogger.logPartnerWillRollback(arbId, BOOK_MAKER);
                } else {
                    log.info("ℹ️ Both failed - no rollback needed | ArbId: {} | Bookmaker: {}", arbId, BOOK_MAKER);
                }
            }

            arbPollingService.killArb(task.getArb());

        } catch (Exception e) {
            flowLogger.logBetPlacementException(arbId, BOOK_MAKER, e);
            syncManager.skipArbAndSync(arbId);

        } finally {
            clearBetSlip(page);
            arbPollingService.killArb(task.getArb());
            syncManager.unRegisterIntent(arbId, BOOK_MAKER);
            randomHumanDelay(500, 1500);

            try {
                navigateBack(page);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            randomHumanDelay(2000, 3000);

            Phaser phaser = task.getBarrier();
            phaser.arriveAndAwaitAdvance();
        }
    }

    /**
     * Wait for partner's result and handle rollback if needed
     * Called by the window that SUCCEEDED in placing their bet
     */
    private void handlePartnerResultAndRollback(Page page, String arbId, String betId, BetLeg myLeg) {
        try {
            log.info("⏳ Waiting for partner to complete | ArbId: {} | Bookmaker: {} | MyBetId: {}",
                    arbId, BOOK_MAKER, betId);

            // Wait for partner to finish betting
            WindowSyncManager.PartnerBetResult partnerResult = syncManager.waitForPartnerBetCompletion(
                    arbId,
                    BOOK_MAKER,
                    Duration.ofSeconds(betTimeoutSeconds + 5)
            );

            if (partnerResult.isSuccess()) {
                log.info("✅ BOTH BETS PLACED SUCCESSFULLY | ArbId: {} | Bookmaker: {}", arbId, BOOK_MAKER);
                flowLogger.logBothBetsSucceeded(arbId, BOOK_MAKER);
                bettingMetricsService.recordArbSuccess();

            } else if (partnerResult.isFailed()) {
                // Partner failed - need to rollback my successful bet
                log.warn("⚠️ PARTNER FAILED - ROLLBACK NEEDED | ArbId: {} | Bookmaker: {} | MyBetId: {} | PartnerReason: {}",
                        arbId, BOOK_MAKER, betId, partnerResult.getMessage());

                flowLogger.logRollbackNeeded(arbId, BOOK_MAKER, betId, partnerResult.getMessage());

                // Initiate rollback
                syncManager.requestRollback(arbId, BOOK_MAKER, "Partner failed: " + partnerResult.getMessage());

                boolean rollbackSuccess = performRollback(page, arbId, betId, myLeg);
                syncManager.notifyRollbackCompleted(arbId, BOOK_MAKER, rollbackSuccess);

                if (rollbackSuccess) {
                    log.info("✅ ROLLBACK SUCCESSFUL | ArbId: {} | Bookmaker: {} | BetId: {}", arbId, BOOK_MAKER, betId);
                    flowLogger.logRollbackSuccess(arbId, BOOK_MAKER, betId);
                    bettingMetricsService.recordRollbackResult(true);
                } else {
                    log.error("❌ ROLLBACK FAILED | ArbId: {} | Bookmaker: {} | BetId: {} | MANUAL INTERVENTION REQUIRED",
                            arbId, BOOK_MAKER, betId);
                    flowLogger.logRollbackFailed(arbId, BOOK_MAKER, betId);
                    bettingMetricsService.recordRollbackResult(false);
                    // TODO: Send alert to operator
                }

            } else {
                log.warn("⏱️ Partner timed out or error | ArbId: {} | Bookmaker: {} | Result: {}",
                        arbId, BOOK_MAKER, partnerResult.getType());
            }

        } catch (Exception e) {
            log.error("❌ Exception during partner result handling | ArbId: {} | Bookmaker: {} | Error: {}",
                    arbId, BOOK_MAKER, e.getMessage(), e);
        }
    }

    /**
     * Perform rollback - cancel/cash out the bet
     * SAME IMPLEMENTATION FOR BOTH WINDOWS
     */
    private boolean performRollback(Page page, String arbId, String betId, BetLeg myLeg) {
        flowLogger.logRollbackAttemptStart(arbId, BOOK_MAKER, betId);

        try {
            // Navigate to bet history
            String baseUrl = BOOK_MAKER == BookMaker.SPORTY_BET ? "https://www.sportybet.com/ng" : "https://www.msport.com/ng/web";
            page.navigate(baseUrl + "/mybets");
            page.waitForTimeout(2000);

            // Look for the specific bet
            String betSelector = String.format("//div[contains(@class, 'bet-item')]//span[contains(text(), '%s')]", betId);

            if (page.locator(betSelector).count() > 0) {
                flowLogger.logRollbackBetFound(betId, BOOK_MAKER);

                // Try to find and click cash out button
                String cashOutSelector = String.format("%s//ancestor::div[contains(@class, 'bet-item')]//button[contains(text(), 'Cash Out')]", betSelector);

                if (page.locator(cashOutSelector).count() > 0) {
                    flowLogger.logRollbackCashOutAvailable(betId, BOOK_MAKER);
                    page.locator(cashOutSelector).first().click();
                    page.waitForTimeout(1000);

                    // Confirm cash out
                    String confirmSelector = "button:has-text('Confirm')";
                    if (page.locator(confirmSelector).count() > 0) {
                        page.locator(confirmSelector).first().click();
                        page.waitForTimeout(2000);

                        flowLogger.logRollbackCashOutExecuted(betId, BOOK_MAKER);

                        // Credit back the stake
                        if (BOOK_MAKER == BookMaker.SPORTY_BET) {
                            sportyLoginUtils.creditAmount(BOOK_MAKER, myLeg.getStake().doubleValue(), arbId);
                        } else {
                            sportyLoginUtils.creditAmount(BOOK_MAKER, myLeg.getStake().doubleValue(), arbId);
                        }

                        flowLogger.logStakeCredited(arbId, BOOK_MAKER, myLeg.getStake().doubleValue());
                        return true;
                    }
                } else {
                    flowLogger.logRollbackCashOutNotAvailable(betId, BOOK_MAKER);
                    // TODO: Implement hedge betting logic
                    return false;
                }
            } else {
                flowLogger.logRollbackBetNotFound(betId, BOOK_MAKER);
                return false;
            }

        } catch (Exception e) {
            flowLogger.logRollbackException(betId, BOOK_MAKER, e);
            return false;
        }

        return false;
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



    public static void takeScreenshot(Page page, String filename) {
        // We use Paths.get() to create the target path
        Path outputPath = Paths.get(filename);

        try {
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(outputPath)        // Sets the output path and filename
                    .setFullPage(true)          // Captures the entire page (including scrolling)
                    .setType(ScreenshotType.PNG) // Use PNG for lossless quality
            );
            System.out.println("✅ Screenshot saved successfully to: " + outputPath.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("❌ Failed to take screenshot: " + e.getMessage());
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

            boolean savedBalance = sportyLoginUtils.updateWalletBalance(page, BOOK_MAKER);
            if (savedBalance) {
                log.info("{} balance saved for balance tracking purposes", BOOK_MAKER);
            }else {
                log.warn("{} balance was not saved due to not been able to locate the amount element");
            }

            mimicHumanBehavior(page);
            goToLivePage(page);


            performLoginIfRequired(page);

                // 🔔 Check for any pop-ups after login
            checkAndClosePopups(page);

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
                        .setTimeout(120000)
                        .setWaitUntil(WaitUntilState.NETWORKIDLE));// More lenient than LOAD

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

            boolean loginSuccess = sportyLoginUtils.performLogin(page, sportyUsername, sportyPassword);

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
            boolean loggedIn = sportyLoginUtils.isLoggedIn(page);

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

//        MockTaskSupplier mockTaskSupplier = new MockTaskSupplier();
        int consecutiveErrors = 0;
        int maxConsecutiveErrors = 5;

        while (isRunning.get()) {
            try {

                isWindowUpAndRunning.set(true);
                // Check if paused
                if (isPaused.get()) {
                    log.info("⏸️ Window paused, waiting... | Bookmaker: {}", bookmaker);
                    Thread.sleep(1000);
                    continue;
                }

                // Health check
                // healthMonitor.checkHealth();
                log.info("📊 Ready to poll task for betting | Bookmaker: {}", bookmaker);

                // Poll for available Leg task
                 LegTask task = arbOrchestrator.getWorkerQueues().get(BOOK_MAKER).poll();
                 log.info("task polled by {}", BOOK_MAKER);
//                LegTask task = mockTaskSupplier.poll();

                if (task == null) {
                    log.info("the polled task is null for {}",BOOK_MAKER);
                    randomHumanDelay(500, 1000); // Small delay to prevent busy waiting
                    consecutiveErrors = 0; // Reset error counter on successful poll
                    continue;
                }

                log.info("🎯 Arb opportunity retrieved | ArbId: {} | Profit: {}% | Bookmaker: {} ",
                        task.getArbId(), task.getArb().getProfitPercentage(), BOOK_MAKER);
                log.info("{}", task.getArb());

                // Validate task
                BetLeg myLeg = task.getLeg();
                if (myLeg == null) {
                    log.warn("{} {} No leg found for bookmaker {} in arb {}",
                            EMOJI_WARNING, EMOJI_BET, bookmaker, task.getArbId());

                    // Notify partner that this arb is invalid
                    syncManager.skipArbAndSync(task.getArbId());
                    arbPollingService.killArb(task.getArb());
                    continue;
                }

                // Check login status BEFORE registering intent
                if (!sportyLoginUtils.isLoggedIn(page)) {
                    log.warn("⚠️ {} not logged in - cannot process bet | ArbId: {}",
                            BOOK_MAKER, task.getArbId());

                    // Notify partner and skip this arb
                    syncManager.notifyBetFailure(task.getArbId(), BOOK_MAKER, "Not LoggedIn");
                    syncManager.skipArbAndSync(task.getArbId());
                    arbPollingService.killArb(task.getArb());

                    // Throw exception to trigger re-login
                    throw new LoginException(BOOK_MAKER + " is not logged in for this bet to be placed");
                }

                // Process the bet placement
                try {
                    processBetPlacement(page, task, myLeg);
                    randomHumanDelay(1000, 2300);

//                    mockTaskSupplier.consume();
                    consecutiveErrors = 0; // Reset on success

                    log.info("✅ Bet processing completed | ArbId: {} | Bookmaker: {}",
                            task.getArbId(), BOOK_MAKER);

                } catch (PlaywrightException pe) {
                    log.error("🎭 Playwright error during bet placement | ArbId: {} | Error: {}",
                            task.getArbId(), pe.getMessage());

                    // Notify failure and skip
                    syncManager.notifyBetFailure(task.getArbId(), BOOK_MAKER,
                            "Playwright error: " + pe.getMessage());
                    syncManager.skipArbAndSync(task.getArbId());
                    arbPollingService.killArb(task.getArb());

                    consecutiveErrors++;


                } catch (Exception ex) {
                    log.error("❌ Unexpected error during bet placement | ArbId: {} | Error: {}",
                            task.getArbId(), ex.getMessage(), ex);

                    // Notify failure and skip
                    syncManager.notifyBetFailure(task.getArbId(), BOOK_MAKER,
                            "Error: " + ex.getMessage());
                    syncManager.skipArbAndSync(task.getArbId());
                    arbPollingService.killArb(task.getArb());

                    consecutiveErrors++;
                }

                // Check if too many consecutive errors
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    log.error("🚨 Too many consecutive errors ({}) - pausing window | Bookmaker: {}",
                            consecutiveErrors, BOOK_MAKER);
                    isPaused.set(true);
                    isWindowUpAndRunning.set(false);
                    // Optionally: trigger alert or notification
                    consecutiveErrors = 0;
                }

                // Mimic human delay between bets
                randomHumanDelay(2000, 5000);

            } catch (InterruptedException e) {
                log.info("🛑 Betting loop interrupted | Bookmaker: {}", bookmaker);
                Thread.currentThread().interrupt();
                break;

            } catch (LoginException le) {
                log.error("🔐 Login failure - pausing betting loop | Bookmaker: {} | Error: {}",
                        bookmaker, le.getMessage());

                // Pause this window until re-login
                isPaused.set(true);
                isWindowUpAndRunning.set(false);

                // Optionally: trigger re-login attempt
                sportyLoginUtils.performLogin(page, sportyUsername, sportyPassword);
                if (sportyLoginUtils.isLoggedIn(page)) {
                    isPaused.getAndSet(false);
                }

                randomHumanDelay(80000, 10000); // Wait before retry

            } catch (Exception e) {
                log.error("❌ Unexpected error in betting loop | Bookmaker: {} | Error: {}",
                        bookmaker, e.getMessage(), e);

                consecutiveErrors++;

                if (consecutiveErrors >= maxConsecutiveErrors) {
                    log.error("🚨 Critical: Too many errors - stopping loop | Bookmaker: {}",
                            BOOK_MAKER);
                    break;
                }

                // Wait before continuing
                randomHumanDelay(3000, 5000);
            }
        }

        log.info("🏁 Betting loop stopped | Bookmaker: {}", bookmaker);

        // Cleanup on exit
        cleanupOnLoopExit(bookmaker);
    }

    private void navigateBack(Page page) throws InterruptedException {
        page.goBack(new Page.GoBackOptions().setTimeout(15000));
        page.waitForLoadState(LoadState.NETWORKIDLE);
        log.info("{} Returned to previous page", EMOJI_NAVIGATION);

    }

    /**
     * Cleanup when loop exits
     */
    private void cleanupOnLoopExit(BookMaker bookmaker) {
        try {
            log.info("🧹 Cleaning up betting loop resources | Bookmaker: {}", bookmaker);

            // Unregister from sync manager
            syncManager.unregisterWindow(bookmaker);

            // Clear any pending sync states
            // syncManager.clearPendingForBookmaker(bookmaker);

            log.info("✅ Cleanup completed | Bookmaker: {}", bookmaker);

        } catch (Exception e) {
            log.error("❌ Error during cleanup | Bookmaker: {} | Error: {}",
                    bookmaker, e.getMessage(), e);
        }
    }

    /**
     * Process bet placement - extracted from main loop for better readability
     */


    /**
     * Monitors betslip odds for up to ~15 seconds.
     * Places the bet using YOUR ORIGINAL replayOddandBet logic the moment odds are acceptable.
     * Returns true ONLY when bet is successfully placed.
     */
    private boolean monitorAndPlace(Page page, BetLeg leg) {
         // 15 seconds max wait
        final long startTime = System.currentTimeMillis();

        log.info("STARTING 15-SECOND ODDS MONITOR for {} → {} @ {}",
                leg.getProviderMarketTitle(), leg.getProviderMarketName(), leg.getOdds());

        while (System.currentTimeMillis() - startTime < MAX_DURATION_MS) {
            boolean placed = replayOddandBet(page, leg);  // YOUR ORIGINAL METHOD

            if (placed) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("BET PLACED SUCCESSFULLY after {}ms — Exiting monitor", elapsed);
                return true;
            }

            // If not placed → odds were too low or error → wait and retry
            long elapsed = System.currentTimeMillis() - startTime;
            long remaining = MAX_DURATION_MS - elapsed;
            if (remaining <= 0) break;

            log.info("Odds not acceptable yet... retrying in 1–1.8s ({}ms left)", remaining);
            randomHumanDelay(600, 1000);  // Human-like pause
        }

        // Timeout reached
        long totalTime = System.currentTimeMillis() - startTime;
        log.warn("15-SECOND MONITOR TIMEOUT — Odds never recovered ({}ms)", totalTime);
        safeRemoveFromSlip(page);
        return false;
    }

    /**
     * Navigate to live betting page
     */
    private void goToLivePage(Page page) {
        final long timeout = 20_000;

        try {
            Locator liveBettingLink = withLocatorRetry(
                    page, "#header_nav_liveBetting",
                    loc -> {
                        if (loc.isVisible(new Locator.IsVisibleOptions().setTimeout(120000))) {
                            return loc;
                        }
                        throw new RuntimeException("ID locator not visible");
                    },
                    RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
            );

            if (liveBettingLink != null) {
                liveBettingLink.click(new Locator.ClickOptions().setTimeout(50000));
                log.info("Clicked 'Live Betting' using ID selector");
            } else {
                throw new Exception("ID locator not visible");
            }
        } catch (Exception e) {
            log.info("ID selector failed, trying fallback...");

            try {
                Locator liveBettingLink = withLocatorRetry(
                        page, "a:has-text('Live Betting')",
                        loc -> loc,
                        RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
                );

                if (liveBettingLink != null) {
                    liveBettingLink.click(new Locator.ClickOptions().setTimeout(50000));
                    log.info("Clicked 'Live Betting' using text selector");
                } else {
                    throw new Exception("Text selector failed");
                }
            } catch (Exception e2) {
                log.info("Text selector failed, trying accessibility role...");

                try {
                    page.getByRole(AriaRole.LINK,
                                    new Page.GetByRoleOptions().setName("Live Betting").setExact(true))
                            .click(new Locator.ClickOptions().setTimeout(50000));
                    log.info("Clicked 'Live Betting' using getByRole (accessibility)");
                } catch (Exception e3) {
                    throw new NavigationException("Failed to click 'Live Betting' tab using all fallback strategies", e3);
                }
            }
        }

        try {
            page.waitForURL(url -> url.toString().contains("/sport/live/"),
                    new Page.WaitForURLOptions().setTimeout(timeout));

            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(50000));

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

        String[] selectors = {
                "a[href='/ng/sport/football/live_list/']",
                "span[data-cms-key='multi_view']",
                "text=Multi View"
        };

        boolean clicked = false;
        for (String selector : selectors) {
            try {
                Locator element = withLocatorRetry(
                        page, selector,
                        loc -> {
                            if (loc.count() > 0 && loc.first().isVisible()) {
                                return loc;
                            }
                            return null;
                        },
                        RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
                );

                if (element != null && element.count() > 0 && element.first().isVisible()) {
                    element.first().scrollIntoViewIfNeeded();
                    randomHumanDelay(500, 1000);
                    element.first().click(new Locator.ClickOptions().setTimeout(120_000));
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

        String currentUrl = page.url();
        if (!currentUrl.contains("/football/live_list")) {
            throw new RuntimeException("Failed to navigate to Multi View. URL: " + currentUrl);
        }

        log.info("✅ Multi View loaded: {}", currentUrl);
        randomHumanDelay(1500, 3000);

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
        log.info("Clicking visible sport tab: {}", displayName);

        String selector = """
    div.sport-name-item:has(div.text:has-text("%s"))
    """.formatted(displayName);

        Locator sportTab = withLocatorRetry(
                page, selector,
                loc -> {
                    loc.first().waitFor(new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(120000));
                    return loc.first();
                },
                RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
        );

        if (sportTab == null) {
            throw new RuntimeException("Could not find sport tab: " + displayName);
        }

        sportTab.scrollIntoViewIfNeeded();
        randomHumanDelay(300, 600);

        sportTab.click(new Locator.ClickOptions()
                .setTimeout(12000)
                .setForce(false)
        );

        page.waitForURL(url -> {
            String urlStr = url.toString().toLowerCase();
            return urlStr.contains("/" + urlSegment.toLowerCase() + "/")
                    && urlStr.contains("live_list");
        }, new Page.WaitForURLOptions().setTimeout(20000));

        log.info("Successfully switched to {} → {}", displayName, page.url());
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
                ".select-title",
                ".sport-simple-select .select-title",
                ".simple-select-wrap .select-title",
                "p.select-title__label",
                "p:has-text('More Sports')"
        };

        for (String selector : dropdownSelectors) {
            try {
                Locator dropdown = withLocatorRetry(
                        page, selector,
                        loc -> {
                            if (loc.count() > 0 && loc.first().isVisible()) {
                                return loc;
                            }
                            return null;
                        },
                        RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
                );

                if (dropdown != null && dropdown.count() > 0 && dropdown.first().isVisible()) {
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
            withLocatorRetry(
                    page, ".select-list",
                    loc -> {
                        page.waitForSelector(".select-list",
                                new Page.WaitForSelectorOptions()
                                        .setTimeout(8000)
                                        .setState(WaitForSelectorState.VISIBLE));
                        return loc;
                    },
                    RETRY_MAX_ATTEMPTS, 8000, RETRY_DELAY_MS
            );
            log.info("✅ Dropdown list is now visible");
        } catch (PlaywrightException e) {
            log.error("❌ Dropdown list did not appear within 8 seconds");
            throw new RuntimeException("Dropdown list failed to open", e);
        }

        // === 3. Find and click the specific sport ===
        try {
            Locator allSports = withLocatorRetry(
                    page, ".select-list .select-item",
                    loc -> loc,
                    RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
            );

            if (allSports == null) {
                throw new RuntimeException("Could not locate sport items in dropdown");
            }

            int totalSports = allSports.count();
            log.info("Found {} sports in dropdown", totalSports);

            Locator sportItem = allSports.filter(new Locator.FilterOptions()
                    .setHasText(displayName));

            if (sportItem.count() == 0) {
                log.error("❌ Sport '{}' not found in dropdown", displayName);

                log.warn("Available sports in dropdown:");
                for (int i = 0; i < Math.min(totalSports, 25); i++) {
                    String text = allSports.nth(i).textContent().trim();
                    log.warn("  [{}] '{}'", i, text);
                }

                throw new RuntimeException("Sport not found in dropdown: " + displayName);
            }

            log.info("✅ Found sport: '{}'", displayName);

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

        // === 4. Wait for navigation ===
        try {
            page.waitForURL(
                    url -> url.contains("/" + urlSegment + "/live_list"),
                    new Page.WaitForURLOptions().setTimeout(15_000)
            );
            log.info("✅ Navigated to {} Multi View: {}", displayName, page.url());

        } catch (PlaywrightException e) {
            String currentUrl = page.url();
            log.warn("⚠️ URL wait timed out. Current URL: {}", currentUrl);

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
                    log.error("Error checking container {}: {}", i, e.getMessage());
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

            log.info("Fuzzy search - Home key: '{}', Away key: '{}'", homeKey, awayKey);

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
                    // Primary: Wait for the main wrapper that contains ALL odds tables
                    page.waitForSelector(".m-detail-wrapper", new Page.WaitForSelectorOptions()
                            .setTimeout(3000));

                    log.info("✅ Match content loaded - .m-detail-wrapper detected");

                } catch (TimeoutError e) {
                    // Fallback 1: Look for any odds table (even if wrapper changed)
                    try {
                        page.waitForSelector(".m-table__wrapper, .m-table-row.m-outcome",
                                new Page.WaitForSelectorOptions().setTimeout(5000));
                        log.info("✅ Match content detected via fallback (odds tables present)");
                    } catch (TimeoutError e2) {
                        // Fallback 2: Look for navigation tabs (All, Main, Game)
                        try {
                            page.waitForSelector(".m-nav-item", new Page.WaitForSelectorOptions()
                                    .setTimeout(3000));
                            log.info("✅ Match content detected - navigation tabs present");
                        } catch (TimeoutError e3) {
                            log.warn("⚠️ Match content not detected - page may have changed or is slow");
                            // Continue anyway — sometimes odds load later
                        }
                    }
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


    // Step 1: Clear bet slip before adding new bet
    private boolean clearBetSlip(Page page) {
        try {
            Locator betslipContainer = withLocatorRetry(
                    page, "#j_betslip .m-betslips",
                    loc -> loc.first(),
                    RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
            );

            if (betslipContainer == null || !betslipContainer.isVisible()) {
                log.info("{} Betslip not visible", EMOJI_INFO);
                return true;
            }

            String betCountText = getBetCount(page);
            if ("0".equals(betCountText) || betCountText.isEmpty()) {
                log.info("{} Betslip already empty", EMOJI_SUCCESS);
                return true;
            }

            Locator removeAllBtn = withLocatorRetry(
                    page, "#j_betslip .m-text-min[data-cms-key='remove_all']",
                    loc -> loc.first(),
                    RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
            );

            if (removeAllBtn != null && removeAllBtn.count() > 0 && removeAllBtn.isVisible()) {
                log.info("{} Using 'Remove All' button", EMOJI_TRASH);
                removeAllBtn.click();
                page.waitForTimeout(1000);

                if (isBetstipEmpty(page)) {
                    log.info("{} Betslip cleared via 'Remove All'", EMOJI_SUCCESS);
                    return true;
                }
            }

            return clearBetsIndividually(page);

        } catch (Exception e) {
            log.error("{} Error clearing betslip: {}", EMOJI_ERROR, e.getMessage());
            return false;
        }
    }

    private boolean clearBetsIndividually(Page page) {
        Locator betList = withLocatorRetry(
                page, "#j_betslip .m-list",
                loc -> loc.first(),
                RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
        );

        if (betList == null || betList.count() == 0 || !betList.isVisible()) {
            return true;
        }

        Locator deleteButtons = withLocatorRetry(
                page, "#j_betslip .m-list .m-item .m-icon-delete",
                loc -> loc,
                RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
        );

        if (deleteButtons == null) return true;

        int betCount = deleteButtons.count();
        if (betCount == 0) return true;

        log.info("{} Removing {} bet(s) individually...", EMOJI_TRASH, betCount);

        for (int i = betCount - 1; i >= 0; i--) {
            try {
                Locator deleteBtn = deleteButtons.nth(i);
                deleteBtn.scrollIntoViewIfNeeded();
                page.waitForTimeout(150);

                deleteBtn.click(new Locator.ClickOptions()
                        .setForce(true)
                        .setTimeout(10000));

                page.waitForTimeout(500);

            } catch (Exception e) {
                log.warn("{} Failed to remove bet {}: {}", EMOJI_WARNING, i, e.getMessage());
            }
        }

        page.waitForTimeout(1000);
        boolean cleared = isBetstipEmpty(page);

        if (cleared) {
            log.info("{} All bets removed", EMOJI_SUCCESS);
        } else {
            log.warn("{} Some bets may remain", EMOJI_WARNING);
        }

        return cleared;
    }

    private boolean isBetstipEmpty(Page page) {
        return page.locator("#j_betslip .m-list .m-item").count() == 0;
    }

    private String getBetCount(Page page) {
        try {
            return page.locator("#j_betslip .m-bet-count").first().textContent().trim();
        } catch (Exception e) {
            return "0";
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
        String market = leg.getProviderMarketTitle();
        String outcome = leg.getProviderMarketName();

        try {
            log.info("Selecting: {} → {}", market, outcome);

            withLocatorRetry(
                    page, "div.m-nav-item--active >> text=All",
                    loc -> {
                        loc.first().waitFor(new Locator.WaitForOptions().setTimeout(10000));
                        return true;
                    },
                    RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
            );

            page.locator("div.m-nav-item:has-text('All')").click();
            randomHumanDelay(100, 200);

            String marketHeaderXPath = String.format(
                    "//div[contains(@class,'m-table__wrapper')]//span[contains(@class,'m-table-header-title') and normalize-space()=%s]",
                    escapeXPath(market)
            );

            // CHANGE 1: Get ALL market headers matching the name
            Locator marketHeaders = withLocatorRetry(
                    page, "xpath=" + marketHeaderXPath,
                    loc -> loc,
                    RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
            );

            if (marketHeaders == null || marketHeaders.count() == 0) {
                log.error("Market NOT FOUND: {}", market);
                takeMarketScreenshot(page, "market-not-found-" + market.replaceAll("[^a-zA-Z0-9]", "_"));
                return false;
            }

            // CHANGE 2: Get ALL corresponding market blocks
            Locator marketBlocks = marketHeaders.locator("xpath=ancestor::div[contains(@class,'m-table__wrapper')]");

            log.info("Found {} market block(s) with name: {}", marketBlocks.count(), market);

            takeMarketScreenshot(page, market.replaceAll("[^a-zA-Z0-9]", "_"));

            // Try to find exact match first
            String targetOutcome = outcome;
            String cellXPath = String.format(
                    ".//div[contains(@class,'m-table-cell--responsive') and not(contains(@class,'m-table-cell--disable'))]" +
                            "[.//span[contains(@class,'m-table-cell-item') and contains(normalize-space(), %s)]]",
                    escapeXPath(targetOutcome)
            );

            Locator outcomeCell = null;
            List<String> allAvailableOutcomes = new ArrayList<>();

            // CHANGE 3: Iterate through all market blocks to find the outcome
            for (int i = 0; i < marketBlocks.count(); i++) {
                Locator currentBlock = marketBlocks.nth(i);

                // Collect outcomes for logging
                List<String> blockOutcomes = currentBlock.locator("span.m-table-cell-item").allTextContents();
                allAvailableOutcomes.addAll(blockOutcomes);

                Locator candidate = currentBlock.locator("xpath=" + cellXPath).first();

                if (candidate.count() > 0) {
                    try {
                        candidate.waitFor(new Locator.WaitForOptions()
                                .setTimeout(1500)
                                .setState(WaitForSelectorState.VISIBLE));
                        outcomeCell = candidate;
                        log.info("Outcome '{}' found in market block {}/{}", outcome, i + 1, marketBlocks.count());
                        break; // Found it — stop searching
                    } catch (TimeoutError ignored) {
                        // Not visible, continue to next block
                    }
                }
            }

            // If still not found, log all available outcomes across all blocks
            if (outcomeCell == null || outcomeCell.count() == 0) {
                log.error("❌ Outcome '{}' not found in any of the {} market(s) named '{}'", outcome, marketBlocks.count(), market);
                log.error("All available outcomes across markets: {}", allAvailableOutcomes);
                takeMarketScreenshot(page, "outcome-not-found-" + market.replaceAll("[^a-zA-Z0-9]", "_"));
                return false;
            }

            // Rest of the logic remains EXACTLY the same
            Locator oddsSpan = outcomeCell.locator("span.m-table-cell-item").nth(1);
            String displayedOdds = oddsSpan.textContent().trim();

            if (targetOutcome.equals(outcome)) {
                log.info("✅ FOUND: {} → {} @ {}", market, outcome, displayedOdds);
            } else {
                log.info("✅ FOUND (fuzzy): {} → {} (matched as '{}') @ {}",
                        market, outcome, targetOutcome, displayedOdds);
            }

            if (!isOddsAcceptable(leg.getOdds().doubleValue(), displayedOdds)) {
                log.warn("⚠️ Odds drifted: expected {} → got {}", leg.getOdds(), displayedOdds);
            }

            outcomeCell.scrollIntoViewIfNeeded();
            randomHumanDelay(100, 200);

            try {
                outcomeCell.evaluate("el => el.style.border = '3px solid red'");
                randomHumanDelay(50, 100);
            } catch (Exception e) {
                log.debug("Could not highlight cell: {}", e.getMessage());
            }

            try {
                outcomeCell.click(new Locator.ClickOptions().setTimeout(10000));
                log.info("✅ CLICKED: {} → {} @ {}", market, targetOutcome, displayedOdds);
            } catch (Exception e) {
                log.warn("Standard click failed, trying JS click: {}", e.getMessage());
                outcomeCell.evaluate("el => el.click()");
                log.info("✅ JS CLICKED: {} → {} @ {}", market, targetOutcome, displayedOdds);
            }

            randomHumanDelay(100, 200);
            return true;

        } catch (Exception e) {
            log.error("❌ FATAL: Failed to select {} → {}", market, outcome, e);
            takeMarketScreenshot(page, "error-" + market.replaceAll("[^a-zA-Z0-9]", "_"));
            return false;
        }
    }



    // Odds tolerance (SportyBet rounds to 2 decimals)
    private boolean isOddsAcceptable(double expected, String displayed) {
        try {
            double actual = Double.parseDouble(displayed.replaceAll("[^0-9.]", ""));
            return Math.abs(actual - expected) <= 0.08;
        } catch (Exception e) {
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
        if (!verifyBetSlip(page, leg)) {
            log.error("{} {} Bet slip verification failed", EMOJI_ERROR, EMOJI_BET);
            return false;
        }

        log.info("{} {} Bet deployment successful", EMOJI_SUCCESS, EMOJI_ROCKET);
        return true;
    }

    // Optional: Verify bet appeared in slip
    private boolean verifyBetSlip(Page page, BetLeg leg) {
        String outcome = leg.getProviderMarketName();
        String market = leg.getProviderMarketTitle();

        try {
            Locator betslip = withLocatorRetry(
                    page, "#j_betslip .m-betslips",
                    loc -> {
                        loc.waitFor(new Locator.WaitForOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(8000));
                        return loc;
                    },
                    RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
            );

            if (betslip == null) return false;

            Locator betItem = withLocatorRetry(
                    page, "#j_betslip .m-betslips .m-list .m-item",
                    loc -> {
                        loc.first().waitFor(new Locator.WaitForOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(3000));
                        return loc.first();
                    },
                    RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
            );

            if (betItem == null) return false;

            String displayedOutcome = withLocatorRetry(
                    page, ".m-item-play span",
                    loc -> betItem.locator(loc).first().textContent().trim(),
                    RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
            );

            String displayedMarket = withLocatorRetry(
                    page, ".m-item-market",
                    loc -> betItem.locator(loc).textContent().trim(),
                    RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
            );

            String displayedOdds = withLocatorRetry(
                    page, ".m-item-odds .m-text-main",

                    loc -> betItem.locator(loc).textContent().trim(),
                    RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
            );

            String displayedTeam = withLocatorRetry(
                    page, ".m-item-team",
                    loc -> betItem.locator(loc).textContent().trim(),
                    RETRY_MAX_ATTEMPTS, RETRY_TIMEOUT_MS, RETRY_DELAY_MS
            );

            boolean isLive = betItem.locator(".m-icon-live").count() > 0;

            if (!displayedOutcome.equalsIgnoreCase(outcome)) {
                log.warn("⚠️ Outcome mismatch: expected '{}' but found '{}'", outcome, displayedOutcome);
                return false;
            }

            if (!displayedMarket.equalsIgnoreCase(market)) {
                log.warn("⚠️ Market mismatch: expected '{}' but found '{}'", market, displayedMarket);
                return false;
            }

            log.info("✅ BET VERIFIED IN SLIP: {} | Market: {} | Odds: {} | Match: {} | Live: {}",
                    displayedOutcome, displayedMarket, displayedOdds, displayedTeam, isLive);

            return true;

        } catch (PlaywrightException pe) {
            if (pe.getMessage().contains("Timeout")) {
                log.warn("⏱️ Timeout waiting for bet in slip: {}", pe.getMessage());
                debugBetslipContents(page, outcome, market);
            } else {
                log.error("❌ Playwright error: {}", pe.getMessage());
            }
            return false;
        } catch (Exception e) {
            log.error("❌ Unexpected error: {}", e.getMessage(), e);
            return false;
        }
    }


    private void debugBetslipContents(Page page, String expectedOutcome, String expectedMarket) {
        try {
            // Check if betslip is even visible
            boolean betslipVisible = page.locator("#j_betslip").isVisible();
            log.warn("❌ TIMEOUT - Betslip visible: {}", betslipVisible);

            if (!betslipVisible) {
                log.warn("Betslip container not found or not visible!");
                return;
            }

            // Get bet count
            String betCount = page.locator(".m-bet-count").first().textContent().trim();
            log.warn("Bet count showing: {}", betCount);

            // List all items in betslip
            Locator allItems = page.locator("#j_betslip .m-list .m-item");
            int itemCount = allItems.count();
            log.warn("Number of items in betslip: {}", itemCount);

            // Show details of each item
            for (int i = 0; i < itemCount; i++) {
                Locator item = allItems.nth(i);
                String outcome = item.locator(".m-item-play span").nth(0).textContent().trim();
                String market = item.locator(".m-item-market").textContent().trim();
                String odds = item.locator(".m-item-odds .m-text-main").textContent().trim();
                String team = item.locator(".m-item-team").textContent().trim();

                log.warn("  Item {}: outcome='{}', market='{}', odds='{}', team='{}'",
                        i, outcome, market, odds, team);
            }

            log.warn("Expected: outcome='{}', market='{}'", expectedOutcome, expectedMarket);

        } catch (Exception e) {
            log.warn("Debug failed: {}", e.getMessage());
        }
    }


    /**
     * Place bet on the page
     */
    private boolean placeBet(Page page, Arb arb, BetLeg leg) {
        BigDecimal stake = leg.getStake();
        long startTime = System.currentTimeMillis();

        log.info("─────────────────────────────────────────────────────────────");
        log.info("START placeBet() → {} → {} @ {} | Stake: {} | {}",
                leg.getProviderMarketTitle(), leg.getProviderMarketName(),
                leg.getOdds(), stake,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")));

        try {
            // ── 1. ENTER STAKE ─────────────────────────────────────
            log.info("[1/6] Entering stake...");
            Locator stakeInput = page.locator("#j_stake_0 input.m-input, .m-input[placeholder*='min']").first();
            if (stakeInput.count() == 0) {
                log.error("Stake input missing");
                return false;
            }
            jsScrollAndFocus(stakeInput, page);
            stakeInput.fill("");
            randomHumanDelay(150, 400);
            SportyLoginUtils.typeFastHumanLike(stakeInput, stake.toPlainString());
            stakeInput.press("Enter");
            randomHumanDelay(200, 500);
            log.info("[OK] Stake entered");

            // ── 2. WAIT FOR BET IN SLIP ─────────────────────────────────
            if (page.locator("div.m-item, .m-bet-item").count() == 0) {
                page.waitForTimeout(3000);
                if (page.locator("div.m-item").count() == 0) {
                    log.error("Bet never appeared in slip");
                    return false;
                }
            }
            log.info("[OK] Bet in slip");

            // ── 3. UNKILLABLE LOOP + FULL POPUP HANDLING ─────────────────────
            log.info("[3/6] Starting UNKILLABLE placement loop...");

            boolean betConfirmed = false;
            boolean oddsChangedFailure = false;
            int cycle = 0;
            final int MAX_CYCLES = 10;

            while (!betConfirmed && !oddsChangedFailure && cycle < MAX_CYCLES) {
                cycle++;
                log.info("[Cycle {}/{}] Checking state...", cycle, MAX_CYCLES);

                // ── A. ODDS CHANGE POPUP (CRITICAL FAILURE) ──
                Locator oddsChangePopup = page.locator("div.m-dialog-wrapper p:has-text('Odds not acceptable')").first();
                if (oddsChangePopup.isVisible(new Locator.IsVisibleOptions().setTimeout(500))) {
                    log.error("ODDS CHANGE DETECTED → 'Odds not acceptable' popup shown → FAILURE");
                    oddsChangedFailure = true;
                    break;
                }

                // ── B. FINAL CONFIRM POPUP ──
                Locator finalConfirm = page.locator("xpath=//button[.//span[text()='Confirm' or text()='Yes' or text()='OK']]").first();
                if (finalConfirm.isVisible(new Locator.IsVisibleOptions().setTimeout(800))) {
                    log.warn("FINAL CONFIRM POPUP → Clicking 'Confirm'");
                    jsScrollAndClick(finalConfirm, page);
                    randomHumanDelay(1000, 1800);
                }

                // ── C. MAIN BUTTON LOGIC ──
                Locator btn = page.locator("button.af-button--primary >> visible=true").first();
                if (btn.count() == 0) {
                    log.info("Primary button gone → likely success");
//                    betConfirmed = true;
//                    break;
                    continue; //todo: placing continue here will still make the game to be monitored
                }

                String text = btn.innerText().trim();
                log.info("Main button: \"{}\" | Disabled: {}", text, btn.isDisabled());

                // 1. Accept Changes
                if (text.matches(".*(Accept Changes|Accept|Confirm Changes).*")) {
                    log.warn("→ Clicking 'Accept Changes' (cycle {})", cycle);
                    jsScrollAndClick(btn, page);
                    randomHumanDelay(300, 900);
                    continue;
                }

                // 2. Place Bet + ENABLED → click
                if (text.matches(".*(Place Bet|Bet Now|Confirm Bet|Place bet).*") && !btn.isDisabled()) {
                    log.info("→ Clicking 'Place Bet' (cycle {})", cycle);
                    jsScrollAndClick(btn, page);
                    randomHumanDelay(1000, 2000);

                    // CHECK "ODDS NOT ACCEPTABLE" RIGHT AFTER CLICK
                    Locator oddsRejected = page.locator("div.m-dialog-wrapper p:has-text('Odds not acceptable')").first();
                    if (oddsRejected.isVisible(new Locator.IsVisibleOptions().setTimeout(3000))) {
                        log.error("ODDS NOT ACCEPTABLE POPUP → Detected after Place Bet click → FAILURE");
                        oddsChangedFailure = true;
                        break;
                    }
                    continue;
                }

                // 3. Place Bet disabled → wait
                if (text.contains("Place Bet") && btn.isDisabled()) {
                    log.info("Place Bet disabled → waiting...");
                    randomHumanDelay(1000, 1500);
                    continue;
                }

                // ── 4. 100% RELIABLE SUCCESS DETECTION (MULTI-LAYER) ──
                boolean successDetected = page.locator("div.m-dialog-wrapper.m-dialog-suc").count() > 0 ||
                        page.locator("text='Submission Successful'").count() > 0 ||
                        page.locator("i.m-icon-suc").count() > 0 ||
                        page.locator("div.booking-code").isVisible(new Locator.IsVisibleOptions().setTimeout(1000));

                if (successDetected) {
                    log.info("SUCCESS CONFIRMED — OFFICIAL 'Submission Successful' MODAL DETECTED!");

                    // Extract booking code
                    try {
                        String code = page.locator("div.booking-code").textContent().trim();
                        log.info("BOOKING CODE: {}", code.isEmpty() ? "Hidden" : code);
                    } catch (Exception ignored) {}

                    betConfirmed = true;
                    break;
                }

                randomHumanDelay(700, 1100);
            }

            // ── HANDLE ODDS CHANGE FAILURE (CLOSE POPUP OUTSIDE LOOP) ──
            if (oddsChangedFailure) {
                log.error("BET PLACEMENT FAILED → Odds changed and not acceptable");
                try {
                    Locator closeBtn = page.locator("div.m-dialog-wrapper button:has-text('OK'), " +
                            "div.m-dialog-wrapper img.close-icon[data-action='close']").first();
                    if (closeBtn.isVisible(new Locator.IsVisibleOptions().setTimeout(2000))) {
                        log.info("Closing 'Odds not acceptable' popup...");
                        jsScrollAndClick(closeBtn, page);
                        randomHumanDelay(800, 1500);
                        log.info("Popup closed");
                    }
                } catch (Exception e) {
                    log.warn("Failed to close odds change popup: {}", e.getMessage());
                }

                long duration = System.currentTimeMillis() - startTime;
                log.info("placeBet() COMPLETED | FAILURE (Odds Changed) | {}ms", duration);
                log.info("─────────────────────────────────────────────────────────────");
                return false;
            }

            if (!betConfirmed) {
                log.error("FAILED after {} cycles → Could not place bet", MAX_CYCLES);
                return false;
            }
            log.info("[OK] Bet placed after {} cycle(s)", cycle);

            // ── 5. FINAL SUCCESS VERIFICATION (HARD CONFIRMATION) ─────────────
            boolean finalSuccess = false;
            try {
                page.waitForFunction("""
            () => {
                return document.querySelector('div.m-dialog-wrapper.m-dialog-suc') ||
                       document.querySelector('span[data-cms-key="submission_successful"]') ||
                       document.querySelector('div.booking-code');
            }
            """, null, new Page.WaitForFunctionOptions().setTimeout(15000));

                log.info("FINAL SUCCESS VERIFIED — Official modal confirmed");

                try {
                    String code = page.locator("div.booking-code").textContent().trim();
                    log.info("FINAL BOOKING CODE: {}", code.isEmpty() ? "Not shown" : code);
                } catch (Exception ignored) {}

                finalSuccess = true;

            } catch (TimeoutError te) {
                log.error("NO SUCCESS MODAL AFTER 15s → BET FAILED");
                return false;
            }

            // ── 6. CLOSE SUCCESS MODAL CLEANLY ─────────────────────────────
            try {
                Locator closeSuccess = page.locator("div.m-dialog-suc button:has-text('OK'), " +
                        "div.m-dialog-suc i.m-icon-close, " +
                        "div.m-dialog-suc [data-action='close']").first();
                jsScrollAndClick(closeSuccess, page);
                randomHumanDelay(600, 1200);
                log.info("Success modal closed");
            } catch (Exception e) {
                log.debug("Success modal already closed");
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("placeBet() COMPLETED | SUCCESS | {}ms | {} cycles", duration, cycle);
            log.info("─────────────────────────────────────────────────────────────");
            return true;

        } catch (Exception e) {
            log.error("FATAL in placeBet(): {}", e.toString());
            e.printStackTrace();
            return false;
        }
    }
// ── HELPER METHODS (100% CORRECT SIGNATURES) ──────────────────────────────────

    private void jsScrollAndClick(Locator locator, Page page) {
        try {
            locator.evaluate("el => el.scrollIntoView({ block: 'center', behavior: 'smooth' })");
            page.waitForTimeout(350);
            locator.click(new Locator.ClickOptions().setForce(true).setTimeout(1500));
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
            Locator successDialog = page.locator("div.m-dialog-wrapper.m-dialog-suc").first();
            successDialog.waitFor(new Locator.WaitForOptions()
                    .setTimeout(5000)
                    .setState(WaitForSelectorState.VISIBLE));

            log.info("{} {} Bet confirmed - Success dialog detected", EMOJI_SUCCESS, EMOJI_BET);

        } catch (TimeoutError e) {
            log.warn("{} {} Confirmation dialog not detected within timeout", EMOJI_WARNING, EMOJI_BET);
        } catch (Exception e) {
            log.warn("{} {} Confirmation detection error: {}", EMOJI_WARNING, EMOJI_BET, e.getMessage());
        }
    }


    /**
     * Extracts odds from betslip, verifies they're acceptable (equal, higher, or within 2% lower),
     * and places the bet if odds are favorable.
     *
     * @param page Playwright page object
     * @param leg BetLeg containing expected odds and bet details
     * @return true if bet was successfully placed, false otherwise
     */
    private boolean replayOddandBet(Page page, BetLeg leg) {
        BigDecimal expectedOdds = leg.getOdds();
        String outcome = leg.getProviderMarketName();   // e.g. "Away"
        String market = leg.getProviderMarketTitle();   // e.g. "Winner"

        try {
            log.info("🔍 Extracting odds from betslip for verification...");

            // Wait for betslip container to be visible
            Locator betslip = page.locator("#j_betslip .m-betslips");
            betslip.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(8000));

            // Wait specifically for the bet item to appear
            Locator betItem = betslip.locator(".m-list .m-item").first();
            betItem.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(3000));

            // ── 1. EXTRACT ODDS FROM BETSLIP ──────────────────────────────────────
            String displayedOddsText = betItem.locator(".m-item-odds .m-text-main").textContent().trim();

            // Parse the odds string to BigDecimal
            BigDecimal currentOdds;
            try {
                currentOdds = new BigDecimal(displayedOddsText);
            } catch (NumberFormatException e) {
                log.error("❌ Failed to parse odds from betslip: '{}'", displayedOddsText);
                return false;
            }

            log.info("📊 Expected Odds: {} | Current Betslip Odds: {}", expectedOdds, currentOdds);

            // ── 2. CALCULATE 2% TOLERANCE THRESHOLD ───────────────────────────────
            // Acceptable if: currentOdds >= expectedOdds * 0.98 (2% lower tolerance)
            BigDecimal lowerThreshold = expectedOdds.multiply(new BigDecimal("0.98"));

            log.info("📉 Lower Threshold (2% tolerance): {}", lowerThreshold.setScale(2, RoundingMode.HALF_UP));

            // ── 3. COMPARE ODDS ────────────────────────────────────────────────────
            int comparison = currentOdds.compareTo(lowerThreshold);

            if (comparison >= 0) {
                // Current odds are equal, higher, or within 2% lower
                String status;
                if (currentOdds.compareTo(expectedOdds) > 0) {
                    status = "HIGHER ✅";
                } else if (currentOdds.compareTo(expectedOdds) == 0) {
                    status = "EXACT MATCH ✅";
                } else {
                    BigDecimal percentageDrop = expectedOdds.subtract(currentOdds)
                            .divide(expectedOdds, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"));
                    status = String.format("WITHIN TOLERANCE ✅ (%.2f%% lower)", percentageDrop);
                }

                log.info("✅ ODDS ACCEPTABLE: {} → Proceeding to place bet", status);

                // ── 4. EXTRACT ADDITIONAL INFO FOR LOGGING ────────────────────────
                String displayedOutcome = betItem.locator(".m-item-play span").first().textContent().trim();
                String displayedMarket = betItem.locator(".m-item-market").textContent().trim();
                String displayedTeam = betItem.locator(".m-item-team").textContent().trim();
                boolean isLive = betItem.locator(".m-icon-live").count() > 0;

                log.info("🎯 Bet Details: {} | Market: {} | Odds: {} | Match: {} | Live: {}",
                        displayedOutcome, displayedMarket, currentOdds, displayedTeam, isLive);

                // ── 5. PLACE THE BET ───────────────────────────────────────────────
                // Create a temporary Arb object if needed (or modify signature to not need it)
                return placeBet(page, null, leg);

            } else {
                // Current odds are more than 2% lower than expected
                BigDecimal percentageDrop = expectedOdds.subtract(currentOdds)
                        .divide(expectedOdds, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));

                log.warn("⚠️ ODDS TOO LOW: Current {} vs Expected {} (%.2f%% drop) → SKIPPING BET",
                        currentOdds, expectedOdds, percentageDrop);

                // Remove from betslip
                safeRemoveFromSlip(page);
                return false;
            }

        } catch (PlaywrightException pe) {
            if (pe.getMessage().contains("Timeout")) {
                log.warn("⏱️ Timeout waiting for bet in slip: {}", pe.getMessage());
            } else {
                log.error("❌ Playwright error during odds verification: {}", pe.getMessage());
            }
            return false;
        } catch (Exception e) {
            log.error("❌ Unexpected error in replayOddandBet: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Extract bet ID from confirmation
     */



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
     *
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
                        .setLocale("en-US")
//                        .setViewportSize(viewportSize)
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
                        .setLocale("en-US")
//                .setViewportSize(profile.getViewport().getWidth(), profile.getViewport().getHeight())
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

    private <T> T withLocatorRetry(Page page, String selector, Function<Locator, T> action,
                                   int maxRetries, long timeoutPerAttemptMs, long delayMs) {
        Locator locator = page.locator(selector);
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return action.apply(locator);  // e.g., locator::click, locator::textContent, etc.
            } catch (TimeoutError te) {
                log.warn("Timeout attempt {} on '{}'", attempt, selector);
//                if (attempt == maxRetries) throw te;
//                page.waitForTimeout(delayMs);
            }
        }
        log.info("returning null for selector {}", selector);
        return null;  // Never reached
    }

    private static void takeMarketScreenshot(Page page, String filenameSuffix) {
        try {
            Path screenshotDir = Paths.get("screenshots", "markets");
            Files.createDirectories(screenshotDir);

            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());

            String filename = String.format("%s-%s-%s.png", timestamp, BOOK_MAKER, filenameSuffix);
            Path screenshotPath = screenshotDir.resolve(filename);

            Locator marketList = page.locator(".m-market-list");
            if (marketList.count() > 0) {
                marketList.screenshot(new Locator.ScreenshotOptions()
                        .setPath(screenshotPath)
                        .setType(ScreenshotType.PNG));
                log.info("📸 Market screenshot saved: {}", screenshotPath);
            } else {
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(screenshotPath)
                        .setFullPage(true)
                        .setType(ScreenshotType.PNG));
                log.info("📸 Full page screenshot saved: {}", screenshotPath);
            }
        } catch (Exception e) {
            log.warn("Failed to take screenshot: {}", e.getMessage());
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

    // Add these methods to both MSportWindow and SportyWindow classes

    /**
     * Stop the betting loop gracefully
     */
    public void stop() {
        log.info("{} {} Stopping {} betting loop...", EMOJI_SHUTDOWN, EMOJI_BET, BOOK_MAKER);
        isRunning.set(false);
    }

    /**
     * Pause the betting loop
     */
    public void pause() {
        log.info("⏸️ {} Pausing {} betting loop...", EMOJI_WARNING, BOOK_MAKER);
        isPaused.set(true);
    }

    /**
     * Resume the betting loop
     */
    public void resume() {
        log.info("▶️ {} Resuming {} betting loop...", EMOJI_SUCCESS, BOOK_MAKER);
        isPaused.set(false);
    }

    /**
     * Check if window is running
     */
    public boolean isRunning() {
        return isRunning.get();
    }

    /**
     * Check if window is paused
     */
    public boolean isPaused() {
        return isPaused.get();
    }

    /**
     * Get window status
     */
    public WindowStatus getStatus() {
        return WindowStatus.builder()
                .bookmaker(BOOK_MAKER)
                .isRunning(isRunning.get())
                .isPaused(isPaused.get())
                .isLoggedIn(isLoggedIn.get())
                .queueSize(taskQueue.size())
                .build();
    }

    public boolean isWindowUpAndRunning() {
        return !isPaused.get() && isWindowUpAndRunning.get();
    }

//    public void setWindowUpAndRunning(boolean windowUpAndRunning) {
//        this.windowUpAndRunning = windowUpAndRunning;
//    }

    /**
     * Window status data class
     */
    @Builder
    @Data
    public static class WindowStatus {
        private BookMaker bookmaker;
        private boolean isRunning;
        private boolean isPaused;
        private boolean isLoggedIn;
        private int queueSize;
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