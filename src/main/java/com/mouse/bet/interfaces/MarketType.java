package com.mouse.bet.interfaces;

import com.mouse.bet.enums.MarketCategory;
import com.mouse.bet.enums.OutcomePosition;
import com.mouse.bet.enums.OutcomeType;
import com.mouse.bet.utils.ArbitrageType;
import com.mouse.bet.utils.ArbitrageUtil;

/**
 * Common interface for all bookmaker market types.
 * Enables cross-bookmaker arbitrage detection despite different marketId/specifier formats.
 */
public interface MarketType {

    String getMarketId();
    String getSpecifier();
    String getNormalizedName();
    MarketCategory getCategory();
    OutcomeType getOutcomeType();
    OutcomePosition getPosition();
    String getProviderKey();

    /**
     * Get the canonical market identifier for cross-bookmaker comparison.
     * This should be the SAME for equivalent markets across different bookies.
     * Format: "CATEGORY:LINE_VALUE:OUTCOME_POSITION"
     *
     * Examples:
     * - "OVER_UNDER_TOTAL:2.5:OVER"
     * - "ASIAN_HANDICAP_FULLTIME:-1.5:PRIMARY"
     * - "MATCH_RESULT::PRIMARY"
     */
    String getCanonicalMarketKey();

    /**
     * Extract the line value from specifier (for O/U, handicap, etc.)
     * Returns null if no line value exists.
     * Can be overridden for bookmaker-specific parsing.
     */
    default String extractLineValue() {
        String spec = getSpecifier();
        if (spec == null) {
            return null;
        }

        // Handle different formats: "total=2.5", "hcp=-1.5", "gamenr=1|total=15.5"
        String[] parts = spec.split("\\|");
        for (String part : parts) {
            if (part.startsWith("total=")) {
                return part.substring(6); // "2.5"
            }
            if (part.startsWith("hcp=")) {
                return part.substring(4); // "-1.5"
            }
        }
        return null;
    }

    /**
     * Check if this outcome can form an arbitrage with another outcome
     * (can be from a different bookmaker)
     */
    default boolean canFormArbitrageWith(MarketType other) {
        return ArbitrageUtil.canFormArbitrage(this, other);
    }

    /**
     * Check if two markets are on the same betting line
     * (same category and line value)
     */
    default boolean isSameMarketLineAs(MarketType other) {
        return ArbitrageUtil.isSameMarketLine(this, other);
    }

    /**
     * Get the arbitrage type with another market
     */
    default ArbitrageType getArbitrageTypeWith(MarketType other) {
        return ArbitrageUtil.getArbitrageType(this, other);
    }


}