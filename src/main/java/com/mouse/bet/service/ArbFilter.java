package com.mouse.bet.service;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.model.OddsChange;
import com.mouse.bet.model.VelocityVolatility;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class for computing derived metrics and confidence scores for arbitrage opportunities.
 *
 */
@Slf4j
public class ArbFilter {

    // Metric tuning parameters
    private static final int METRICS_WINDOW_MINUTES = 10;  // lookback for stability metrics
    private static final int MIN_SAMPLES_FOR_VOL = 3;      // minimum deltas to compute stdev
    private static final double VOLATILITY_WEIGHT = 0.08;  // weight on sigma in confidence score
    private static final double CHANGE_RATE_WEIGHT = 0.35; // weight on change rate per minute

    /**
     * Recompute all derived metrics for an arb based on recent odds history.
     * Updates: meanOddsLegA/B, velocityPctPerSec, volatilitySigma, confidenceScore, oddsChangeCount
     */
    public static void recomputeDerivedMetrics(Arb arb) {
        final Instant now = Instant.now();
        final Instant cutoff = now.minusSeconds(METRICS_WINDOW_MINUTES * 60L);

        // Recent odds changes from entity helper (sorted DESC)
        final List<OddsChange> recent = arb.getOddsChangesBetween(cutoff, now);

        // Keep oddsChangeCount consistent with stored map
        arb.setOddsChangeCount(arb.getOddsHistory() != null ? arb.getOddsHistory().size() : 0);

        // Current odds for fallbacks
        final BigDecimal curA = arb.getLegA().map(BetLeg::getOdds).orElse(null);
        final BigDecimal curB = arb.getLegB().map(BetLeg::getOdds).orElse(null);

        // Means over window (SMA of new odds)
        arb.setMeanOddsLegA(meanOfNewOddsA(recent).orElse(curA != null ? curA.doubleValue() : null));
        arb.setMeanOddsLegB(meanOfNewOddsB(recent).orElse(curB != null ? curB.doubleValue() : null));

        // Velocity (|%| per sec) and Volatility (stdev of % moves)
        VelocityVolatility vv = computeVelocityAndVolatility(recent);
        arb.setVelocityPctPerSec(vv.velocityPctPerSec());
        arb.setVolatilitySigma(vv.volatilitySigma());

        // Confidence score (0..1): higher = more stable
        double confidence = computeConfidenceScore(vv.volatilitySigma(), recent, cutoff, now);
        arb.setConfidenceScore(confidence);

        if (log.isDebugEnabled()) {
            log.debug("Recomputed metrics for {} | confidence={} volatility={} velocity={}",
                    arb.getArbId(), confidence, vv.volatilitySigma(), vv.velocityPctPerSec());
        }
    }

    /**
     * Compute mean of newOddsA from recent changes
     */
    private static Optional<Double> meanOfNewOddsA(List<OddsChange> changes) {
        List<BigDecimal> vals = changes.stream()
                .map(OddsChange::getNewOddsA)
                .filter(Objects::nonNull)
                .toList();

        if (vals.isEmpty()) return Optional.empty();

        BigDecimal sum = vals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(sum.divide(BigDecimal.valueOf(vals.size()), 8, RoundingMode.HALF_UP).doubleValue());
    }

    /**
     * Compute mean of newOddsB from recent changes
     */
    private static Optional<Double> meanOfNewOddsB(List<OddsChange> changes) {
        List<BigDecimal> vals = changes.stream()
                .map(OddsChange::getNewOddsB)
                .filter(Objects::nonNull)
                .toList();

        if (vals.isEmpty()) return Optional.empty();

        BigDecimal sum = vals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(sum.divide(BigDecimal.valueOf(vals.size()), 8, RoundingMode.HALF_UP).doubleValue());
    }

    /**
     * Compute velocity (% change per second) and volatility (standard deviation of % changes)
     */
    private static VelocityVolatility computeVelocityAndVolatility(List<OddsChange> changes) {
        if (changes == null || changes.size() < 2) {
            return new VelocityVolatility(0.0, 0.0);
        }

        // Oldest -> newest for time span
        List<OddsChange> seq = new ArrayList<>(changes);
        seq.sort(Comparator.comparing(OddsChange::getTimestamp));

        List<Double> pctDeltas = new ArrayList<>();
        Instant firstTs = seq.get(0).getTimestamp();
        Instant lastTs = seq.get(seq.size() - 1).getTimestamp();

        for (var ch : seq) {
            Double pctA = calculatePercentageDelta(ch.getOldOddsA(), ch.getNewOddsA());
            Double pctB = calculatePercentageDelta(ch.getOldOddsB(), ch.getNewOddsB());

            if (pctA == null && pctB == null) continue;

            if (pctA != null && pctB != null) {
                // Average of both leg changes
                pctDeltas.add((Math.abs(pctA) + Math.abs(pctB)) / 2.0);
            } else {
                // Only one leg changed
                pctDeltas.add(Math.abs(pctA != null ? pctA : pctB));
            }
        }

        if (pctDeltas.isEmpty()) {
            return new VelocityVolatility(0.0, 0.0);
        }

        long seconds = Math.max(1L, lastTs.getEpochSecond() - firstTs.getEpochSecond());
        double totalAbsPct = pctDeltas.stream().mapToDouble(Double::doubleValue).sum();

        // Velocity: % per second (absolute)
        double velocity = totalAbsPct / seconds;

        // Volatility: Standard deviation of per-change % moves
        double volatility = 0.0;
        if (pctDeltas.size() >= MIN_SAMPLES_FOR_VOL) {
            double mean = totalAbsPct / pctDeltas.size();
            double variance = pctDeltas.stream()
                    .mapToDouble(v -> (v - mean) * (v - mean))
                    .sum() / (pctDeltas.size() - 1);
            volatility = Math.sqrt(variance);
        }

        return new VelocityVolatility(velocity, volatility);
    }

    /**
     * Calculate percentage delta: (new-old)/old * 100
     * Returns null if either value is null or old value is zero
     */
    private static Double calculatePercentageDelta(BigDecimal oldVal, BigDecimal newVal) {
        if (oldVal == null || newVal == null) return null;
        if (oldVal.compareTo(BigDecimal.ZERO) == 0) return null;

        return newVal.subtract(oldVal)
                .divide(oldVal, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    /**
     * Compute confidence score ∈ [0,1]. Higher means more stable.
     * Penalizes both volatility and change rate in the window using exponential decay.
     */
    private static double computeConfidenceScore(Double volatilitySigma,
                                          List<OddsChange> recent,
                                          Instant from,
                                          Instant to) {
        if (volatilitySigma == null) volatilitySigma = 0.0;

        long seconds = Math.max(1L, to.getEpochSecond() - from.getEpochSecond());
        double changesPerMin = (recent != null ? recent.size() : 0) / (seconds / 60.0);

        // Exponential decay scoring
        double volatilityScore = Math.exp(-VOLATILITY_WEIGHT * volatilitySigma);  // 0..1
        double changeRateScore = Math.exp(-CHANGE_RATE_WEIGHT * changesPerMin);   // 0..1

        double score = volatilityScore * changeRateScore;

        // Clamp to [0, 1]
        return Math.max(0.0, Math.min(1.0, score));
    }


}