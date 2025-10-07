package com.mouse.bet.enums;

import com.mouse.bet.interfaces.MarketType;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum Bet9jaMarketType implements MarketType {
    MATCH_ODDS_HOME("S_1X2_1", "HOME", MarketCategory.MATCH_RESULT, OutcomeType.HOME),
    MATCH_ODDS_DRAW("S_1X2_X", "DRAW", MarketCategory.MATCH_RESULT, OutcomeType.DRAW),
    MATCH_ODDS_AWAY("S_1X2_2", "AWAY", MarketCategory.MATCH_RESULT, OutcomeType.AWAY),

    // Double Chance
    DOUBLE_CHANCE_HOME_DRAW("S_DC_1X", "HOME_OR_DRAW", MarketCategory.DOUBLE_CHANCE, OutcomeType.HOME_OR_DRAW),
    DOUBLE_CHANCE_HOME_AWAY("S_DC_12", "HOME_OR_AWAY", MarketCategory.DOUBLE_CHANCE, OutcomeType.HOME_OR_AWAY),
    DOUBLE_CHANCE_DRAW_AWAY("S_DC_X2", "DRAW_OR_AWAY", MarketCategory.DOUBLE_CHANCE, OutcomeType.DRAW_OR_AWAY),

    // Over/Under
    OVER_UNDER_05_OVER("S_OU@0.5_O", "OVER_0.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_0_5),
    OVER_UNDER_05_UNDER("S_OU@0.5_U", "UNDER_0.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_0_5),
    OVER_UNDER_15_OVER("S_OU@1.5_O", "OVER_1.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_1_5),
    OVER_UNDER_15_UNDER("S_OU@1.5_U", "UNDER_1.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_1_5),
    OVER_UNDER_25_OVER("S_OU@2.5_O", "OVER_2.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_2_5),
    OVER_UNDER_25_UNDER("S_OU@2.5_U", "UNDER_2.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_2_5),
    OVER_UNDER_35_OVER("S_OU@3.5_O", "OVER_3.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_3_5),
    OVER_UNDER_35_UNDER("S_OU@3.5_U", "UNDER_3.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_3_5),
    OVER_UNDER_45_OVER("S_OU@4.5_O", "OVER_4.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_4_5),
    OVER_UNDER_45_UNDER("S_OU@4.5_U", "UNDER_4.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_4_5),

    // Both Teams to Score
    BTTS_YES("S_BTTS_Y", "BOTH_TEAMS_TO_SCORE_YES", MarketCategory.BTTS, OutcomeType.BOTH_TEAMS_TO_SCORE_YES),
    BTTS_NO("S_BTTS_N", "BOTH_TEAMS_TO_SCORE_NO", MarketCategory.BTTS, OutcomeType.BOTH_TEAMS_TO_SCORE_NO),
    // Over/Under 0.5
    IST_HALF_OVER_UNDER_05_OVER("S_OU1T@0.5_O", "IST_HALF_OVER_0.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_OVER_05),
    IST_HALF_OVER_UNDER_05_UNDER("S_OU1T@0.5_U", "IST_HALF_UNDER_0.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_UNDER_05),

    // Over/Under 1.5
    IST_HALF_OVER_UNDER_15_OVER("S_OU1T@1.5_O", "IST_HALF_OVER_1.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_OVER_15),
    IST_HALF_OVER_UNDER_15_UNDER("S_OU1T@1.5_U", "IST_HALF_UNDER_1.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_UNDER_15),

    // Over/Under 2.5
    IST_HALF_OVER_UNDER_25_OVER("S_OU1T@2.5_O", "IST_HALF_OVER_2.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_OVER_25),
    IST_HALF_OVER_UNDER_25_UNDER("S_OU1T@2.5_U", "IST_HALF_UNDER_2.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_UNDER_25),

    // Over/Under 3.5
    IST_HALF_OVER_UNDER_35_OVER("S_OU1T@3.5_O", "IST_HALF_OVER_3.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_OVER_35),
    IST_HALF_OVER_UNDER_35_UNDER("S_OU1T@3.5_U", "IST_HALF_UNDER_3.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_UNDER_35),

    // Over/Under 4.5
    IST_HALF_OVER_UNDER_45_OVER("S_OU1T@4.5_O", "IST_HALF_OVER_4.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_OVER_45),
    IST_HALF_OVER_UNDER_45_UNDER("S_OU1T@4.5_U", "IST_HALF_UNDER_4.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_UNDER_45),

    // Over/Under 5.5
    IST_HALF_OVER_UNDER_55_OVER("S_OU1T@5.5_O", "IST_HALF_OVER_5.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_OVER_55),
    IST_HALF_OVER_UNDER_55_UNDER("S_OU1T@5.5_U", "IST_HALF_UNDER_5.5", MarketCategory.OVER_UNDER_1STHALF, OutcomeType.IST_HALF_UNDER_55),
    // 2nd Half Over/Under 0.5
    SECOND_HALF_OVER_UNDER_05_OVER("S_OU2T@0.5_O", "2ND_HALF_OVER_0.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_OVER_05),
    SECOND_HALF_OVER_UNDER_05_UNDER("S_OU2T@0.5_U", "2ND_HALF_UNDER_0.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_UNDER_05),

    // 2nd Half Over/Under 1.5
    SECOND_HALF_OVER_UNDER_15_OVER("S_OU2T@1.5_O", "2ND_HALF_OVER_1.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_OVER_15),
    SECOND_HALF_OVER_UNDER_15_UNDER("S_OU2T@1.5_U", "2ND_HALF_UNDER_1.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_UNDER_15),

    // 2nd Half Over/Under 2.5
    SECOND_HALF_OVER_UNDER_25_OVER("S_OU2T@2.5_O", "2ND_HALF_OVER_2.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_OVER_25),
    SECOND_HALF_OVER_UNDER_25_UNDER("S_OU2T@2.5_U", "2ND_HALF_UNDER_2.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_UNDER_25),

    // 2nd Half Over/Under 3.5
    SECOND_HALF_OVER_UNDER_35_OVER("S_OU2T@3.5_O", "2ND_HALF_OVER_3.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_OVER_35),
    SECOND_HALF_OVER_UNDER_35_UNDER("S_OU2T@3.5_U", "2ND_HALF_UNDER_3.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_UNDER_35),

    // 2nd Half Over/Under 4.5
    SECOND_HALF_OVER_UNDER_45_OVER("S_OU2T@4.5_O", "2ND_HALF_OVER_4.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_OVER_45),
    SECOND_HALF_OVER_UNDER_45_UNDER("S_OU2T@4.5_U", "2ND_HALF_UNDER_4.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_UNDER_45),

    // 2nd Half Over/Under 5.5
    SECOND_HALF_OVER_UNDER_55_OVER("S_OU2T@5.5_O", "2ND_HALF_OVER_5.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_OVER_55),
    SECOND_HALF_OVER_UNDER_55_UNDER("S_OU2T@5.5_U", "2ND_HALF_UNDER_5.5", MarketCategory.OVER_UNDER_2NDHALF, OutcomeType.SECOND_HALF_UNDER_55),

    //ASIAN HANDICAPS

    // --- Asian Handicap Fulltime ---
    ASIAN_HANDICAP_HOME_MINUS_025("S_AH@-0.25_1", "ASIAN_HANDICAP_HOME_-0.25", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_025),
    ASIAN_HANDICAP_AWAY_PLUS_025("S_AH@+0.25_2", "ASIAN_HANDICAP_AWAY_+0.25", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_025),

    ASIAN_HANDICAP_HOME_MINUS_05("S_AH@-0.5_1", "ASIAN_HANDICAP_HOME_-0.5", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_05),
    ASIAN_HANDICAP_AWAY_PLUS_05("S_AH@+0.5_2", "ASIAN_HANDICAP_AWAY_+0.5", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_05),

    ASIAN_HANDICAP_HOME_MINUS_075("S_AH@-0.75_1", "ASIAN_HANDICAP_HOME_-0.75", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_075),
    ASIAN_HANDICAP_AWAY_PLUS_075("S_AH@+0.75_2", "ASIAN_HANDICAP_AWAY_+0.75", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_075),

    ASIAN_HANDICAP_HOME_MINUS_10("S_AH@-1.0_1", "ASIAN_HANDICAP_HOME_-1.0", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_10),
    ASIAN_HANDICAP_AWAY_PLUS_10("S_AH@+1.0_2", "ASIAN_HANDICAP_AWAY_+1.0", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_10),

    ASIAN_HANDICAP_HOME_MINUS_125("S_AH@-1.25_1", "ASIAN_HANDICAP_HOME_-1.25", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_125),
    ASIAN_HANDICAP_AWAY_PLUS_125("S_AH@+1.25_2", "ASIAN_HANDICAP_AWAY_+1.25", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_125),

    ASIAN_HANDICAP_HOME_MINUS_15("S_AH@-1.5_1", "ASIAN_HANDICAP_HOME_-1.5", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_15),
    ASIAN_HANDICAP_AWAY_PLUS_15("S_AH@+1.5_2", "ASIAN_HANDICAP_AWAY_+1.5", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_15),

    ASIAN_HANDICAP_HOME_MINUS_175("S_AH@-1.75_1", "ASIAN_HANDICAP_HOME_-1.75", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_175),
    ASIAN_HANDICAP_AWAY_PLUS_175("S_AH@+1.75_2", "ASIAN_HANDICAP_AWAY_+1.75", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_175),

    ASIAN_HANDICAP_HOME_MINUS_20("S_AH@-2.0_1", "ASIAN_HANDICAP_HOME_-2.0", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_20),
    ASIAN_HANDICAP_AWAY_PLUS_20("S_AH@+2.0_2", "ASIAN_HANDICAP_AWAY_+2.0", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_20),

    ASIAN_HANDICAP_HOME_MINUS_225("S_AH@-2.25_1", "ASIAN_HANDICAP_HOME_-2.25", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_225),
    ASIAN_HANDICAP_AWAY_PLUS_225("S_AH@+2.25_2", "ASIAN_HANDICAP_AWAY_+2.25", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_225),

    ASIAN_HANDICAP_HOME_MINUS_25("S_AH@-2.5_1", "ASIAN_HANDICAP_HOME_-2.5", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_25),
    ASIAN_HANDICAP_AWAY_PLUS_25("S_AH@+2.5_2", "ASIAN_HANDICAP_AWAY_+2.5", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_25),

    ASIAN_HANDICAP_HOME_MINUS_275("S_AH@-2.75_1", "ASIAN_HANDICAP_HOME_-2.75", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_275),
    ASIAN_HANDICAP_AWAY_PLUS_275("S_AH@+2.75_2", "ASIAN_HANDICAP_AWAY_+2.75", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_275),

    ASIAN_HANDICAP_HOME_MINUS_30("S_AH@-3.0_1", "ASIAN_HANDICAP_HOME_-3.0", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_30),
    ASIAN_HANDICAP_AWAY_PLUS_30("S_AH@+3.0_2", "ASIAN_HANDICAP_AWAY_+3.0", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_30),

    ASIAN_HANDICAP_HOME_MINUS_325("S_AH@-3.25_1", "ASIAN_HANDICAP_HOME_-3.25", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_325),
    ASIAN_HANDICAP_AWAY_PLUS_325("S_AH@+3.25_2", "ASIAN_HANDICAP_AWAY_+3.25", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_325),

    CORNERS_OVER_UNDER_65_OVER("S_OUC@6.5_O", "CORNERS_OVER_6.5", MarketCategory.CORNERS_OVER_UNDER_FULLTIME, OutcomeType.CORNERS_OVER_65),
    CORNERS_OVER_UNDER_65_UNDER("S_OUC@6.5_U", "CORNERS_UNDER_6.5", MarketCategory.CORNERS_OVER_UNDER_FULLTIME, OutcomeType.CORNERS_UNDER_65),

    CORNERS_OVER_UNDER_75_OVER("S_OUC@7.5_O", "CORNERS_OVER_7.5", MarketCategory.CORNERS_OVER_UNDER_FULLTIME, OutcomeType.CORNERS_OVER_75),
    CORNERS_OVER_UNDER_75_UNDER("S_OUC@7.5_U", "CORNERS_UNDER_7.5", MarketCategory.CORNERS_OVER_UNDER_FULLTIME, OutcomeType.CORNERS_UNDER_75),

    CORNERS_OVER_UNDER_85_OVER("S_OUC@8.5_O", "CORNERS_OVER_8.5", MarketCategory.CORNERS_OVER_UNDER_FULLTIME, OutcomeType.CORNERS_OVER_85),
    CORNERS_OVER_UNDER_85_UNDER("S_OUC@8.5_U", "CORNERS_UNDER_8.5", MarketCategory.CORNERS_OVER_UNDER_FULLTIME, OutcomeType.CORNERS_UNDER_85),

    CORNERS_OVER_UNDER_95_OVER("S_OUC@9.5_O", "CORNERS_OVER_9.5", MarketCategory.CORNERS_OVER_UNDER_FULLTIME, OutcomeType.CORNERS_OVER_95),
    CORNERS_OVER_UNDER_95_UNDER("S_OUC@9.5_U", "CORNERS_UNDER_9.5", MarketCategory.CORNERS_OVER_UNDER_FULLTIME, OutcomeType.CORNERS_UNDER_95),

    CORNERS_OVER_UNDER_105_OVER("S_OUC@10.5_O", "CORNERS_OVER_10.5", MarketCategory.CORNERS_OVER_UNDER_FULLTIME, OutcomeType.CORNERS_OVER_105),
    CORNERS_OVER_UNDER_105_UNDER("S_OUC@10.5_U", "CORNERS_UNDER_10.5", MarketCategory.CORNERS_OVER_UNDER_FULLTIME, OutcomeType.CORNERS_UNDER_105),

    CORNERS_OVER_UNDER_115_OVER("S_OUC@11.5_O", "CORNERS_OVER_11.5", MarketCategory.CORNERS_OVER_UNDER_FULLTIME, OutcomeType.CORNERS_OVER_115),
    CORNERS_OVER_UNDER_115_UNDER("S_OUC@11.5_U", "CORNERS_UNDER_11.5", MarketCategory.CORNERS_OVER_UNDER_FULLTIME, OutcomeType.CORNERS_UNDER_115),

    CORNERS_OVER_UNDER_125_OVER("S_OUC@12.5_O", "CORNERS_OVER_12.5", MarketCategory.CORNERS_OVER_UNDER_FULLTIME, OutcomeType.CORNERS_OVER_125),
    CORNERS_OVER_UNDER_125_UNDER("S_OUC@12.5_U", "CORNERS_UNDER_12.5", MarketCategory.CORNERS_OVER_UNDER_FULLTIME, OutcomeType.CORNERS_UNDER_125);




    private final String providerKey;
    private final String normalizedName;
    private final MarketCategory category;
    private final OutcomeType outcomeType;

    private static final Map<String, Bet9jaMarketType> BY_PROVIDER_KEY =
            Arrays.stream(values())
                    .collect(Collectors.toMap(
                            Bet9jaMarketType::getProviderKey,
                            Function.identity(),
                            (existing, replacement) -> existing // Keep first if duplicate keys
                    ));

    Bet9jaMarketType(String providerKey, String normalizedName, MarketCategory category, OutcomeType outcomeType) {
        this.providerKey = providerKey;
        this.normalizedName = normalizedName;
        this.category = category;
        this.outcomeType = outcomeType;
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

    public static Bet9jaMarketType fromProviderKey(String providerKey) {
        Bet9jaMarketType market = BY_PROVIDER_KEY.get(providerKey);
        if (market == null) {
            throw new IllegalArgumentException("Unknown Bet9ja market key: " + providerKey);
        }
        return market;
    }

    public static Optional<Bet9jaMarketType> safeFromProviderKey(String providerKey) {
        return Optional.ofNullable(BY_PROVIDER_KEY.get(providerKey));
    }

    public static boolean isKnownMarket(String providerKey) {
        return BY_PROVIDER_KEY.containsKey(providerKey);
    }

    public static Optional<MarketCategory> getCategoryForProviderKey(String providerKey) {
        return safeFromProviderKey(providerKey).map(Bet9jaMarketType::getCategory);
    }

    public static String getNormalizedNameSafe(String providerKey) {
        return safeFromProviderKey(providerKey)

                .map(Bet9jaMarketType::getNormalizedName)
                .orElse(providerKey); // Return original key if unknown
    }

    public static boolean isOverUnderMarket(String providerKey) {
        return safeFromProviderKey(providerKey)
                .map(m -> m.getCategory() == MarketCategory.OVER_UNDER_TOTAL)
                .orElse(false);
    }

    public OutcomeType getOutcomeType() {
        return outcomeType;
    }

    // Add this method to get opposite market type if exists
    public Optional<Bet9jaMarketType> getOppositeMarket() {
        if (outcomeType.getOpposite() == null) {
            return Optional.empty();
        }

        for (Bet9jaMarketType market : values()) {
            if (market.outcomeType == outcomeType.getOpposite()) {
                return Optional.of(market);
            }
        }
        return Optional.empty();
    }


}

