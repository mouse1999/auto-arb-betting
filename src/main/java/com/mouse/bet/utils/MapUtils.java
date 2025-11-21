package com.mouse.bet.utils;

import lombok.Getter;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class to resolve MSport live table tennis market codes
 * e.g. LIVETT_12HNDN20@0.5_2H → "Asian Handicap Match -0.5 (Away)"
 */
@Component
public class MapUtils {

    @Getter
    public static class MarketInfo {
        private final String label;
        private final String description;
        private final String category; // e.g. "Handicap", "Winner", "Total", "Correct Score"

        public MarketInfo(String label, String description, String category) {
            this.label = label;
            this.description = description;
            this.category = category;
        }

        @Override
        public String toString() {
            return label + " | " + description;
        }
    }

    // Master map: prefix → MarketInfo
    private final Map<String, MarketInfo> marketMap = new HashMap<>();

    @PostConstruct
    public void init() {
        // === MATCH WINNER ===
        put("LIVETT_12_1HH", "Match Winner", "Home wins the match", "Winner");
        put("LIVETT_12_2HH", "Match Winner", "Away wins the match", "Winner");

        // === CURRENT SET WINNER ===
        put("LIVETT_SW@3_1", "Current Set Winner", "Home wins 3rd set", "Winner");
        put("LIVETT_SW@3_2", "Current Set Winner", "Away wins 3rd set", "Winner");

        // === ASIAN HANDICAP MATCH ===
        put("LIVETT_12HNDN20", "Asian Handicap Match -0.5", "Home wins match (no push)", "Handicap");
        put("LIVETT_12HNDN20", "Asian Handicap Match +0.5", "Away doesn't lose match", "Handicap");

        // === CORRECT SCORE IN SETS (Best of 5) ===
        put("LIVETT_12B5_3-0", "Correct Score 3-0", "Home wins 3-0", "Correct Score");
        put("LIVETT_12B5_3-1", "Correct Score 3-1", "Home wins 3-1", "Correct Score");
        put("LIVETT_12B5_3-2", "Correct Score 3-2", "Home wins 3-2", "Correct Score");
        put("LIVETT_12B5_0-3", "Correct Score 0-3", "Away wins 3-0", "Correct Score");
        put("LIVETT_12B5_1-3", "Correct Score 1-3", "Away wins 1-3", "Correct Score");
        put("LIVETT_12B5_2-3", "Correct Score 2-3", "Away wins 2-3", "Correct Score");

        // === SETS EXACT (Correct Set Score) ===
        put("LIVETT_SETSEXN_0", "Correct Set Score 3-0", "Match ends 3-0", "Correct Score");
        put("LIVETT_SETSEXN_1", "Correct Set Score 3-1", "Match ends 3-1", "Correct Score");
        put("LIVETT_SETSEXN_2", "Correct Set Score 3-2", "Match ends 3-2", "Correct Score");
        put("LIVETT_SETSEXN_3", "Correct Set Score 2-3", "Match ends 2-3", "Correct Score");
        put("LIVETT_SETSEXN_4", "Correct Set Score 1-3", "Match ends 1-3", "Correct Score");
        put("LIVETT_SETSEXN_5", "Correct Set Score 0-3", "Match ends 0-3", "Correct Score");

        // === ODD/EVEN (Current Set) ===
        put("LIVETT_OE3PN_OD", "Odd/Even 3rd Set", "Total points in 3rd set is odd", "Odd/Even");
        put("LIVETT_OE3PN_EV", "Odd/Even 3rd Set", "Total points in 3rd set is even", "Odd/Even");

        // === NEXT POINT MARKETS (very common in live TT) ===
        put("LIVETT_1210P3", "Next Point", "Who scores 10th point in 3rd set", "Next Point");
        put("LIVETT_1215P3", "Next Point", "Who scores 15th point in 3rd set", "Next Point");
        put("LIVETT_1220P3", "Next Point", "Who scores 20th point in 3rd set", "Next Point");

        // === TOTAL POINTS IN SET (Over/Under) ===
        put("LIVETT_OU3PN", "Total Points 3rd Set", "Over/Under total points in current set", "Total");

        // Add more as needed...
    }

    private void put(String key, String label, String description, String category) {
        marketMap.put(key, new MarketInfo(label, description, category));
    }

    /**
     * Find the MarketInfo for a given full market code using prefix matching.
     * Example: input "LIVETT_12HNDN20@0.5_2H" → returns Asian Handicap +0.5 info
     */
    public MarketInfo resolveBet9jaMarketCode(String marketCode) {
        if (marketCode == null || marketCode.isBlank()) {
            return null;
        }

        // Try exact match first
        MarketInfo exact = marketMap.get(marketCode);
        if (exact != null) {
            return exact;
        }

        // Then longest prefix match
        return marketMap.entrySet().stream()
                .filter(entry -> marketCode.startsWith(entry.getKey()))
                .max(Comparator.comparingInt(e -> e.getKey().length())) // longest match wins
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    /**
     * Get all known market codes that start with given prefix
     */
    public List<MarketInfo> findAllStartingWith(String prefix) {
        return marketMap.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    /**
     * Get all markets in a category (e.g. "Handicap", "Winner")
     */
    public List<MarketInfo> getByCategory(String category) {
        return marketMap.values().stream()
                .filter(m -> category.equalsIgnoreCase(m.getCategory()))
                .collect(Collectors.toList());
    }

    // === Quick test in main (remove in prod) ===
    public static void main(String[] args) {
        MapUtils utils = new MapUtils();
        utils.init();

        System.out.println(utils.resolveBet9jaMarketCode("LIVETT_12HNDN20@0.5_2H")); // → Asian Handicap +0.5
        System.out.println(utils.resolveBet9jaMarketCode("LIVETT_SW@3_1"));          // → Current Set Winner
        System.out.println(utils.resolveBet9jaMarketCode("LIVETT_SETSEXN_1"));       // → Correct Set Score 3-1
    }
}