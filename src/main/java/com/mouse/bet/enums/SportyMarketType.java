package com.mouse.bet.enums;

import com.mouse.bet.interfaces.MarketType;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum SportyMarketType implements MarketType {

    // 1X2 (Match Odds) FOOTBALL
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
    ODD_EVEN_EVEN("26", null, "EVEN", MarketCategory.ODD_EVEN, OutcomeType.EVEN),


    //BASKETBALL

    // BASKETBALL

    // 1. Match Winner (ID: 219) - Two-Way Outcome (incl. overtime)
    BASKETBALL_WINNER_HOME("219", null, "HOME_WINNER_OT", MarketCategory.BASKETBALL_MATCH_WINNER, OutcomeType.BASKETBALL_WINNER_HOME),
    BASKETBALL_WINNER_AWAY("219", null, "AWAY_WINNER_OT", MarketCategory.BASKETBALL_MATCH_WINNER, OutcomeType.BASKETBALL_WINNER_AWAY),

    // 2. Full Match Over/Under (ID: 225) - (incl. overtime)
    BASKETBALL_FT_OU_1695_OVER("225", "total=169.5", "OVER_169.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_OVER_169_5),
    BASKETBALL_FT_OU_1695_UNDER("225", "total=169.5", "UNDER_169.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_UNDER_169_5),
    BASKETBALL_FT_OU_1705_OVER("225", "total=170.5", "OVER_170.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_OVER_170_5),
    BASKETBALL_FT_OU_1705_UNDER("225", "total=170.5", "UNDER_170.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_UNDER_170_5),
    BASKETBALL_FT_OU_1715_OVER("225", "total=171.5", "OVER_171.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_OVER_171_5),
    BASKETBALL_FT_OU_1715_UNDER("225", "total=171.5", "UNDER_171.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_UNDER_171_5),
    BASKETBALL_FT_OU_1725_OVER("225", "total=172.5", "OVER_172.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_OVER_172_5),
    BASKETBALL_FT_OU_1725_UNDER("225", "total=172.5", "UNDER_172.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_UNDER_172_5),
    BASKETBALL_FT_OU_1735_OVER("225", "total=173.5", "OVER_173.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_OVER_173_5),
    BASKETBALL_FT_OU_1735_UNDER("225", "total=173.5", "UNDER_173.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_UNDER_173_5),
    BASKETBALL_FT_OU_1745_OVER("225", "total=174.5", "OVER_174.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_OVER_174_5),
    BASKETBALL_FT_OU_1745_UNDER("225", "total=174.5", "UNDER_174.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_UNDER_174_5),
    BASKETBALL_FT_OU_1755_OVER("225", "total=175.5", "OVER_175.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_OVER_175_5),
    BASKETBALL_FT_OU_1755_UNDER("225", "total=175.5", "UNDER_175.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_UNDER_175_5),
    BASKETBALL_FT_OU_1765_OVER("225", "total=176.5", "OVER_176.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_OVER_176_5),
    BASKETBALL_FT_OU_1765_UNDER("225", "total=176.5", "UNDER_176.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_UNDER_176_5),
    BASKETBALL_FT_OU_1775_OVER("225", "total=177.5", "OVER_177.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_OVER_177_5),
    BASKETBALL_FT_OU_1775_UNDER("225", "total=177.5", "UNDER_177.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_UNDER_177_5),
    BASKETBALL_FT_OU_1785_OVER("225", "total=178.5", "OVER_178.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_OVER_178_5),
    BASKETBALL_FT_OU_1785_UNDER("225", "total=178.5", "UNDER_178.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_UNDER_178_5),
    BASKETBALL_FT_OU_1795_OVER("225", "total=179.5", "OVER_179.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_OVER_179_5),
    BASKETBALL_FT_OU_1795_UNDER("225", "total=179.5", "UNDER_179.5", MarketCategory.BASKETBALL_OVER_UNDER_TOTAL, OutcomeType.BASKETBALL_UNDER_179_5),

    // 3. 1st Half Over/Under (ID: 68)
    BASKETBALL_1H_OU_845_OVER("68", "total=84.5", "OVER_84.5", MarketCategory.BASKETBALL_OVER_UNDER_1STHALF, OutcomeType.BASKETBALL_1H_OVER_84_5),
    BASKETBALL_1H_OU_845_UNDER("68", "total=84.5", "UNDER_84.5", MarketCategory.BASKETBALL_OVER_UNDER_1STHALF, OutcomeType.BASKETBALL_1H_UNDER_84_5),
    BASKETBALL_1H_OU_855_OVER("68", "total=85.5", "OVER_85.5", MarketCategory.BASKETBALL_OVER_UNDER_1STHALF, OutcomeType.BASKETBALL_1H_OVER_85_5),
    BASKETBALL_1H_OU_855_UNDER("68", "total=85.5", "UNDER_85.5", MarketCategory.BASKETBALL_OVER_UNDER_1STHALF, OutcomeType.BASKETBALL_1H_UNDER_85_5),
    BASKETBALL_1H_OU_865_OVER("68", "total=86.5", "OVER_86.5", MarketCategory.BASKETBALL_OVER_UNDER_1STHALF, OutcomeType.BASKETBALL_1H_OVER_86_5),
    BASKETBALL_1H_OU_865_UNDER("68", "total=86.5", "UNDER_86.5", MarketCategory.BASKETBALL_OVER_UNDER_1STHALF, OutcomeType.BASKETBALL_1H_UNDER_86_5),
    BASKETBALL_1H_OU_875_OVER("68", "total=87.5", "OVER_87.5", MarketCategory.BASKETBALL_OVER_UNDER_1STHALF, OutcomeType.BASKETBALL_1H_OVER_87_5),
    BASKETBALL_1H_OU_875_UNDER("68", "total=87.5", "UNDER_87.5", MarketCategory.BASKETBALL_OVER_UNDER_1STHALF, OutcomeType.BASKETBALL_1H_UNDER_87_5),
    BASKETBALL_1H_OU_885_OVER("68", "total=88.5", "OVER_88.5", MarketCategory.BASKETBALL_OVER_UNDER_1STHALF, OutcomeType.BASKETBALL_1H_OVER_88_5),
    BASKETBALL_1H_OU_885_UNDER("68", "total=88.5", "UNDER_88.5", MarketCategory.BASKETBALL_OVER_UNDER_1STHALF, OutcomeType.BASKETBALL_1H_UNDER_88_5),
    BASKETBALL_1H_OU_895_OVER("68", "total=89.5", "OVER_89.5", MarketCategory.BASKETBALL_OVER_UNDER_1STHALF, OutcomeType.BASKETBALL_1H_OVER_89_5),
    BASKETBALL_1H_OU_895_UNDER("68", "total=89.5", "UNDER_89.5", MarketCategory.BASKETBALL_OVER_UNDER_1STHALF, OutcomeType.BASKETBALL_1H_UNDER_89_5),
    BASKETBALL_1H_OU_905_OVER("68", "total=90.5", "OVER_90.5", MarketCategory.BASKETBALL_OVER_UNDER_1STHALF, OutcomeType.BASKETBALL_1H_OVER_90_5),
    BASKETBALL_1H_OU_905_UNDER("68", "total=90.5", "UNDER_90.5", MarketCategory.BASKETBALL_OVER_UNDER_1STHALF, OutcomeType.BASKETBALL_1H_UNDER_90_5),

    // 4. Quarter Over/Under (ID: 236)
    BASKETBALL_Q1_OU_415_OVER("236", "total=41.5|quarternr=1", "OVER_41.5", MarketCategory.BASKETBALL_OVER_UNDER_QUARTER, OutcomeType.BASKETBALL_Q1_OVER_41_5),
    BASKETBALL_Q1_OU_415_UNDER("236", "total=41.5|quarternr=1", "UNDER_41.5", MarketCategory.BASKETBALL_OVER_UNDER_QUARTER, OutcomeType.BASKETBALL_Q1_UNDER_41_5),
    BASKETBALL_Q2_OU_415_OVER("236", "total=41.5|quarternr=2", "OVER_41.5", MarketCategory.BASKETBALL_OVER_UNDER_QUARTER, OutcomeType.BASKETBALL_Q2_OVER_41_5),
    BASKETBALL_Q2_OU_415_UNDER("236", "total=41.5|quarternr=2", "UNDER_41.5", MarketCategory.BASKETBALL_OVER_UNDER_QUARTER, OutcomeType.BASKETBALL_Q2_UNDER_41_5),
    BASKETBALL_Q3_OU_405_OVER("236", "total=40.5|quarternr=3", "OVER_40.5", MarketCategory.BASKETBALL_OVER_UNDER_QUARTER, OutcomeType.BASKETBALL_Q3_OVER_40_5),
    BASKETBALL_Q3_OU_405_UNDER("236", "total=40.5|quarternr=3", "UNDER_40.5", MarketCategory.BASKETBALL_OVER_UNDER_QUARTER, OutcomeType.BASKETBALL_Q3_UNDER_40_5),
    BASKETBALL_Q3_OU_415_OVER("236", "total=41.5|quarternr=3", "OVER_41.5", MarketCategory.BASKETBALL_OVER_UNDER_QUARTER, OutcomeType.BASKETBALL_Q3_OVER_41_5),
    BASKETBALL_Q3_OU_415_UNDER("236", "total=41.5|quarternr=3", "UNDER_41.5", MarketCategory.BASKETBALL_OVER_UNDER_QUARTER, OutcomeType.BASKETBALL_Q3_UNDER_41_5),

    BASKETBALL_HCP_NEG_11_5_HOME("223", "hcp=-11.5", "Home (-11.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_HOME_NEG_11_5),
    BASKETBALL_HCP_NEG_11_5_AWAY("223", "hcp=-11.5", "Away (+11.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_AWAY_POS_11_5),

    // Handicap -10.5
    BASKETBALL_HCP_NEG_10_5_HOME("223", "hcp=-10.5", "Home (-10.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_HOME_NEG_10_5),
    BASKETBALL_HCP_NEG_10_5_AWAY("223", "hcp=-10.5", "Away (+10.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_AWAY_POS_10_5),

    // Handicap -9.5
    BASKETBALL_HCP_NEG_9_5_HOME("223", "hcp=-9.5", "Home (-9.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_HOME_NEG_9_5),
    BASKETBALL_HCP_NEG_9_5_AWAY("223", "hcp=-9.5", "Away (+9.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_AWAY_POS_9_5),

    // Handicap -8.5
    BASKETBALL_HCP_NEG_8_5_HOME("223", "hcp=-8.5", "Home (-8.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_HOME_NEG_8_5),
    BASKETBALL_HCP_NEG_8_5_AWAY("223", "hcp=-8.5", "Away (+8.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_AWAY_POS_8_5),

    // Handicap -7.5
    BASKETBALL_HCP_NEG_7_5_HOME("223", "hcp=-7.5", "Home (-7.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_HOME_NEG_7_5),
    BASKETBALL_HCP_NEG_7_5_AWAY("223", "hcp=-7.5", "Away (+7.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_AWAY_POS_7_5),

    // Handicap -6.5
    BASKETBALL_HCP_NEG_6_5_HOME("223", "hcp=-6.5", "Home (-6.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_HOME_NEG_6_5),
    BASKETBALL_HCP_NEG_6_5_AWAY("223", "hcp=-6.5", "Away (+6.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_AWAY_POS_6_5),

    // Handicap -5.5
    BASKETBALL_HCP_NEG_5_5_HOME("223", "hcp=-5.5", "Home (-5.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_HOME_NEG_5_5),
    BASKETBALL_HCP_NEG_5_5_AWAY("223", "hcp=-5.5", "Away (+5.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_AWAY_POS_5_5),

    // Handicap -4.5
    BASKETBALL_HCP_NEG_4_5_HOME("223", "hcp=-4.5", "Home (-4.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_HOME_NEG_4_5),
    BASKETBALL_HCP_NEG_4_5_AWAY("223", "hcp=-4.5", "Away (+4.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_AWAY_POS_4_5),

    // Handicap -3.5
    BASKETBALL_HCP_NEG_3_5_HOME("223", "hcp=-3.5", "Home (-3.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_HOME_NEG_3_5),
    BASKETBALL_HCP_NEG_3_5_AWAY("223", "hcp=-3.5", "Away (+3.5)", MarketCategory.BASKETBALL_HANDICAP, OutcomeType.HANDICAP_AWAY_POS_3_5);

    //TABLE TENNIS

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
