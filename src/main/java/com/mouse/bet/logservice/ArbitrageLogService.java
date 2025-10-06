package com.mouse.bet.logservice;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.entity.BetLeg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArbitrageLogService {

    private final MetricService metricService;

    /**
     * Called when a new arbitrage candidate is produced by the detector.
     */
    public void logArb(Arb arb) {
        log.info("ARB DETECTED | eventId={} league={} market={} selection={} profitPercentage={} lastSeen={} shouldBet={}",
                arb.getEventId(), arb.getLeague(), arb.getMarketType(), arb.getSelectionKey(),
                arb.getPeakProfitPercentage(), arb.getLastSeenAt(), arb.isShouldBet());

        metricService.recordMetric("arb.detected.count", 1);

        metricService.recordMetric("arb.profit_percentage", arb.getProfitPercentage());

    }

    /**
     * Called when the Arb is rejected by risk filter (e.g., TTL/velocity/liquidity).
     */
    public void logFiltered(Arb arb, String reason) {
        log.debug("ARB FILTERED | reason={} eventId={} market={} ",
                reason, arb.getEventId(), arb.getMarketType());
        metricService.recordMetric("arb.filtered.count", 1);
    }

    /**
     * Called when an Arb cannot be funded across bookies.
     */
    public void logInsufficientFunds(Arb arb) {
        log.warn("Insufficient Fund in one or both Bookmaker | eventId={} legA={} legB={} requiredAStake={} requiredBStake={}",
                arb.getEventId(),
                safeLeg(arb.getLegA()), safeLeg(arb.getLegB()),
                arb.getStakeA(), arb.getStakeB());
        metricService.recordMetric("arb.unfunded.count", 1);
    }

    public void logCacheInsert(String marketKey, int sizeAfter) {
        log.debug("CACHE INSERT | marketKey={} sizeAfter={}", marketKey, sizeAfter);
        metricService.recordMetric("arb.cache.insert.count", 1);
    }

    public void logCacheEvict(String marketKey, int sizeAfter) {
        log.debug("CACHE EVICT | marketKey={} sizeAfter={}", marketKey, sizeAfter);
        metricService.recordMetric("arb.cache.evict.count", 1);
    }

    public void logError(String message, Throwable t) {
        log.error(message, t);
        metricService.recordMetric("arb.error.count", 1);
    }
    public void logInfo(String message, Throwable t) {
        log.info(message, t);

    }


    private static String safeLeg(BetLeg leg) {
        if (leg == null) return "null";
        return leg.getBookmaker() + "@" + leg.getOdds();
    }
}
