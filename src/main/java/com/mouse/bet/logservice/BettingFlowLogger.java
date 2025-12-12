package com.mouse.bet.logservice;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.BookMaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Centralized logger for betting flow events.
 * All logs are captured with structured context for routing to bookmaker-specific files.
 */
@Slf4j
@Component
public class BettingFlowLogger {

    private static final String EMOJI_START = "🎯";
    private static final String EMOJI_BET = "💰";
    private static final String EMOJI_WARNING = "⚠️";
    private static final String EMOJI_SYNC = "🔄";
    private static final String EMOJI_NAVIGATION = "🧭";
    private static final String EMOJI_SUCCESS = "✅";
    private static final String EMOJI_CLOCK = "⏰";
    private static final String EMOJI_ROCKET = "🚀";
    private static final String EMOJI_ERROR = "❌";

    // ==========================================
    // BET PLACEMENT START
    // ==========================================

    public void logBetPlacementStart(String arbId, BookMaker bookmaker, BigDecimal odds) {
        log.info("{} {} Starting bet placement | ArbId: {} | Bookmaker: {} | Odds: {}",
                EMOJI_START, EMOJI_BET, arbId, bookmaker, odds);
    }

    // ==========================================
    // INTENT REGISTRATION
    // ==========================================

    public void logArbCancelledDuringIntent(String arbId, BookMaker bookmaker) {
        log.warn("{} {} Arb already cancelled - skipping | ArbId: {} | Bookmaker: {}",
                EMOJI_WARNING, EMOJI_SYNC, arbId, bookmaker);
    }

    // ==========================================
    // NAVIGATION
    // ==========================================

    public void logNavigationStart(String arbId, BookMaker bookmaker) {
        log.info("{} {} Navigating to game | ArbId: {} | Bookmaker: {}",
                EMOJI_NAVIGATION, EMOJI_BET, arbId, bookmaker);
    }

    public void logGameNotAvailable(String arbId, BookMaker bookmaker) {
        log.info("{} {} Game not available during navigation | ArbId: {} | Bookmaker: {}",
                EMOJI_WARNING, EMOJI_BET, arbId, bookmaker);
    }

    // ==========================================
    // READY SYNC
    // ==========================================

    public void logPartnerTimeout(String arbId, BookMaker bookmaker) {
        log.warn("{} {} Partner timed out while we were navigating - skipping | ArbId: {} | Bookmaker: {}",
                EMOJI_WARNING, EMOJI_SYNC, arbId, bookmaker);
    }

    public void logMarkedReady(String arbId, BookMaker bookmaker) {
        log.info("{} {} Marked ready, waiting for partner | ArbId: {} | Bookmaker: {}",
                EMOJI_SUCCESS, EMOJI_SYNC, arbId, bookmaker);
    }

    public void logPartnersNotReady(String arbId, BookMaker bookmaker) {
        log.warn("{} {} Partners not ready - both windows skipping | ArbId: {} | Bookmaker: {}",
                EMOJI_WARNING, EMOJI_SYNC, arbId, bookmaker);
    }

    public void logBothPartnersReady(String arbId, BookMaker bookmaker) {
        log.info("{} {} ✓ Both partners ready, proceeding | ArbId: {} | Bookmaker: {}",
                EMOJI_SUCCESS, EMOJI_SYNC, arbId, bookmaker);
    }

    // ==========================================
    // ROLE DETERMINATION
    // ==========================================

    public void logPrimaryRole(String arbId, BookMaker bookmaker) {
        log.info("{} 🥇 I am PRIMARY bookmaker | ArbId: {} | Bookmaker: {}",
                EMOJI_ROCKET, arbId, bookmaker);
    }

    public void logSecondaryRole(String arbId, BookMaker bookmaker, BookMaker primaryBookmaker) {
        log.info("{} 🥈 I am SECONDARY bookmaker | ArbId: {} | Bookmaker: {} | Primary: {}",
                EMOJI_CLOCK, arbId, bookmaker, primaryBookmaker);
    }

    // ==========================================
    // EXCEPTION HANDLING
    // ==========================================

    public void logBetPlacementException(String arbId, BookMaker bookmaker, Exception e) {
        log.error("{} {} Exception during bet placement | ArbId: {} | Bookmaker: {} | Error: {}",
                EMOJI_ERROR, EMOJI_BET, arbId, bookmaker, e.getMessage(), e);
    }

    // ==========================================
    // PRIMARY BETTING FLOW
    // ==========================================

    public void logPrimaryBettingStart(String arbId, BookMaker bookmaker) {
        log.info("⚡ PRIMARY: Starting bet placement | ArbId: {} | Bookmaker: {}",
                arbId, bookmaker);
    }

    public void logPrimaryOddsNotAvailable(String arbId, BookMaker bookmaker) {
        log.info("{} {} PRIMARY: Odds not available or changed | ArbId: {} | Bookmaker: {}",
                EMOJI_WARNING, EMOJI_BET, arbId, bookmaker);
    }

    public void logPrimaryBetPlaced(String arbId, BookMaker bookmaker, BigDecimal stake, BigDecimal odds) {
        log.info("{} {} PRIMARY: Bet placed successfully | ArbId: {} | Bookmaker: {} | Stake: {} | Odds: {}",
                EMOJI_SUCCESS, EMOJI_BET, arbId, bookmaker, stake, odds);
    }

    public void logPrimaryBetFailed(String arbId, BookMaker bookmaker) {
        log.error("{} {} PRIMARY: Bet placement failed | ArbId: {} | Bookmaker: {}",
                EMOJI_ERROR, EMOJI_BET, arbId, bookmaker);
    }

    public void logPrimaryReadyForNext(String arbId, BookMaker bookmaker) {
        log.info("PRIMARY ready to move on to next LegTask | ArbId: {} | Bookmaker: {}",
                arbId, bookmaker);
    }

    // ==========================================
    // SECONDARY BETTING FLOW
    // ==========================================

    public void logSecondaryWaitingForPrimary(String arbId, BookMaker bookmaker) {
        log.info("⏳ SECONDARY: Waiting for primary to complete | ArbId: {} | Bookmaker: {}",
                arbId, bookmaker);
    }

    public void logSecondaryPrimaryFailed(String arbId, BookMaker bookmaker, String reason) {
        log.warn("{} {} SECONDARY: Primary failed or timeout - NOT placing bet | ArbId: {} | Bookmaker: {} | Reason: {}",
                EMOJI_WARNING, EMOJI_SYNC, arbId, bookmaker, reason);
    }

    public void logSecondaryPrimarySucceeded(String arbId, BookMaker bookmaker) {
        log.info("{} {} SECONDARY: Primary succeeded - proceeding with bet | ArbId: {} | Bookmaker: {}",
                EMOJI_SUCCESS, EMOJI_SYNC, arbId, bookmaker);
    }

    public void logSecondaryOddsNotAvailableAfterPrimary(String arbId, BookMaker bookmaker) {
        log.error("{} {} SECONDARY: Odds not available after PRIMARY success! | ArbId: {} | Bookmaker: {}",
                EMOJI_ERROR, EMOJI_BET, arbId, bookmaker);
    }

    public void logSecondaryBetPlaced(String arbId, BookMaker bookmaker, BigDecimal stake, BigDecimal odds) {
        log.info("{} {} SECONDARY: Bet placed successfully | ArbId: {} | Bookmaker: {} | Stake: {} | Odds: {}",
                EMOJI_SUCCESS, EMOJI_BET, arbId, bookmaker, stake, odds);
    }

    public void logSecondaryBetFailedAfterPrimary(String arbId, BookMaker bookmaker) {
        log.error("{} {} SECONDARY: Bet placement FAILED after PRIMARY success! | ArbId: {} | Bookmaker: {}",
                EMOJI_ERROR, EMOJI_BET, arbId, bookmaker);
    }

    public void logSecondaryRetryAttempt(String arbId, BookMaker bookmaker) {
        log.warn("{} {} SECONDARY: Attempting retry since primary succeeded | ArbId: {} | Bookmaker: {}",
                EMOJI_WARNING, EMOJI_SYNC, arbId, bookmaker);
    }

    public void logSecondaryRetrySuccess(String arbId, BookMaker bookmaker) {
        log.info("✓ SECONDARY: Bet placed after retry | ArbId: {} | Bookmaker: {}",
                arbId, bookmaker);
    }

    public void logSecondaryRetryFailedRollback(String arbId, BookMaker bookmaker) {
        log.error("✗ SECONDARY: Bet failed after retry - rollback required | ArbId: {} | Bookmaker: {}",
                arbId, bookmaker);
    }

    public void logSecondaryReadyForNext(String arbId, BookMaker bookmaker) {
        log.info("SECONDARY ready to move on to next LegTask | ArbId: {} | Bookmaker: {}",
                arbId, bookmaker);
    }

    // ==========================================
    // PRIMARY ROLLBACK FLOW
    // ==========================================

    public void logPrimaryWaitingForSecondary(String arbId, BookMaker bookmaker, String betId) {
        log.info("PRIMARY: Waiting for secondary to complete | ArbId: {} | Bookmaker: {} | BetId: {}",
                arbId, bookmaker, betId);
    }

    public void logPrimaryRollbackNeeded(String arbId, BookMaker bookmaker, String betId, String reason) {
        log.warn("{} 🔄 PRIMARY: ROLLBACK NEEDED | ArbId: {} | Bookmaker: {} | BetId: {} | Reason: {}",
                EMOJI_WARNING, arbId, bookmaker, betId, reason);
    }

    public void logPrimaryRollbackSuccess(String arbId, BookMaker bookmaker, String betId) {
        log.info("✅ PRIMARY: Rollback successful | ArbId: {} | Bookmaker: {} | BetId: {}",
                arbId, bookmaker, betId);
    }

    public void logPrimaryRollbackFailed(String arbId, BookMaker bookmaker, String betId) {
        log.error("❌ PRIMARY: Rollback FAILED | ArbId: {} | Bookmaker: {} | BetId: {} - ⚠️ MANUAL INTERVENTION REQUIRED!",
                arbId, bookmaker, betId);
    }

    public void logPrimarySecondarySucceeded(String arbId, BookMaker bookmaker) {
        log.info("✅ PRIMARY: Secondary succeeded - no rollback needed | ArbId: {} | Bookmaker: {}",
                arbId, bookmaker);
    }

    public void logPrimaryInterruptedWaitingForRollback(String arbId, BookMaker bookmaker) {
        log.warn("PRIMARY: Interrupted while waiting for rollback status | ArbId: {} | Bookmaker: {}",
                arbId, bookmaker);
    }

    // ==========================================
    // ROLLBACK EXECUTION
    // ==========================================

    public void logRollbackAttemptStart(String arbId, BookMaker bookmaker, String betId) {
        log.warn("🔄 PRIMARY: Attempting to rollback bet | ArbId: {} | Bookmaker: {} | BetId: {}",
                arbId, bookmaker, betId);
    }

    public void logRollbackBetFound(String betId, BookMaker bookmaker) {
        log.info("Found bet in history | Bookmaker: {} | BetId: {}", bookmaker, betId);
    }

    public void logRollbackCashOutAvailable(String betId, BookMaker bookmaker) {
        log.info("Cash out available | Bookmaker: {} | BetId: {}", bookmaker, betId);
    }

    public void logRollbackCashOutExecuted(String betId, BookMaker bookmaker) {
        log.info("✅ Cash out executed | Bookmaker: {} | BetId: {}", bookmaker, betId);
    }

    public void logRollbackCashOutNotAvailable(String betId, BookMaker bookmaker) {
        log.warn("Cash out not available (bet may be live) | Bookmaker: {} | BetId: {}",
                bookmaker, betId);
    }

    public void logRollbackHedgeAttempt(String betId, BookMaker bookmaker) {
        log.info("Attempting hedge bet instead | Bookmaker: {} | BetId: {}", bookmaker, betId);
    }

    public void logRollbackBetNotFound(String betId, BookMaker bookmaker) {
        log.warn("Bet not found in history yet | Bookmaker: {} | BetId: {}", bookmaker, betId);
    }

    public void logRollbackException(String betId, BookMaker bookmaker, Exception e) {
        log.error("💥 Exception during rollback | Bookmaker: {} | BetId: {} | Error: {}",
                bookmaker, betId, e.getMessage(), e);
    }

    // ==========================================
    // ADDITIONAL CONTEXTUAL LOGS
    // ==========================================

    public void logRollbackRequest(String arbId, BookMaker bookmaker, String reason) {
        log.error("🔙 Rollback requested | ArbId: {} | Bookmaker: {} | Reason: {}",
                arbId, bookmaker, reason);
    }

    public void logBetConfirmationWait(String arbId, BookMaker bookmaker) {
        log.debug("Waiting for bet confirmation | ArbId: {} | Bookmaker: {}",
                arbId, bookmaker);
    }

    public void logBetIdExtracted(String arbId, BookMaker bookmaker, String betId) {
        log.info("Bet ID extracted | ArbId: {} | Bookmaker: {} | BetId: {}",
                arbId, bookmaker, betId);
    }

    public void logArbSuccess(String arbId, BookMaker bookmaker) {
        log.info("🎉 ARB COMPLETED SUCCESSFULLY | ArbId: {} | Bookmaker: {}",
                arbId, bookmaker);
    }

    public void logArbFailure(String arbId, BookMaker bookmaker, String reason) {
        log.error("💥 ARB FAILED | ArbId: {} | Bookmaker: {} | Reason: {}",
                arbId, bookmaker, reason);
    }

    public void logBetDeploymentCheck(String arbId, BookMaker bookmaker, boolean deployed) {
        log.info("Bet deployment check | ArbId: {} | Bookmaker: {} | Deployed: {}",
                arbId, bookmaker, deployed);
    }

    public void logStakeSpent(String arbId, BookMaker bookmaker, BigDecimal stake) {
        log.info("💸 Stake spent | ArbId: {} | Bookmaker: {} | Amount: {}",
                arbId, bookmaker, stake);
    }

    public void logStakeCredited(String arbId, BookMaker bookmaker, double amount) {
        log.info("💰 Stake credited back | ArbId: {} | Bookmaker: {} | Amount: {}",
                arbId, bookmaker, amount);
    }
}