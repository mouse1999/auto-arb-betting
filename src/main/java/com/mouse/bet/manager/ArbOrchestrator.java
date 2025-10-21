package com.mouse.bet.manager;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.enums.Status;
import com.mouse.bet.model.arb.LegResult;
import com.mouse.bet.service.ArbService;

import com.mouse.bet.tasks.LegTask;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

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

    /** Max time for all legs (including retries) to complete. */
    private final Duration legTimeout;

    /** Retry policy for each Leg. */
    private final int maxRetries;
    private final Duration retryBackoff;

    /** Orchestrator loop. */
    private final ExecutorService orchestratorExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arb-orchestrator");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final BigDecimal MIN_PROFIT_PERCENTAGE = BigDecimal.TEN;
    private final int LIMIT = 3;
    private final Random random = new Random();

    public ArbOrchestrator(ArbService arbService,
                           Duration legTimeout,
                           int maxRetries,
                           Duration retryBackoff) {
        this.arbService = Objects.requireNonNull(arbService);
        this.legTimeout = Objects.requireNonNull(legTimeout);
        this.maxRetries = maxRetries;
        this.retryBackoff = Objects.requireNonNull(retryBackoff);
    }

    /** Register a worker queue for a bookmaker. Call this at startup after creating workers. */
    public void registerWorker(BookMaker bookmaker, BlockingQueue<LegTask> queue) {
        Objects.requireNonNull(bookmaker);
        Objects.requireNonNull(queue);
        workerQueues.put(bookmaker, queue);
        registeredWorkers = Set.copyOf(workerQueues.keySet());
    }

    /** Non-blocking: put an Arb into the single slot; returns false if busy. */
    public boolean tryLoadArb(Arb arb) {
        Objects.requireNonNull(arb);
        return arbQueue.offer(arb);
    }

    /** Blocking: put an Arb into the single slot; waits until free. */
    public void loadArb(Arb arb) throws InterruptedException {
        Objects.requireNonNull(arb);
        arbQueue.put(arb);
    }

    /** Start the orchestrator loop. Safe to call multiple times. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            orchestratorExec.submit(this::runLoop);
        }
    }

    /** Stop the orchestrator loop. */
    public void stop() {
        running.set(false);
        orchestratorExec.shutdownNow();
    }

    private void runLoop() {
        log.info("ArbOrchestrator started.");
        while (running.get()) {
            try {
                // Try to take an Arb from the single-slot queue; if empty, proactively fetch one
                Arb arb = arbQueue.poll(150, TimeUnit.MILLISECONDS);
                if (arb == null) {
                    Optional<Arb> next = getNextActiveArb(arbService.fetchTopArbsByMetrics(MIN_PROFIT_PERCENTAGE, LIMIT)); // implement your criteria
                    next.ifPresent(arbQueue::offer);
                    arb = arbQueue.poll(200, TimeUnit.MILLISECONDS);
                    if (arb == null) continue;
                }

                processOneArb(arb);

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                log.error("Orchestrator loop error", ex);
            }
        }
        log.info("ArbOrchestrator stopped.");
    }

    private Optional<Arb> getNextActiveArb(List<Arb> arbs) {

        if (arbs == null || arbs.isEmpty()) {
            return Optional.empty();
        }
        int randomIndex = random.nextInt(arbs.size());

        return Optional.of(arbs.get(randomIndex));
    }

    private void processOneArb(Arb arb) throws InterruptedException {
        // Mark as IN_PROGRESS to avoid being picked again
        arb.setStatus(Status.IN_PROGRESS);
        arbService.saveArb(arb);

        Map<BookMaker, BetLeg> legsByBook = legsByBookmaker(arb);

        // If Arb has no legs, just complete it.
        if (legsByBook.isEmpty()) {
            log.info("Arb {} has no legs. Completing.", arb.getArbId());
            arb.setStatus(Status.COMPLETED);
            arbService.saveArb(arb);
            return;
        }

        // all legs must have a registered worker.
        //    If any leg's bookmaker isn't registered, fail the Arb immediately.
        Set<BookMaker> missingWorkers = new HashSet<>(legsByBook.keySet());
        missingWorkers.removeAll(registeredWorkers); // anything left is missing
        if (!missingWorkers.isEmpty()) {
            log.warn("Arb {} rejected: no registered workers for {}", arb.getArbId(), missingWorkers);
            arb.setStatus(Status.FAILED);
            arbService.saveArb(arb);
            return;
        }

        // Targets are exactly the bookmakers that have legs on this Arb
        List<BookMaker> targets = new ArrayList<>(legsByBook.keySet());

        Phaser barrier = new Phaser(targets.size());
        ConcurrentMap<BookMaker, LegResult> results = new ConcurrentHashMap<>();

        // Dispatch tasks to matching workers only .
        for (BookMaker bm : targets) {
            BlockingQueue<LegTask> q = workerQueues.get(bm);
            if (q == null) {
                // Defensive
                log.warn("Worker queue for {} missing at dispatch time. Failing Arb {}.", bm, arb.getArbId());
                arb.setStatus(Status.FAILED);
                arbService.saveArb(arb);
                return;
            }

            LegTask task = LegTask.builder()
                    .arbId(arb.getArbId())
                    .arb(arb)
                    .leg(legsByBook.get(bm))
                    .bookmaker(bm)
                    .maxRetries(maxRetries)
                    .retryBackoff(retryBackoff)
                    .barrier(barrier)
                    .results(results)
                    .build();

            q.put(task); // may block briefly if worker's queue is full
        }

        // BLOCK until ALL leg tasks finish
        int phase = barrier.getPhase();
        barrier.awaitAdvance(phase);

        boolean allSuccess = results.size() == targets.size()
                && results.values().stream().allMatch(LegResult::success);

        arb.setStatus(allSuccess ? Status.COMPLETED : Status.FAILED);
        arbService.saveArb(arb);

        log.info("Arb {} finished with status {}.", arb.getArbId(), arb.getStatus());
    }


    private Map<BookMaker, BetLeg> legsByBookmaker(Arb arb) {
        return arb.getLegs().stream()
                .collect(Collectors.toMap(BetLeg::getBookmaker, l -> l, (a, b) -> a));
    }


}
