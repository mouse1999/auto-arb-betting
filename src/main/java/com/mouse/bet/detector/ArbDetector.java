package com.mouse.bet.detector;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.enums.Status;
import com.mouse.bet.finance.WalletService;
import com.mouse.bet.model.NormalizedEvent;
import com.mouse.bet.logservice.ArbitrageLogService;
import com.mouse.bet.service.ArbService;
import com.mouse.bet.utils.ArbFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Detects arbitrage opportunities from incoming events and validates wallet balances.
 * Automatically cleans up events older than 3-5 seconds to maintain performance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArbDetector {

    private final Map<String, ConcurrentLinkedQueue<NormalizedEvent>> eventCache = new ConcurrentHashMap<>();
    private final BlockingQueue<Arb> arbQueue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, Object> eventLocks = new ConcurrentHashMap<>();

    private final ExecutorService detectionExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);;
    private final ExecutorService arbProcessorExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;

    private final WalletService walletService;
    private final ArbitrageLogService arbitrageLogService;
    private final ArbFactory arbFactory;
    private final ArbService arbService;

    private static final int EVENT_EXPIRY_SECONDS = 5;
    private static final int MAX_EVENTS_PER_GROUP = 50;

    @PostConstruct
    public void init() {
        startArbProcessor();
        arbitrageLogService.logInfo("ArbDetector started", null);
        log.info("ArbDetector started");
    }

    /**
     * Add event to cache and trigger arbitrage detection
     */
    public void addEventToPool(NormalizedEvent event) {
        if (event == null || event.getEventId() == null) {
            log.warn("Cannot add null event or event without eventId");
            return;
        }

//        event.setLastUpdated(Instant.now());
        String eventId = event.getEventId();

        log.debug("Adding event from {} for eventId={}", event.getBookie(), eventId);

        // Add to cache with size limit
        eventCache.compute(eventId, (key, queue) -> {
            if (queue == null) {
                queue = new ConcurrentLinkedQueue<>();
            }

            // Remove oldest if queue is full
            if (queue.size() >= MAX_EVENTS_PER_GROUP) {
                queue.poll();
            }

            queue.add(event);

            // Immediate cleanup of old events from this queue
            Instant cutoff = Instant.now().minusSeconds(EVENT_EXPIRY_SECONDS);
            queue.removeIf(e -> e.getLastUpdated().isBefore(cutoff));

            return queue.isEmpty() ? null : queue;
        });

        detectArbitrage(eventId);
    }

    /**
     * Detect arbitrage opportunities for a specific event
     */
    private void detectArbitrage(String eventId) {
        detectionExecutor.submit(() -> {
            Object lock = eventLocks.computeIfAbsent(eventId, k -> new Object());

            synchronized (lock) {
                try {
                    ConcurrentLinkedQueue<NormalizedEvent> events = eventCache.get(eventId);

                    if (events == null || events.size() < 2) {
                        return;
                    }

                    List<NormalizedEvent> eventList = new ArrayList<>(events);
                    log.debug("Analyzing {} events for arbitrage (eventId={})", eventList.size(), eventId);

                    List<Arb> opportunities = arbFactory.findOpportunities(eventList);

                    if (!opportunities.isEmpty()) {
                        log.info("Found {} arbs for eventId={}", opportunities.size(), eventId);
                        opportunities.forEach(arbQueue::offer);

                        for (Arb opportunity : opportunities) {
                            arbitrageLogService.logArb(opportunity);
                        }

                    }

                } catch (Exception e) {
                    log.error("Detection error for eventId={}", eventId, e);
                    arbitrageLogService.logError("Error detected while trying to detect arb for an event", e);
                }
            }
        });
    }

    /**
     * Process detected arbitrage opportunities
     */
    private void startArbProcessor() {
        arbProcessorExecutor.execute(() -> {
            log.info("Arb processor started");

            while (running || !arbQueue.isEmpty()) {
                try {
                    Arb arb = arbQueue.poll(100, TimeUnit.MILLISECONDS);

                    if (arb != null) {
                        processArb(arb);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Arb processor interrupted");
                    break;
                } catch (Exception e) {
                    log.error("Error in arb processor", e);
                }
            }

            log.info("Arb processor stopped");
        });
    }

    /**
     * Process individual arbitrage opportunity
     */
    private void processArb(Arb arb) {
        try {
            log.debug("Processing arb arbId={}, profit={}", arb.getArbId(), arb.getProfitPercentage());


            if (arb.getStatus() == Status.INSUFFICIENT_BALANCE) {
                log.warn("Insufficient balance for arbId={}", arb.getArbId());
                arbitrageLogService.logInsufficientFunds(arb);
            }

            arbService.saveArb(arb);
            log.info("Saved arb arbId={}, status={}", arb.getArbId(), arb.getStatus());

        } catch (Exception e) {
            log.error("Error processing arb eventId={}", arb.getArbId(), e);
            arbitrageLogService.logError("ArbDetector Processing error: " + arb.getArbId(), e);
        }
    }

    /**
     * Cleanup old events every 3 minute
     */
    @Scheduled(fixedRate = 30000)
    public void cleanupOldEvents() {
        Instant cutoff = Instant.now().minusSeconds(EVENT_EXPIRY_SECONDS);
        int removedQueues = 0;

        try {
            Iterator<Map.Entry<String, ConcurrentLinkedQueue<NormalizedEvent>>> iterator =
                    eventCache.entrySet().iterator();

            while (iterator.hasNext()) {
                Map.Entry<String, ConcurrentLinkedQueue<NormalizedEvent>> entry = iterator.next();
                ConcurrentLinkedQueue<NormalizedEvent> queue = entry.getValue();

                queue.removeIf(event -> event.getLastUpdated().isBefore(cutoff));

                if (queue.isEmpty()) {
                    iterator.remove();
                    eventLocks.remove(entry.getKey());
                    removedQueues++;
                }
            }

            if (removedQueues > 0) {
                log.debug("Cleanup removed {} empty queues", removedQueues);
            }

        } catch (Exception e) {
            log.error("Error during cleanup", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ArbDetector...");
        running = false;

        detectionExecutor.shutdown();
        arbProcessorExecutor.shutdown();

        try {
            if (!detectionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                detectionExecutor.shutdownNow();
            }
            if (!arbProcessorExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                arbProcessorExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            detectionExecutor.shutdownNow();
            arbProcessorExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        arbitrageLogService.logInfo("ArbDetector shutdown complete", null);
        log.info("ArbDetector shutdown complete");
    }

    // Monitoring methods
    public int getCacheSize() {
        return eventCache.size();
    }

    public long getTotalEvents() {
        return eventCache.values().stream().mapToInt(Queue::size).sum();
    }

    public int getPendingArbs() {
        return arbQueue.size();
    }
}