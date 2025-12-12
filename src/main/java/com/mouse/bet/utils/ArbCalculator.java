package com.mouse.bet.utils;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class ArbCalculator {

    //method to calculate the arbitrage percentage using odds from both bookies(oddA, OddB)
    //calculate stake amount at any point
    //method to roound the stake amount to avoid detection of arbing activites
    //a method to take in total stake amount and an arb as parammeter and return update arb with stakes for each betleg or bookie
    private static final int DEFAULT_DECIMAL_PLACES = 2;
    private static final BigDecimal MIN_STAKE = new BigDecimal("10");
    private static final BigDecimal MAX_RANDOMIZATION_PERCENTAGE = new BigDecimal("0.05"); // 5%
    private static final BigDecimal MAX_STAKE = new BigDecimal("50000");
    private static final BigDecimal STEP_50  = new BigDecimal("50");
    private static final BigDecimal STEP_100 = new BigDecimal("100");
    // High precision; no final setScale applied.
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);



    /**
     * Calculate the arbitrage percentage from two odds (for 2-way arb)
     * Formula: Arbitrage % = (1/oddsA + 1/oddsB) * 100
     *
     * @param oddsA odds for leg A
     * @param oddsB odds for leg B
     * @return arbitrage percentage (< 100 means profit opportunity)
     */
    public static BigDecimal calculateArbitragePercentage(BigDecimal oddsA, BigDecimal oddsB) {
        if (oddsA == null || oddsB == null ||
                oddsA.compareTo(BigDecimal.ZERO) <= 0 || oddsB.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid odds for arbitrage calculation: oddsA={}, oddsB={}", oddsA, oddsB);
            return BigDecimal.valueOf(100);
        }

        BigDecimal impliedProbA = BigDecimal.ONE.divide(oddsA, 6, RoundingMode.HALF_UP);
        BigDecimal impliedProbB = BigDecimal.ONE.divide(oddsB, 6, RoundingMode.HALF_UP);

        BigDecimal arbPercentage = impliedProbA.add(impliedProbB)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);

        log.debug("Calculated arbitrage percentage: {}% from oddsA: {}, oddsB: {}",
                arbPercentage, oddsA, oddsB);
        return arbPercentage;
    }

    /**
     * Calculate profit percentage from arbitrage percentage
     * Formula: Profit % = ((1 / (Arbitrage % / 100)) - 1) * 100
     *
     * @param arbitragePercentage the arbitrage percentage
     * @return profit percentage
     */
    public static BigDecimal calculateProfitPercentage(BigDecimal arbitragePercentage) {
        if (arbitragePercentage == null || arbitragePercentage.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal profitPercentage = BigDecimal.ONE
                .divide(arbitragePercentage.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP),
                        6, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);

        log.debug("Calculated profit percentage: {}% from arbitrage percentage: {}%",
                profitPercentage, arbitragePercentage);
        return profitPercentage;
    }

    public static BigDecimal stakeForBookieA(BigDecimal oddsA, BigDecimal oddsB, BigDecimal totalStake) {
        BigDecimal denom = oddsA.add(oddsB, MC);
        return totalStake.multiply(oddsB, MC).divide(denom, MC);
    }

    public static BigDecimal stakeForBookieB(BigDecimal oddsA, BigDecimal oddsB, BigDecimal totalStake) {
        BigDecimal denom = oddsA.add(oddsB, MC);
        return totalStake.multiply(oddsA, MC).divide(denom, MC); // no final rounding
    }


    /**
     * Round stake amount to avoid detection by bookmakers
     * Applies randomization and rounding to make stakes look more natural
     *
     * @param stake original stake amount
     * @return rounded stake amount
     */
    public static BigDecimal roundStakeForAntiDetection(BigDecimal stake) {
        return roundStakeForAntiDetection(stake, DEFAULT_DECIMAL_PLACES, MAX_RANDOMIZATION_PERCENTAGE);
    }

    /**
     * Round stake amount with custom parameters
     *
     * @param stake original stake amount
     * @param decimalPlaces number of decimal places (0, 1, or 2)
     * @param maxRandomizationPercentage maximum randomization as decimal (e.g., 0.05 = 5%)
     * @return rounded stake amount
     */
    private static BigDecimal roundStakeForAntiDetection(
            BigDecimal stake,
            int decimalPlaces,
            BigDecimal maxRandomizationPercentage
    ) {
        if (stake == null || stake.signum() <= 0) return BigDecimal.ZERO;

        // Clamp and drop decimals (NGN typically whole naira)
        BigDecimal s = clamp(stake.setScale(0, RoundingMode.DOWN), MIN_STAKE, MAX_STAKE);

        // Candidates
        BigDecimal c50  = roundNearestStep(s, STEP_50);   // e.g., 6,433 -> 6,450
        BigDecimal c100 = roundNearestStep(s, STEP_100);  // e.g., 6,433 -> 6,400

        // Choose primary: big tickets prefer 100s; otherwise pick the closer of 50 vs 100 (ties go up)
        BigDecimal primary;
        if (s.compareTo(new BigDecimal("10000")) >= 0) {
            primary = c100; // 15,397 -> 15,400
        } else {
            BigDecimal d50  = s.subtract(c50).abs();
            BigDecimal d100 = s.subtract(c100).abs();
            primary = (d50.compareTo(d100) <= 0) ? c50 : c100; // 6,433 -> 6,450
        }
        BigDecimal secondary = primary.equals(c50) ? c100 : c50; // alternate nice level

        // Small chance to pick the secondary to avoid perfect patterns
        double altProb = toProb(maxRandomizationPercentage, 0.18, 0.30); // default 18%, cap 30%
        if (ThreadLocalRandom.current().nextDouble() < altProb) {
            primary = secondary;
        }

        // Final clamp & scale (if you insist on decimals, though NGN usually 0)
        return clamp(primary, MIN_STAKE, MAX_STAKE)
                .setScale(Math.max(0, decimalPlaces), RoundingMode.DOWN);
    }


    private static BigDecimal roundNearestStep(BigDecimal value, BigDecimal step) {
        BigDecimal[] qr = value.divideAndRemainder(step);
        BigDecimal lower = qr[0].multiply(step);
        BigDecimal upper = lower.add(step);
        // ties go up
        BigDecimal dl = value.subtract(lower).abs();
        BigDecimal du = upper.subtract(value).abs();
        return (dl.compareTo(du) <= 0) ? lower : upper;
    }

    private static BigDecimal clamp(BigDecimal v, BigDecimal min, BigDecimal max) {
        if (v.compareTo(min) < 0) return min;
        if (v.compareTo(max) > 0) return max;
        return v;
    }

    private static double toProb(BigDecimal p, double defaultVal, double cap) {
        if (p == null) return defaultVal;
        double d = p.doubleValue();
        if (d <= 0) return 0.0;
        if (d >= 1) return Math.min(1.0, cap);
        return Math.min(d, cap);
    }

}
