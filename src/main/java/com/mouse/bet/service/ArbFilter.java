package com.mouse.bet.service;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.model.OddsChange;
import com.mouse.bet.model.VelocityVolatility;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Utility class for computing derived metrics and confidence scores for arbitrage opportunities.
 */
@Slf4j
@Service
public class ArbFilter {

    private static final int METRICS_WINDOW_MINUTES = 10;
    private static final int MIN_SAMPLES_FOR_VOL = 3;
    private static final double VOLATILITY_WEIGHT = 0.08;
    private static final double CHANGE_RATE_WEIGHT = 0.35;
    private static final int MAX_RECENT_ODDS_CHANGES = 3;
    private static final long MAX_STALE_SECONDS = 5;

    @Value("${arb.session.min-stable-seconds:30}")
    private int minStableSessionSeconds;

    @Value("${arb.session.max-allowed-breaks:2}")
    private int maxAllowedBreaks;

    // Emoji constants
    private static final String EMOJI_CHART = "📊";
    private static final String EMOJI_CALC = "🧮";
    private static final String EMOJI_WARNING = "⚠️";
    private static final String EMOJI_ERROR = "❌";
    private static final String EMOJI_SUCCESS = "✅";
    private static final String EMOJI_CLOCK = "⏰";

    /**
     * Check if arb has been stable long enough for betting
     * Combines session duration with other stability metrics
     */
    public boolean isStableForBetting(Arb arb) {
        try {
            if (arb == null) return false;

            // Check session duration
            Long sessionSeconds = arb.getCurrentSessionDurationSeconds();
            if (sessionSeconds == null || sessionSeconds < minStableSessionSeconds) {
                log.info("{} {} Session too short: {}s (need {}s) | ArbId: {}",
                        EMOJI_CLOCK, EMOJI_WARNING,
                        sessionSeconds, minStableSessionSeconds, arb.getArbId());
                return false;
            }

            // Check continuity breaks
            if (arb.getContinuityBreakCount() > maxAllowedBreaks) {
                log.info("{} {} Too many continuity breaks: {} | ArbId: {}",
                        EMOJI_WARNING, EMOJI_CLOCK,
                        arb.getContinuityBreakCount(), arb.getArbId());
                return false;
            }

            // Check odds stability
            if (!hasStableOdds(arb)) {
                log.info("{} {} Odds not stable | ArbId: {}",
                        EMOJI_WARNING, EMOJI_CHART, arb.getArbId());
                return false;
            }

            // Check recent activity
            if (isStale(arb)) {
                log.info("{} {} Arb is stale | Last updated: {} | ArbId: {}",
                        EMOJI_CLOCK, EMOJI_WARNING,
                        arb.getLastUpdatedAt(), arb.getArbId());
                return false;
            }

            log.info("{} {} Arb is stable | Session: {}s | Breaks: {} | ArbId: {}",
                    EMOJI_SUCCESS, EMOJI_CLOCK,
                    sessionSeconds, arb.getContinuityBreakCount(), arb.getArbId());

            return true;

        } catch (Exception e) {
            log.error("{} {} Error checking stability | Error: {}",
                    EMOJI_ERROR, EMOJI_CALC, e.getMessage());
            return false;
        }
    }

    /**
     * Check if odds have been stable recently
     */
    private boolean hasStableOdds(Arb arb) {
        if (arb.getOddsChangeCount() == null || arb.getOddsChangeCount() < 2) {
            return true; // Not enough data to determine
        }

        Instant cutoff = Instant.now().minusSeconds(minStableSessionSeconds); // Last 30 seconds
        List<OddsChange> recent = arb.getOddsChangesBetween(cutoff, Instant.now());

        if (recent.size() > MAX_RECENT_ODDS_CHANGES) {
            log.debug("Too many recent odds changes: {} | ArbId: {}", recent.size(), arb.getArbId());
            return false;
        }

        return true;
    }

    /**
     * Check if arb is stale (no recent updates)
     */
    private boolean isStale(Arb arb) {
        if (arb.getLastUpdatedAt() == null) return true;

        long ageSeconds = Duration.between(arb.getLastUpdatedAt(), Instant.now()).getSeconds();
        return ageSeconds > MAX_STALE_SECONDS;
    }

    /**
     * Recompute all derived metrics for an arb
     */
    public void recomputeMetrics(Arb arb) {
        try {
            if (arb == null) {
                log.error("{} {} Null arb provided to recomputeMetrics", EMOJI_ERROR, EMOJI_CALC);
                return;
            }

            log.info("{} {} Starting metric recomputation | ArbId: {} | Session: {}s",
                    EMOJI_CHART, EMOJI_CALC, arb.getArbId(), arb.getCurrentSessionDurationSeconds());

            Instant now = Instant.now();
            Instant cutoff = now.minusSeconds(METRICS_WINDOW_MINUTES * 60L);

            List<OddsChange> recent = arb.getOddsChangesBetween(cutoff, now);
            log.info("Retrieved {} odds changes within {}-minute window",
                    recent != null ? recent.size() : 0, METRICS_WINDOW_MINUTES);

            // Update odds change count
            int oddsChangeCount = arb.getOddsHistory() != null ? arb.getOddsHistory().size() : 0;
            arb.setOddsChangeCount(oddsChangeCount);
            log.info("Total odds change count: {}", oddsChangeCount);

            // Current odds as fallback
            BigDecimal curA = arb.getLegA().map(BetLeg::getOdds).orElse(null);
            BigDecimal curB = arb.getLegB().map(BetLeg::getOdds).orElse(null);
            log.info("Current odds | LegA: {} | LegB: {}", curA, curB);

            // Calculate mean odds
            Double meanA = calculateMeanOddsA(recent, curA);
            Double meanB = calculateMeanOddsB(recent, curB);
            arb.setMeanOddsLegA(meanA);
            arb.setMeanOddsLegB(meanB);
            log.info("Mean odds calculated | LegA: {} | LegB: {}", meanA, meanB);

            // Calculate velocity and volatility
            VelocityVolatility vv = calculateVelocityAndVolatility(recent);
            arb.setVelocityPctPerSec(vv.velocityPctPerSec());
            arb.setVolatilitySigma(vv.volatilitySigma());
            log.info("Velocity & volatility | Velocity: {}%/sec | Volatility: {}",
                    vv.velocityPctPerSec(), vv.volatilitySigma());

            // Calculate confidence score
            Double confidence = calculateConfidenceScore(vv.volatilitySigma(), recent, cutoff, now);
            arb.setConfidenceScore(confidence);

            log.info("{} {} Metrics updated successfully | ArbId: {} | Confidence: {} | Volatility: {} | Velocity: {}",
                    EMOJI_SUCCESS, EMOJI_CHART, arb.getArbId(), confidence, vv.volatilitySigma(), vv.velocityPctPerSec());

        } catch (Exception e) {
            log.error("{} {} Error recomputing metrics | ArbId: {} | Error: {}",
                    EMOJI_ERROR, EMOJI_CALC, arb != null ? arb.getArbId() : "unknown", e.getMessage(), e);
        }
    }

    /**
     * Calculate mean odds for Leg A
     */
    private Double calculateMeanOddsA(List<OddsChange> changes, BigDecimal fallback) {
        try {
            List<BigDecimal> vals = changes.stream()
                    .map(OddsChange::getNewOddsA)
                    .filter(Objects::nonNull)
                    .toList();

            if (vals.isEmpty()) {
                log.info("No odds changes for LegA, using fallback: {}", fallback);
                return fallback != null ? fallback.doubleValue() : null;
            }

            BigDecimal sum = vals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            Double mean = sum.divide(BigDecimal.valueOf(vals.size()), 8, RoundingMode.HALF_UP).doubleValue();
            log.info("Calculated mean odds LegA from {} samples: {}", vals.size(), mean);
            return mean;

        } catch (Exception e) {
            log.error("{} Error calculating mean odds for LegA: {}", EMOJI_ERROR, e.getMessage(), e);
            return fallback != null ? fallback.doubleValue() : null;
        }
    }

    /**
     * Calculate mean odds for Leg B
     */
    private Double calculateMeanOddsB(List<OddsChange> changes, BigDecimal fallback) {
        try {
            List<BigDecimal> vals = changes.stream()
                    .map(OddsChange::getNewOddsB)
                    .filter(Objects::nonNull)
                    .toList();

            if (vals.isEmpty()) {
                log.info("No odds changes for LegB, using fallback: {}", fallback);
                return fallback != null ? fallback.doubleValue() : null;
            }

            BigDecimal sum = vals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            Double mean = sum.divide(BigDecimal.valueOf(vals.size()), 8, RoundingMode.HALF_UP).doubleValue();
            log.info("Calculated mean odds LegB from {} samples: {}", vals.size(), mean);
            return mean;

        } catch (Exception e) {
            log.error("{} Error calculating mean odds for LegB: {}", EMOJI_ERROR, e.getMessage(), e);
            return fallback != null ? fallback.doubleValue() : null;
        }
    }

    /**
     * Calculate velocity and volatility metrics
     */
    private VelocityVolatility calculateVelocityAndVolatility(List<OddsChange> changes) {
        try {
            if (changes == null || changes.size() < 2) {
                log.info("Insufficient data for velocity/volatility calculation (samples: {})",
                        changes != null ? changes.size() : 0);
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
                log.info("No valid percentage deltas found");
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
                log.info("Volatility calculated from {} samples: {}", pctDeltas.size(), volatility);
            } else {
                log.info("{} Insufficient samples ({}) for volatility calculation (min: {})",
                        EMOJI_WARNING, pctDeltas.size(), MIN_SAMPLES_FOR_VOL);
            }

            log.info("Velocity/volatility computed | Time span: {}s | Deltas: {} | Velocity: {}",
                    seconds, pctDeltas.size(), velocity);
            return new VelocityVolatility(velocity, volatility);

        } catch (Exception e) {
            log.error("{} Error calculating velocity and volatility: {}", EMOJI_ERROR, e.getMessage(), e);
            return new VelocityVolatility(0.0, 0.0);
        }
    }

    /**
     * Calculate percentage delta between two odds values
     */
    private Double calculatePercentageDelta(BigDecimal oldVal, BigDecimal newVal) {
        try {
            if (oldVal == null || newVal == null) return null;
            if (oldVal.compareTo(BigDecimal.ZERO) == 0) {
                log.info("{} Division by zero avoided in percentage delta calculation", EMOJI_WARNING);
                return null;
            }

            return newVal.subtract(oldVal)
                    .divide(oldVal, 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();

        } catch (Exception e) {
            log.error("{} Error calculating percentage delta | Old: {} | New: {} | Error: {}",
                    EMOJI_ERROR, oldVal, newVal, e.getMessage());
            return null;
        }
    }

    /**
     * Calculate confidence score (0..1)
     * Higher = more stable
     */
    private double calculateConfidenceScore(Double volatilitySigma,
                                            List<OddsChange> recent,
                                            Instant from,
                                            Instant to) {
        try {
            if (volatilitySigma == null) {
                log.info("Null volatility sigma, defaulting to 0.0");
                volatilitySigma = 0.0;
            }

            long seconds = Math.max(1L, to.getEpochSecond() - from.getEpochSecond());
            double changesPerMin = (recent != null ? recent.size() : 0) / (seconds / 60.0);

            double volatilityScore = Math.exp(-VOLATILITY_WEIGHT * volatilitySigma);
            double changeRateScore = Math.exp(-CHANGE_RATE_WEIGHT * changesPerMin);

            double score = volatilityScore * changeRateScore;
            double clampedScore = Math.max(0.0, Math.min(1.0, score));

            log.info("Confidence score computed | Raw: {} | Clamped: {} | VolScore: {} | ChangeRateScore: {} | ChangesPerMin: {}",
                    score, clampedScore, volatilityScore, changeRateScore, changesPerMin);

            return clampedScore;

        } catch (Exception e) {
            log.error("{} Error calculating confidence score: {}", EMOJI_ERROR, e.getMessage(), e);
            return 0.0;
        }
    }

    /**
     * Calculate mean deviation from SMA
     */
    public double calculateMeanDeviation(Arb arb) {
        try {
            if (arb == null) {
                log.error("{} Null arb provided to calculateMeanDeviation", EMOJI_ERROR);
                return 0.0;
            }

            log.info("Calculating mean deviation for ArbId: {} | Session: {}s",
                    arb.getArbId(), arb.getCurrentSessionDurationSeconds());

            double devA = 0.0, devB = 0.0;
            int n = 0;

            BigDecimal legAOdds = arb.getLegA().map(BetLeg::getOdds).orElse(null);
            if (legAOdds != null && arb.getMeanOddsLegA() != null && arb.getMeanOddsLegA() > 0) {
                devA = Math.abs(legAOdds.doubleValue() / arb.getMeanOddsLegA() - 1.0) * 100.0;
                log.info("LegA deviation: {}% | Current: {} | Mean: {}", devA, legAOdds, arb.getMeanOddsLegA());
                n++;
            } else {
                log.info("Skipping LegA deviation (odds: {}, mean: {})", legAOdds, arb.getMeanOddsLegA());
            }

            BigDecimal legBOdds = arb.getLegB().map(BetLeg::getOdds).orElse(null);
            if (legBOdds != null && arb.getMeanOddsLegB() != null && arb.getMeanOddsLegB() > 0) {
                devB = Math.abs(legBOdds.doubleValue() / arb.getMeanOddsLegB() - 1.0) * 100.0;
                log.info("LegB deviation: {}% | Current: {} | Mean: {}", devB, legBOdds, arb.getMeanOddsLegB());
                n++;
            } else {
                log.info("Skipping LegB deviation (odds: {}, mean: {})", legBOdds, arb.getMeanOddsLegB());
            }

            double meanDev = n == 0 ? 0.0 : (devA + devB) / n;
            log.info("Mean deviation calculated: {}% (from {} legs)", meanDev, n);
            return meanDev;

        } catch (Exception e) {
            log.error("{} Error calculating mean deviation | ArbId: {} | Error: {}",
                    EMOJI_ERROR, arb != null ? arb.getArbId() : "unknown", e.getMessage(), e);
            return 0.0;
        }
    }

    /**
     * Calculate recency boost
     */
    public double calculateRecencyBoost(Arb arb) {
        try {
            if (arb == null) {
                log.error("{} Null arb provided to calculateRecencyBoost", EMOJI_ERROR);
                return 0.0;
            }

            Instant lu = arb.getLastUpdatedAt();
            if (lu == null) {
                log.info("No last updated timestamp for ArbId: {}, boost = 0.0", arb.getArbId());
                return 0.0;
            }

            long ageSec = Math.max(0, Instant.now().getEpochSecond() - lu.getEpochSecond());
            double boost;

            if (ageSec <= 3) {
                boost = 1.0;
            } else if (ageSec <= 10) {
                boost = 0.5;
            } else {
                boost = 0.0;
            }

            log.info("Recency boost calculated | ArbId: {} | Age: {}s | Boost: {}",
                    arb.getArbId(), ageSec, boost);
            return boost;

        } catch (Exception e) {
            log.error("{} Error calculating recency boost | ArbId: {} | Error: {}",
                    EMOJI_ERROR, arb != null ? arb.getArbId() : "unknown", e.getMessage(), e);
            return 0.0;
        }
    }
}