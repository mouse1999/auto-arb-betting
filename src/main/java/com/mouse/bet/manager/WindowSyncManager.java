package com.mouse.bet.manager;

import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.window.SportyWindow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

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
     * Called by a window when it's ready to place bet for a specific arb
     */
    public void markReady(String arbId, BookMaker bookmaker) {
        ArbSyncState state = syncMap.computeIfAbsent(arbId, k -> new ArbSyncState());
        state.markReady(bookmaker);
        log.info("Window READY | ArbId: {} | Bookmaker: {}", arbId, bookmaker);
    }

    /**
     * Wait for BOTH partner windows to be ready (within timeout)
     * Returns true if both are ready in time
     */
    public boolean waitForPartnersReady(String arbId, BookMaker myBookmaker, Duration timeout) {
        ArbSyncState state = syncMap.computeIfAbsent(arbId, k -> new ArbSyncState());

        // Determine the partner bookmaker
        BookMaker partner = myBookmaker == BookMaker.SPORTY_BET ? BookMaker.M_SPORT : BookMaker.SPORTY_BET;

        log.info("Waiting for partner {} to be ready | ArbId: {} | Timeout: {}s",
                partner, arbId, timeout.toSeconds());

        try {
            boolean bothReady = state.latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);

            if (bothReady) {
                log.info("Both windows READY and synchronized | ArbId: {}", arbId);
                return true;
            } else {
                log.warn("Timeout waiting for partner {} | ArbId: {}", partner, arbId);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for partner | ArbId: {}", arbId);
            return false;
        }
    }

    /**
     * Notify that this bookmaker successfully placed the bet
     */
    public void notifyBetPlaced(String arbId, BookMaker bookmaker) {
        ArbSyncState state = syncMap.get(arbId);
        if (state != null) {
            state.recordBetPlaced(bookmaker);
            log.info("Bet PLACED and recorded | ArbId: {} | Bookmaker: {}", arbId, bookmaker);
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
            log.warn("Bet FAILED | ArbId: {} | Bookmaker: {} | Reason: {}", arbId, bookmaker, reason);
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
     * Clean up sync state after both windows are done (success or failure)
     */
    private void cleanupIfDone(String arbId) {
        ArbSyncState state = syncMap.get(arbId);
        if (state != null && state.isCompleted()) {
            syncMap.remove(arbId);
            log.debug("Cleaned up sync state for ArbId: {}", arbId);
        }
    }

    /**
     * Optional: Force cleanup (e.g., on shutdown)
     */
    public void clearAll() {
        syncMap.clear();
        log.info("WindowSyncManager state cleared");
    }

    public int getActiveCoordinationCount() {
        return 0;
    }

    public int getRegisteredWindowCount() {
        return 0;
    }

    // =================================================================
    // Inner class to track synchronization state per Arb
    // =================================================================
    private static class ArbSyncState {
        private final CountDownLatch latch = new CountDownLatch(2); // Exactly 2 windows
        private final Set<BookMaker> readyWindows = ConcurrentHashMap.newKeySet();
        private final Map<BookMaker, Boolean> betPlaced = new ConcurrentHashMap<>();
        private final Map<BookMaker, String> failures = new ConcurrentHashMap<>();

        public synchronized void markReady(BookMaker bookmaker) {
            if (readyWindows.add(bookmaker)) {
                latch.countDown();
            }
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
            return betPlaced.size() >= 2 || failures.size() >= 1; // or both done
        }
    }
}