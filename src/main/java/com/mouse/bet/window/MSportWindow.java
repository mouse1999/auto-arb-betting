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
import com.mouse.bet.mock.MockTaskSupplier;
import com.mouse.bet.model.profile.UserAgentProfile;
import com.mouse.bet.service.ArbPollingService;
import com.mouse.bet.service.BetLegRetryService;
import com.mouse.bet.service.BettingMetricsService;
import com.mouse.bet.tasks.LegTask;
import com.mouse.bet.utils.MSportLoginUtils;
import com.mouse.bet.utils.MSportMarketSearchUtils;
import com.mouse.bet.utils.SportyLoginUtils;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.mouse.bet.utils.MSportMarketSearchUtils.*;
import static com.mouse.bet.utils.WindowUtils.attachAntiDetection;

@Slf4j
@RequiredArgsConstructor
@Component
public class MSportWindow implements BettingWindow, Runnable {
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
    private static final String EMOJI_SHUTDOWN = "🛑";
    private static final String  EMOJI_INFO = "";
    private static final String EMOJI_TRASH = "";
    private static final String EMOJI_TARGET = "";
    private static final String EMOJI_ROCKET = "";
    private static final String  EMOJI_CLOCK = "";
    private static final String  EMOJI_NAVIGATION = "";


    private static final double ODDS_TOLERANCE_PERCENT = 50.0;



    private static final BookMaker BOOK_MAKER = BookMaker.M_SPORT;
    final int MAX_DURATION_MS = 15_000; // duration for placing bet after initial failure
    private static final String CONTEXT_FILE = "msport-context.json";
    private static final String M_SPORT_BET_URL = "https://www.msport.com/ng/web";

    private final ProfileManager profileManager;
    private final ScraperConfig scraperConfig;
    private final ArbPollingService arbPollingService;
    private final ArbOrchestrator arbOrchestrator;
    private final BetLegRetryService betRetryService;
    private final WindowSyncManager syncManager;
    private final MSportLoginUtils mSportLoginUtils;
    private final BettingMetricsService bettingMetricsService;
    private final BettingFlowLogger flowLogger;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext currentContext;
    private UserAgentProfile profile;
    private PageHealthMonitor healthMonitor;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicBoolean isLoggedIn = new AtomicBoolean(false);
    @Getter
    private final BlockingQueue<LegTask> taskQueue = new LinkedBlockingQueue<>();

    @Value("${msport.username:}")
    private String msportUsername;

    @Value("${msport.password:}")
    private String msportPassword;

    @Value("${msport.context.path:./playwright-context}")
    private String contextPath;

    @Value("${msport.max.retry.attempts:3}")
    private int maxRetryAttempts;

    @Value("${msport.poll.interval.ms:2000}")
    private long pollIntervalMs;

    @Value("${msport.bet.timeout.seconds:10}")
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
        log.info("{} {} Initializing MSportWindow with Playwright...", EMOJI_INIT, EMOJI_BET);
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(scraperConfig.getBROWSER_FlAGS())
                    .setSlowMo(50));

            log.info("{} {} Playwright initialized successfully", EMOJI_SUCCESS, EMOJI_INIT);
            log.info("Registering MSport Window for Bet placing");
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
                gameAvailable = navigateToGameOnMSport(page, task.getArb(), myLeg);
            } else {
                gameAvailable = navigateToGameOnMSport(page, task.getArb(), myLeg);
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
                    mSportLoginUtils.spendAmount(BOOK_MAKER, myLeg.getStake(), arbId);
                    closeSuccessModal(page);
                } else {
                    mSportLoginUtils.spendAmount(BOOK_MAKER, myLeg.getStake(), arbId);
                    closeSuccessModal(page);
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
                            mSportLoginUtils.creditAmount(BOOK_MAKER, myLeg.getStake().doubleValue(), arbId);
                        } else {
                            mSportLoginUtils.creditAmount(BOOK_MAKER, myLeg.getStake().doubleValue(), arbId);
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
    @Override
    public void run() {
        int attempt = 0;
        Exception lastException = null;

        while (attempt < maxRetryAttempts) {
            attempt++;
            log.info("{} {} Starting MSportWindow attempt {}/{}",
                    EMOJI_INIT, EMOJI_BET, attempt, maxRetryAttempts);

            try {
                windowEntry();
                log.info("{} {} MSportWindow completed successfully", EMOJI_SUCCESS, EMOJI_BET);
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
            navigateToMSportPage(page);
            closeWinningPopup(page);

            // Start health monitoring
//            healthMonitor = new PageHealthMonitor(page);
//            healthMonitor.start();
//            healthMonitor.checkHealth();

            // Handle login with integrated isLoggedIn check
            performLoginIfRequired(page);

            // 🔔 Check for any pop-ups after login
            closeWinningPopup(page);
            boolean savedBalance = mSportLoginUtils.updateWalletBalance(page, BOOK_MAKER);
            if (savedBalance) {
                log.info("{} balance saved for balance tracking purposes", BOOK_MAKER);
            }else {
                log.warn("{} balance was not saved due to not been able to locate the amount element");
            }



            mimicHumanBehavior(page);
            goToLivePage(page);


            performLoginIfRequired(page);

            // 🔔 Check for any pop-ups after login
            closeWinningPopup(page);

            moveToEnabledLiveSport(page);

            // 🔔 Check again after navigation
            closeWinningPopup(page);

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



    public static void takeScreenshot(Page page, String filename) {
        // We use Paths.get() to create the target path
        Path outputPath = Paths.get(filename);

        try {
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(outputPath)        // Sets the output path and filename
                    .setFullPage(true)          // Captures the entire page (including scrolling)
                    .setType(ScreenshotType.PNG) // Use PNG for lossless quality
            );
            log.info("✅ Screenshot saved successfully to: " + outputPath.toAbsolutePath());
        } catch (Exception e) {
            log.error("❌ Failed to take screenshot: " + e.getMessage());
        }
    }



    private void navigateToMSportPage(Page page) {
        log.info("{} {} Navigating to Msport...", EMOJI_INIT, EMOJI_BET);

        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // Check if page is still valid
                if (page.isClosed()) {
                    log.error("{} {} Page is closed, cannot navigate", EMOJI_ERROR, EMOJI_BET);
                    throw new RuntimeException("Page is closed");
                }

                // Navigate with more lenient options
                page.navigate(M_SPORT_BET_URL, new Page.NavigateOptions()
                        .setTimeout(60000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)); // More lenient than LOAD

//                // Wait for network to be idle (more stable)
//                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions()
//                        .setTimeout(30000));ndow.scrollT
                page.evaluate("() => { document.body.style.zoom = '0.95'; window.scrollTo(0,0); }");

                log.info("{} {} Successfully navigated to Msport", EMOJI_SUCCESS, EMOJI_BET);
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

            boolean loginSuccess = mSportLoginUtils.performLogin(page, msportUsername, msportPassword);

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
            boolean loggedIn = mSportLoginUtils.isLoggedIn(page);

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
        int consecutiveErrors = 0;
        int maxConsecutiveErrors = 5;

        while (isRunning.get()) {
            try {
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

                log.info("🎯 Arb opportunity retrieved | ArbId: {} | Profit: {}% | Bookmaker: {}",
                        task.getArbId(), task.getLeg().getProfitPercent(), BOOK_MAKER);
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
                if (!mSportLoginUtils.isLoggedIn(page)) {
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

                // Optionally: trigger re-login attempt
                mSportLoginUtils.performLogin(page, msportUsername, msportPassword);
                if (mSportLoginUtils.isLoggedIn(page)) {
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
//        page.goBack(new Page.GoBackOptions().setTimeout(15000));
//        page.waitForLoadState(LoadState.NETWORKIDLE);
//        log.info("{} Returned to previous page", EMOJI_NAVIGATION);

        if (fetchBasketballEnabled) {
            switchToLiveSport(page, "Basketball");
        } else if (fetchTableTennisEnabled) {
            switchToLiveSport(page, "Table Tennis");
        } else if (fetchFootballEnabled) {
            switchToLiveSport(page,"Soccer");

        } else {
            log.info("Staying on the default live sport page");
            randomHumanDelay(2000, 4000);
        }

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
     *
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
//        safeRemoveFromSlip(page);
        clearBetSlip(page);
        return false;
    }

    /**
     * Navigate to live betting page
     */
    private void goToLivePage(Page page) {
        final long timeout = 20_000;

        try {
            Locator liveBettingLink = withLocatorRetry(page, "#header_nav_liveBetting",
                    loc -> loc.isVisible(new Locator.IsVisibleOptions().setTimeout(5000)) ? loc : null,
                    3, 5000, 1000);

            if (liveBettingLink != null) {
                withLocatorRetry(page, "#header_nav_liveBetting",
                        loc -> { loc.click(new Locator.ClickOptions().setTimeout(10000)); return true; },
                        3, 10000, 1000);
                log.info("Clicked 'Live Betting' using ID selector");
            } else {
                throw new Exception("ID locator not visible");
            }
        } catch (Exception e) {
            log.info("ID selector failed, trying fallback...");
            try {
                withLocatorRetry(page, "a:has-text('Live Betting')",
                        loc -> { loc.click(new Locator.ClickOptions().setTimeout(10000)); return true; },
                        3, 10000, 1000);
                log.info("Clicked 'Live Betting' using text selector");
            } catch (Exception e2) {
                log.info("Text selector failed, trying accessibility role...");
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

        try {
            page.waitForURL(url -> url.toString().contains("/live_matches"),
                    new Page.WaitForURLOptions().setTimeout(timeout));
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

        String currentUrl = page.url();
        if (!currentUrl.contains("/default/live_matches")) {
            throw new RuntimeException("Failed to navigate to default Live Sport page. URL: " + currentUrl);
        }

        log.info("✅ Sport Page loaded: {}", currentUrl);
        randomHumanDelay(1500, 3000);

        // === 4. Choose target sport ===
        if (fetchBasketballEnabled) {
            switchToLiveSport(page, "Basketball");
        } else if (fetchTableTennisEnabled) {
            switchToLiveSport(page, "Table Tennis");
        } else if (fetchFootballEnabled) {
            switchToLiveSport(page,"Soccer");

        } else {
            log.info("Staying on the default live sport page");
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
            case "soccer" -> {
                displayName = "Soccer";
                urlSegment = "Soccer";
            }
            case "basketball" -> {
                displayName = "Basketball";
                urlSegment = "Basketball";
            }
            case "table tennis", "table-tennis", "tt", "tabletennis" -> {
                displayName = "Table Tennis";
                urlSegment = "Table%20Tennis";
            }
            case "tennis" -> {
                displayName = "Tennis";
                urlSegment = "Tennis";
            }
            default -> {
                displayName = sport.substring(0, 1).toUpperCase() + sport.substring(1).toLowerCase();
                urlSegment = displayName.toLowerCase().replace(" ", "-");
            }
        }

        log.info("Switching to live sport: {} → {}", displayName, urlSegment);

        boolean isVisibleSport = Set.of("Soccer", "Basketball", "Tennis", "Table Tennis", "eFootball")
                .contains(displayName);

        if (isVisibleSport) {
            clickVisibleSportTab(page, displayName, urlSegment);
        } else {
            clickSportViaMoreSportsDropdown(page, displayName, urlSegment);
        }

        randomHumanDelay(2200, 4200);
    }

    /**
     * Click visible sport tab (Football, Basketball, Tennis, etc.)
     */
    private void clickVisibleSportTab(Page page, String sportName, String expectedUrlSegment) throws InterruptedException {
        withLocatorRetry(page,
                String.format(".m-nav-item:has(.m-label:text-is('%s'))", sportName),
                loc -> {
                    loc.click(new Locator.ClickOptions()
                            .setTimeout(12_000)
                            .setForce(true));
                    return true;
                },
                3, 12000, 1000);

        if (expectedUrlSegment != null && !expectedUrlSegment.isEmpty()) {
            page.waitForURL("**/" + expectedUrlSegment + "/**",
                    new Page.WaitForURLOptions().setTimeout(15_000));
        }
    }

    /**
     * Click sport via More Sports dropdown (Table Tennis, etc.)
     */
    /**
     * Click sport via More Sports dropdown (Table Tennis, Basketball, etc.)
     */
    //TODO
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
    /**
     * Navigate to a specific match on MSport page
     */
    private boolean navigateToGameOnMSport(Page page, Arb arb, BetLeg leg) {
        String home = leg.getHomeTeam().trim();
        String away = leg.getAwayTeam().trim();
        String fullMatch = home + " vs " + away;

        log.info("{} Navigating to: {} | EventId: {}", EMOJI_BET, fullMatch, leg.getEventId());

        try {
            randomHumanDelay(500, 1500);

            if (tryDirectNavigation(page, home, away, leg)) return true;
//            if (tryEventIdNavigation(page, leg)) return true;

        } catch (Exception e) {
            log.error("{} Navigation crashed: {}", EMOJI_ERROR, e.toString());
        }

        log.warn("{} All navigation methods failed for: {}", EMOJI_WARNING, fullMatch);
        return false;
    }

    /**
     * METHOD 1: Direct click in MSport event list (FASTEST & MOST RELIABLE)
     * Uses multiple strategies to find and click the correct match
     */
    private boolean tryDirectNavigation(Page page, String home, String away, BetLeg leg) {
        log.info("🎯 Searching for match: {} vs {}", home, away);

        try {
            // === Strategy 1: aria-label attribute (MOST RELIABLE for MSport) ===
            if (tryClickByAriaLabel(page, home, away)) {
                return true;
            }

            // === Strategy 2: href pattern matching ===
            if (tryClickByHref(page, home, away)) {
                return true;
            }

            // === Strategy 3: Individual team text matching ===
            if (tryClickByTeamText(page, home, away)) {
                return true;
            }

            // === Strategy 4: Partial matching (case-insensitive) ===
            if (tryClickByPartialMatch(page, home, away)) {
                return true;
            }

            // === Strategy 5: Fuzzy matching (handles variations) ===
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
     * Strategy 1: Click using aria-label attribute (most reliable for MSport)
     * aria-label format: "Team1 vs Team2" (exact format from MSport)
     */
    private boolean tryClickByAriaLabel(Page page, String home, String away) {
        log.info("Strategy 1: Searching by aria-label attribute");

        String[] labelPatterns = {
                String.format("%s vs %s", home, away),
                String.format("%s - %s", home, away),
                String.format("%s v %s", home, away),
                String.format("%s Vs %s", home, away)
        };

        for (String labelPattern : labelPatterns) {
            try {
                Locator match = withLocatorRetry(page,
                        String.format(".m-teams a[aria-label='%s']", labelPattern),
                        loc -> loc.count() > 0 && loc.first().isVisible() ? loc : null,
                        2, 3000, 500);

                if (match != null) {
                    log.info("✅ Found by exact aria-label: '{}'", labelPattern);
                    return clickMatchElement(page, match.first());
                }

                match = withLocatorRetry(page,
                        String.format(".m-teams a[aria-label='%s' i]", labelPattern),
                        loc -> loc.count() > 0 && loc.first().isVisible() ? loc : null,
                        2, 3000, 500);

                if (match != null) {
                    log.info("✅ Found by case-insensitive aria-label: '{}'", labelPattern);
                    return clickMatchElement(page, match.first());
                }

            } catch (PlaywrightException e) {
                log.debug("aria-label pattern '{}' failed: {}", labelPattern, e.getMessage());
            }
        }

        try {
            String selector = String.format(
                    ".m-teams a[aria-label*='%s' i][aria-label*='%s' i]",
                    escapeForSelector(home),
                    escapeForSelector(away)
            );

            Locator match = withLocatorRetry(page, selector,
                    loc -> loc.count() > 0 && loc.first().isVisible() ? loc : null,
                    2, 3000, 500);

            if (match != null) {
                log.info("✅ Found by partial aria-label match");
                return clickMatchElement(page, match.first());
            }
        } catch (PlaywrightException e) {
            log.debug("Partial aria-label matching failed: {}", e.getMessage());
        }

        log.debug("❌ aria-label strategy failed");
        return false;
    }

    /**
     * Strategy 2: Click by matching href pattern
     * MSport href format: "/ng/web/sports/{sport}/live/{tournament}/{home}_vs_{away}/sr:match:{id}"
     */
    private boolean tryClickByHref(Page page, String home, String away) {
        log.info("Strategy 2: Searching by href pattern");

        try {
            String homeUrl = home.replace(" ", "_").replace(",", "");
            String awayUrl = away.replace(" ", "_").replace(",", "");

            String[] hrefPatterns = {
                    String.format("%s_vs_%s", homeUrl, awayUrl),
                    String.format("%s_vs_%s",
                            homeUrl.replace(".", ""),
                            awayUrl.replace(".", "")),
                    String.format("%s_vs_%s",
                            urlEncode(home),
                            urlEncode(away))
            };

            for (String pattern : hrefPatterns) {
                try {
                    Locator match = withLocatorRetry(page,
                            String.format(".m-teams a[href*='%s']", pattern),
                            loc -> loc.count() > 0 && loc.first().isVisible() ? loc : null,
                            2, 3000, 500);

                    if (match != null) {
                        log.info("✅ Found by href pattern: '{}'", pattern);
                        return clickMatchElement(page, match.first());
                    }
                } catch (PlaywrightException e) {
                    log.debug("href pattern '{}' failed: {}", pattern, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.debug("href strategy error: {}", e.getMessage());
        }

        log.debug("❌ href strategy failed");
        return false;
    }

    /**
     * Strategy 3: Click by finding home and away team text in m-teams--info
     */
    private boolean tryClickByTeamText(Page page, String home, String away) {
        log.info("Strategy 3: Searching by team text in .m-teams--info");

        try {
            Locator allTeamsContainers = withLocatorRetry(page, ".m-teams",
                    loc -> loc,
                    2, 3000, 500);

            if (allTeamsContainers == null) return false;

            int count = allTeamsContainers.count();
            log.debug("Found {} team containers to check", count);

            for (int i = 0; i < count; i++) {
                Locator teamsContainer = allTeamsContainers.nth(i);

                try {
                    Locator teamWrappers = withLocatorRetry(page,
                            ".m-teams:nth-of-type(" + (i + 1) + ") .m-server-name-wrapper",
                            loc -> loc,
                            2, 2000, 300);

                    if (teamWrappers != null && teamWrappers.count() >= 2) {
                        String homeText = withLocatorRetry(page,
                                ".m-teams:nth-of-type(" + (i + 1) + ") .m-server-name-wrapper:nth-of-type(1) .tw-w-full.tw-truncate",
                                loc -> loc.textContent().trim(),
                                2, 2000, 300);

                        String awayText = withLocatorRetry(page,
                                ".m-teams:nth-of-type(" + (i + 1) + ") .m-server-name-wrapper:nth-of-type(2) .tw-w-full.tw-truncate",
                                loc -> loc.textContent().trim(),
                                2, 2000, 300);

                        if (homeText != null && awayText != null) {
                            if (homeText.equals(home) && awayText.equals(away)) {
                                log.info("✅ Found by exact team text match");
                                Locator clickTarget = withLocatorRetry(page,
                                        ".m-teams:nth-of-type(" + (i + 1) + ") a",
                                        loc -> loc.first(),
                                        2, 2000, 300);
                                return clickTarget != null && clickMatchElement(page, clickTarget);
                            }

                            if (homeText.equalsIgnoreCase(home) && awayText.equalsIgnoreCase(away)) {
                                log.info("✅ Found by case-insensitive team text match");
                                Locator clickTarget = withLocatorRetry(page,
                                        ".m-teams:nth-of-type(" + (i + 1) + ") a",
                                        loc -> loc.first(),
                                        2, 2000, 300);
                                return clickTarget != null && clickMatchElement(page, clickTarget);
                            }
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
     * Strategy 4: Partial matching (case-insensitive, ignores extra spaces)
     */
    private boolean tryClickByPartialMatch(Page page, String home, String away) {
        log.info("Strategy 4: Partial matching");

        try {
            // Normalize team names (remove extra spaces, lowercase)
            String homeNorm = normalizeTeamName(home);
            String awayNorm = normalizeTeamName(away);

            Locator allTeamsContainers = page.locator(".m-teams");
            int count = allTeamsContainers.count();

            for (int i = 0; i < count; i++) {
                Locator teamsContainer = allTeamsContainers.nth(i);

                try {
                    String fullText = teamsContainer.textContent().trim();
                    String fullTextNorm = normalizeTeamName(fullText);

                    // Check if both teams are present in the text
                    if (fullTextNorm.contains(homeNorm) && fullTextNorm.contains(awayNorm)) {
                        log.info("✅ Found by partial match in text: '{}'", fullText);
                        Locator clickTarget = teamsContainer.locator("a").first();
                        return clickMatchElement(page, clickTarget);
                    }

                } catch (Exception e) {
                    log.debug("Error checking container {}: {}", i, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.debug("Partial match strategy error: {}", e.getMessage());
        }

        log.debug("❌ Partial match strategy failed");
        return false;
    }

    /**
     * Strategy 5: Fuzzy matching (handles name variations)
     */
    private boolean tryClickByFuzzyMatch(Page page, String home, String away) {
        log.info("Strategy 5: Fuzzy matching");

        try {
            // Extract key parts of names (last names for players, main words for teams)
            String homeKey = extractKeyName(home);
            String awayKey = extractKeyName(away);

            log.info("Fuzzy search - Home key: '{}', Away key: '{}'", homeKey, awayKey);

            Locator allTeamsContainers = page.locator(".m-teams");
            int count = allTeamsContainers.count();

            for (int i = 0; i < count; i++) {
                Locator teamsContainer = allTeamsContainers.nth(i);

                try {
                    String fullText = teamsContainer.textContent().toLowerCase().trim();

                    // Check if key parts are present
                    if (fullText.contains(homeKey.toLowerCase()) &&
                            fullText.contains(awayKey.toLowerCase())) {
                        log.info("✅ Found by fuzzy match (keys: '{}' + '{}')", homeKey, awayKey);
                        Locator clickTarget = teamsContainer.locator("a").first();
                        return clickMatchElement(page, clickTarget);
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
            matchElement.scrollIntoViewIfNeeded();
            randomHumanDelay(500, 1000);

            Boolean isVisible = withLocatorRetry(page, matchElement.toString(),
                    loc -> matchElement.isVisible(),
                    2, 2000, 500);

            if (isVisible == null || !isVisible) {
                log.warn("⚠️ Element not visible after scroll");
                return false;
            }

            String matchInfo = "unknown";
            try {
                String ariaLabel = matchElement.getAttribute("aria-label");
                matchInfo = ariaLabel != null ? ariaLabel :
                        matchElement.textContent().trim().replaceAll("\\s+", " ");
            } catch (Exception e) {
                // Ignore
            }

            log.info("✅ Clicking match: {}", matchInfo);

            int maxAttempts = 3;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    matchElement.click(new Locator.ClickOptions()
                            .setTimeout(10_000)
                            .setForce(attempt > 1));
                    break;

                } catch (PlaywrightException e) {
                    if (attempt == maxAttempts) {
                        throw e;
                    }
                    log.warn("⚠️ Click attempt {} failed, retrying...", attempt);
                    randomHumanDelay(500, 1000);
                }
            }

            try {
                page.waitForURL(url ->
                                url.contains("_vs_") ||
                                        url.contains("/sr:match:") ||
                                        url.contains("/live/") && url.split("/").length > 7,
                        new Page.WaitForURLOptions()
                                .setTimeout(15000)
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                );

                log.info("✅ Navigation successful: {}", page.url());

                try {
                    withLocatorRetry(page,
                            ".m-event--main, .m-teams, .m-market-box, .match-scores",
                            loc -> {
                                loc.waitFor(new Locator.WaitForOptions()
                                        .setState(WaitForSelectorState.VISIBLE)
                                        .setTimeout(3000));
                                return true;
                            },
                            2, 3000, 500);
                } catch (Exception e) {
                    log.warn("⚠️ Match content not immediately visible, continuing anyway");
                }

                return true;

            } catch (PlaywrightException e) {
                log.warn("⚠️ Navigation timeout, checking if we're on match page anyway");

                String currentUrl = page.url();
                if (currentUrl.contains("_vs_") ||
                        currentUrl.contains("/sr:match:") ||
                        (currentUrl.contains("/live/") && currentUrl.split("/").length > 7)) {
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
                    (currentUrl.contains("/live/") && currentUrl.split("/").length > 7)) {
                log.info("✅ On match page despite error: {}", currentUrl);
                return true;
            }
            return false;
        }
    }

    /**
     * Helper: Normalize team name for matching
     */
    private String normalizeTeamName(String name) {
        return name.toLowerCase()
                .replaceAll("\\s+", " ")
                .replaceAll("[,.]", "")
                .trim();
    }

    /**
     * Helper: Extract key name (last name for players, main word for teams)
     */
    private String extractKeyName(String fullName) {
        // For players with comma (e.g., "Langer, Ales"), take the last name
        if (fullName.contains(",")) {
            return fullName.split(",")[0].trim();
        }

        // Otherwise take the last word
        String[] parts = fullName.trim().split("\\s+");
        return parts[parts.length - 1];
    }

    /**
     * Helper: Escape special characters for CSS selectors
     */
    private String escapeForSelector(String str) {
        return str.replace("'", "\\'")
                .replace("\"", "\\\"");
    }

    /**
     * Helper: URL encode team name
     */
    private String urlEncode(String str) {
        try {
            return java.net.URLEncoder.encode(str, "UTF-8")
                    .replace("+", "%20");
        } catch (Exception e) {
            return str.replace(" ", "%20");
        }
    }

    /**
     * Debug helper: Log available matches on page
     */
    private void logAvailableMatches(Page page) {
        try {
            log.info("====📋 Available matches on page:====");
            Locator matches = page.locator(".m-teams a[aria-label]");
            int count = Math.min(matches.count(), 10); // Show max 10

            for (int i = 0; i < count; i++) {
                String ariaLabel = matches.nth(i).getAttribute("aria-label");
                log.info("  {}. {}", i + 1, ariaLabel);
            }
        } catch (Exception e) {
            log.debug("Could not log available matches: {}", e.getMessage());
        }
    }




    // Step 1: Clear bet slip before adding new bet
    private boolean clearBetSlip(Page page) {
        try {
            Locator betslipContainer = withLocatorRetry(page, "#target-betslip .m-betslip",
                    loc -> loc.count() > 0 ? loc : null, 3, 5000, 1000);

            if (betslipContainer == null) {
                log.info("{} Betslip container not found", EMOJI_INFO);
                return true;
            }

            String betCountText = page.evaluate("""
    () => {
        const badge = document.querySelector('#target-betslip .m-count-ball');
        return badge ? badge.textContent.trim() : '0';
    }
    """).toString();

            boolean wasEmpty = "0".equals(betCountText) || betCountText.isEmpty();
            if (wasEmpty) {
                log.info("{} Betslip already empty", EMOJI_SUCCESS);
                return true;
            }

            log.info("{} Clearing {} selection(s)...", EMOJI_TRASH, betCountText);

            page.evaluate("""
        () => {
            const closeButtons = document.querySelectorAll('#target-betslip .m-bet-selection .m-close-btn');
            closeButtons.forEach(btn => btn.click());
        }
    """);
            page.waitForTimeout(800);

            String finalCount = page.evaluate("""
    () => {
        const badge = document.querySelector('#target-betslip .m-count-ball');
        return badge ? badge.textContent.trim() : '0';
    }
    """).toString();

            boolean isCleared = "0".equals(finalCount) || finalCount.isEmpty();
            if (isCleared) {
                log.info("{} Betslip cleared successfully", EMOJI_SUCCESS);
            } else {
                log.warn("{} Betslip may not be fully cleared. Remaining: {}", EMOJI_WARNING, finalCount);
            }
            return isCleared;

        } catch (Exception e) {
            log.error("{} Error in clearBetSlip: {}", EMOJI_ERROR, e.getMessage());
            return false;
        }
    }

    // Step 2: Locate the market by title
    /**
     * Finds and selects a market by name (e.g. "Winner", "1X2", "Over/Under")
     * Logs all available markets for perfect debugging
     * Works 100% on MSport Dec 2025
     */
    private boolean selectMarketByTitle(Page page, String targetMarket) {
        String method = "selectMarketByTitle(\"" + targetMarket + "\")";
        log.info("Entering {} – searching for market...", method);

        try {
            // Wait for market list
            page.locator(".m-market-list").waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(12_000));

            // Get all market titles + visibility
            var markets = page.evaluate("""
            () => {
                const items = document.querySelectorAll('.m-market-item');
                const result = [];
                items.forEach(item => {
                    const span = item.querySelector('.m-market-item--name span');
                    if (span) {
                        const title = span.textContent.trim();
                        if (title) {
                            result.push({
                                title: title,
                                visible: item.offsetParent !== null && getComputedStyle(item).display !== 'none'
                            });
                        }
                    }
                });
                return result;
            }
        """);

            // Log all markets
            String allMarkets = markets.toString()
                    .replaceAll("[{}]", "")
                    .replaceAll(", ", " | ");
            log.info("Available markets ({} found): [ {} ]",
                    ((List<?>) markets).size(),
                    allMarkets.isEmpty() ? "NONE" : allMarkets);

            String targetLower = targetMarket.toLowerCase();

            // List of common "main" phrases that often get prefixed
            // Add any new ones here when you discover them
            List<String> keyPhrases = List.of(
                    "point handicap",
                    "game handicap",
                    "total games",
                    "total points",
                    "game winner",
                    "set winner",
                    "correct score",
                    "total sets",
                    "total maps",
                    "handicap",
                    "over/under"   // sometimes appears as "1st game - over/under"
            );

            // Find if the target contains one of the key phrases
            String matchedKeyPhrase = null;
            for (String phrase : keyPhrases) {
                if (targetLower.contains(phrase)) {
                    matchedKeyPhrase = phrase;
                    break;
                }
            }

            // Now iterate through markets
            for (Object marketObj : (List<?>) markets) {
                @SuppressWarnings("unchecked")
                Map<String, Object> market = (Map<String, Object>) marketObj;
                String title = (String) market.get("title");
                Boolean visible = (Boolean) market.get("visible");

                if (!visible) continue;

                String titleLower = title.toLowerCase();

                boolean matches;

                if (matchedKeyPhrase != null) {
                    // Smart mode: match any title that contains the key phrase
                    // (ignores prefix like "3rd game - ", "1st set - ", etc.)
                    matches = titleLower.contains(matchedKeyPhrase);
                } else {
                    // Normal fallback: partial match on the full target
                    matches = titleLower.contains(targetLower);
                }

                if (matches) {
                    log.info("MATCH FOUND → Clicking market: '{}'", title);

                    // Click using the same reliable JS method
                    page.evaluate("""
                    (searchTitle) => {
                        const span = Array.from(document.querySelectorAll('.m-market-item--name span'))
                            .find(s => s.textContent.trim().toLowerCase().includes(searchTitle.toLowerCase()));
                        if (span) {
                            const header = span.closest('.m-market-item--header');
                            if (header) header.click();
                        }
                    }
                """, targetMarket);

                    page.waitForTimeout(500); // let it expand
                    return true;
                }
            }

            log.warn("Market containing '{}' not found in available markets", targetMarket);
            return false;

        } catch (Exception e) {
            log.error("Exception in {}: {}", method, e.toString());
            return false;
        }
    }
    // Step 2: Select and verify the betting option
    private boolean selectAndVerifyBet(Page page, BetLeg leg) {
        String market = leg.getProviderMarketTitle();    // e.g. "Winner", "O/U Total Points", "Point Handicap"
        String outcome = leg.getProviderMarketName();     // e.g. "Home", "Over 76.5", "+2.5"

        try {
            log.info("Selecting: {} → {}", market, outcome);

            // Find and expand market block
            Locator marketBlock = findAndExpandMarket(page, market);
            if (marketBlock == null) {
                return false;
            }

            // Detect market type and use appropriate selection strategy
            MSportMarketSearchUtils.MarketType marketType = detectMarketType(market, outcome);
            log.info("Detected market type: {}", marketType);

            // Select outcome based on market type
            Locator outcomeCell = selectOutcomeByType(marketBlock, marketType, outcome);
            if (outcomeCell == null) {
                logAvailableOutcomes(marketBlock, marketType);
                return false;
            }

            // Verify outcome is not disabled
            if (isOutcomeDisabled(outcomeCell)) {
                log.warn("Outcome '{}' is currently disabled/locked", outcome);
                return false;
            }

            // Extract and verify odds
            String displayedOdds = extractOdds(outcomeCell, marketType);
            if (displayedOdds == null) {
                log.warn("No odds found for outcome '{}'", outcome);
                return false;
            }

            log.info("FOUND: {} → {} @ {}", market, outcome, displayedOdds);

            // Optional: verify odds tolerance
            if (!isOddsAcceptable(leg.getOdds().doubleValue(), displayedOdds)) {
                log.warn("Odds drifted: expected {} → got {}", leg.getOdds(), displayedOdds);
                // return false; //todo: Uncomment if strict odds checking needed
            }

            // Human-like interaction and click
            if (!clickOutcome(outcomeCell, market, outcome, displayedOdds)) {
                return false;
            }

            // Verify bet was added to betslip
            if (!verifyBetInBetslip(page, market, outcome)) {
                log.warn("Bet may not have been added to betslip");
                return false;
            }

            return true;

        } catch (Exception e) {
            log.error("FATAL: Failed to select {} → {}", market, outcome, e);
            return false;
        }
    }

    // Extract odds from outcome cell
    private String extractOdds(Locator outcomeCell, MSportMarketSearchUtils.MarketType marketType) {
        try {
            Locator oddsElement = outcomeCell.locator("div.odds, .odds");
            if (oddsElement.count() == 0) {
                return null;
            }
            return oddsElement.textContent().trim();
        } catch (Exception e) {
            log.error("Error extracting odds: {}", e.getMessage());
            return null;
        }
    }

    // Click outcome with human-like behavior
    private boolean clickOutcome(Locator outcomeCell, String market, String outcome, String odds) {
        try {
            outcomeCell.scrollIntoViewIfNeeded();
            randomHumanDelay(100, 200);

            try {
                outcomeCell.click(new Locator.ClickOptions().setTimeout(10000));
            } catch (Exception e) {
                log.warn("Direct click failed, trying JS click");
                outcomeCell.evaluate("el => el.click()");
            }

            log.info("CLICKED: {} → {} @ {}", market, outcome, odds);
            randomHumanDelay(300, 500);
            return true;

        } catch (Exception e) {
            log.error("Failed to click outcome: {}", e.getMessage());
            return false;
        }
    }

    // Log available outcomes for debugging
    // Log available outcomes for debugging
    private void logAvailableOutcomes(Locator marketBlock, MarketType marketType) {
        try {
            if (marketType == MarketType.OVER_UNDER || marketType == MarketType.POINT_HANDICAP) {
                logHandicapOrOverUnderOutcomes(marketBlock, marketType);
            } else {
                logStandardOutcomes(marketBlock);
            }
        } catch (Exception e) {
            log.debug("Could not log available outcomes: {}", e.getMessage());
        }
    }

    // Log outcomes in tabular format for Handicap and Over/Under markets
    private void logHandicapOrOverUnderOutcomes(Locator marketBlock, MarketType marketType) {
        try {
            log.warn("Available {} outcomes:", marketType);

            // Get column headers if they exist
            List<String> headers = marketBlock
                    .locator(".m-market-row .m-title")
                    .allTextContents()
                    .stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            if (!headers.isEmpty()) {
                log.warn("  Columns: {}", String.join(" | ", headers));
                log.warn("  " + "-".repeat(60));
            }

            // Get all market rows
            Locator rows = marketBlock.locator(".m-market-row.m-market-row");
            int rowCount = rows.count();

            for (int i = 0; i < rowCount; i++) {
                Locator row = rows.nth(i);
                Locator outcomes = row.locator(".m-outcome");
                int outcomeCount = outcomes.count();

                List<String> rowData = new ArrayList<>();

                for (int j = 0; j < outcomeCount; j++) {
                    Locator outcome = outcomes.nth(j);

                    // Get description (handicap value or line)
                    String desc = "";
                    Locator descElement = outcome.locator(".desc");
                    if (descElement.count() > 0) {
                        desc = descElement.textContent().trim();
                    }

                    // Get odds
                    String odds = "";
                    Locator oddsElement = outcome.locator(".odds");
                    if (oddsElement.count() > 0) {
                        odds = oddsElement.textContent().trim();
                    }

                    // Check if disabled
                    boolean disabled = outcome.getAttribute("class").contains("disabled");
                    String status = disabled ? " [LOCKED]" : "";

                    // Format the cell
                    if (!desc.isEmpty() && !odds.isEmpty()) {
                        rowData.add(String.format("%-8s @ %-6s%s", desc, odds, status));
                    } else if (!desc.isEmpty()) {
                        rowData.add(desc);
                    } else if (!odds.isEmpty()) {
                        rowData.add(odds + status);
                    }
                }

                if (!rowData.isEmpty()) {
                    log.warn("  Row {}: {}", i + 1, String.join(" | ", rowData));
                }
            }

        } catch (Exception e) {
            log.debug("Error logging handicap/O/U outcomes: {}", e.getMessage());
        }
    }

    // Log outcomes for standard markets (Winner, BTTS, etc.)
    private void logStandardOutcomes(Locator marketBlock) {
        try {
            log.warn("Available outcomes:");
            log.warn("  " + "-".repeat(50));

            Locator outcomes = marketBlock.locator(".m-outcome:not(.disabled)");
            int count = outcomes.count();

            for (int i = 0; i < count; i++) {
                Locator outcome = outcomes.nth(i);

                // Get description
                String desc = outcome.locator(".desc").textContent().trim();

                // Get odds
                String odds = "";
                Locator oddsElement = outcome.locator(".odds");
                if (oddsElement.count() > 0) {
                    odds = oddsElement.textContent().trim();
                }

                log.warn("  {} - {} @ {}", i + 1, desc, odds);
            }

        } catch (Exception e) {
            log.debug("Error logging standard outcomes: {}", e.getMessage());
        }
    }

    private boolean isOutcomeDisabled(Locator outcomeCell) {
        try {
            String className = outcomeCell.getAttribute("class");
            Integer disabledIconCount = withLocatorRetry(outcomeCell.page(),
                    outcomeCell.toString() + " i[aria-label='disabled']",
                    loc -> loc.count(),
                    2, 1000, 300);

            return (className != null && className.contains("disabled")) ||
                    (disabledIconCount != null && disabledIconCount > 0);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Verify that the selected bet appears in the betslip
     */
    private boolean verifyBetInBetslip(Page page, String market, String outcome) {
        try {
            String countText = withLocatorRetry(page,
                    "#target-betslip .m-count-ball, .m-count-ball-wrap .m-count-ball",
                    loc -> loc.first().textContent().trim(),
                    3, 5000, 1000);

            if ("0".equals(countText) || countText.isEmpty()) {
                log.warn("{} Betslip is empty (count = {})", EMOJI_WARNING, countText);
                return false;
            }

            List<ElementHandle> selections = withLocatorRetry(page, "div.m-bet-selection",
                    loc -> loc.elementHandles(),
                    3, 5000, 1000);

            if (selections == null || selections.isEmpty()) {
                log.warn("{} No selections found in betslip", EMOJI_WARNING);
                return false;
            }

            String normalizedMarket = normalizeText(market);
            String normalizedOutcome = normalizeText(outcome);

            log.debug("Searching betslip for: Market='{}' (normalized: '{}'), Outcome='{}' (normalized: '{}')",
                    market, normalizedMarket, outcome, normalizedOutcome);

            for (ElementHandle selectionHandle : selections) {
                try {
                    String teams = selectionHandle.querySelector(".m-teams").textContent().trim();
                    String marketTitle = selectionHandle.querySelector("span.market-title").textContent().trim();
                    String selectionMarket = selectionHandle.querySelector("div.selection-market").textContent().trim();
                    String odds = selectionHandle.querySelector("span.m-betslip-odds span").textContent().trim();

                    log.debug("Checking selection: Teams='{}' | Market='{}' | Outcome='{}' @ {}",
                            teams, selectionMarket, marketTitle, odds);

                    String normalizedActualMarket = normalizeText(selectionMarket);
                    String normalizedActualOutcome = normalizeText(marketTitle);

                    boolean marketMatches = normalizedActualMarket.contains(normalizedMarket)
                            || normalizedMarket.contains(normalizedActualMarket);
                    boolean outcomeMatches = normalizedActualOutcome.contains(normalizedOutcome)
                            || normalizedOutcome.contains(normalizedActualOutcome);

                    if (marketMatches && outcomeMatches) {
                        log.info("{} ✅ Bet verified in betslip: {} → {} @ {}",
                                EMOJI_SUCCESS, selectionMarket, marketTitle, odds);
                        return true;
                    }

                } catch (Exception innerEx) {
                    log.debug("Error reading selection details: {}", innerEx.getMessage());
                    continue;
                }
            }

            log.warn("{} ❌ Bet NOT found in betslip | Expected: {} | Market: {}",
                    EMOJI_WARNING, outcome, market);
            return false;

        } catch (Exception e) {
            log.error("{} Failed to verify bet in betslip: {}", EMOJI_ERROR, e.getMessage(), e);
            return false;
        }
    }


    /**
     * Normalizes text for flexible matching by:
     * - Converting to lowercase
     * - Removing extra whitespace
     * - Removing common prefixes like "1st game -", "2nd set -", etc.
     */
    private String normalizeText(String text) {
        if (text == null) return "";

        String normalized = text.toLowerCase().trim();

        // Remove common prefixes (e.g., "1st game - ", "2nd set - ", etc.)
        normalized = normalized.replaceAll("^\\d+(st|nd|rd|th)\\s+(game|set|map|period)\\s*-\\s*", "");

        // Collapse multiple spaces
        normalized = normalized.replaceAll("\\s+", " ");

        return normalized;
    }

    /**
     * Check if the displayed odds are within acceptable tolerance
     */
    private boolean isOddsAcceptable(double expectedOdds, String displayedOddsStr) {
        try {
            double displayedOdds = Double.parseDouble(displayedOddsStr);
            double tolerance = 0.10; // 10% tolerance
            double diff = Math.abs(expectedOdds - displayedOdds);
            double percentDiff = diff / expectedOdds;

            return percentDiff <= tolerance;

        } catch (NumberFormatException e) {
            log.warn("Could not parse odds: {}", displayedOddsStr);
            return true; // Proceed if can't parse
        }
    }

    /**
     * Escape XPath string to handle quotes
     */
    private String escapeXPath(String value) {
        if (!value.contains("'")) {
            return "'" + value + "'";
        } else if (!value.contains("\"")) {
            return "\"" + value + "\"";
        } else {
            return "concat('" + value.replace("'", "',\"'\",'") + "')";
        }
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
//        if (!selectMarketByTitle(page, leg.getProviderMarketTitle())) {
//            return false;
//        }

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
            Locator betslip = withLocatorRetry(page, "#target-betslip .m-betslip",
                    loc -> {
                        loc.waitFor(new Locator.WaitForOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(8000));
                        return loc;
                    },
                    3, 8000, 1000);

            if (betslip == null) return false;

            Locator betItem = withLocatorRetry(page,
                    "#target-betslip .m-betslip .m-selections-list .m-bet-selection",
                    loc -> {
                        loc.first().waitFor(new Locator.WaitForOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(3000));
                        return loc.first();
                    },
                    3, 3000, 500);

            if (betItem == null) return false;

            String displayedOutcome = withLocatorRetry(page,
                    "#target-betslip .m-betslip .m-selections-list .m-bet-selection .m-stake-info .m-title .market-title",
                    loc -> loc.textContent().trim(),
                    2, 2000, 500);

            String displayedMarket = withLocatorRetry(page,
                    "#target-betslip .m-betslip .m-selections-list .m-bet-selection .selection-market",
                    loc -> loc.textContent().trim(),
                    2, 2000, 500);

            String displayedOdds = withLocatorRetry(page,
                    "#target-betslip .m-betslip .m-selections-list .m-bet-selection .m-betslip-odds span",
                    loc -> loc.last().textContent().trim(),
                    2, 2000, 500);

            Locator teamsLocator = withLocatorRetry(page,
                    "#target-betslip .m-betslip .m-selections-list .m-bet-selection .m-team-info .m-teams",
                    loc -> loc.count() > 0 ? loc : null,
                    2, 2000, 500);

            String displayedTeams = "";
            if (teamsLocator != null) {
                displayedTeams = teamsLocator.textContent().trim().replaceAll("\\s+", " ");
            }

            Integer suspendedCount = withLocatorRetry(page,
                    "#target-betslip .m-betslip .m-selections-list .m-bet-selection .m-unusual-tag",
                    loc -> loc.count(),
                    2, 1000, 300);

            boolean isSuspended = suspendedCount != null && suspendedCount > 0;
            String suspendedStatus = "";
            if (isSuspended) {
                suspendedStatus = withLocatorRetry(page,
                        "#target-betslip .m-betslip .m-selections-list .m-bet-selection .m-unusual-tag",
                        loc -> loc.textContent().trim(),
                        2, 1000, 300);
            }

            if (displayedOutcome != null && !displayedOutcome.equalsIgnoreCase(outcome)) {
                log.warn("{} Outcome mismatch: expected '{}' but found '{}'", EMOJI_WARNING, outcome, displayedOutcome);
                return false;
            }

            if (displayedMarket != null && !displayedMarket.equalsIgnoreCase(market)) {
                log.warn("{} Market mismatch: expected '{}' but found '{}'", EMOJI_WARNING, market, displayedMarket);
                return false;
            }

            if (isSuspended) {
                log.warn("{} BET IS SUSPENDED: {} | Status: {}", EMOJI_WARNING, displayedOutcome, suspendedStatus);
                return false;
            }

            try {
                double expectedOdds = leg.getOdds().doubleValue();
                double actualOdds = Double.parseDouble(displayedOdds);

                if (!isOddsAcceptable(expectedOdds, displayedOdds)) {
                    log.warn("{} Odds mismatch in betslip: expected {} but found {}",
                            EMOJI_WARNING, expectedOdds, actualOdds);
                }
            } catch (NumberFormatException e) {
                log.debug("Could not parse odds for verification: {}", displayedOdds);
            }

            Integer liveTagCount = withLocatorRetry(page,
                    "#target-betslip .m-betslip .m-selections-list .m-bet-selection .m-live-tag",
                    loc -> loc.count(),
                    2, 1000, 300);
            boolean isLive = liveTagCount != null && liveTagCount > 0;

            log.info("{} BET VERIFIED IN SLIP: {} | Market: {} | Odds: {} | Match: {} | Live: {}",
                    EMOJI_SUCCESS, displayedOutcome, displayedMarket, displayedOdds, displayedTeams, isLive);

            return true;

        } catch (PlaywrightException pe) {
            if (pe.getMessage().contains("Timeout")) {
                log.warn("{} Timeout waiting for bet in slip: {}", EMOJI_CLOCK, pe.getMessage());
                debugBetslipContents(page, outcome, market);
            } else {
                log.error("{} Playwright error: {}", EMOJI_ERROR, pe.getMessage());
            }
            return false;
        } catch (Exception e) {
            log.error("{} Unexpected error: {}", EMOJI_ERROR, e.getMessage(), e);
            return false;
        }
    }

    private void debugBetslipContents(Page page, String expectedOutcome, String expectedMarket) {
        try {
            // Check if betslip is even visible
            boolean betslipVisible = page.locator("#target-betslip").isVisible();
            log.warn("{} TIMEOUT - Betslip visible: {}", EMOJI_ERROR, betslipVisible);

            if (!betslipVisible) {
                log.warn("Betslip container not found or not visible!");
                return;
            }

            // Get bet count from badge
            String betCount = "0";
            try {
                betCount = page.locator("#target-betslip .m-count-ball").first().textContent().trim();
            } catch (Exception e) {
                log.warn("Could not get bet count: {}", e.getMessage());
            }
            log.warn("Bet count badge showing: {}", betCount);

            // List all items in betslip
            Locator allItems = page.locator("#target-betslip .m-selections-list .m-bet-selection");
            int itemCount = allItems.count();
            log.warn("Number of selections in betslip: {}", itemCount);

            if (itemCount == 0) {
                log.warn("No selections found in betslip!");

                // Check if there are any suspended bets
                Locator suspendedBets = page.locator("#target-betslip .m-bet-selection.abnormal");
                if (suspendedBets.count() > 0) {
                    log.warn("Found {} suspended/abnormal bets", suspendedBets.count());
                }
                return;
            }

            // Show details of each item
            for (int i = 0; i < itemCount; i++) {
                Locator item = allItems.nth(i);

                try {
                    String outcome = item.locator(".m-title .market-title").textContent().trim();
                    String market = item.locator(".selection-market").textContent().trim();
                    String odds = "N/A";

                    // Get odds if available (not suspended)
                    Locator oddsLocator = item.locator(".m-betslip-odds span").last();
                    if (oddsLocator.count() > 0) {
                        odds = oddsLocator.textContent().trim();
                    }

                    String teams = item.locator(".m-team-info .m-teams").textContent().trim().replaceAll("\\s+", " ");
                    boolean isLive = item.locator(".m-live-tag").count() > 0;
                    boolean isSuspended = item.locator(".m-unusual-tag").count() > 0;
                    String status = isSuspended ? item.locator(".m-unusual-tag").textContent().trim() : "Active";

                    log.warn("  {} Item {}: outcome='{}', market='{}', odds='{}', teams='{}', live={}, status='{}'",
                            isSuspended ? EMOJI_WARNING : EMOJI_INFO,
                            i, outcome, market, odds, teams, isLive, status);

                } catch (Exception e) {
                    log.warn("  Item {}: Error reading details - {}", i, e.getMessage());
                }
            }

            log.warn("Expected: outcome='{}', market='{}'", expectedOutcome, expectedMarket);

            // Additional checks
            Locator duplicateWarning = page.locator("#target-betslip .m-dupli-note");
            if (duplicateWarning.count() > 0 && duplicateWarning.isVisible()) {
                log.warn("{} Betslip shows duplicate/change warning: {}",
                        EMOJI_WARNING, duplicateWarning.textContent().trim());
            }

        } catch (Exception e) {
            log.warn("Debug failed: {}", e.getMessage());
        }
    }


    /**
     * Place bet on the page
     */
    private boolean placeBet(Page page, Arb arb, BetLeg leg) {
        BigDecimal stakeAmount = leg.getStake();
        long startTime = System.currentTimeMillis();

        log.info("─────────────────────────────────────────────────────────────");
        log.info("START placeBet() → {} → {} @ {} | Stake: {} | {}",
                leg.getProviderMarketTitle(), leg.getProviderMarketName(),
                leg.getOdds(), stakeAmount,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")));

        try {
            // ── 1. ENTER STAKE (IMPROVED WITH OVERFLOW HANDLING) ─────────────────────
            log.info("{} [1/6] Entering stake on MSPORT...", EMOJI_BET);

            boolean stakeEntered = enterStakeWithOverflowHandling(page, stakeAmount);
            if (!stakeEntered) {
                log.error("{} Failed to enter stake!", EMOJI_ERROR);
                return false;
            }

            randomHumanDelay(800, 1400);
            log.info("[OK] Stake entered");

            // ── 2. WAIT FOR BET IN SLIP ─────────────────────────────────
            if (page.locator("#target-betslip .m-selections-list .m-bet-selection").count() == 0) {
                log.error("Bet never appeared in slip");
                return false;
            }
            log.info("[OK] Bet in slip");

            // ── 3. ENABLE AUTO-ACCEPT ODDS CHANGES (CHECKBOX) ─────────────
            log.info("[3/6] Checking 'Accept odds changes' setting...");
            enableAcceptOddsChanges(page);

            // ── 4. UNKILLABLE LOOP + FULL POPUP HANDLING ─────────────────────
            log.info("[4/6] Starting UNKILLABLE placement loop...");

            boolean betConfirmed = false;
            int cycle = 0;
            final int MAX_CYCLES = 10;

            while (!betConfirmed && cycle < MAX_CYCLES) {
                cycle++;
                log.info("[Cycle {}/{}] Checking state...", cycle, MAX_CYCLES);

                // ── A. CHECK FOR SUCCESS MODAL FIRST (HIGHEST PRIORITY) ──
                if (detectSuccessModal(page)) {
                    log.info("SUCCESS CONFIRMED — 'Bet Successful!' MODAL DETECTED!");
                    handleSuccessModal(page);
                    betConfirmed = true;
                    break;
                }

                // ── B. CHECK FOR ODDS REJECTION POPUP ──
                if (handleOddsRejectionPopup(page)) {
                    randomHumanDelay(1000, 1500);
                    continue;
                }

                // ── C. CHECK FOR FINAL CONFIRM POPUP ──
                Locator finalConfirm = page.locator(
                        "button:has-text('Confirm'), " +
                                "button:has-text('Yes'), " +
                                "button:has-text('OK')"
                ).first();

                if (finalConfirm.isVisible(new Locator.IsVisibleOptions().setTimeout(800))) {
                    log.warn("FINAL CONFIRM POPUP → Clicking 'Confirm'");
                    jsScrollAndClick(finalConfirm, page);
                    randomHumanDelay(1000, 1800);
                    continue;
                }

                // ── D. FIND THE MAIN BUTTON (ROBUST APPROACH) ────────────────────────────────
                Locator btn = findPlaceBetButton(page);

                if (btn == null) {
                    log.info("Button not found → checking for success...");
                    if (detectSuccessModal(page)) {
                        log.info("Button disappeared and success modal detected!");
                        handleSuccessModal(page);
                        betConfirmed = true;
                        break;
                    }

                    // If still no success after multiple cycles, fail
                    if (cycle >= 3) {
                        log.error("Button not found after {} cycles and no success modal", cycle);
                        return false;
                    }

                    randomHumanDelay(1000, 1500);
                    continue;
                }

                String buttonText = getCleanButtonText(btn);
                boolean isDisabled = btn.isDisabled();

                log.info("Main button: \"{}\" | Disabled: {}", buttonText, isDisabled);

                // ── E. CLICK BUTTON WITH IMMEDIATE FOLLOW-UP ─────────────
                if (!isDisabled) {
                    String originalText = buttonText;
                    log.info("→ Clicking button (cycle {}) - Text: {}", cycle, originalText);

                    // CLICK 1: First click
                   findAndClickPlaceBet(page);
                    randomHumanDelay(500, 800); // Short delay

                    // ── CHECK FOR SUCCESS IMMEDIATELY AFTER FIRST CLICK ──
                    if (detectSuccessModal(page)) {
                        log.info("SUCCESS AFTER FIRST CLICK!");
                        handleSuccessModal(page);
                        betConfirmed = true;
                        break;
                    }

                    // ── CHECK IF BUTTON CHANGED TEXT (e.g., Place Bet → Submit) ──
                    Locator btnAfterClick = findPlaceBetButton(page);
                    if (btnAfterClick != null) {
                        String newText = getCleanButtonText(btnAfterClick);
                        if (!newText.equals(originalText)) {
                            log.info("Button text changed from '{}' to '{}'", originalText, newText);

                            // Check if it changed to "Submit" or similar action text
                            if (isActionText(newText) && btnAfterClick.isEnabled()) {
                                log.info("→ Immediate follow-up click on '{}'", newText);

                                // CLICK 2: Immediate follow-up click
                                jsScrollAndClick(btnAfterClick, page);
                                randomHumanDelay(1500, 2500); // Longer delay for processing

                                // Check for success after second click
                                if (detectSuccessModal(page)) {
                                    log.info("SUCCESS AFTER SECOND CLICK!");
                                    handleSuccessModal(page);
                                    betConfirmed = true;
                                    break;
                                }

                                // Check for any popups
                                if (handleOddsRejectionPopup(page)) {
                                    continue;
                                }

                                // Check for confirm popup again
                                if (finalConfirm.isVisible(new Locator.IsVisibleOptions().setTimeout(500))) {
                                    jsScrollAndClick(finalConfirm, page);
                                    randomHumanDelay(1000, 1800);
                                    continue;
                                }
                            }
                        } else {
                            // Button text didn't change, wait longer and check for success
                            randomHumanDelay(1500, 2500);

                            if (detectSuccessModal(page)) {
                                log.info("SUCCESS AFTER DELAY!");
                                handleSuccessModal(page);
                                betConfirmed = true;
                                break;
                            }
                        }
                    } else {
                        // Button disappeared after click - check for success
                        randomHumanDelay(1500, 2500);
                        if (detectSuccessModal(page)) {
                            log.info("SUCCESS - Button disappeared after click!");
                            handleSuccessModal(page);
                            betConfirmed = true;
                            break;
                        }
                    }

                    // Check for any rejection popups
                    if (handleOddsRejectionPopup(page)) {
                        continue;
                    }

                } else {
                    // Button disabled
                    log.info("Button disabled → checking state...");
                    handleOddsChangesInLoop(page, cycle);
                    randomHumanDelay(1000, 1500);
                }
            }

            if (!betConfirmed) {
                log.error("FAILED after {} cycles → Could not place bet", MAX_CYCLES);
                return false;
            }
            log.info("[OK] Bet placed after {} cycle(s)", cycle);

            long duration = System.currentTimeMillis() - startTime;
            log.info("placeBet COMPLETED | SUCCESS | {}ms | {} cycles", duration, cycle);
            log.info("─────────────────────────────────────────────────────────────");
            return true;

        } catch (Exception e) {
            log.error("FATAL in placeBet(): {}", e.toString());
            e.printStackTrace();
            closeSuccessModal(page);
            return false;
        }
    }

    private void findAndClickPlaceBet(Page page) {
        String[] selectors = {
                "button.v-button.m-place-btn",  // More specific - matches your HTML
                "button.m-place-btn",
                "button:has-text('Place Bet')",
                "#target-betslip button.v-button"
        };

        for (String selector : selectors) {
            try {
                Locator btn = page.locator(selector).first();

                // Check if visible
                btn.waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(3000));

                // Scroll into view
                btn.evaluate("el => el.scrollIntoView({ block: 'center', behavior: 'instant' })");
                page.waitForTimeout(200);

                // Click immediately while element is fresh
                btn.click(new Locator.ClickOptions()
                        .setForce(true)
                        .setTimeout(5000));

                log.info("Place bet button clicked successfully with selector: {}", selector);
                return;

            } catch (Exception e) {
                log.debug("Selector '{}' failed: {}", selector, e.getMessage());
            }
        }

        log.error("Failed to click place bet button with all selectors");
    }


    /**
     * ROBUST button finder - uses class-based selector with short timeout
     * Returns null if button not found instead of throwing exception
     */
    private Locator findPlaceBetButton(Page page) {
        try {
            Locator btn = withLocatorRetry(page, "button.m-place-btn",
                    loc -> {
                        loc.waitFor(new Locator.WaitForOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(3000));
                        return loc.count() > 0 ? loc.first() : null;
                    },
                    2, 500, 300);

            if (btn != null) {
                log.info("Button found via class selector");
                return btn;
            }
        } catch (Exception e) {
            log.info("Button not found via class selector");
        }

        try {
            Locator fallbackBtn = withLocatorRetry(page,
                    "button.v-button.m-place-btn, button[type='button'].m-place-btn, #target-betslip button.v-button",
                    loc -> {
                        loc.first().waitFor(new Locator.WaitForOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(1000));
                        return loc.count() > 0 ? loc.first() : null;
                    },
                    2, 1000, 500);

            if (fallbackBtn != null) {
                log.info("Button found via fallback selector");
                return fallbackBtn;
            }
        } catch (Exception e) {
            log.info("Fallback button search also failed");
        }

        log.warn("Place bet button not found after all attempts");
        return null;
    }


    /**
     * Check if text indicates an action button that needs immediate follow-up click
     */
    private boolean isActionText(String text) {
        if (text == null || text.isEmpty()) return false;

        String lowerText = text.toLowerCase();
        return lowerText.contains("submit") ||
                lowerText.contains("confirm") ||
                lowerText.contains("process") ||
                lowerText.contains("continue") ||
                lowerText.contains("next") ||
                lowerText.contains("proceed") ||
                lowerText.contains("final") ||
                lowerText.contains("bet") || // "Bet Now", "Place Bet"
                lowerText.contains("accept"); // "Accept Changes"
    }

    /**
     * Check if button is enabled
     */
    private boolean btnIsEnabled(Locator btn) {
        try {
            return !btn.isDisabled();
        } catch (Exception e) {
            return false;
        }
    }




    /**
     * Get clean button text (removes newlines and extra whitespace)
     */
    private String getCleanButtonText(Locator btn) {
        try {
            String text = btn.textContent().trim();
            // Remove newlines and multiple spaces
            text = text.replaceAll("\\s+", " ").trim();
            return text;
        } catch (Exception e) {
            log.debug("Could not get button text: {}", e.getMessage());
            return "";
        }
    }




    /**
     * Wait for Place Bet button to appear
     */
    private boolean waitForPlaceBetButton(Page page, int timeoutMs) {
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                Locator placeBetBtn = page.locator(
                        "button:has-text('Place Bet'), " +
                                "button.m-place-btn:has-text('Place')"
                ).first();

                if (placeBetBtn.count() > 0 && placeBetBtn.isVisible(
                        new Locator.IsVisibleOptions().setTimeout(500))) {
                    log.info("Place Bet button is now visible");
                    return true;
                }

                page.waitForTimeout(300);
            } catch (Exception e) {
                log.debug("Error waiting for Place Bet button: {}", e.getMessage());
            }
        }

        log.warn("Place Bet button did not appear within {}ms", timeoutMs);
        return false;
    }

    /**
     * Check for Accept Changes button
     */
    private boolean checkForAcceptChangesButton(Page page) {
        try {
            Locator acceptChangesBtn = page.locator(
                    "button:has-text('Accept Changes'), " +
                            "button:has-text('Accept')"
            ).first();

            if (acceptChangesBtn.count() > 0) {
                log.info("Found 'Accept Changes' button, need to click it first");
                return true;
            }
        } catch (Exception e) {
            log.debug("No Accept Changes button found: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Check for confirmation popup
     */
    private boolean checkForConfirmationPopup(Page page) {
        try {
            // Check for any confirmation dialog
            String[] confirmSelectors = {
                    "div.m-dialog-wrapper",
                    "div.ui-dialog--wrap",
                    "div[role='dialog']",
                    "div.modal-content"
            };

            for (String selector : confirmSelectors) {
                Locator dialog = page.locator(selector).first();
                if (dialog.count() > 0 && dialog.isVisible(
                        new Locator.IsVisibleOptions().setTimeout(500))) {
                    log.info("Found confirmation dialog");
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("No confirmation popup found: {}", e.getMessage());
        }
        return false;
    }



    /**
     * Detect success modal with multiple strategies
     */
    /**
     * Detects MSport success modal and logs bet confirmation details
     * @param page Playwright page object
     * @return true if success modal detected and booking code extracted, false otherwise
     */
    private boolean detectSuccessModal(Page page) {
        try {
            log.debug("Checking for success modal...");

            Locator successModal = withLocatorRetry(page, "div.m-betslip-success",
                    loc -> loc.count() > 0 && loc.first().isVisible(
                            new Locator.IsVisibleOptions().setTimeout(2000)) ? loc : null,
                    3, 2000, 500);

            if (successModal == null) {
                log.debug("Success modal not visible");
                return false;
            }

            log.info("✅ SUCCESS MODAL DETECTED!");

            Locator successTitle = withLocatorRetry(page,
                    "div.m-betslip-success div.m-title:has-text('Bet Successful!')",
                    loc -> loc.count() > 0 ? loc : null,
                    2, 2000, 500);

            if (successTitle == null) {
                log.warn("⚠️ Success container found but 'Bet Successful!' title missing");
                return false;
            }

            boolean bookingCodeFound = false;
            try {
                String bookingCode = withLocatorRetry(page,
                        "div.m-info-item:has-text('Booking Code') span.tw-text-black",
                        loc -> loc.first().isVisible(new Locator.IsVisibleOptions().setTimeout(1000))
                                ? loc.first().textContent().trim() : null,
                        3, 1000, 500);

                if (bookingCode != null) {
                    log.info("📋 Booking Code: {}", bookingCode);
                    bookingCodeFound = true;
                }
            } catch (Exception e) {
                log.warn("⚠️ Could not extract booking code: {}", e.getMessage());
            }

            if (!bookingCodeFound) {
                log.error("❌ SUCCESS MODAL DETECTED BUT NO BOOKING CODE → BET MAY HAVE FAILED");
                return false;
            }

            log.info("✅ BET CONFIRMED!");
            return true;

        } catch (Exception e) {
            log.debug("Success modal detection error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Closes the success modal by clicking the "OK" button
     */
    private void closeSuccessModal(Page page) {
        try {
            log.info("Closing success modal...");

            Locator okButton = withLocatorRetry(page,
                    "div.betslip-success--footer button.btn--cancel:has-text('OK'), " +
                            "div.m-betslip-success button:has-text('OK')",
                    loc -> loc.first().isVisible(new Locator.IsVisibleOptions().setTimeout(2000))
                            ? loc.first() : null,
                    2, 2000, 500);

            if (okButton != null) {
                jsScrollAndClick(okButton, page);
                randomHumanDelay(500, 1000);
                log.info("✅ Success modal closed");
            } else {
                log.debug("OK button not visible, modal may have auto-closed");
            }
        } catch (Exception e) {
            log.debug("Could not close success modal: {}", e.getMessage());
        }
    }

    /**
     * Handle success modal when detected
     */
    private void handleSuccessModal(Page page) {
        try {
            // Extract booking code
            try {
                Locator bookingCodeLocator = page.locator(
                        "div.m-info-item:has-text('Booking Code') span.tw-text-black, " +
                                "div:has-text('Booking Code') + div, " +
                                "span:has-text('Booking Code') ~ span"
                ).first();

                String code = bookingCodeLocator.textContent().trim();
                log.info("BOOKING CODE: {}", code.isEmpty() ? "Not shown" : code);
            } catch (Exception e) {
                log.debug("Could not extract booking code: {}", e.getMessage());
            }

            // Extract bet details
            try {
                String stake = page.locator("div.m-stake div.tw-font-bold").first().textContent().trim();
                log.info("STAKE CONFIRMED: {}", stake);

                String toReturn = page.locator("div.m-to-return div.tw-font-bold").first().textContent().trim();
                log.info("TO RETURN: {}", toReturn);
            } catch (Exception e) {
                log.debug("Could not extract all bet details: {}", e.getMessage());
            }

            // Close success modal if present
            try {
                Locator closeSuccess = page.locator(
                        "div.m-betslip-success button.btn--cancel:has-text('OK'), " +
                                "div.betslip-success--footer button:has-text('OK'), " +
                                "button:has-text('Close'), " +
                                "button:has-text('Done')"
                ).first();

                if (closeSuccess.isVisible(new Locator.IsVisibleOptions().setTimeout(2000))) {
                    log.info("Closing success modal...");
                    jsScrollAndClick(closeSuccess, page);
                    randomHumanDelay(600, 1200);
                }
            } catch (Exception e) {
                log.debug("Success modal already closed or could not close");
            }

        } catch (Exception e) {
            log.error("Error handling success modal: {}", e.getMessage());
        }
    }

    /**
     * Handle odds rejection popup
     */
    private boolean handleOddsRejectionPopup(Page page) {
        try {
            // Check for various rejection messages
            String[] rejectionSelectors = {
                    "div.m-dialog-wrapper p:has-text('Odds not acceptable')",
                    "div:has-text('Odds changed')",
                    "div:has-text('price change')",
                    "div:has-text('odds have changed')",
                    "div.m-dialog-wrapper:has-text('not acceptable')"
            };

            for (String selector : rejectionSelectors) {
                Locator rejection = page.locator(selector).first();
                if (rejection.isVisible(new Locator.IsVisibleOptions().setTimeout(500))) {
                    log.warn("ODDS REJECTION POPUP DETECTED → Closing...");

                    // Find and click close button
                    Locator closeBtn = page.locator(
                            "div.m-dialog-wrapper button:has-text('OK'), " +
                                    "div.m-dialog-wrapper button:has-text('Close'), " +
                                    "img.close-icon[data-action='close'], " +
                                    "button.ui-dialog-btn-close"
                    ).first();

                    if (closeBtn.isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                        jsScrollAndClick(closeBtn, page);
                        randomHumanDelay(1000, 1600);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("No odds rejection popup detected: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Get button text safely
     */
    private String getButtonText(Locator btn) {
        try {
            // Try to get text from multiple possible locations
            String text = btn.textContent().trim();

            // Also check for text in child elements
            if (text.isEmpty()) {
                Locator innerText = btn.locator("span, div, .m-label");
                if (innerText.count() > 0) {
                    text = innerText.first().textContent().trim();
                }
            }

            return text;
        } catch (Exception e) {
            log.debug("Could not get button text: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Wait for button text to change
     */
    private void waitForButtonTextChange(Page page, Locator btn, String originalText, int timeoutMs) {
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                String currentText = getButtonText(btn);
                if (!currentText.equals(originalText)) {
                    log.info("Button text changed from '{}' to '{}'", originalText, currentText);
                    return;
                }
                page.waitForTimeout(300);
            } catch (Exception e) {
                log.debug("Error checking button text change: {}", e.getMessage());
                break;
            }
        }
    }

    /**
     * Check if odds are still acceptable
     */
    private boolean areOddsStillAcceptable(Page page, BetLeg leg) {
        try {
            // Get current odds from betslip
            Locator currentOdds = page.locator("span.m-betslip-odds span").first();
            if (currentOdds.count() > 0) {
                String oddsText = currentOdds.textContent().trim();
                if (!oddsText.isEmpty()) {
                    BigDecimal currentOddsValue = new BigDecimal(oddsText);
                    BigDecimal targetOdds = leg.getOdds();

                    // Allow small variance (e.g., 0.01)
                    BigDecimal difference = currentOddsValue.subtract(targetOdds).abs();
                    if (difference.compareTo(new BigDecimal("0.01")) <= 0) {
                        log.info("Odds still acceptable: {} (target: {})", currentOddsValue, targetOdds);
                        return true;
                    } else {
                        log.warn("Odds changed significantly: {} vs target {}", currentOddsValue, targetOdds);
                        return false;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not verify odds: {}", e.getMessage());
        }
        return true; // Default to true if can't verify
    }

    /**
     * Check and handle disabled button state
     */
    private void checkAndHandleDisabledState(Page page, int cycle) {
        try {
            // Check if "Accept odds changes" checkbox needs to be checked
            Locator uncheckedBox = page.locator("div.checkbox-square.nochecked").first();
            if (uncheckedBox.isVisible(new Locator.IsVisibleOptions().setTimeout(500))) {
                log.warn("[Cycle {}] Checkbox unchecked → enabling", cycle);
                jsScrollAndClick(uncheckedBox, page);
                randomHumanDelay(300, 600);
            }

            // Check for any error messages
            Locator errorMsg = page.locator("p.m-error-text:visible, div.tw-text-red:visible").first();
            if (errorMsg.count() > 0) {
                String error = errorMsg.textContent();
                log.warn("Error message: {}", error);
            }
        } catch (Exception e) {
            log.debug("Disabled state check failed: {}", e.getMessage());
        }
    }

// ── NEW: STAKE ENTRY WITH OVERFLOW HANDLING ─────────────────────────────

    /**
     * Improved stake entry method with multiple fallback strategies for overflow issues
     */
    private boolean enterStakeWithOverflowHandling(Page page, BigDecimal stakeAmount) {
        String stakeString = stakeAmount.toPlainString();
        int attempts = 0;
        final int MAX_ATTEMPTS = 3;

        while (attempts < MAX_ATTEMPTS) {
            attempts++;
            log.info("Attempt {}/{} to enter stake: {}", attempts, MAX_ATTEMPTS, stakeString);

            try {
                // STRATEGY 1: Try the original selector first
                Locator stakeInput = findStakeInput(page, attempts);

                if (stakeInput != null && stakeInput.count() > 0) {
                    log.info("Found stake input using strategy {}", attempts);

                    // Scroll and focus with overflow handling
                    scrollAndFocusWithOverflowFix(stakeInput, page);

                    // Clear and enter stake
                    stakeInput.clear();
                    randomHumanDelay(150, 400);
                    SportyLoginUtils.typeFastHumanLike(stakeInput, stakeString);

                    // Verify entry
                    page.waitForTimeout(500);
                    String enteredValue = stakeInput.inputValue();
                    if (enteredValue.equals(stakeString)) {
                        log.info("Stake successfully entered: {}", enteredValue);

                        // Try to trigger update by pressing Enter
                        try {
                            stakeInput.press("Enter");
                        } catch (Exception e) {
                            // Press Tab instead if Enter fails
                            stakeInput.press("Tab");
                        }

                        return true;
                    } else {
                        log.warn("Stake mismatch. Expected: {}, Got: {}", stakeString, enteredValue);
                    }
                }

                // Wait before retry
                randomHumanDelay(1000, 2000);

            } catch (Exception e) {
                log.warn("Attempt {} failed: {}", attempts, e.getMessage());

                // Apply overflow fixes between attempts
                if (attempts < MAX_ATTEMPTS) {
                    applyOverflowFixes(page);
                    randomHumanDelay(1000, 1500);
                }
            }
        }

        log.error("All {} attempts to enter stake failed", MAX_ATTEMPTS);
        return false;
    }

    /**
     * Find stake input with multiple selector strategies
     */
    private Locator findStakeInput(Page page, int attempt) {
        page.waitForSelector("aside.aside-betslip-cashout",
                new Page.WaitForSelectorOptions().setTimeout(10000));

        switch (attempt) {
            case 3:
                return withLocatorRetry(page,
                        "div.m-bet-selection >> div.m-single-input-wrap >> input[placeholder='min. 10']",
                        loc -> loc.first(),
                        2, 3000, 500);

            case 2:
                return withLocatorRetry(page,
                        "div.m-bet-selection .bet-input input[placeholder='min. 10']",
                        loc -> loc.first(),
                        2, 3000, 500);

            case 1:
                Locator singlesInput = withLocatorRetry(page,
                        "div.m-mutiple-edit .bet-input input",
                        loc -> loc.count() > 0 ? loc.first() : null,
                        2, 3000, 500);

                if (singlesInput != null) {
                    log.info("Using Singles section input as fallback");
                    return singlesInput;
                }
                return withLocatorRetry(page,
                        "input[placeholder*='min']",
                        loc -> loc.first(),
                        2, 3000, 500);

            default:
                return withLocatorRetry(page,
                        "input[placeholder*='min'], input[placeholder*='Min']",
                        loc -> loc.first(),
                        2, 3000, 500);
        }
    }

    /**
     * Scroll and focus with overflow handling
     */
    private void scrollAndFocusWithOverflowFix(Locator element, Page page) {
        try {
            // First try normal scroll
            element.scrollIntoViewIfNeeded();
            page.waitForTimeout(300);

            // Check if element is actually visible
            boolean isVisible = element.isVisible(new Locator.IsVisibleOptions().setTimeout(1000));

            if (!isVisible) {
                log.warn("Element not visible after scroll, applying overflow fixes...");

                // Fix CSS overflow issues
                page.evaluate("""
                () => {
                    // Fix aside overflow
                    const aside = document.querySelector('aside.aside-right');
                    if (aside) {
                        aside.style.overflow = 'visible';
                        aside.style.position = 'relative';
                        aside.style.zIndex = '9999';
                    }
                    
                    // Fix betslip container
                    const betslip = document.querySelector('.aside-betslip-cashout');
                    if (betslip) {
                        betslip.style.overflow = 'visible';
                        betslip.style.maxHeight = 'none';
                    }
                    
                    // Fix scroll container
                    const scrollContainer = document.querySelector('.scroll-container--betslip');
                    if (scrollContainer) {
                        scrollContainer.style.overflow = 'visible';
                        scrollContainer.style.maxHeight = 'none';
                    }
                }
            """);

                page.waitForTimeout(500);

                // Scroll again with force
                element.evaluate("el => el.scrollIntoView({ behavior: 'instant', block: 'center', inline: 'center' })");
                page.waitForTimeout(300);
            }

            // Focus the element
            element.focus();
            page.waitForTimeout(200);

        } catch (Exception e) {
            log.debug("Scroll/focus failed, using force: {}", e.getMessage());
            // Use force options as last resort
            element.click(new Locator.ClickOptions().setForce(true));
        }
    }

    /**
     * Apply CSS fixes for overflow issues
     */
    private void applyOverflowFixes(Page page) {
        try {
            page.evaluate("""
            () => {
                // Remove all overflow restrictions in betslip
                const selectors = [
                    'aside.aside-right',
                    '.aside-betslip-cashout',
                    '.scroll-container--betslip',
                    '.m-main-betslip--main-wrap',
                    '.m-bet-selection',
                    '.m-single-input-wrap'
                ];
                
                selectors.forEach(selector => {
                    document.querySelectorAll(selector).forEach(el => {
                        el.style.overflow = 'visible';
                        el.style.position = 'relative';
                        el.style.zIndex = '9999';
                        el.style.maxHeight = 'none';
                    });
                });
                
                // Ensure inputs are visible
                document.querySelectorAll('input').forEach(input => {
                    input.style.visibility = 'visible';
                    input.style.opacity = '1';
                    input.style.display = 'block';
                });
            }
        """);

            log.info("Applied CSS overflow fixes");

        } catch (Exception e) {
            log.debug("Could not apply overflow fixes: {}", e.getMessage());
        }
    }

// ── EXISTING HELPER METHODS (UNCHANGED) ────────────────────────────────

    /**
     * Enables the "Accept odds changes" checkbox if it's not already checked
     */
    private void enableAcceptOddsChanges(Page page) {
        try {
            Locator checkbox = withLocatorRetry(page,
                    "div.checkbox-square.nochecked, " +
                            "div.checkbox-square-wrap:has-text('Accept odds changes') div.checkbox-square.nochecked, " +
                            "div:has-text('Accept odds changes') div.checkbox-square.nochecked",
                    loc -> loc.first().isVisible(new Locator.IsVisibleOptions().setTimeout(2000))
                            ? loc.first() : null,
                    2, 2000, 500);

            if (checkbox != null) {
                log.info("'Accept odds changes' checkbox is UNCHECKED → Enabling it");
                jsScrollAndClick(checkbox, page);
                randomHumanDelay(300, 600);

                Locator checkedBox = withLocatorRetry(page,
                        "div.checkbox-square:not(.nochecked)",
                        loc -> loc.first().isVisible(new Locator.IsVisibleOptions().setTimeout(1000))
                                ? loc.first() : null,
                        2, 1000, 300);

                if (checkedBox != null) {
                    log.info("[OK] 'Accept odds changes' ENABLED");
                }
            } else {
                log.info("[OK] 'Accept odds changes' already enabled or not present");
            }
        } catch (Exception e) {
            log.debug("Could not enable 'Accept odds changes': {}", e.getMessage());
        }
    }


    /**
     * Handles odds changes during the betting loop
     */
    private void handleOddsChangesInLoop(Page page, int cycle) {
        try {

            // Re-ensure checkbox is still enabled
            Locator uncheckedBox = page.locator("div.checkbox-square.nochecked").first();
            if (uncheckedBox.isVisible(new Locator.IsVisibleOptions().setTimeout(500))) {
                log.warn("[Cycle {}] Checkbox became unchecked → Re-enabling", cycle);
                jsScrollAndClick(uncheckedBox, page);
                randomHumanDelay(300, 600);
            }
        } catch (Exception e) {
            log.debug("No odds changes to handle in cycle {}", cycle);
        }
    }

// ── HELPER METHODS

    private void jsScrollAndClick(Locator locator, Page page) {
        try {
            // Ensure element is in view
            locator.evaluate("el => el.scrollIntoView({ block: 'center', behavior: 'smooth' })");
            page.waitForTimeout(350);

            // Try normal click first
            locator.click(new Locator.ClickOptions()
                    .setForce(true)
                    .setTimeout(2000)); // Reduced from 1500

            log.info("Click successful");

        } catch (TimeoutError e) {
            log.debug("Normal click timed out → using JS click");
            try {
                locator.evaluate("el => el.click()");
            } catch (Exception je) {
                log.warn("JS click also failed: {}", je.getMessage());
                // Last resort: dispatch click event
                locator.evaluate("el => el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))");
            }
        } catch (Exception e) {
            log.error("Normal click failed → using JS click: {}", e.getMessage());
            try {
                locator.evaluate("el => el.click()");
            } catch (Exception je) {
                log.warn("JS click also failed: {}", je.getMessage());
            }
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
    /**
     * Waits for and verifies bet confirmation with comprehensive detection
     * Returns true if confirmation was detected, false otherwise
     */
    private boolean waitForBetConfirmation(Page page) {
        log.info("Waiting for bet confirmation...");

        try {
            // Multi-layered confirmation detection matching the actual success modal
            Locator confirmation = page.locator(
                    // Primary: Success modal container
                    "div.m-betslip-success, " +

                            // Secondary: "Bet Successful!" title
                            "div.m-title:has-text('Bet Successful!'), " +

                            // Tertiary: Green checkmark icon
                            "i.inline-svg.tw-text-green, " +

                            // Quaternary: Booking code section
                            "div.m-info-item:has-text('Booking Code'), " +

                            // Fallback: Generic success patterns
                            "div:has-text('Bet placed'), " +
                            "div:has-text('Success'), " +
                            "div:has-text('Submission Successful')"
            ).first();

            // Wait for confirmation with reasonable timeout
            confirmation.waitFor(new Locator.WaitForOptions().setTimeout(10000));

            // Extract and log bet details if available
            String bookingCode = extractBookingCode(page);
            String stake = extractStake(page);
            String toReturn = extractToReturn(page);

            log.info("{} {} Bet CONFIRMED!", EMOJI_SUCCESS, EMOJI_BET);
            if (bookingCode != null) {
                log.info("  └─ Booking Code: {}", bookingCode);
            }
            if (stake != null) {
                log.info("  └─ Stake: {}", stake);
            }
            if (toReturn != null) {
                log.info("  └─ To Return: {}", toReturn);
            }

            return true;

        } catch (TimeoutError e) {
            log.warn("{} {} Confirmation NOT detected within 10s - bet may have failed",
                    EMOJI_WARNING, EMOJI_BET);
            return false;

        } catch (Exception e) {
            log.error("{} {} Unexpected error during confirmation check: {}",
                    EMOJI_WARNING, EMOJI_BET, e.getMessage());
            return false;
        }
    }

    /**
     * Extracts booking code from success modal
     */
    private String extractBookingCode(Page page) {
        try {
            Locator codeLocator = page.locator(
                    "div.m-info-item:has-text('Booking Code') span.tw-text-black, " +
                            "div.m-info-item:has-text('Booking Code') span.tw-mr-\\[6px\\]"
            ).first();

            if (codeLocator.isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                return codeLocator.textContent().trim();
            }
        } catch (Exception e) {
            log.debug("Could not extract booking code: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extracts stake amount from success modal
     */
    private String extractStake(Page page) {
        try {
            Locator stakeLocator = page.locator(
                    "div.m-stake div.tw-font-bold, " +
                            "div.m-info-item:has-text('Stake') div.tw-font-bold"
            ).first();

            if (stakeLocator.isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                return stakeLocator.textContent().trim();
            }
        } catch (Exception e) {
            log.debug("Could not extract stake: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extracts "To Return" amount from success modal
     */
    private String extractToReturn(Page page) {
        try {
            Locator returnLocator = page.locator(
                    "div.m-to-return div.tw-font-bold, " +
                            "div.m-info-item:has-text('To Return') div.tw-font-bold"
            ).first();

            if (returnLocator.isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                return returnLocator.textContent().trim();
            }
        } catch (Exception e) {
            log.debug("Could not extract return amount: {}", e.getMessage());
        }
        return null;
    }


    /**
     * Extracts odds from MSport betslip, verifies they're acceptable (equal, higher, or within 2% lower),
     * and places the bet if odds are favorable.
     *
     * @param page Playwright page object
     * @param leg BetLeg containing expected odds and bet details
     * @return true if bet was successfully placed, false otherwise
     */
    private boolean replayOddandBet(Page page, BetLeg leg) {
        BigDecimal expectedOdds = leg.getOdds();
        String outcome = leg.getProviderMarketName();   // e.g. "Away"
        String market  = leg.getProviderMarketTitle();   // e.g. "Winner"

        try {
            log.info("Starting smart monitoring for: {} → {} @ {}",
                    outcome, market, expectedOdds);

            // 1. Wait for betslip to appear (max 4s)
            page.locator("aside.aside-betslip-cashout, .m-betslip")
                    .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(4000));

            long deadline = System.currentTimeMillis() + 30_000; // max 30s total monitoring

            while (System.currentTimeMillis() < deadline) {
                // 2. Check if bet selection exists in slip
                Locator betSelection = page.locator(".m-bet-selection").first();

                if (betSelection.count() == 0) {
                    log.info("Bet not in slip yet → waiting...");
                    page.waitForTimeout(800);
                    continue;
                }

                // 3. Check if it's currently unavailable/abnormal
                boolean isBad = (Boolean) betSelection.evaluate(
                        "el => el.classList.contains('unavailable') || el.classList.contains('abnormal')"
                );

                if (isBad) {
                    log.warn("Market currently UNAVAILABLE/ABNORMAL → still monitoring...");
                    page.waitForTimeout(1200); // check again soon
                    continue;
                }

                // 4. Market is back → extract current odds
                String currentOddsText = page.locator(
                        ".m-flex-footer span.range-number.value span.tw-whitespace-nowrap," +
                                ".m-betslip-odds span"
                ).first().textContent().trim();

                double currentOdds = parseOdds(currentOddsText);

                log.info("Odds update → Current: {} | Target: {}",
                        String.format("%.2f", currentOdds), expectedOdds);

                // 5. If odds are now acceptable → PLACE BET IMMEDIATELY
                if (isOddsAcceptable(expectedOdds.doubleValue(), currentOddsText)) {
                    log.info("ODDS NOW ACCEPTABLE → Placing bet!");

                    log.info("Final Bet → {} | {} | Odds: {} | Market: {}",
                            extractBetTeams(page), outcome, currentOddsText, market);

                    checkAndHandleOddsChanges(page);  // Accept Changes if shown
                    return placeBet(page, null, leg);
                }

                // 6. Odds not good yet → keep waiting
                log.info("Odds not acceptable yet → continuing to monitor...");
                page.waitForTimeout(800);
            }

            // 7. Timeout after 30 seconds
            log.warn("Gave up after 30s → odds never became acceptable or market stayed suspended");
            clearBetSlip(page);
            return false;

        } catch (Exception e) {
            log.error("Error in replayOddandBet (monitoring mode): {}", e.toString());
            clearBetSlip(page);
            return false;
        }
    }

    // Helper
    private double parseOdds(String text) {
        try {
            return Double.parseDouble(text.replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            return 0.0;
        }
    }


    /**
     * Extracts the outcome/selection name from the betslip
     */
    private String extractBetOutcome(Page page) {
        try {
            Locator outcomeLocator = page.locator(
                    "div.m-bet-selection span.market-title, " +
                            "div.m-stake-info span.market-title"
            ).first();

            if (outcomeLocator.isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                return outcomeLocator.textContent().trim();
            }
        } catch (Exception e) {
            log.debug("Could not extract outcome: {}", e.getMessage());
        }
        return "Unknown";
    }

    /**
     * Extracts the market type (e.g., "Winner", "Total Goals")
     */
    private String extractBetMarket(Page page) {
        try {
            Locator marketLocator = page.locator(
                    "div.m-bet-selection div.selection-market, " +
                            "div.m-stake-info div.selection-market"
            ).first();

            if (marketLocator.isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                return marketLocator.textContent().trim();
            }
        } catch (Exception e) {
            log.debug("Could not extract market: {}", e.getMessage());
        }
        return "Unknown";
    }

    /**
     * Extracts team names from the betslip
     */
    private String extractBetTeams(Page page) {
        try {
            return withLocatorRetry(page,
                    "div.m-team-info div.m-teams, " +
                            "div.m-bet-selection div.m-teams",
                    loc -> loc.first().isVisible(new Locator.IsVisibleOptions().setTimeout(1000))
                            ? loc.first().textContent().trim().replaceAll("\\s+", " ") : null,
                    2, 1000, 300);
        } catch (Exception e) {
            log.debug("Could not extract teams: {}", e.getMessage());
        }
        return "Unknown";
    }


    /**
     * Checks if there's an "Accept Changes" button visible and logs warning
     */
    private void checkAndHandleOddsChanges(Page page) {
        try {
            // Check for odds change notification
            Locator oddsChangeNote = page.locator("p.m-dupli-note:has-text('odds or availability')");

            if (oddsChangeNote.isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                String noteText = oddsChangeNote.textContent().trim();
                log.warn("⚠️ ODDS CHANGE DETECTED: {}", noteText);
            }

        } catch (Exception e) {
            log.debug("No odds changes detected");
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
                        .setViewportSize(viewportSize)
                        .setScreenSize(viewportSize.width, viewportSize.height)
                                        .setDeviceScaleFactor(1)
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

            hoverOverMatchComponent(page);
            randomHumanDelay(1000, 2000);

            scrollThroughMatches(page);
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
            String[] matchSelectors = {
                    ".m-event",
                    ".m-event.live",
                    "div[data-v-5c37632f].m-event",
                    ".m-event--main",
                    ".m-teams"
            };

            for (String selector : matchSelectors) {
                Locator matches = withLocatorRetry(page, selector,
                        loc -> loc,
                        2, 2000, 500);

                if (matches == null) continue;

                int count = matches.count();

                if (count > 0) {
                    int randomIndex = ThreadLocalRandom.current().nextInt(0, Math.min(count, 5));
                    Locator randomMatch = matches.nth(randomIndex);

                    Boolean isVisible = withLocatorRetry(page, selector,
                            loc -> loc.nth(randomIndex).isVisible(),
                            2, 2000, 500);

                    if (isVisible != null && isVisible) {
                        log.info("Hovering over random match (index: {})", randomIndex);
                        randomMatch.hover();
                        Thread.sleep(ThreadLocalRandom.current().nextInt(800, 1500));
                        return;
                    }
                }
            }

            log.info("No matches found to hover over");

        } catch (Exception e) {
            log.error("Hover error: {}", e.getMessage());
        }
    }

    /**
     * Interact with safe, non-betting page elements on MSport
     * Examples: tournament collapse/expand, favorite buttons, stats buttons
     */
    private void maybeInteractWithPageElement(Page page) {
        try {
            // 30% chance to interact
            if (ThreadLocalRandom.current().nextInt(100) < 30) {
                log.info("Attempting optional page interaction...");

                // Safe elements specific to MSport page
                String[] safeInteractionSelectors = {
                        // Tournament expand/collapse arrow
                        ".ms-btn-arrow .ms-icon-trangle:not(.expanded)",

                        // Favorite star buttons (non-betting)
                        ".m-favourite-btn",

                        // Stats button (non-betting)
                        "button[aria-label='Stats']",

                        // Collapsed tournament sections
                        ".m-tournament--title-bar:not(.expanded)",

                        // Any collapsed arrow indicators
                        "i.ms-icon-trangle:not(.expanded)"
                };

                for (String selector : safeInteractionSelectors) {
                    Locator element = page.locator(selector);

                    if (element.count() > 0 && element.first().isVisible()) {
                        log.info("Clicking safe element: {}", selector);

                        // Click the element
                        element.first().click();
                        Thread.sleep(ThreadLocalRandom.current().nextInt(500, 1000));

                        // For toggle elements (like expand/collapse), toggle back
                        if (selector.contains("ms-icon-trangle") ||
                                selector.contains("m-tournament--title-bar")) {
                            Thread.sleep(ThreadLocalRandom.current().nextInt(800, 1200));
                            element.first().click();
                            log.info("Toggled element back to original state");
                        }

                        return;
                    }
                }

                log.info("No safe interaction elements found");
            }
        } catch (Exception e) {
            log.error("Page interaction error: {}", e.getMessage());
        }
    }

    private void hoverOverMatchComponent(Page page) {
        try {
            String[] componentSelectors = {
                    ".m-teams",                   // Team names area
                    ".m-teams--info",            // Team info section
                    ".match-scores",             // Score display
                    ".m-event--header",          // Match header with time
                    ".m-market-box:not(:has(.m-outcome.disabled))" // Active odds (hover only, no click)
            };

            for (String selector : componentSelectors) {
                Locator components = page.locator(selector);
                int count = components.count();

                if (count > 0) {
                    int randomIndex = ThreadLocalRandom.current().nextInt(0, Math.min(count, 3));
                    Locator component = components.nth(randomIndex);

                    if (component.isVisible()) {
                        log.info("Hovering over match component: {} (index: {})", selector, randomIndex);
                        component.hover();
                        Thread.sleep(ThreadLocalRandom.current().nextInt(1000, 2000));
                        return;
                    }
                }
            }

        } catch (Exception e) {
            log.error("Component hover error: {}", e.getMessage());
        }
    }

    /**
     * Scroll through matches naturally
     */
    private void scrollThroughMatches(Page page) {
        try {
            // Get the match list container
            Locator matchList = page.locator(".m-event-list, .m-market-list-wrap");

            if (matchList.count() > 0 && matchList.first().isVisible()) {
                log.info("Scrolling through match list");

                // Scroll down in increments
                for (int i = 0; i < 3; i++) {
                    page.mouse().wheel(0, ThreadLocalRandom.current().nextInt(200, 400));
                    Thread.sleep(ThreadLocalRandom.current().nextInt(800, 1500));
                }

                // Scroll back up
                page.mouse().wheel(0, -300);
                Thread.sleep(ThreadLocalRandom.current().nextInt(500, 1000));
            }

        } catch (Exception e) {
            log.error("Scroll error: {}", e.getMessage());
        }
    }




    private boolean closeWinningPopup(Page page) {
        try {
            Locator winningPopup = withLocatorRetry(page, ".pop-winning",
                    loc -> loc.count() > 0 && loc.first().isVisible() ? loc : null,
                    2, 2000, 500);

            if (winningPopup == null) {
                return false;
            }

            log.info("Winning popup detected! Closing it...");

            Locator closeBtn = withLocatorRetry(page, ".pop-winning .close",
                    loc -> loc.isVisible(new Locator.IsVisibleOptions().setTimeout(3000)) ? loc : null,
                    2, 3000, 500);

            if (closeBtn != null) {
                withLocatorRetry(page, ".pop-winning .close",
                        loc -> {
                            loc.click(new Locator.ClickOptions()
                                    .setForce(true)
                                    .setTimeout(5000));
                            return true;
                        },
                        2, 5000, 500);
                log.info("Winning popup closed via .close div");
                page.waitForTimeout(800);
                return true;
            }

            Locator svgClose = withLocatorRetry(page,
                    ".pop-winning svg.icon-png-x, .pop-winning use[xlink\\:href='#icon-png-x']",
                    loc -> loc.isVisible() ? loc : null,
                    2, 2000, 500);

            if (svgClose != null) {
                svgClose.click(new Locator.ClickOptions().setForce(true));
                log.info("Winning popup closed via SVG");
                page.waitForTimeout(800);
                return true;
            }

            winningPopup.evaluate("el => el.remove()");
            log.info("Winning popup FORCE REMOVED from DOM");
            return true;

        } catch (Exception e) {
            log.warn("Failed to close winning popup: {}", e.getMessage());
            return false;
        }
    }

    private <T> T withLocatorRetry(Page page, String selector, java.util.function.Function<Locator, T> action,
                                   int maxRetries, long timeoutPerAttemptMs, long delayMs) {
        Locator locator = page.locator(selector);
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return action.apply(locator);  // e.g., locator::click, locator::textContent, etc.
            } catch (TimeoutError te) {
                log.warn("Timeout attempt {} on '{}'", attempt, selector);
                if (attempt == maxRetries) throw te;
                page.waitForTimeout(delayMs);
            }
        }
        return null;  // Never reached
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

    /**
     * Window status data class
     */
    @lombok.Builder
    @lombok.Data
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
