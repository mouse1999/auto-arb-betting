package com.mouse.bet.manager;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.enums.Status;
import com.mouse.bet.model.arb.LegResult;
import com.mouse.bet.service.ArbPollingService;
import com.mouse.bet.service.ArbService;

import com.mouse.bet.tasks.LegTask;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Single-slot orchestrator: processes one Arb at a time.
 * Dispatches legs to per-bookmaker worker queues and waits (via Phaser) until all assigned legs finish.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArbOrchestrator {
    /** Single active Arb slot. */
    @Getter
    private final BlockingQueue<Arb> arbQueue = new ArrayBlockingQueue<>(1);

    /** Per-bookmaker worker task queues. */
    @Getter
    private final ConcurrentMap<BookMaker, BlockingQueue<LegTask>> workerQueues = new ConcurrentHashMap<>();

    /** Set of registered workers (keys of workerQueues). */
    private volatile Set<BookMaker> registeredWorkers = Set.of();

    private final ArbService arbService;


    @Value("${sporty.poll.interval.ms:2000}")
    private long pollIntervalMs;

    /** Retry policy for each Leg. */
    private int maxRetries;
    private Duration retryBackoff;

    /** Orchestrator loop. */
    private final ExecutorService orchestratorExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arb-orchestrator");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);


    /** Register a worker queue for a bookmaker. Call this at startup after creating workers. */
    public void registerWorker(BookMaker bookmaker, BlockingQueue<LegTask> queue) {
        Objects.requireNonNull(bookmaker);
        Objects.requireNonNull(queue);
        log.info("Registering worker | Bookmaker: {} | QueueCapacity: {}",
                bookmaker, queue.remainingCapacity() + queue.size());

        workerQueues.put(bookmaker, queue);
        registeredWorkers = Set.copyOf(workerQueues.keySet());

        log.info("Worker registered successfully | Bookmaker: {} | TotalRegisteredWorkers: {}",
                bookmaker, registeredWorkers.size());
    }

    /** Non-blocking: put an Arb into the single slot; returns false if busy. */
    public boolean tryLoadArb(Arb arb) {
        Objects.requireNonNull(arb);
        boolean loaded = arbQueue.offer(arb);

        if (loaded) {
            log.info("Arb loaded into queue (non-blocking) | ArbId: {} | Status: {} | LegsCount: {}",
                    arb.getArbId(), arb.getStatus(), arb.getLegs().size());
        } else {
            log.warn("Failed to load Arb (queue full) | ArbId: {} | QueueSize: {}",
                    arb.getArbId(), arbQueue.size());
        }

        return loaded;
    }

    /** Blocking: put an Arb into the single slot; waits until free. */
    public void loadArb(Arb arb) throws InterruptedException {
        Objects.requireNonNull(arb);
        log.info("Attempting to load Arb (blocking) | ArbId: {} | QueueSize: {}",
                arb.getArbId(), arbQueue.size());

        arbQueue.put(arb);

        log.info("Arb loaded into queue (blocking completed) | ArbId: {} | Status: {} | LegsCount: {}",
                arb.getArbId(), arb.getStatus(), arb.getLegs().size());
    }

    /** Start the orchestrator loop. Safe to call multiple times. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting ArbOrchestrator | RegisteredWorkers: {} | PollIntervalMs: {}",
                    registeredWorkers, pollIntervalMs);
            orchestratorExec.submit(this::runLoop);
        } else {
            log.debug("ArbOrchestrator start() called but already running");
        }
    }

    /** Stop the orchestrator loop. */
    public void stop() {
        log.info("Stopping ArbOrchestrator | CurrentQueueSize: {}", arbQueue.size());
        running.set(false);
        orchestratorExec.shutdownNow();
        log.info("ArbOrchestrator shutdown initiated");
    }

    private void runLoop() {
        log.info("ArbOrchestrator loop started | Thread: {}", Thread.currentThread().getName());

        while (running.get()) {
            try {
                // Poll with timeout — returns null if nothing in queue within 150ms
                log.trace("Polling arb queue | QueueSize: {}", arbQueue.size());
                Arb arb = arbQueue.peek();

                // === NULL CHECK: Skip if no arb available (normal during idle) ===
                if (arb == null) {
                    // Optional: small sleep to reduce CPU usage during idle periods
                    // page.waitForTimeout(100); // if you have a page, or Thread.sleep(100);
                    continue;  // Go back to while loop — wait for next arb
                }

                // === ARB FOUND → PROCESS IT ===
                log.info("=== Processing Arb | ArbId: {} | Status: {} | LegsCount: {} ===",
                        arb.getArbId(), arb.getStatus(), arb.getLegs().size());

                processOneArb(arb);

                log.info("=== Completed Processing Arb | ArbId: {} | FinalStatus: {} ===",
                        arb.getArbId(), arb.getStatus());
                log.info("Trying to clear arb queue");

                arbQueue.clear();

            } catch (InterruptedException ie) {
                log.warn("ArbOrchestrator loop interrupted", ie);
                Thread.currentThread().interrupt();
                break;

            } catch (Exception ex) {
                log.error("Unexpected error in orchestrator loop | Type: {} | Message: {}",
                        ex.getClass().getSimpleName(), ex.getMessage(), ex);
                // Continue loop — don't let one bad arb kill the whole orchestrator
            }
        }

        log.info("ArbOrchestrator loop stopped | FinalQueueSize: {}", arbQueue.size());
    }

    public void processOneArb(Arb arb) throws InterruptedException {
        log.info("Starting arb processing | ArbId: {} | CurrentStatus: {}",
                arb.getArbId(), arb.getStatus());

        // Mark as IN_PROGRESS to avoid being picked again
        arb.setStatus(Status.IN_PROGRESS);
        arbService.saveArb(arb);
        log.info("Arb marked as IN_PROGRESS | ArbId: {}", arb.getArbId());

        Map<BookMaker, BetLeg> legsByBook = legsByBookmaker(arb);
        log.info("Arb legs grouped by bookmaker | ArbId: {} | BookmakersCount: {} | Bookmakers: {}",
                arb.getArbId(), legsByBook.size(), legsByBook.keySet());

        // If Arb has no legs, just complete it.
        if (legsByBook.isEmpty()) {
            log.warn("Arb has no legs, completing immediately | ArbId: {}", arb.getArbId());
            arb.setStatus(Status.COMPLETED);
            arbService.saveArb(arb);
            return;
        }

        // All legs must have a registered worker
        Set<BookMaker> missingWorkers = new HashSet<>(legsByBook.keySet());
        missingWorkers.removeAll(registeredWorkers);

        if (!missingWorkers.isEmpty()) {
            log.error("Arb rejected - missing workers | ArbId: {} | MissingWorkers: {} | RegisteredWorkers: {}",
                    arb.getArbId(), missingWorkers, registeredWorkers);
            arb.setStatus(Status.FAILED);
            arbService.saveArb(arb);
            return;
        }

        log.info("All required workers available | ArbId: {} | RequiredBookmakers: {}",
                arb.getArbId(), legsByBook.keySet());

        // Targets are exactly the bookmakers that have legs on this Arb
        List<BookMaker> targets = new ArrayList<>(legsByBook.keySet());
        Phaser barrier = new Phaser(targets.size());
        ConcurrentMap<BookMaker, LegResult> results = new ConcurrentHashMap<>();

        log.info("Initializing leg dispatch | ArbId: {} | TargetsCount: {} | PhaserParties: {}",
                arb.getArbId(), targets.size(), barrier.getRegisteredParties());

        // Dispatch tasks to matching workers only
        for (BookMaker bm : targets) {
            BlockingQueue<LegTask> q = workerQueues.get(bm);

            if (q == null) {
                log.error("Worker queue missing at dispatch time | ArbId: {} | Bookmaker: {} | FailingArb",
                        arb.getArbId(), bm);
                arb.setStatus(Status.FAILED);
                arbService.saveArb(arb);
                return;
            }

            BetLeg leg = legsByBook.get(bm);
            log.info("Preparing leg task | ArbId: {} | Bookmaker: {} | LegId: {} | Market: {} | Selection: {} | Odds: {} | Stake: {}",
                    arb.getArbId(),
                    bm,
                    leg.getId(),
                    leg.getProviderMarketTitle(),
                    leg.getProviderMarketName(),
                    leg.getOdds(),
                    leg.getStake());

            LegTask task = LegTask.builder()
                    .arbId(arb.getArbId())
                    .arb(arb)
                    .leg(leg)
                    .bookmaker(bm)
                    .maxRetries(maxRetries)
                    .retryBackoff(retryBackoff)
                    .barrier(barrier)
                    .results(results)
                    .build();

            log.debug("Dispatching leg task to worker queue | ArbId: {} | Bookmaker: {} | QueueSize: {} | QueueCapacity: {}",
                    arb.getArbId(), bm, q.size(), q.remainingCapacity());

            q.put(task); // may block briefly if worker's queue is full

            log.info("Leg task dispatched successfully | ArbId: {} | Bookmaker: {} | LegId: {} | QueueSize: {}",
                    arb.getArbId(), bm, leg.getId(), q.size());
        }

        log.info("All leg tasks dispatched, waiting for completion | ArbId: {} | TotalLegs: {} | PhaserPhase: {}",
                arb.getArbId(), targets.size(), barrier.getPhase());

        // BLOCK until ALL leg tasks finish
        int phase = barrier.getPhase();
        barrier.awaitAdvance(phase);

        log.info("All leg tasks completed | ArbId: {} | ResultsReceived: {} | ExpectedResults: {}",
                arb.getArbId(), results.size(), targets.size());

        // Log individual leg results
        results.forEach((bookmaker, result) -> {
            log.info("Leg result | ArbId: {} | Bookmaker: {} | Success: {} | Message: {}",
                    arb.getArbId(), bookmaker, result.success(), result.message());
        });

        boolean allSuccess = results.size() == targets.size()
                && results.values().stream().allMatch(LegResult::success);

        Status finalStatus = allSuccess ? Status.COMPLETED : Status.FAILED;
        arb.setStatus(finalStatus);
        arbService.saveArb(arb);

        if (allSuccess) {
            log.info("Arb completed successfully | ArbId: {} | Status: {} | SuccessfulLegs: {}/{}",
                    arb.getArbId(), finalStatus, results.size(), targets.size());
        } else {
            long failedCount = results.values().stream().filter(r -> !r.success()).count();
            log.error("Arb failed | ArbId: {} | Status: {} | FailedLegs: {} | SuccessfulLegs: {} | TotalLegs: {}",
                    arb.getArbId(), finalStatus, failedCount, results.size() - failedCount, targets.size());
        }
    }

    private Map<BookMaker, BetLeg> legsByBookmaker(Arb arb) {
        Map<BookMaker, BetLeg> grouped = arb.getLegs().stream()
                .collect(Collectors.toMap(BetLeg::getBookmaker, l -> l, (a, b) -> a));

        log.debug("Grouped legs by bookmaker | ArbId: {} | Groups: {}",
                arb.getArbId(),
                grouped.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining(", ")));

        return grouped;
    }
}