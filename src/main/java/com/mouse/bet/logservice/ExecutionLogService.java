package com.mouse.bet.logservice;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.BetLegStatus;
import com.mouse.bet.enums.BookMaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionLogService {

    private final MetricService metricService;


    /** Call when you begin trying to place an arb's legs. */
    public Instant logExecutionStart(Arb arb, BookMaker bookMaker) {
//        log.info("EXECUTION START | eventId={} market={} selection={} Bookmaker={} stake={}",
//                arb.getNormalEventId(), arb.getSelectionKey(),
//                bookMaker, arb.getLegA().getBookmaker() == bookMaker ?
//                        arb.getLegA().getStake() : arb.getLegB().getStake());
        metricService.recordMetric("arb.exec."+bookMaker+".start.count", 1);
        return Instant.now();
    }

    /** Call after the attempt completes. Use the Instant from logExecutionStart for duration. */
    public void logExecutionResult(Arb arb, boolean success, Instant startedAt, BookMaker bookMaker) {
        long ms = startedAt == null ? 0 : Duration.between(startedAt, Instant.now()).toMillis();
        log.info("EXECUTION {} |  eventId={} market={} tookMs={} expectedProfit={}",
                success ? BetLegStatus.PLACED : BetLegStatus.FAILED,
                arb.getArbId(),
                ms,
                safe(arb.getProfitPercentage()));

        metricService.recordMetric(success ? "arb.exec."+bookMaker+"success.count" : "arb.exec."+bookMaker+".fail.count", 1);
        metricService.recordMetric("arb.exec."+bookMaker+"duration_ms", ms);
    }



    public void logLegAttempt(Arb arb, BetLeg leg, BigDecimal plannedStake) {
        log.info("LEG ATTEMPT | bookMaker={} odds={} plannedStake={} marketCategory={} selection={} eventId={}",
                safeBookie(leg), leg == null ? null : leg.getOdds(),
                safe(plannedStake),
                arb.getArbId());
        metricService.recordMetric("arb.exec."+leg.getBookmaker()+".leg.attempt.count", 1);
    }

    public void logLegSuccess(Arb arb, BetLeg leg, String betId, long latencyMs, BigDecimal stakedAmount) {
        log.info("LEG SUCCESS | bookie={} betId={} latencyMs={} eventId={} stakedAmount={}",
                leg.getBookmaker(), betId, latencyMs, arb.getArbId(), stakedAmount);
        metricService.recordMetric("arb.exec"+leg.getBookmaker()+".leg.success.count", 1);
        metricService.recordMetric("arb.exec"+leg.getBookmaker()+".leg.stake_amount", stakedAmount);
        metricService.recordMetric("arb.exec"+leg.getBookmaker()+".leg.latency_ms", latencyMs);
    }

    public void logLegFailure(Arb arb, BetLeg leg, String reason, Throwable t) {
        log.warn("LEG FAIL | bookie={} reason={} eventId={}", leg.getBookmaker(), reason, arb.getArbId(), t);
        metricService.recordMetric("arb.exec"+leg.getBookmaker()+".leg.fail.count", 1);
    }

    // ---------- Retry / Cancel / Hedge ----------

    public void logRetry(BetLeg leg, String eventId, String reason) {
        log.warn("RETRY | bookie={} eventId={} reason={}", leg.getBookmaker(), eventId, reason);
        metricService.recordMetric("arb.exec"+leg.getBookmaker()+".retry.count", 1);

    }

    public void logHedge(String bookie, BigDecimal deltaStake, Double hedgeOdds) {
        log.info("HEDGE | bookie={} deltaStake={} odds={}", bookie, safe(deltaStake), hedgeOdds);
        metricService.recordMetric("arb.exec.hedge.count", 1);
    }


    public void logError(String message, Throwable t) {
        log.error(message, t);
        metricService.recordMetric("arb.exec.error.count", 1);
    }


    private static String safe(Object o) { return o == null ? "null" : o.toString(); }

    private static String safeBookie(BetLeg leg) { return leg == null ? "null" : leg.getBookmaker().name(); }
}
