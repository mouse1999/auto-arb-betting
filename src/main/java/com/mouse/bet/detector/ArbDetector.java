package com.mouse.bet.detector;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.enums.Status;
import com.mouse.bet.finance.WalletService;
import com.mouse.bet.model.NormalizedEvent;
import com.mouse.bet.logservice.ArbitrageLogService;
import com.mouse.bet.model.msport.MSportEvent;
import com.mouse.bet.service.ArbService;
import com.mouse.bet.utils.ArbFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

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

    private static final int EVENT_EXPIRY_SECONDS = 2;
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

        log.info("Adding event from {} for eventId={}", event.getBookie(), eventId);

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
            return queue;
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

                    // Filter events to only include those within 2 seconds of the most recent event
                    List<NormalizedEvent> filteredEvents = filterEventsWithinTimeWindow(eventList);

                    if (filteredEvents.size() < 2) {
                        log.info("Not enough events within {}-second time window for eventId={}, skipping arbitrage detection",EVENT_EXPIRY_SECONDS, eventId);
                        return;
                    }

                    log.info("Analyzing {} events for arbitrage (eventId={}) after time filtering", filteredEvents.size(), eventId);

                    List<Arb> opportunities = arbFactory.findOpportunities(filteredEvents);

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
     * Filters events to only include those within the specified time window of the most recent event.
     * This ensures we're only comparing odds that were seen at approximately the same time.
     *
     * @param events List of normalized events to filter
     * @return Filtered list of events within the time window
     */
    private List<NormalizedEvent> filterEventsWithinTimeWindow(List<NormalizedEvent> events) {
        if (events == null || events.isEmpty()) {
            return Collections.emptyList();
        }

        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(ArbDetector.EVENT_EXPIRY_SECONDS);

        // ✅ First: Log all incoming events and fix null seenAt
        if (log.isDebugEnabled()) {
            log.debug("Incoming {} events for time-window filtering (expiry={}s, cutoff={})",
                    events.size(), ArbDetector.EVENT_EXPIRY_SECONDS, cutoff);
        }

        int nullSeenAtCount = 0;

        for (NormalizedEvent event : events) {
            if (event == null) {
                continue; // skip null events entirely
            }

            Instant seenAt = event.getSeenAt();

            if (log.isDebugEnabled()) {
                String eventId = event.getEventId() != null ? event.getEventId() : "unknown";
                BookMaker bookie = event.getBookie();
                log.debug("Event before filter: eventId={}, bookie={}, seenAt={}",
                        eventId, bookie, seenAt);
            }

            // ✅ Fix: If seenAt is null, set it to now
            if (seenAt == null) {
                event.setSeenAt(now);  // assuming there's a setter
                seenAt = now;
                nullSeenAtCount++;
                if (log.isWarnEnabled()) {
                    log.warn("seenAt was null for eventId={}, bookie={} — auto-set to now",
                            event.getEventId(), event.getBookie());
                }
            }
        }

        if (nullSeenAtCount > 0 && log.isInfoEnabled()) {
            log.info("Fixed {} events with null seenAt by setting to current time", nullSeenAtCount);
        }

        // ✅ Now filter: keep events seen within the last N seconds
        List<NormalizedEvent> filtered = events.stream()
                .filter(Objects::nonNull)
                .filter(event -> {
                    Instant seenAt = event.getSeenAt(); // now guaranteed non-null
                    return !seenAt.isBefore(cutoff);
                })
                .collect(Collectors.toList());

        // ✅ Summary
        if (filtered.size() < events.size()) {
            log.info("Time-window filtering: kept {}/{} events (seen within last {}s)",
                    filtered.size(), events.size(), ArbDetector.EVENT_EXPIRY_SECONDS);
        } else if (log.isDebugEnabled()) {
            log.debug("Time-window filtering: all {} events are fresh (within {}s)",
                    events.size(), ArbDetector.EVENT_EXPIRY_SECONDS);
        }

        return filtered;
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

                queue.removeIf(event -> event.getSeenAt().isBefore(cutoff));

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