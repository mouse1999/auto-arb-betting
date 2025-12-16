package com.mouse.bet.utils;

import com.mouse.bet.enums.OutcomePosition;
import com.mouse.bet.interfaces.MarketType;
import com.mouse.bet.enums.MarketCategory;
import com.mouse.bet.utils.ArbitrageType;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility class for detecting arbitrage opportunities across different bookmakers.
 * Handles bookmakers with different marketId/specifier formats by using canonical keys.
 */
@Slf4j
public class ArbitrageUtil {

    private ArbitrageUtil() {
        // Utility class - prevent instantiation
    }

    /**
     * Check if two market types can form an arbitrage opportunity.
     * Uses canonical keys for cross-bookmaker comparison.
     *
     * @param market1 First market (can be from any bookmaker)
     * @param market2 Second market (can be from any bookmaker)
     * @return true if they can form an arbitrage
     */
    public static boolean canFormArbitrage(MarketType market1, MarketType market2) {
        if (market1 == null || market2 == null) {
            return false;
        }
        MarketCategory cm1 = market1.getCategory();
        MarketCategory cm2 = market2.getCategory();



        // Must be same category and line value
        if (!cm1.equals(cm2)) {
            log.trace("Different categories: {} vs {}", cm1, cm2);
            return false;
        }


        // Check if positions are opposite
        boolean isOpposite = areOppositePositions(market1.getPosition(), market2.getPosition());

        if (isOpposite) {
            log.debug("✅ Arbitrage opportunity: {} [{}] vs {} [{}]",
                    market2.getPosition(), market1.getPosition(), cm2, cm2);
        }

        return isOpposite;
    }

    /**
     * Check if two outcomes are on the same market line
     * (same category and specifier, but can be same or different positions)
     *
     * @param market1 First market
     * @param market2 Second market
     * @return true if same market line
     */
    public static boolean isSameMarketLine(MarketType market1, MarketType market2) {
        if (market1 == null || market2 == null) {
            return false;
        }

        String key1 = market1.getCanonicalMarketKey();
        String key2 = market2.getCanonicalMarketKey();

        if (key1 == null || key2 == null) {
            return false;
        }

        CanonicalMarket cm1 = parseCanonicalKey(key1);
        CanonicalMarket cm2 = parseCanonicalKey(key2);

        return cm1.category.equals(cm2.category) &&
                lineValuesMatch(cm1.lineValue, cm2.lineValue);
    }

    /**
     * Get the arbitrage type between two market types.
     *
     * @param market1 First market
     * @param market2 Second market
     * @return ArbitrageType enum value
     */
    public static ArbitrageType getArbitrageType(MarketType market1, MarketType market2) {
        if (!canFormArbitrage(market1, market2)) {
            return ArbitrageType.NONE;
        }

        MarketCategory category = market1.getCategory();

        if (category == MarketCategory.OVER_UNDER_TOTAL ||
                category == MarketCategory.OVER_UNDER_1STHALF ||
                category == MarketCategory.OVER_UNDER_2NDHALF ||
                category == MarketCategory.TABLE_TENNIS_GAME_POINT) {
            return ArbitrageType.OVER_UNDER;
        }

        if (category == MarketCategory.ASIAN_HANDICAP_FULLTIME) {
            return ArbitrageType.ASIAN_HANDICAP;
        }

        if (category == MarketCategory.GAME_POINT_HANDICAP) {
            return ArbitrageType.GAME_POINT_HANDICAP;
        }

        if (category == MarketCategory.MATCH_RESULT) {
            return ArbitrageType.MATCH_RESULT;
        }

        if (category == MarketCategory.DRAW_NO_BET) {
            return ArbitrageType.DRAW_NO_BET;
        }

        if (category == MarketCategory.BTTS) {
            return ArbitrageType.BOTH_TEAMS_TO_SCORE;
        }

        if (category == MarketCategory.DOUBLE_CHANCE) {
            return ArbitrageType.DOUBLE_CHANCE;
        }

        if (category == MarketCategory.ODD_EVEN) {
            return ArbitrageType.ODD_EVEN;
        }

        if (category == MarketCategory.BASKETBALL_MATCH_WINNER) {
            return ArbitrageType.MATCH_WINNER;
        }
        if (category == MarketCategory.POINT_HANDICAP) {
            return ArbitrageType.POINT_HANDICAP;
        }

        return ArbitrageType.OTHER;
    }

    /**
     * Check if two line values match (handles null and floating point comparison)
     */
    private static boolean lineValuesMatch(String line1, String line2) {
        // Both null - match
        if (line1 == null && line2 == null) {
            return true;
        }

        // One null, one not - no match
        if (line1 == null || line2 == null) {
            return false;
        }

        // Try numeric comparison for floating point tolerance
        try {
            double val1 = Double.parseDouble(line1);
            double val2 = Double.parseDouble(line2);
            return Math.abs(val1 - val2) < 0.01; // Tolerance for floating point errors
        } catch (NumberFormatException e) {
            // Fall back to string comparison for non-numeric values
            return line1.equals(line2);
        }
    }

    /**
     * Check if two positions are opposite
     */
    private static boolean areOppositePositions(OutcomePosition pos1, OutcomePosition pos2) {
        if (pos1 == null || pos2 == null) {
            return false;
        }

        return (pos1.equals(OutcomePosition.OVER) && pos2.equals(OutcomePosition.UNDER)) ||
                (pos1.equals(OutcomePosition.UNDER) && pos2.equals(OutcomePosition.OVER)) ||
                (pos1.equals(OutcomePosition.PRIMARY) && pos2.equals(OutcomePosition.OPPOSITE)) ||
                (pos1.equals(OutcomePosition.OPPOSITE) && pos2.equals(OutcomePosition.PRIMARY)) ||
                (pos1.equals(OutcomePosition.OPPOSING_HOME) && pos2.equals(OutcomePosition.PRIMARY)) ||
                (pos1.equals(OutcomePosition.PRIMARY) && pos2.equals(OutcomePosition.OPPOSING_HOME));
    }

    /**
     * Parse a canonical key into its components
     */
    private static CanonicalMarket parseCanonicalKey(String key) {
        String[] parts = key.split(":", -1); // -1 to keep empty strings
        return new CanonicalMarket(
                parts[0],                                              // category
                parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null, // lineValue
                parts.length > 2 && !parts[2].isEmpty() ? parts[2] : null  // position
        );
    }

    /**
     * Internal class to hold parsed canonical market data
     */
    private static class CanonicalMarket {
        final String category;
        final String lineValue;
        final String position;

        CanonicalMarket(String category, String lineValue, String position) {
            this.category = category;
            this.lineValue = lineValue;
            this.position = position;
        }
    }


}