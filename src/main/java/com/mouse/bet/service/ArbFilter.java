package com.mouse.bet.service;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.model.OddsChange;
import com.mouse.bet.model.VelocityVolatility;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

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
@Service
public class ArbFilter {

    private static final int METRICS_WINDOW_MINUTES = 10;
    private static final int MIN_SAMPLES_FOR_VOL = 3;
    private static final double VOLATILITY_WEIGHT = 0.08;
    private static final double CHANGE_RATE_WEIGHT = 0.35;

    // Emoji constants
    private static final String EMOJI_CHART = "📊";
    private static final String EMOJI_CALC = "🧮";

    /**
     * Recompute all derived metrics for an arb
     */
    public void recomputeMetrics(Arb arb) {
        log.debug("{} {} Recomputing metrics | ArbId: {}", EMOJI_CHART, EMOJI_CALC, arb.getArbId());

        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(METRICS_WINDOW_MINUTES * 60L);

        List<OddsChange> recent = arb.getOddsChangesBetween(cutoff, now);

        // Update odds change count
        arb.setOddsChangeCount(arb.getOddsHistory() != null ? arb.getOddsHistory().size() : 0);

        // Current odds as fallback
        BigDecimal curA = arb.getLegA().map(BetLeg::getOdds).orElse(null);
        BigDecimal curB = arb.getLegB().map(BetLeg::getOdds).orElse(null);

        // Calculate mean odds
        arb.setMeanOddsLegA(calculateMeanOddsA(recent, curA));
        arb.setMeanOddsLegB(calculateMeanOddsB(recent, curB));

        // Calculate velocity and volatility
        VelocityVolatility vv = calculateVelocityAndVolatility(recent);
        arb.setVelocityPctPerSec(vv.velocityPctPerSec());
        arb.setVolatilitySigma(vv.volatilitySigma());

        // Calculate confidence score
        double confidence = calculateConfidenceScore(vv.volatilitySigma(), recent, cutoff, now);
        arb.setConfidenceScore(confidence);

        log.debug("{} Metrics updated | Confidence: {:.2f} | Volatility: {:.2f} | Velocity: {:.2f}",
                EMOJI_CHART, confidence, vv.volatilitySigma(), vv.velocityPctPerSec());
    }

    /**
     * Calculate mean odds for Leg A
     */
    private Double calculateMeanOddsA(List<OddsChange> changes, BigDecimal fallback) {
        List<BigDecimal> vals = changes.stream()
                .map(OddsChange::getNewOddsA)
                .filter(Objects::nonNull)
                .toList();

        if (vals.isEmpty()) {
            return fallback != null ? fallback.doubleValue() : null;
        }

        BigDecimal sum = vals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(vals.size()), 8, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Calculate mean odds for Leg B
     */
    private Double calculateMeanOddsB(List<OddsChange> changes, BigDecimal fallback) {
        List<BigDecimal> vals = changes.stream()
                .map(OddsChange::getNewOddsB)
                .filter(Objects::nonNull)
                .toList();

        if (vals.isEmpty()) {
            return fallback != null ? fallback.doubleValue() : null;
        }

        BigDecimal sum = vals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(vals.size()), 8, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Calculate velocity and volatility metrics
     */
    private VelocityVolatility calculateVelocityAndVolatility(List<OddsChange> changes) {
        if (changes == null || changes.size() < 2) {
            return new VelocityVolatility(0.0, 0.0);
        }

        List<OddsChange> seq = new ArrayList<>(changes);
        seq.sort(Comparator.comparing(OddsChange::getTimestamp));

        List<Double> pctDeltas = new ArrayList<>();
        Instant firstTs = seq.get(0).getTimestamp();
        Instant lastTs = seq.get(seq.size() - 1).getTimestamp();

        for (OddsChange ch : seq) {
            Double pctA = calculatePercentageDelta(ch.getOldOddsA(), ch.getNewOddsA());
            Double pctB = calculatePercentageDelta(ch.getOldOddsB(), ch.getNewOddsB());

            if (pctA == null && pctB == null) continue;

            if (pctA != null && pctB != null) {
                pctDeltas.add((Math.abs(pctA) + Math.abs(pctB)) / 2.0);
            } else {
                pctDeltas.add(Math.abs(pctA != null ? pctA : pctB));
            }
        }

        if (pctDeltas.isEmpty()) {
            return new VelocityVolatility(0.0, 0.0);
        }

        long seconds = Math.max(1L, lastTs.getEpochSecond() - firstTs.getEpochSecond());
        double totalAbsPct = pctDeltas.stream().mapToDouble(Double::doubleValue).sum();

        double velocity = totalAbsPct / seconds;

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
     * Calculate percentage delta between two odds values
     */
    private Double calculatePercentageDelta(BigDecimal oldVal, BigDecimal newVal) {
        if (oldVal == null || newVal == null) return null;
        if (oldVal.compareTo(BigDecimal.ZERO) == 0) return null;

        return newVal.subtract(oldVal)
                .divide(oldVal, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    /**
     * Calculate confidence score (0..1)
     * Higher = more stable
     */
    private double calculateConfidenceScore(Double volatilitySigma,
                                            List<OddsChange> recent,
                                            Instant from,
                                            Instant to) {
        if (volatilitySigma == null) volatilitySigma = 0.0;

        long seconds = Math.max(1L, to.getEpochSecond() - from.getEpochSecond());
        double changesPerMin = (recent != null ? recent.size() : 0) / (seconds / 60.0);

        double volatilityScore = Math.exp(-VOLATILITY_WEIGHT * volatilitySigma);
        double changeRateScore = Math.exp(-CHANGE_RATE_WEIGHT * changesPerMin);

        double score = volatilityScore * changeRateScore;

        return Math.max(0.0, Math.min(1.0, score));
    }

    /**
     * Calculate mean deviation from SMA
     */
    public double calculateMeanDeviation(Arb arb) {
        double devA = 0.0, devB = 0.0;
        int n = 0;

        BigDecimal legAOdds = arb.getLegA().map(BetLeg::getOdds).orElse(null);
        if (legAOdds != null && arb.getMeanOddsLegA() != null && arb.getMeanOddsLegA() > 0) {
            devA = Math.abs(legAOdds.doubleValue() / arb.getMeanOddsLegA() - 1.0) * 100.0;
            n++;
        }

        BigDecimal legBOdds = arb.getLegB().map(BetLeg::getOdds).orElse(null);
        if (legBOdds != null && arb.getMeanOddsLegB() != null && arb.getMeanOddsLegB() > 0) {
            devB = Math.abs(legBOdds.doubleValue() / arb.getMeanOddsLegB() - 1.0) * 100.0;
            n++;
        }

        return n == 0 ? 0.0 : (devA + devB) / n;
    }

    /**
     * Calculate recency boost
     */
    public double calculateRecencyBoost(Arb arb) {
        Instant lu = arb.getLastUpdatedAt();
        if (lu == null) return 0.0;

        long ageSec = Math.max(0, Instant.now().getEpochSecond() - lu.getEpochSecond());

        if (ageSec <= 3) return 1.0;
        if (ageSec <= 10) return 0.5;
        return 0.0;
    }
}