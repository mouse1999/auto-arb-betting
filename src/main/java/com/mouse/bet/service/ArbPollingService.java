package com.mouse.bet.service;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.manager.ArbOrchestrator;
import com.mouse.bet.repository.ArbRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class ArbPollingService {

    private final ArbRepository arbRepository;
    private final ArbOrchestrator arbOrchestrator;
    private final ArbService arbService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollingThread;

    @Value("${arb.min.profit:2.0}")
    private double minProfit;

    @Value("${arb.poll.interval.ms:200}")
    private long pollIntervalMs;

    @Value("${arb.lookback.seconds:2}")
    private int lookbackSeconds;

    @Value("${arb.polling.min-session-seconds:5}")
    private int minSessionSeconds = 3;

    @Value("${arb.polling.max-breaks:2}")
    private int maxBreaks = 2;

    @Value("${arb.polling.fresh-cutoff-seconds:2}")
    private int freshCutoffSeconds = 2;

    // Performance tracking
    private final LongSummaryStatistics queryStats = new LongSummaryStatistics();
    private final LongSummaryStatistics processingStats = new LongSummaryStatistics();
    private final AtomicInteger successfulPolls = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final AtomicInteger consecutiveSlowPolls = new AtomicInteger(0);
    private final AtomicInteger totalArbsProcessed = new AtomicInteger(0);

    // Dynamic profit adjustment
    private volatile BigDecimal currentMinProfit;

    @PostConstruct
    public void startPolling() {
        log.info("🎬 INITIALIZING ArbPollingService...");
        log.debug("Configuration | MinProfit: {}% | PollInterval: {}ms | MinSession: {}s | MaxBreaks: {} | FreshCutoff: {}s",
                minProfit, pollIntervalMs, minSessionSeconds, maxBreaks, freshCutoffSeconds);

        if (running.compareAndSet(false, true)) {
            log.info("🔄 Setting running flag to TRUE");

            // Initialize dynamic profit
            currentMinProfit = calculateDynamicMinProfit();
            log.info("💰 Initial dynamic min profit calculated: {}%", currentMinProfit);

            pollingThread = new Thread(this::pollArbsInfinitelyFast, "FastArbPoller");
            pollingThread.setDaemon(false);
            log.debug("🧵 Polling thread created | Name: FastArbPoller | Daemon: false");

            pollingThread.start();
            log.info("✅ ⚡ FAST Arb polling STARTED | Interval: {}ms | Min session: {}s | Fresh: {}s",
                    pollIntervalMs, minSessionSeconds, freshCutoffSeconds);
        } else {
            log.warn("⚠️ Polling already running, skipping start");
        }
    }

    /**
     * OPTIMIZED FAST POLLING METHOD
     */
    private void pollArbsInfinitelyFast() {
        log.info("🚀 ═══════════════════════════════════════════════════════");
        log.info("🚀 Starting FAST arb polling with stability filtering...");
        log.info("🚀 ═══════════════════════════════════════════════════════");

        // Warm up on first run
        log.info("🔥 Initiating query warm-up phase...");
        warmUpQueries();

        while (running.get()) {
            long pollStart = System.currentTimeMillis();
            long queryStart = pollStart;
            Instant now = Instant.now();

            log.trace("🔄 Poll cycle started | PollCount: {} | Timestamp: {}",
                    successfulPolls.get() + 1, now);

            try {
                // 1. UPDATE DYNAMIC MIN PROFIT (every 10 polls)
                if (successfulPolls.get() % 10 == 0) {
                    log.debug("📊 Recalculating dynamic min profit | PollCount: {}", successfulPolls.get());
                    BigDecimal oldProfit = currentMinProfit;
                    currentMinProfit = calculateDynamicMinProfit();
                    if (!oldProfit.equals(currentMinProfit)) {
                        log.info("💰 Dynamic min profit adjusted | Old: {}% → New: {}%", oldProfit, currentMinProfit);
                    }
                }

                // 2. CALCULATE CUTOFFS
                Instant freshCutoff = now.minusSeconds(freshCutoffSeconds);
                Instant sessionCutoff = now.minusSeconds(minSessionSeconds);
                log.trace("⏰ Cutoffs calculated | Fresh: {} | Session: {}", freshCutoff, sessionCutoff);

                // 3. FAST QUERY - Try JPA first, fallback to native
                log.trace("🔍 Querying stable arbs | MinProfit: {}% | FreshCutoff: {}s ago | SessionCutoff: {}s ago",
                        currentMinProfit, freshCutoffSeconds, minSessionSeconds);

                Page<Arb> stableArbsPage = getStableArbsFast(
                        currentMinProfit,
                        freshCutoff,
                        sessionCutoff
                );

                long queryTime = System.currentTimeMillis() - queryStart;
                queryStats.accept(queryTime);
                log.debug("✅ Query completed | Duration: {}ms | ResultCount: {}",
                        queryTime, stableArbsPage.getTotalElements());

                List<Arb> stableArbs = stableArbsPage.getContent();

                // 4. FAST JAVA PROCESSING
                long processStart = System.currentTimeMillis();
                List<Arb> filteredArbs = new ArrayList<>();
                int processed = 0;

                if (!stableArbs.isEmpty()) {
                    log.debug("🔎 Filtering {} arbs through stability checks...", stableArbs.size());

                    // Quick filtering
                    filteredArbs = filterArbsFast(stableArbs, now);
                    log.debug("✅ Filtering completed | Input: {} → Output: {}",
                            stableArbs.size(), filteredArbs.size());

                    if (!filteredArbs.isEmpty()) {
                        log.info("🎯 Processing {} filtered arbs...", filteredArbs.size());
                        // Fast processing
                        processed = processArbsFast(filteredArbs);
                        log.info("✅ Processing completed | Processed: {} arbs", processed);
                    } else {
                        log.trace("ℹ️ No arbs passed filtering stage");
                    }

                    totalArbsProcessed.addAndGet(processed);
                } else {
                    log.trace("ℹ️ No stable arbs returned from query");
                }

                long totalProcessTime = System.currentTimeMillis() - processStart;
                processingStats.accept(totalProcessTime);
                log.debug("⏱️ Processing time: {}ms", totalProcessTime);

                // 5. LOGGING & MONITORING
                successfulPolls.incrementAndGet();
                logPollingStats(queryTime, totalProcessTime, stableArbs.size(),
                        filteredArbs.size(), processed, now);

                // 6. PERFORMANCE MONITORING
                monitorPerformance(pollStart);

                // 7. PRECISE SLEEP CONTROL
                long totalPollTime = System.currentTimeMillis() - pollStart;
                log.trace("⏱️ Total poll cycle time: {}ms", totalPollTime);
                preciseSleep(totalPollTime);

            } catch (InterruptedException e) {
                log.info("🛑 Fast polling interrupted | Reason: Thread interrupt signal received");
                Thread.currentThread().interrupt();
                break;

            } catch (Exception e) {
                log.error("💥 Exception during poll cycle | PollCount: {} | Error: {}",
                        successfulPolls.get(), e.getMessage());
                if (log.isDebugEnabled()) {
                    log.debug("Poll exception stack trace:", e);
                }
                handlePollError(e);
            }
        }

        log.info("🛑 Polling loop exited | Running: {}", running.get());
        logFinalStats();
    }

    /**
     * FAST QUERY METHOD - tries JPA first, falls back to native
     */
    private Page<Arb> getStableArbsFast(BigDecimal minProfit, Instant freshCutoff,
                                        Instant sessionCutoff) {
        log.trace("🔍 Attempting JPA query first...");
        try {
            // Try JPA first (usually faster)
            Page<Arb> result = arbRepository.findStableArbsForBetting(
                    minProfit,
                    freshCutoff,
                    sessionCutoff,
                    maxBreaks,
                    PageRequest.of(0, 100, Sort.by(Sort.Order.desc("lastUpdatedAt")))
            );
            log.trace("✅ JPA query succeeded | Results: {}", result.getTotalElements());
            return result;
        } catch (Exception e) {
            // Fall back to native query
            log.debug("⚠️ JPA query failed, falling back to native query | Reason: {}", e.getMessage());
            Page<Arb> result = arbRepository.findStableArbsNative(
                    minProfit,
                    freshCutoff,
                    (long) minSessionSeconds,
                    maxBreaks,
                    PageRequest.of(0, 100, Sort.unsorted())
            );
            log.debug("✅ Native query succeeded | Results: {}", result.getTotalElements());
            return result;
        }
    }

    /**
     * FAST FILTERING METHOD - single pass, minimal allocations
     */
    private List<Arb> filterArbsFast(List<Arb> arbs, Instant now) {
        log.trace("🔎 Starting fast filtering | InputCount: {}", arbs.size());
        List<Arb> filtered = new ArrayList<>(Math.min(20, arbs.size()));
        int rejectedShouldBet = 0, rejectedSession = 0, rejectedBreaks = 0,
                rejectedExpired = 0, rejectedLegs = 0;

        for (Arb arb : arbs) {
            // Fast checks in order of probability to fail
//            if (!arb.isShouldBet()) {
//                rejectedShouldBet++;
//                log.trace("❌ Arb {} rejected | Reason: shouldBet=false", arb.getArbId());
//                continue;
//            }

            Long sessionSec = arb.getCurrentSessionDurationSeconds();
            if (sessionSec == null || sessionSec < minSessionSeconds) {
                rejectedSession++;
                log.trace("❌ Arb {} rejected | Reason: Session too short ({}s < {}s)",
                        arb.getArbId(), sessionSec, minSessionSeconds);
                continue;
            }

            if (arb.getContinuityBreakCount() > maxBreaks) {
                rejectedBreaks++;
                log.trace("❌ Arb {} rejected | Reason: Too many breaks ({} > {})",
                        arb.getArbId(), arb.getContinuityBreakCount(), maxBreaks);
                continue;
            }

//            if (arb.isExpired(now)) {
//                rejectedExpired++;
//                log.trace("❌ Arb {} rejected | Reason: Expired", arb.getArbId());
//                continue;
//            }

            // Check legs and odds
            BetLeg legA = arb.getLegA().orElse(null);
            BetLeg legB = arb.getLegB().orElse(null);
            if (legA == null || legB == null ||
                    legA.getOdds() == null || legB.getOdds() == null) {
                rejectedLegs++;
                log.trace("❌ Arb {} rejected | Reason: Invalid legs or odds", arb.getArbId());
                continue;
            }

            filtered.add(arb);
            log.trace("✅ Arb {} passed all filters | Profit: {}% | Session: {}s",
                    arb.getArbId(), arb.getProfitPercentage(), sessionSec);

            // Early exit if we have enough
            if (filtered.size() >= 20) {
                log.debug("⚡ Early exit: Reached 20 filtered arbs");
                break;
            }
        }

        // Log rejection summary
        if (log.isDebugEnabled() && (rejectedShouldBet + rejectedSession + rejectedBreaks +
                rejectedExpired + rejectedLegs) > 0) {
            log.debug("📊 Filter rejection summary | ShouldBet: {} | Session: {} | Breaks: {} | Expired: {} | Legs: {}",
                    rejectedShouldBet, rejectedSession, rejectedBreaks, rejectedExpired, rejectedLegs);
        }

        // Fast sort if needed
        if (filtered.size() > 1) {
            log.trace("🔄 Sorting {} filtered arbs...", filtered.size());
            filtered.sort(this::compareArbsForSorting);
            log.trace("✅ Sorting completed");
        }

        log.debug("✅ Filtering completed | Input: {} → Output: {}", arbs.size(), filtered.size());
        return filtered;
    }

    /**
     * FAST ARB COMPARISON for sorting
     */
    private int compareArbsForSorting(Arb a1, Arb a2) {
        log.trace("⚖️ Comparing arbs for sorting | A1: {} | A2: {}", a1.getArbId(), a2.getArbId());

        // 1. Profit percentage (descending)
        int profitCompare = a2.getProfitPercentage()
                .compareTo(a1.getProfitPercentage());
        if (profitCompare != 0) {
            log.trace("⚖️ Sorted by profit | Winner: {}", profitCompare < 0 ? a1.getArbId() : a2.getArbId());
            return profitCompare;
        }

        // 2. Session duration (descending)
        Long s1 = a1.getCurrentSessionDurationSeconds();
        Long s2 = a2.getCurrentSessionDurationSeconds();
        int result = Long.compare(
                s2 != null ? s2 : 0,
                s1 != null ? s1 : 0
        );
        log.trace("⚖️ Sorted by session duration | Winner: {}", result < 0 ? a1.getArbId() : a2.getArbId());
        return result;
    }

    /**
     * Process ONLY the single best stable arb from the list
     * Returns 1 if one was processed, 0 otherwise
     */
    private int processArbsFast(List<Arb> arbs) {
        log.debug("🎯 Selecting best arb from {} candidates...", arbs.size());

        if (arbs == null || arbs.isEmpty()) {
            log.trace("ℹ️ No arbs to process (list empty or null)");
            return 0;
        }

        // Select the single best arb
        log.trace("🔍 Scanning arbs for best candidate...");
        Arb bestArb = arbs.stream()
                .max(this::compareArbsForSelection)
                .orElse(null);

        if (bestArb == null) {
            log.warn("⚠️ Failed to select best arb from list");
            return 0;
        }

        // Log the selection (helpful for monitoring)
        Long sessionSec = bestArb.getCurrentSessionDurationSeconds();
        Double confidence = bestArb.getConfidenceScore();
        Double volatility = bestArb.getVolatilitySigma();
        log.info("🎯 ═══════════════════════════════════════════════════════");
        log.info("🎯 SELECTED BEST ARB");
        log.info("🎯 ═══════════════════════════════════════════════════════");
        log.info("🎯 ID: {} | Sport: {}", bestArb.getArbId(), bestArb.getSportEnum());
        log.info("🎯 Profit: {}% | Session: {}s | Breaks: {}",
                bestArb.getProfitPercentage(),
                sessionSec != null ? sessionSec : "N/A",
                bestArb.getContinuityBreakCount());
        log.info("🎯 Confidence: {} | Volatility: {}",
                confidence != null ? String.format("%.3f", confidence) : "N/A",
                volatility != null ? String.format("%.3f", volatility) : "N/A");
        log.info("🎯 ═══════════════════════════════════════════════════════");

        // Try to load/bet on it
        try {
            log.info("🚀 Loading arb into orchestrator | ArbId: {}", bestArb.getArbId());
            arbOrchestrator.tryLoadArb(bestArb);
            log.info("✅ Arb successfully loaded | ArbId: {}", bestArb.getArbId());
            return 1;
        } catch (Exception e) {
            log.warn("❌ Failed to load best arb | ArbId: {} | Error: {}",
                    bestArb.getArbId(), e.getMessage());
            if (log.isDebugEnabled()) {
                log.debug("Load failure details for arb {}", bestArb.getArbId(), e);
            }
            return 0;
        }
    }

    /**
     * Comprehensive comparison for selecting the SINGLE best arb
     * Higher return value = better arb
     */
    private int compareArbsForSelection(Arb a1, Arb a2) {
        log.trace("🔍 Comparing arbs for selection | A1: {} vs A2: {}", a1.getArbId(), a2.getArbId());

        // 1. Profit % (primary driver)
        int profitCmp = a2.getProfitPercentage().compareTo(a1.getProfitPercentage());
        if (profitCmp != 0) {
            log.trace("⚖️ Selection by profit | Winner: {}", profitCmp < 0 ? a1.getArbId() : a2.getArbId());
            return profitCmp;
        }

        // 2. Session duration (longer = more stable)
        Long s1 = a1.getCurrentSessionDurationSeconds();
        Long s2 = a2.getCurrentSessionDurationSeconds();
        int sessionCmp = Long.compare(s2 != null ? s2 : 0, s1 != null ? s1 : 0);
        if (sessionCmp != 0) {
            log.trace("⚖️ Selection by session | Winner: {}", sessionCmp < 0 ? a1.getArbId() : a2.getArbId());
            return sessionCmp;
        }

        // 3. Fewer continuity breaks
        int breaksCmp = Integer.compare(
                a1.getContinuityBreakCount() != null ? a1.getContinuityBreakCount() : 0,
                a2.getContinuityBreakCount() != null ? a2.getContinuityBreakCount() : 0
        );
        if (breaksCmp != 0) {
            log.trace("⚖️ Selection by breaks | Winner: {}", breaksCmp < 0 ? a1.getArbId() : a2.getArbId());
            return breaksCmp;
        }

        // 4. Higher confidence score
        Double c1 = a1.getConfidenceScore();
        Double c2 = a2.getConfidenceScore();
        int confCmp = Double.compare(c2 != null ? c2 : 0.0, c1 != null ? c1 : 0.0);
        if (confCmp != 0) {
            log.trace("⚖️ Selection by confidence | Winner: {}", confCmp < 0 ? a1.getArbId() : a2.getArbId());
            return confCmp;
        }

        // 5. Lower volatility
        Double v1 = a1.getVolatilitySigma();
        Double v2 = a2.getVolatilitySigma();
        int volCmp = Double.compare(v1 != null ? v1 : 999.0, v2 != null ? v2 : 999.0);
        if (volCmp != 0) {
            log.trace("⚖️ Selection by volatility | Winner: {}", volCmp < 0 ? a1.getArbId() : a2.getArbId());
            return volCmp;
        }

        // 6. Most recent update (fresher data)
        int result = a2.getLastUpdatedAt().compareTo(a1.getLastUpdatedAt());
        log.trace("⚖️ Selection by freshness | Winner: {}", result < 0 ? a1.getArbId() : a2.getArbId());
        return result;
    }

    /**
     * DYNAMIC MIN PROFIT CALCULATION
     */
    private BigDecimal calculateDynamicMinProfit() {
        LocalTime now = LocalTime.now();
        BigDecimal baseProfit = BigDecimal.valueOf(minProfit);
        log.trace("📊 Calculating dynamic min profit | CurrentTime: {} | BaseProfit: {}%", now, baseProfit);

        // Peak hours (6PM - 11PM): require higher profit
        if (now.isAfter(LocalTime.of(18, 0)) && now.isBefore(LocalTime.of(23, 0))) {
            BigDecimal adjusted = baseProfit.multiply(new BigDecimal("1.15")); // 15% higher
            log.debug("🕐 Peak hours detected (6PM-11PM) | Profit requirement increased | {}% → {}%",
                    baseProfit, adjusted);
            return adjusted;
        }

        // Off-peak (1AM - 6AM): accept lower profit
        if (now.isAfter(LocalTime.of(1, 0)) && now.isBefore(LocalTime.of(6, 0))) {
            BigDecimal adjusted = baseProfit.multiply(new BigDecimal("0.85")); // 15% lower
            log.debug("🌙 Off-peak hours detected (1AM-6AM) | Profit requirement decreased | {}% → {}%",
                    baseProfit, adjusted);
            return adjusted;
        }

        log.trace("⏰ Normal hours | Using base profit: {}%", baseProfit);
        return baseProfit;
    }

    /**
     * PRECISE SLEEP CONTROL
     */
    private void preciseSleep(long totalPollTime) throws InterruptedException {
        long sleepTime = pollIntervalMs - totalPollTime;
        log.trace("💤 Sleep calculation | PollTime: {}ms | Interval: {}ms | SleepTime: {}ms",
                totalPollTime, pollIntervalMs, sleepTime);

        if (sleepTime > 0) {
            // For very short sleeps, use Thread.yield()
            if (sleepTime < 10) {
                log.trace("⚡ Very short sleep ({}ms) - using Thread.yield()", sleepTime);
                Thread.yield();
            } else {
                log.trace("💤 Sleeping for {}ms", sleepTime);
                Thread.sleep(sleepTime);
            }
        } else {
            log.trace("⚠️ Poll exceeded interval | Overrun: {}ms", Math.abs(sleepTime));

            // Track slow polls
            if (totalPollTime > pollIntervalMs * 2) {
                int slowCount = consecutiveSlowPolls.incrementAndGet();
                log.debug("🐌 Slow poll detected | Count: {} | Duration: {}ms (threshold: {}ms)",
                        slowCount, totalPollTime, pollIntervalMs * 2);

                if (slowCount >= 3) {
                    log.warn("🐌 WARNING: {} consecutive slow polls detected (>{}ms)",
                            slowCount, pollIntervalMs * 2);
                }
            } else {
                if (consecutiveSlowPolls.get() > 0) {
                    log.debug("✅ Poll speed normalized | Resetting slow poll counter");
                }
                consecutiveSlowPolls.set(0);
            }
        }
    }

    /**
     * PERFORMANCE MONITORING
     */
    private void monitorPerformance(long pollStart) {
        long totalPollTime = System.currentTimeMillis() - pollStart;
        log.trace("📊 Performance monitoring | PollTime: {}ms | Threshold: {}ms",
                totalPollTime, pollIntervalMs);

        // Reset slow poll counter if back to normal
        if (totalPollTime <= pollIntervalMs) {
            if (consecutiveSlowPolls.get() > 0) {
                log.debug("✅ Performance back to normal | Resetting slow poll counter");
                consecutiveSlowPolls.set(0);
            }
        }

        // Periodically log detailed stats
        if (successfulPolls.get() % 100 == 0) {
            log.info("📊 Periodic stats checkpoint reached | PollCount: {}", successfulPolls.get());
            logDetailedStats();
        }
    }

    /**
     * ERROR HANDLER with exponential backoff
     */
    private void handlePollError(Exception e) {
        int currentErrorCount = errorCount.incrementAndGet();
        log.error("💥 Poll error occurred | ErrorCount: {} | ErrorType: {}",
                currentErrorCount, e.getClass().getSimpleName());

        // Calculate backoff (capped at 5 seconds)
        long backoffMs = Math.min(5000, currentErrorCount * 500);
        log.warn("⏸️ Applying exponential backoff | BackoffDuration: {}ms | ErrorCount: {}",
                backoffMs, currentErrorCount);

        // Log only occasional errors to avoid spam
        if (currentErrorCount % 5 == 0) {
            log.error("❌ Recurring poll error #{} | Message: {}", currentErrorCount, e.getMessage());
        } else if (log.isDebugEnabled()) {
            log.debug("❌ Poll error #{} | Message: {}", currentErrorCount, e.getMessage());
        }

        // Quick backoff sleep
        try {
            log.debug("💤 Sleeping for backoff period: {}ms", backoffMs);
            Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
            log.warn("🛑 Backoff sleep interrupted | Stopping polling");
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    /**
     * LOG POLLING STATS (optimized)
     */
    private void logPollingStats(long queryTime, long processTime,
                                 int totalFound, int filteredCount,
                                 int processedCount, Instant now) {
        // Only log every 10 successful polls to reduce logging overhead
        if (successfulPolls.get() % 10 != 0 && filteredCount == 0) {
            return;
        }

        if (filteredCount > 0) {
            log.info("⚡ ═══════════════════════════════════════════════════════");
            log.info("⚡ POLL #{} SUMMARY", successfulPolls.get());
            log.info("⚡ ═══════════════════════════════════════════════════════");
            log.info("⚡ Query: {}ms | Process: {}ms", queryTime, processTime);
            log.info("⚡ Pipeline: Found {} → Filtered {} → Processed {}",
                    totalFound, filteredCount, processedCount);
            log.info("⚡ ═══════════════════════════════════════════════════════");
        } else if (successfulPolls.get() % 20 == 0) {
            log.debug("⏳ No stable arbs found | Poll #{} | Query: {}ms | Min profit: {}%",
                    successfulPolls.get(), queryTime, currentMinProfit);
        }
    }

    /**
     * LOG DETAILED STATS (less frequent)
     */
    private void logDetailedStats() {
        log.info("📊 ═══════════════════════════════════════════════════════");
        log.info("📊 DETAILED PERFORMANCE STATISTICS");
        log.info("📊 ═══════════════════════════════════════════════════════");
        log.info("📊 Query Performance | Avg: {}ms | Min: {}ms | Max: {}ms",
                String.format("%.1f", queryStats.getAverage()),
                queryStats.getMin(), queryStats.getMax());
        log.info("📊 Processing Performance | Avg: {}ms | Min: {}ms | Max: {}ms",
                String.format("%.1f", processingStats.getAverage()),
                processingStats.getMin(), processingStats.getMax());
        log.info("📊 Polling Statistics | Total: {} | Successful: {} | Errors: {}",
                successfulPolls.get(), successfulPolls.get(), errorCount.get());
        log.info("📊 Arb Processing | Total: {} | Slow polls: {}",
                totalArbsProcessed.get(), consecutiveSlowPolls.get());
        log.info("📊 Current Config | MinProfit: {}% | Interval: {}ms",
                currentMinProfit, pollIntervalMs);
        log.info("📊 ═══════════════════════════════════════════════════════");
    }
    /**
     * LOG FINAL STATS when polling stops
     */
    private void logFinalStats() {
        log.info("📊 FINAL STATS | " +
                        "Total polls: {} | Avg query: {}ms | " +
                        "Avg process: {}ms | Total processed: {} | " +
                        "Total errors: {}",
                successfulPolls.get(),
                String.format("%.1f", queryStats.getAverage()),
                String.format("%.1f", processingStats.getAverage()),
                totalArbsProcessed.get(),
                errorCount.get());
    }

    /**
     * WARM UP QUERIES on startup
     */
    private void warmUpQueries() {
        try {
            Instant now = Instant.now();
            // Warm up both query types
            arbRepository.findStableArbsForBetting(
                    BigDecimal.valueOf(minProfit),
                    now.minusSeconds(freshCutoffSeconds),
                    now.minusSeconds(minSessionSeconds),
                    maxBreaks,
                    PageRequest.of(0, 1)
            );

            arbRepository.findStableArbsNative(
                    BigDecimal.valueOf(minProfit),
                    now.minusSeconds(freshCutoffSeconds),
                    (long) minSessionSeconds,
                    maxBreaks,
                    PageRequest.of(0, 1, Sort.unsorted())
            );

            log.debug("✅ Query cache warmed up");
        } catch (Exception e) {
            log.debug("Query warmup failed (normal on first run): {}", e.getMessage());
        }
    }

    /**
     * Original killArb method
     */
    public void killArb(Arb arb) {
        if (arb != null) {
            arbService.saveArb(arb);
        } else {
            log.warn("arb is null and cannot be saved");
        }
    }

    @PreDestroy
    public void stopPolling() {
        log.info("🛑 Stopping fast arb polling...");
        running.set(false);

        if (pollingThread != null) {
            pollingThread.interrupt();
            try {
                pollingThread.join(3000); // Wait max 3 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        logFinalStats();
    }

    public boolean isRunning() {
        return running.get();
    }

    // Getters for monitoring
    public int getSuccessfulPolls() {
        return successfulPolls.get();
    }

    public int getTotalArbsProcessed() {
        return totalArbsProcessed.get();
    }

    public double getAverageQueryTime() {
        return queryStats.getAverage();
    }

    public double getAverageProcessingTime() {
        return processingStats.getAverage();
    }
}