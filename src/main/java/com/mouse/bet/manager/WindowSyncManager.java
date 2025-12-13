package com.mouse.bet.manager;

import com.mouse.bet.enums.BookMaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class WindowSyncManager {

    // Tracks which windows have signaled "ready" for a specific arbId
    private final Map<String, ArbSyncState> syncMap = new ConcurrentHashMap<>();

    // Registered windows (only two expected: SPORTY_BET and MSPORT)
    private final Map<BookMaker, Object> registeredWindows = new ConcurrentHashMap<>();

    // Strategy for determining primary bookmaker
    private PrimaryBookmakerStrategy primaryStrategy = PrimaryBookmakerStrategy.MSPORT_FIRST; //todo: change to lower odd first

    /**
     * Register a window (SportyWindow or MsportWindow) with the sync manager
     */
    public synchronized void registerWindow(BookMaker bookmaker, Object window) {
        registeredWindows.put(bookmaker, window);
        log.info("Window registered: {} ({})", bookmaker, window.getClass().getSimpleName());
    }

    /**
     * Set the strategy for determining primary bookmaker
     */
    public void setPrimaryBookmakerStrategy(PrimaryBookmakerStrategy strategy) {
        this.primaryStrategy = strategy;
        log.info("Primary bookmaker strategy set to: {}", strategy);
    }

    /**
     * Register intent to participate in this arb (called BEFORE navigation)
     * Returns false if arb is already cancelled
     */
    public boolean registerIntent(String arbId, BookMaker bookmaker, double odds) {
        ArbSyncState state = syncMap.computeIfAbsent(arbId, k -> new ArbSyncState());

        if (state.isCancelled()) {
            log.warn("⚠️ Cannot register intent - arb already cancelled | ArbId: {} | Bookmaker: {}",
                    arbId, bookmaker);
            return false;
        }

        state.registerIntent(bookmaker, odds);
        log.info("✓ Intent registered | ArbId: {} | Bookmaker: {} | Odds: {}", arbId, bookmaker, odds);
        return true;
    }

    /**
     * Unregister intent (cleanup)
     */
    public void unRegisterIntent(String arbId, BookMaker bookmaker) {
        ArbSyncState state = syncMap.get(arbId);
        if (state != null) {
            state.unRegisterIntent(bookmaker);
            log.info("Intent unregistered | ArbId: {} | Bookmaker: {}", arbId, bookmaker);
        }
    }

    /**
     * Called by a window when it's ready to place bet for a specific arb
     * Returns false if arb is already cancelled
     */
    public boolean markReady(String arbId, BookMaker bookmaker) {
        ArbSyncState state = syncMap.computeIfAbsent(arbId, k -> new ArbSyncState());

        if (state.isCancelled()) {
            log.warn("⚠️ Cannot mark ready - arb already cancelled by partner | ArbId: {} | Bookmaker: {}",
                    arbId, bookmaker);
            return false;
        }

        state.markReady(bookmaker);
        log.info("✓ Window READY | ArbId: {} | Bookmaker: {}", arbId, bookmaker);
        return true;
    }

    /**
     * Wait for partner with automatic skip on timeout
     * Returns true only if BOTH windows are ready and synchronized
     */
    public boolean waitForPartnersReadyOrTimeout(String arbId, BookMaker myBookmaker, Duration timeout) {
        ArbSyncState state = syncMap.computeIfAbsent(arbId, k -> new ArbSyncState());

        if (state.isCancelled()) {
            log.warn("⚠️ Arb already cancelled by partner, not waiting | ArbId: {} | Bookmaker: {}",
                    arbId, myBookmaker);
            return false;
        }

        BookMaker partner = myBookmaker == BookMaker.SPORTY_BET ? BookMaker.M_SPORT : BookMaker.SPORTY_BET;

        if (!state.isReady(myBookmaker)) {
            log.error("❌ BUG: Waiting but {} never marked ready! | ArbId: {}", myBookmaker, arbId);
            skipArbAndSync(arbId);
            return false;
        }

        log.info("⏳ Waiting for partner {} to be ready | ArbId: {} | Timeout: {}s | My bookmaker: {}",
                partner, arbId, timeout.toSeconds(), myBookmaker);

        long startTime = System.currentTimeMillis();

        try {
            boolean awaitResult = state.latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            long elapsedTime = System.currentTimeMillis() - startTime;

            if (state.isCancelled()) {
                log.warn("⚠️ Arb was cancelled while waiting | ArbId: {} | Elapsed: {}ms | Bookmaker: {}",
                        arbId, elapsedTime, myBookmaker);
                return false;
            }

            if (awaitResult) {
                if (state.areBothReady()) {
                    // Determine primary/secondary ONCE when both are ready
                    state.determinePrimaryBookmaker(primaryStrategy);
                    log.info("✅ Both windows READY and synchronized | ArbId: {} | Elapsed: {}ms | Primary: {} | Secondary: {}",
                            arbId, elapsedTime, state.getPrimaryBookmaker(), state.getSecondaryBookmaker());
                    return true;
                } else {
                    log.error("⚠️ Latch completed but not both ready (race condition?) | ArbId: {} | Ready: {}",
                            arbId, state.getReadyWindows());
                    skipArbAndSync(arbId);
                    return false;
                }
            } else {
                log.warn("⏱️ Timeout waiting for partner {} | ArbId: {} | Elapsed: {}ms | Bookmaker: {}",
                        partner, arbId, elapsedTime, myBookmaker);

                if (state.trySetTimeoutFlag(myBookmaker)) {
                    log.info("🚫 {} triggered timeout - skipping arb for both windows | ArbId: {}",
                            myBookmaker, arbId);
                    skipArbAndSync(arbId);
                } else {
                    log.info("⏭️ Partner already triggered timeout - following skip | ArbId: {}", arbId);
                }

                return false;
            }
        } catch (InterruptedException e) {
            long elapsedTime = System.currentTimeMillis() - startTime;
            Thread.currentThread().interrupt();
            log.warn("🛑 Interrupted while waiting for partner | ArbId: {} | Elapsed: {}ms | Bookmaker: {}",
                    arbId, elapsedTime, myBookmaker);

            if (state.trySetTimeoutFlag(myBookmaker)) {
                skipArbAndSync(arbId);
            }

            return false;
        }
    }

    /**
     * Determine if this bookmaker should bet first (is primary)
     */
    public boolean isPrimaryBookmaker(String arbId, BookMaker bookmaker) {
        ArbSyncState state = syncMap.get(arbId);
        if (state == null) {
            log.warn("⚠️ No sync state found for arbId: {}", arbId);
            return false;
        }
        return bookmaker.equals(state.getPrimaryBookmaker());
    }

    /**
     * Get the primary bookmaker for this arb
     */
    public BookMaker getPrimaryBookmaker(String arbId) {
        ArbSyncState state = syncMap.get(arbId);
        return state != null ? state.getPrimaryBookmaker() : null;
    }

    /**
     * Wait for primary bookmaker to complete their bet
     * Secondary bookmaker calls this before placing their bet
     */
    public BetResult waitForPrimaryBetResult(String arbId, BookMaker myBookmaker, Duration timeout) {
        ArbSyncState state = syncMap.get(arbId);
        if (state == null) {
            log.error("❌ No sync state found | ArbId: {}", arbId);
            return BetResult.error("No sync state");
        }

        if (state.isCancelled()) {
            log.warn("⚠️ Arb cancelled, not waiting for primary | ArbId: {}", arbId);
            return BetResult.cancelled("Arb cancelled");
        }

        BookMaker primary = state.getPrimaryBookmaker();
        if (primary == null) {
            log.error("❌ Primary bookmaker not determined | ArbId: {}", arbId);
            return BetResult.error("Primary not determined");
        }

        if (myBookmaker.equals(primary)) {
            log.error("❌ BUG: Primary bookmaker trying to wait for itself | ArbId: {} | Bookmaker: {}",
                    arbId, myBookmaker);
            return BetResult.error("Invalid call - primary cannot wait for itself");
        }

        log.info("⏳ Secondary {} waiting for primary {} to complete bet | ArbId: {} | Timeout: {}s",
                myBookmaker, primary, arbId, timeout.toSeconds());

        long startTime = System.currentTimeMillis();

        try {
            boolean completed = state.primaryCompletedLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - startTime;

            if (!completed) {
                log.warn("⏱️ Timeout waiting for primary {} | ArbId: {} | Elapsed: {}ms",
                        primary, arbId, elapsed);
                skipArbAndSync(arbId);
                return BetResult.timeout("Primary bet timeout");
            }

            if (state.isCancelled()) {
                log.warn("⚠️ Arb cancelled while waiting for primary | ArbId: {}", arbId);
                return BetResult.cancelled("Arb cancelled");
            }

            if (state.hasPlacedBet(primary)) {
                log.info("✅ Primary {} successfully placed bet | ArbId: {} | Elapsed: {}ms",
                        primary, arbId, elapsed);
                return BetResult.success();
            } else {
                String failureReason = state.getFailureReason(primary);
                log.warn("❌ Primary {} failed to place bet | ArbId: {} | Reason: {} | Elapsed: {}ms",
                        primary, arbId, failureReason, elapsed);
                skipArbAndSync(arbId);
                return BetResult.failure(failureReason);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("🛑 Interrupted waiting for primary | ArbId: {} | Bookmaker: {}", arbId, myBookmaker);
            skipArbAndSync(arbId);
            return BetResult.error("Interrupted");
        }
    }

    /**
     * Notify that primary bookmaker has completed (success or failure)
     * This releases the secondary bookmaker
     */
    public void notifyPrimaryCompleted(String arbId, BookMaker bookmaker, boolean success, String failureReason) {
        ArbSyncState state = syncMap.get(arbId);
        if (state == null) {
            log.warn("⚠️ No sync state for primary completion | ArbId: {}", arbId);
            return;
        }

        if (!bookmaker.equals(state.getPrimaryBookmaker())) {
            log.error("❌ BUG: Non-primary bookmaker trying to signal completion | ArbId: {} | Bookmaker: {} | Primary: {}",
                    arbId, bookmaker, state.getPrimaryBookmaker());
            return;
        }

        if (success) {
            state.recordBetPlaced(bookmaker);
            log.info("✅ Primary {} completed SUCCESSFULLY | ArbId: {}", bookmaker, arbId);
        } else {
            state.recordFailure(bookmaker, failureReason);
            log.warn("❌ Primary {} completed with FAILURE | ArbId: {} | Reason: {}", bookmaker, arbId, failureReason);
        }

        // Release secondary bookmaker
        state.primaryCompletedLatch.countDown();
    }

    /**
     * Notify that this bookmaker successfully placed the bet
     */
    public void notifyBetPlaced(String arbId, BookMaker bookmaker) {
        ArbSyncState state = syncMap.get(arbId);
        if (state != null) {
            state.recordBetPlaced(bookmaker);
            log.info("✅ Bet PLACED and recorded | ArbId: {} | Bookmaker: {}", arbId, bookmaker);
        }
        cleanupIfDone(arbId);
    }

    /**
     * Notify that bet placement failed
     */
    public void notifyBetFailure(String arbId, BookMaker bookmaker, String reason) {
        ArbSyncState state = syncMap.get(arbId);
        if (state != null) {
            state.recordFailure(bookmaker, reason);
            log.warn("❌ Bet FAILED | ArbId: {} | Bookmaker: {} | Reason: {}", arbId, bookmaker, reason);
        }
        cleanupIfDone(arbId);
    }

    /**
     * Request rollback - called by secondary if they fail after primary succeeded
     * Returns true if rollback was initiated
     */
    public boolean requestRollback(String arbId, BookMaker requestor, String reason) {
        ArbSyncState state = syncMap.get(arbId);
        if (state == null) {
            log.warn("⚠️ No sync state for rollback request | ArbId: {}", arbId);
            return false;
        }

        log.warn("🔄 ROLLBACK REQUESTED | ArbId: {} | Requestor: {} | Reason: {}", arbId, requestor, reason);

        state.initiateRollback(requestor, reason);
        return true;
    }

    /**
     * Check if rollback is needed for this bookmaker
     */
    public boolean needsRollback(String arbId, BookMaker bookmaker) {
        ArbSyncState state = syncMap.get(arbId);
        if (state == null) return false;

        return state.needsRollback() && state.hasPlacedBet(bookmaker);
    }

    /**
     * Get rollback reason
     */
    public String getRollbackReason(String arbId) {
        ArbSyncState state = syncMap.get(arbId);
        return state != null ? state.getRollbackReason() : null;
    }

    /**
     * Notify that rollback completed for a bookmaker
     */
    public void notifyRollbackCompleted(String arbId, BookMaker bookmaker, boolean success) {
        ArbSyncState state = syncMap.get(arbId);
        if (state != null) {
            state.recordRollbackCompleted(bookmaker, success);
            log.info("🔄 Rollback {} for {} | ArbId: {}",
                    success ? "SUCCEEDED" : "FAILED", bookmaker, arbId);
        }
        cleanupIfDone(arbId);
    }

    /**
     * Skip this arb and cancel for both windows
     */
    public void skipArbAndSync(String arbId) {
        ArbSyncState state = syncMap.get(arbId);
        if (state != null) {
            state.cancel();
            log.info("⏭️ Arb cancelled and synced - both windows will skip | ArbId: {}", arbId);
        } else {
            log.warn("⚠️ Attempted to skip non-existent arb state | ArbId: {}", arbId);
        }
        scheduledCleanup(arbId, 5000);
    }

    /**
     * Check if arb was cancelled/skipped
     */
    public boolean wasArbCancelled(String arbId) {
        ArbSyncState state = syncMap.get(arbId);
        return state != null && state.isCancelled();
    }

    /**
     * Check if the partner has already placed their bet
     */
    public boolean hasPartnerPlacedBet(String arbId, BookMaker myBookmaker) {
        ArbSyncState state = syncMap.get(arbId);
        if (state == null) return false;

        BookMaker partner = myBookmaker == BookMaker.SPORTY_BET ? BookMaker.M_SPORT : BookMaker.SPORTY_BET;
        return state.hasPlacedBet(partner);
    }

    /**
     * Get detailed sync status for debugging
     */
    public String getSyncStatus(String arbId) {
        ArbSyncState state = syncMap.get(arbId);
        if (state == null) {
            return "No sync state";
        }
        return state.getStateSummary();
    }

    private void scheduledCleanup(String arbId, long delayMs) {
        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
                .execute(() -> {
                    syncMap.remove(arbId);
                    log.debug("🧹 Delayed cleanup completed | ArbId: {}", arbId);
                });
    }

    private void cleanupIfDone(String arbId) {
        ArbSyncState state = syncMap.get(arbId);
        if (state != null && state.isCompleted()) {
            syncMap.remove(arbId);
            log.debug("🧹 Cleaned up sync state for ArbId: {}", arbId);
        }
    }

    public synchronized void unregisterWindow(BookMaker bookmaker) {
        registeredWindows.remove(bookmaker);
        log.info("Window unregistered: {}", bookmaker);
    }

    public void clearPendingForBookmaker(BookMaker bookmaker) {
        int cleared = 0;
        for (Map.Entry<String, ArbSyncState> entry : syncMap.entrySet()) {
            ArbSyncState state = entry.getValue();
            if (state.hasIntent(bookmaker) && !state.isCompleted()) {
                skipArbAndSync(entry.getKey());
                cleared++;
            }
        }
        log.info("Cleared {} pending sync states for {}", cleared, bookmaker);
    }

    public void clearAll() {
        syncMap.clear();
        log.info("🧹 WindowSyncManager state cleared");
    }

    public int getActiveCoordinationCount() {
        return syncMap.size();
    }

    public int getRegisteredWindowCount() {
        return registeredWindows.size();
    }

    // =================================================================
    // Supporting Classes
    // =================================================================

    public enum PrimaryBookmakerStrategy {
        LOWER_ODDS_FIRST,      // Lower odds (safer bet) goes first
        HIGHER_ODDS_FIRST,     // Higher odds (riskier bet) goes first
        SPORTY_BET_FIRST,      // Always SportyBet first
        MSPORT_FIRST,          // Always MSport first
        ROUND_ROBIN            // Alternate between bookmakers
    }

    public static class BetResult {
        private final ResultType type;
        private final String message;

        private BetResult(ResultType type, String message) {
            this.type = type;
            this.message = message;
        }

        public static BetResult success() {
            return new BetResult(ResultType.SUCCESS, null);
        }

        public static BetResult failure(String reason) {
            return new BetResult(ResultType.FAILURE, reason);
        }

        public static BetResult timeout(String reason) {
            return new BetResult(ResultType.TIMEOUT, reason);
        }

        public static BetResult cancelled(String reason) {
            return new BetResult(ResultType.CANCELLED, reason);
        }

        public static BetResult error(String reason) {
            return new BetResult(ResultType.ERROR, reason);
        }

        public boolean isSuccess() {
            return type == ResultType.SUCCESS;
        }

        public boolean shouldProceed() {
            return type == ResultType.SUCCESS;
        }

        public String getMessage() {
            return message;
        }

        public ResultType getType() {
            return type;
        }

        public enum ResultType {
            SUCCESS, FAILURE, TIMEOUT, CANCELLED, ERROR
        }
    }

    // =================================================================
    // Inner class to track synchronization state per Arb
    // =================================================================
    private static class ArbSyncState {
        private final CountDownLatch latch = new CountDownLatch(2);
        private final CountDownLatch primaryCompletedLatch = new CountDownLatch(1);
        private final Set<BookMaker> intentRegistered = ConcurrentHashMap.newKeySet();
        private final Set<BookMaker> readyWindows = ConcurrentHashMap.newKeySet();
        private final Map<BookMaker, Double> oddsMap = new ConcurrentHashMap<>();
        private final Map<BookMaker, Boolean> betPlaced = new ConcurrentHashMap<>();
        private final Map<BookMaker, String> failures = new ConcurrentHashMap<>();
        private final Map<BookMaker, Boolean> rollbackCompleted = new ConcurrentHashMap<>();

        private volatile boolean cancelled = false;
        private volatile BookMaker timeoutTriggeredBy = null;
        private volatile BookMaker primaryBookmaker = null;
        private volatile BookMaker secondaryBookmaker = null;
        private volatile boolean rollbackNeeded = false;
        private volatile String rollbackReason = null;
        private volatile BookMaker rollbackRequestor = null;

        private final AtomicBoolean timeoutFlagSet = new AtomicBoolean(false);
        private static int roundRobinCounter = 0;

        public synchronized void registerIntent(BookMaker bookmaker, double odds) {
            if (!cancelled) {
                intentRegistered.add(bookmaker);
                oddsMap.put(bookmaker, odds);
            }
        }

        public synchronized void unRegisterIntent(BookMaker bookmaker) {
            intentRegistered.remove(bookmaker);
            oddsMap.remove(bookmaker);
        }

        public synchronized void markReady(BookMaker bookmaker) {
            if (!cancelled && readyWindows.add(bookmaker)) {
                latch.countDown();
            }
        }

        public boolean isReady(BookMaker bookmaker) {
            return readyWindows.contains(bookmaker);
        }

        public boolean areBothReady() {
            return readyWindows.size() == 2;
        }

        public Set<BookMaker> getReadyWindows() {
            return new HashSet<>(readyWindows);
        }

        public synchronized void determinePrimaryBookmaker(PrimaryBookmakerStrategy strategy) {
            if (primaryBookmaker != null) {
                return; // Already determined
            }

            List<BookMaker> bookmakers = new ArrayList<>(readyWindows);
            if (bookmakers.size() != 2) {
                return;
            }

            BookMaker bm1 = bookmakers.get(0);
            BookMaker bm2 = bookmakers.get(1);

            switch (strategy) {
                case LOWER_ODDS_FIRST:
                    double odds1 = oddsMap.getOrDefault(bm1, 0.0);
                    double odds2 = oddsMap.getOrDefault(bm2, 0.0);
                    primaryBookmaker = odds1 <= odds2 ? bm1 : bm2;
                    break;

                case HIGHER_ODDS_FIRST:
                    odds1 = oddsMap.getOrDefault(bm1, 0.0);
                    odds2 = oddsMap.getOrDefault(bm2, 0.0);
                    primaryBookmaker = odds1 >= odds2 ? bm1 : bm2;
                    break;

                case SPORTY_BET_FIRST:
                    primaryBookmaker = BookMaker.SPORTY_BET;
                    break;

                case MSPORT_FIRST:
                    primaryBookmaker = BookMaker.M_SPORT;
                    break;

                case ROUND_ROBIN:
                    roundRobinCounter++;
                    primaryBookmaker = (roundRobinCounter % 2 == 0) ? BookMaker.SPORTY_BET : BookMaker.M_SPORT;
                    break;

                default:
                    primaryBookmaker = bm1; // Fallback
            }

            secondaryBookmaker = primaryBookmaker == bm1 ? bm2 : bm1;
        }

        public BookMaker getPrimaryBookmaker() {
            return primaryBookmaker;
        }

        public BookMaker getSecondaryBookmaker() {
            return secondaryBookmaker;
        }

        public boolean trySetTimeoutFlag(BookMaker bookmaker) {
            if (timeoutFlagSet.compareAndSet(false, true)) {
                timeoutTriggeredBy = bookmaker;
                return true;
            }
            return false;
        }

        public synchronized void cancel() {
            if (!cancelled) {
                cancelled = true;
                while (latch.getCount() > 0) {
                    latch.countDown();
                }
                while (primaryCompletedLatch.getCount() > 0) {
                    primaryCompletedLatch.countDown();
                }
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public synchronized void recordBetPlaced(BookMaker bookmaker) {
            betPlaced.put(bookmaker, true);
        }

        public synchronized void recordFailure(BookMaker bookmaker, String reason) {
            failures.put(bookmaker, reason);
            betPlaced.put(bookmaker, false);
        }

        public boolean hasPlacedBet(BookMaker bookmaker) {
            return Boolean.TRUE.equals(betPlaced.get(bookmaker));
        }

        public String getFailureReason(BookMaker bookmaker) {
            return failures.get(bookmaker);
        }

        public synchronized void initiateRollback(BookMaker requestor, String reason) {
            rollbackNeeded = true;
            rollbackReason = reason;
            rollbackRequestor = requestor;
        }

        public boolean needsRollback() {
            return rollbackNeeded;
        }

        public String getRollbackReason() {
            return rollbackReason;
        }

        public synchronized void recordRollbackCompleted(BookMaker bookmaker, boolean success) {
            rollbackCompleted.put(bookmaker, success);
        }

        public boolean isCompleted() {
            if (rollbackNeeded) {
                // If rollback needed, wait for rollback completion
                return rollbackCompleted.size() >= betPlaced.keySet().stream()
                        .filter(bm -> Boolean.TRUE.equals(betPlaced.get(bm))).count();
            }
            return betPlaced.size() >= 2 || failures.size() >= 1 || cancelled;
        }

        public String getStateSummary() {
            return String.format("Primary: %s, Secondary: %s, Ready: %s, Placed: %s, Failed: %s, " +
                            "Cancelled: %s, Rollback: %s, TimeoutBy: %s",
                    primaryBookmaker, secondaryBookmaker, readyWindows, betPlaced.keySet(),
                    failures.keySet(), cancelled, rollbackNeeded, timeoutTriggeredBy);
        }

        public boolean hasIntent(BookMaker bookmaker) {
            return intentRegistered.contains(bookmaker);
        }
    }
}