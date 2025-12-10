package com.mouse.bet.manager;

import com.mouse.bet.enums.BookMaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class WindowSyncManager {

    // Tracks which windows have signaled "ready" for a specific arbId
    private final Map<String, ArbSyncState> syncMap = new ConcurrentHashMap<>();

    // Registered windows (only two expected: SPORTY_BET and MSPORT)
    private final Map<BookMaker, Object> registeredWindows = new ConcurrentHashMap<>();

    /**
     * Register a window (SportyWindow or MsportWindow) with the sync manager
     */
    public synchronized void registerWindow(BookMaker bookmaker, Object window) {
        registeredWindows.put(bookmaker, window);
        log.info("Window registered: {} ({})", bookmaker, window.getClass().getSimpleName());
    }

    /**
     * Register intent to participate in this arb (called BEFORE navigation)
     * Returns false if arb is already cancelled
     */
    public boolean registerIntent(String arbId, BookMaker bookmaker) {
        ArbSyncState state = syncMap.computeIfAbsent(arbId, k -> new ArbSyncState());

        // Check if already cancelled before registering
        if (state.isCancelled()) {
            log.warn("⚠️ Cannot register intent - arb already cancelled | ArbId: {} | Bookmaker: {}",
                    arbId, bookmaker);
            return false;
        }

        state.registerIntent(bookmaker);
        log.info("✓ Intent registered | ArbId: {} | Bookmaker: {}", arbId, bookmaker);
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

        // ✅ CHECK IF ALREADY CANCELLED (partner may have timed out)
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
     * Returns false immediately if arb is already cancelled
     */
    public boolean waitForPartnersReadyOrTimeout(String arbId, BookMaker myBookmaker, Duration timeout) {
        ArbSyncState state = syncMap.computeIfAbsent(arbId, k -> new ArbSyncState());

        // ✅ CHECK IF ALREADY CANCELLED BEFORE WAITING (partner may have timed out)
        if (state.isCancelled()) {
            log.warn("⚠️ Arb already cancelled by partner, not waiting | ArbId: {} | Bookmaker: {}",
                    arbId, myBookmaker);
            return false;
        }

        BookMaker partner = myBookmaker == BookMaker.SPORTY_BET ? BookMaker.M_SPORT : BookMaker.SPORTY_BET;

        // Check if we actually marked ourselves as ready (defensive check)
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

            // ✅ RE-CHECK CANCELLATION AFTER WAKING UP (could be cancelled during wait)
            if (state.isCancelled()) {
                log.warn("⚠️ Arb was cancelled while waiting | ArbId: {} | Elapsed: {}ms | Bookmaker: {}",
                        arbId, elapsedTime, myBookmaker);
                return false;
            }

            if (awaitResult) {
                // Latch reached zero - verify BOTH are actually ready
                if (state.areBothReady()) {
                    log.info("✅ Both windows READY and synchronized | ArbId: {} | Elapsed: {}ms",
                            arbId, elapsedTime);
                    return true;
                } else {
                    // Edge case: latch counted down but not both ready (shouldn't happen)
                    log.error("⚠️ Latch completed but not both ready (race condition?) | ArbId: {} | Ready: {}",
                            arbId, state.getReadyWindows());
                    skipArbAndSync(arbId);
                    return false;
                }
            } else {
                // Timeout occurred
                log.warn("⏱️ Timeout waiting for partner {} | ArbId: {} | Elapsed: {}ms | Bookmaker: {}",
                        partner, arbId, elapsedTime, myBookmaker);

                // ✅ ONLY THE FIRST WINDOW TO DETECT TIMEOUT SHOULD TRIGGER SKIP
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

            // Only trigger skip if we're the first to be interrupted
            if (state.trySetTimeoutFlag(myBookmaker)) {
                skipArbAndSync(arbId);
            }

            return false;
        }
    }

    /**
     * Skip this arb and cancel for both windows
     * This ensures the partner window will also fail when it reaches any sync method
     */
    public void skipArbAndSync(String arbId) {
        ArbSyncState state = syncMap.get(arbId);
        if (state != null) {
            state.cancel();
            log.info("⏭️ Arb cancelled and synced - both windows will skip | ArbId: {}", arbId);
        } else {
            log.warn("⚠️ Attempted to skip non-existent arb state | ArbId: {}", arbId);
        }
        // Keep state for a bit so late-arriving window sees cancellation
        scheduledCleanup(arbId, 5000); // Clean up after 5 seconds
    }

    /**
     * Check if arb was cancelled/skipped
     */
    public boolean wasArbCancelled(String arbId) {
        ArbSyncState state = syncMap.get(arbId);
        return state != null && state.isCancelled();
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

    /**
     * Schedule cleanup of arb state after delay
     */
    private void scheduledCleanup(String arbId, long delayMs) {
        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
                .execute(() -> {
                    syncMap.remove(arbId);
                    log.debug("🧹 Delayed cleanup completed | ArbId: {}", arbId);
                });
    }

    /**
     * Clean up sync state after both windows are done (success or failure)
     */
    private void cleanupIfDone(String arbId) {
        ArbSyncState state = syncMap.get(arbId);
        if (state != null && state.isCompleted()) {
            syncMap.remove(arbId);
            log.debug("🧹 Cleaned up sync state for ArbId: {}", arbId);
        }
    }

    /**
     * Unregister a window (called when loop exits)
     */
    public synchronized void unregisterWindow(BookMaker bookmaker) {
        registeredWindows.remove(bookmaker);
        log.info("Window unregistered: {}", bookmaker);
    }

    /**
     * Clear all pending sync states for a specific bookmaker
     */
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

    /**
     * Force cleanup (e.g., on shutdown)
     */
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
    // Inner class to track synchronization state per Arb
    // =================================================================
    private static class ArbSyncState {
        private final CountDownLatch latch = new CountDownLatch(2);
        private final Set<BookMaker> intentRegistered = ConcurrentHashMap.newKeySet();
        private final Set<BookMaker> readyWindows = ConcurrentHashMap.newKeySet();
        private final Map<BookMaker, Boolean> betPlaced = new ConcurrentHashMap<>();
        private final Map<BookMaker, String> failures = new ConcurrentHashMap<>();
        private volatile boolean cancelled = false;
        private volatile BookMaker timeoutTriggeredBy = null;
        private final AtomicBoolean timeoutFlagSet = new AtomicBoolean(false);

        public synchronized void registerIntent(BookMaker bookmaker) {
            if (!cancelled) {
                intentRegistered.add(bookmaker);
            }
        }

        public synchronized void unRegisterIntent(BookMaker bookmaker) {
            intentRegistered.remove(bookmaker);
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
                // Release all waiting threads
                while (latch.getCount() > 0) {
                    latch.countDown();
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

        public boolean isCompleted() {
            return betPlaced.size() >= 2 || failures.size() >= 1 || cancelled;
        }

        public String getStateSummary() {
            return String.format("Ready: %s, Placed: %s, Failed: %s, Cancelled: %s, TimeoutBy: %s",
                    readyWindows, betPlaced.keySet(), failures.keySet(), cancelled, timeoutTriggeredBy);
        }

        public boolean hasIntent(BookMaker bookmaker) {
            return intentRegistered.contains(bookmaker);
        }
    }
}