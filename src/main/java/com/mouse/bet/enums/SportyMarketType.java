package com.mouse.bet.enums;

import com.mouse.bet.interfaces.MarketType;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum SportyMarketType implements MarketType {

    // 1X2 (Match Odds)
    MATCH_ODDS_HOME("1", null, "HOME", MarketCategory.MATCH_RESULT, OutcomeType.HOME),
    MATCH_ODDS_DRAW("1", null, "DRAW", MarketCategory.MATCH_RESULT, OutcomeType.DRAW),
    MATCH_ODDS_AWAY("1", null, "AWAY", MarketCategory.MATCH_RESULT, OutcomeType.AWAY),

    // Double Chance
    DOUBLE_CHANCE_HOME_DRAW("10", null, "HOME_OR_DRAW", MarketCategory.DOUBLE_CHANCE, OutcomeType.HOME_OR_DRAW),
    DOUBLE_CHANCE_HOME_AWAY("10", null, "HOME_OR_AWAY", MarketCategory.DOUBLE_CHANCE, OutcomeType.HOME_OR_AWAY),
    DOUBLE_CHANCE_DRAW_AWAY("10", null, "DRAW_OR_AWAY", MarketCategory.DOUBLE_CHANCE, OutcomeType.DRAW_OR_AWAY),

    // Draw No Bet
    DRAW_NO_BET_HOME("11", null, "HOME", MarketCategory.DRAW_NO_BET, OutcomeType.HOME_DNB),
    DRAW_NO_BET_AWAY("11", null, "AWAY", MarketCategory.DRAW_NO_BET, OutcomeType.AWAY_DNB),

    ASIAN_HANDICAP_HOME_MINUS_15("16", "hcp=-1.5","HOME_(-1.5)", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_15),
    ASIAN_HANDICAP_AWAY_PLUS_15("16", "hcp=-1.5", "AWAY_(+1.5)", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_15),
    ASIAN_HANDICAP_HOME_MINUS_25("16", "hcp=-2.5","HOME_(-2.5)", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_25),
    ASIAN_HANDICAP_AWAY_PLUS_25("16", "hcp=-2.5", "AWAY_(+2.5)", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_25),
    ASIAN_HANDICAP_HOME_MINUS_35("16", "hcp=-3.5","HOME_(-3.5)", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_35),
    ASIAN_HANDICAP_AWAY_PLUS_35("16", "hcp=-3.5", "AWAY_(+3.5)", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_35),
    ASIAN_HANDICAP_HOME_MINUS_45("16", "hcp=-4.5","HOME_(-4.5)", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_45),
    ASIAN_HANDICAP_AWAY_PLUS_45("16", "hcp=-4.5", "AWAY_(+4.5)", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_45),

    IST_HALF_OVER_UNDER_15_OVER("68", "total=1.5", "OVER_1.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_OVER_15),
    IST_HALF_OVER_UNDER_15_UNDER("68", "total=1.5", "UNDER_1.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_UNDER_15),
    IST_HALF_OVER_UNDER_25_OVER("68", "total=2.5", "OVER_2.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_OVER_25),
    IST_HALF_OVER_UNDER_25_UNDER("68", "total=2.5", "UNDER_2.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_UNDER_25),
    IST_HALF_OVER_UNDER_35_OVER("68", "total=3.5", "OVER_3.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_OVER_35),
    IST_HALF_OVER_UNDER_35_UNDER("68", "total=3.5", "UNDER_3.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_UNDER_35),

    SECOND_HALF_OVER_UNDER_05_OVER("90", "total=0.5", "OVER_0.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_OVER_05),
    SECOND_HALF_OVER_UNDER_05_UNDER("90", "total=0.5", "UNDER_0.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_UNDER_05),
    SECOND_HALF_OVER_UNDER_15_OVER("90", "total=1.5", "OVER_1.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_OVER_15),
    SECOND_HALF_OVER_UNDER_15_UNDER("90", "total=1.5", "UNDER_1.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_UNDER_15),
    SECOND_HALF_OVER_UNDER_25_OVER("90", "total=2.5", "OVER_2.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_OVER_25),
    SECOND_HALF_OVER_UNDER_25_UNDER("90", "total=2.5", "UNDER_2.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_UNDER_25),
    SECOND_HALF_OVER_UNDER_35_OVER("90", "total=3.5", "OVER_3.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_OVER_35),
    SECOND_HALF_OVER_UNDER_35_UNDER("90", "total=3.5", "UNDER_3.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_UNDER_35),


    // Over/Under - The specifier (e.g., "total=2.5") is the key differentiator
    OVER_UNDER_05_OVER("18", "total=0.5", "OVER_0.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_0_5),
    OVER_UNDER_05_UNDER("18", "total=0.5", "UNDER_0.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_0_5),

    OVER_UNDER_15_OVER("18", "total=1.5", "OVER_1.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_1_5),
    OVER_UNDER_15_UNDER("18", "total=1.5", "UNDER_1.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_1_5),

    OVER_UNDER_25_OVER("18", "total=2.5", "OVER_2.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_2_5),
    OVER_UNDER_25_UNDER("18", "total=2.5", "UNDER_2.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_2_5),

    OVER_UNDER_35_OVER("18", "total=3.5", "OVER_3.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_3_5),
    OVER_UNDER_35_UNDER("18", "total=3.5", "UNDER_3.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_3_5),

    OVER_UNDER_45_OVER("18", "total=4.5", "OVER_4.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_4_5),
    OVER_UNDER_45_UNDER("18", "total=4.5", "UNDER_4.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_4_5),

    // Both Teams to Score (GG/NG)
    BTTS_YES("29", null, "BOTH_TEAMS_TO_SCORE_YES", MarketCategory.BTTS, OutcomeType.BOTH_TEAMS_TO_SCORE_YES),
    BTTS_NO("29", null, "BOTH_TEAMS_TO_SCORE_NO", MarketCategory.BTTS, OutcomeType.BOTH_TEAMS_TO_SCORE_NO),

    // Odd/Even
    ODD_EVEN_ODD("26", null, "ODD", MarketCategory.ODD_EVEN, OutcomeType.ODD),
    ODD_EVEN_EVEN("26", null, "EVEN", MarketCategory.ODD_EVEN, OutcomeType.EVEN);

    private final String marketId;
    private final String specifier;
    private final String normalizedName;
    private final MarketCategory category;
    private final OutcomeType outcomeType;

    // The provider key is a combination of marketId and specifier to uniquely identify a market line.
    private final String providerKey;

    private static final Map<String, SportyMarketType> BY_PROVIDER_KEY;

    static {
        BY_PROVIDER_KEY = Arrays.stream(values())
                .collect(Collectors.toMap(
                        SportyMarketType::getProviderKey,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));
    }

    SportyMarketType(String marketId, String specifier, String normalizedName, MarketCategory category, OutcomeType outcomeType) {
        this.marketId = marketId;
        this.specifier = specifier;
        this.normalizedName = normalizedName;
        this.category = category;
        this.outcomeType = outcomeType;
        // Create a unique key. Use marketId alone if specifier is null.
        this.providerKey = specifier == null ? marketId + ":" + normalizedName : marketId + ":" + specifier + ":" + normalizedName;
    }

    public String getMarketId() {
        return marketId;
    }

    public String getSpecifier() {
        return specifier;
    }

    public String getProviderKey() {
        return providerKey;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public MarketCategory getCategory() {
        return category;
    }

    public OutcomeType getOutcomeType() {
        return outcomeType;
    }

    // Static lookup methods
    public static SportyMarketType fromProviderKey(String providerKey) {
        SportyMarketType market = BY_PROVIDER_KEY.get(providerKey);
        if (market == null) {
            throw new IllegalArgumentException("Unknown Sporty market key: " + providerKey);
        }
        return market;
    }

    public static Optional<SportyMarketType> safeFromProviderKey(String providerKey) {
        return Optional.ofNullable(BY_PROVIDER_KEY.get(providerKey));
    }

    public static boolean isKnownMarket(String providerKey) {
        return BY_PROVIDER_KEY.containsKey(providerKey);
    }

    public static Optional<MarketCategory> getCategoryForProviderKey(String providerKey) {
        return safeFromProviderKey(providerKey).map(SportyMarketType::getCategory);
    }

    public static String getNormalizedNameSafe(String providerKey) {
        return safeFromProviderKey(providerKey)
                .map(SportyMarketType::getNormalizedName)
                .orElse(providerKey);
    }

    public static boolean isOverUnderMarket(String providerKey) {
        return safeFromProviderKey(providerKey)
                .map(m -> m.getCategory() == MarketCategory.OVER_UNDER_TOTAL)
                .orElse(false);
    }

    // Helper method to generate the provider key from raw JSON data
    public static String generateProviderKey(String marketId, String specifier, String outcomeDesc) {
//        return specifier == null || specifier.isEmpty() ? marketId : marketId + ":" + specifier;
        if (specifier != null && !specifier.isEmpty()) {
            return marketId + ":" + specifier + ":" + outcomeDesc;
        }
        return marketId + ":" + outcomeDesc;
    }
}
