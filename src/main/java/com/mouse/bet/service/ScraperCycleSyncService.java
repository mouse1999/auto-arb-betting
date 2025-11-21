package com.mouse.bet.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScraperCycleSyncService {

    // Use Long to prevent overflow forever
    private final AtomicLong currentCycleId = new AtomicLong(0);

    // Map: cycleId -> SyncState
    private final ConcurrentMap<Long, SyncState> cycles = new ConcurrentHashMap<>();

    // Minimum time a cycle must live before being consumed when incomplete (prevents storm)
    private static final Duration MIN_CYCLE_LIFETIME = Duration.ofMillis(300);

    public record SyncState(
            long cycleId,
            Instant createdAt,
            Set<String> joinedScrapers, // Use scraper identifiers!
            CountDownLatch latch,
            boolean forceTerminated
    ) {
        public boolean isComplete(int expectedParties) {
            return joinedScrapers.size() >= expectedParties;
        }

        public boolean isExpired(Duration timeout) {
            return Duration.between(createdAt, Instant.now()).compareTo(timeout) > 0;
        }

        public boolean isTooYoungForEarlyConsumption() {
            return Duration.between(createdAt, Instant.now()).compareTo(MIN_CYCLE_LIFETIME) < 0;
        }
    }

    /**
     * Wait for partner scraper with timeout.
     * Returns true if both are ready, false if timed out or interrupted.
     */
    public boolean waitForPartner(String scraperId, Duration timeout) {
        long cycleId = currentCycleId.get();
        SyncState state = cycles.get(cycleId);

        if (state == null) {
            // We missed the current cycle → create a new one
            cycleId = advanceToNextCycle();
            state = cycles.get(cycleId);
        }

        boolean joined = state.joinedScrapers().add(scraperId);
        log.info("[SYNC] Scraper {} joined cycle {} (total parties: {})", scraperId, cycleId, state.joinedScrapers().size());

        if (state.isComplete(2)) {
            log.info("PERFECT SYNC ACHIEVED — Both scrapers ready (cycle {})", cycleId);
            advanceToNextCycle(); // Prepare next cycle early
            return true;
        }

        // Wait with timeout
        try {
            boolean ready = state.latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (ready) {
                log.info("Partner arrived in time for scraper {} (cycle {})", scraperId, cycleId);
                return true;
            } else {
                log.info("Partner not ready in {}s for scraper {} — skipping this cycle",
                        timeout.getSeconds(), scraperId);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for partner (scraper {})", scraperId);
            return false;
        }
    }

    /**
     * Force move to next cycle (only used during startup or recovery)
     */
    public synchronized long forceNewCycle() {
        return advanceToNextCycle();
    }

    private long advanceToNextCycle() {
        long oldCycle = currentCycleId.get();
        long newCycle = currentCycleId.incrementAndGet();

        // Terminate old cycle if exists
        SyncState oldState = cycles.remove(oldCycle);
        if (oldState != null) {
            oldState.latch.countDown(); // Unblock any waiters
            log.info("ADVANCED TO NEXT CYCLE — Old cycle {} terminated, new cycle {} ready", oldCycle, newCycle);
        } else {
            log.info("NEW SYNC CYCLE CREATED (cycle {})", newCycle);
        }

        SyncState newState = new SyncState(
                newCycle,
                Instant.now(),
                ConcurrentHashMap.newKeySet(),
                new CountDownLatch(1), // Only release when 2nd scraper arrives or we move on
                false
        );
        cycles.put(newCycle, newState);

        // Auto-consume incomplete cycles after timeout + grace period
        scheduleCycleCleanup(newCycle, newState);

        return newCycle;
    }

    private void scheduleCycleCleanup(long cycleId, SyncState state) {
        CompletableFuture.delayedExecutor(90, TimeUnit.SECONDS)
                .execute(() -> {
                    SyncState current = cycles.get(cycleId);
                    if (current == state && !current.isComplete(2) && !current.isTooYoungForEarlyConsumption()) {
                        log.info("CYCLE {} CONSUMED — only {} scraper(s) arrived. Starting fresh cycle...",
                                cycleId, current.joinedScrapers().size());
                        advanceToNextCycle();
                    }
                });
    }

    // Call on application startup
    @EventListener(ApplicationReadyEvent.class)
    public void initFirstCycle() {
        advanceToNextCycle();
    }
}