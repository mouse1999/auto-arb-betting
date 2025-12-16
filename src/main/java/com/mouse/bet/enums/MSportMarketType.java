package com.mouse.bet.enums;

import com.mouse.bet.interfaces.MarketType;
import lombok.Getter;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MSport-specific market type enumeration.
 * Uses MSport's native marketId and specifier formats.
 */
@Getter
public enum MSportMarketType implements MarketType {

    // ========================================================================
    // FOOTBALL MARKETS
    // ========================================================================

    // --- 1X2 (Match Odds) ---
    MATCH_ODDS_HOME("1", null, "HOME", MarketCategory.MATCH_RESULT, OutcomeType.HOME, OutcomePosition.PRIMARY),
    MATCH_ODDS_DRAW("1", null, "DRAW", MarketCategory.MATCH_RESULT, OutcomeType.DRAW, OutcomePosition.SINGLE),
    MATCH_ODDS_AWAY("1", null, "AWAY", MarketCategory.MATCH_RESULT, OutcomeType.AWAY, OutcomePosition.OPPOSING_HOME),

    // --- Double Chance ---
    DOUBLE_CHANCE_HOME_DRAW("10", null, "HOME_OR_DRAW", MarketCategory.DOUBLE_CHANCE, OutcomeType.HOME_OR_DRAW, OutcomePosition.SINGLE),
    DOUBLE_CHANCE_HOME_AWAY("10", null, "HOME_OR_AWAY", MarketCategory.DOUBLE_CHANCE, OutcomeType.HOME_OR_AWAY, OutcomePosition.SINGLE),
    DOUBLE_CHANCE_DRAW_AWAY("10", null, "DRAW_OR_AWAY", MarketCategory.DOUBLE_CHANCE, OutcomeType.DRAW_OR_AWAY, OutcomePosition.SINGLE),

    // --- Draw No Bet ---
    DRAW_NO_BET_HOME("11", null, "HOME", MarketCategory.DRAW_NO_BET, OutcomeType.HOME_DNB, OutcomePosition.PRIMARY),
    DRAW_NO_BET_AWAY("11", null, "AWAY", MarketCategory.DRAW_NO_BET, OutcomeType.AWAY_DNB, OutcomePosition.OPPOSITE),

    // --- Over/Under Full Time ---
    OVER_UNDER_0_5_OVER("18", "total=0.5", "OVER_0.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_0_5, OutcomePosition.OVER),
    OVER_UNDER_0_5_UNDER("18", "total=0.5", "UNDER_0.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_0_5, OutcomePosition.UNDER),
    OVER_UNDER_1_5_OVER("18", "total=1.5", "OVER_1.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_1_5, OutcomePosition.OVER),
    OVER_UNDER_1_5_UNDER("18", "total=1.5", "UNDER_1.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_1_5, OutcomePosition.UNDER),
    OVER_UNDER_2_5_OVER("18", "total=2.5", "OVER_2.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_2_5, OutcomePosition.OVER),
    OVER_UNDER_2_5_UNDER("18", "total=2.5", "UNDER_2.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_2_5, OutcomePosition.UNDER),
    OVER_UNDER_3_5_OVER("18", "total=3.5", "OVER_3.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_3_5, OutcomePosition.OVER),
    OVER_UNDER_3_5_UNDER("18", "total=3.5", "UNDER_3.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_3_5, OutcomePosition.UNDER),
    OVER_UNDER_4_5_OVER("18", "total=4.5", "OVER_4.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_4_5, OutcomePosition.OVER),
    OVER_UNDER_4_5_UNDER("18", "total=4.5", "UNDER_4.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_4_5, OutcomePosition.UNDER),

    // --- Both Teams to Score ---
    BTTS_YES("29", null, "BOTH_TEAMS_TO_SCORE_YES", MarketCategory.BTTS, OutcomeType.BOTH_TEAMS_TO_SCORE_YES, OutcomePosition.PRIMARY),
    BTTS_NO("29", null, "BOTH_TEAMS_TO_SCORE_NO", MarketCategory.BTTS, OutcomeType.BOTH_TEAMS_TO_SCORE_NO, OutcomePosition.OPPOSITE),

    // --- Odd/Even ---
    ODD_EVEN_ODD("26", null, "ODD", MarketCategory.ODD_EVEN, OutcomeType.ODD, OutcomePosition.PRIMARY),
    ODD_EVEN_EVEN("26", null, "EVEN", MarketCategory.ODD_EVEN, OutcomeType.EVEN, OutcomePosition.OPPOSITE),

    // --- Asian Handicap (Sample) ---
    ASIAN_HANDICAP_HOME_MINUS_1_5("16", "hcp=-1.5", "HOME_(-1.5)", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_1_5, OutcomePosition.PRIMARY),
    ASIAN_HANDICAP_AWAY_PLUS_1_5("16", "hcp=-1.5", "AWAY_(+1.5)", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_1_5, OutcomePosition.OPPOSITE),
    ASIAN_HANDICAP_HOME_PLUS_1_5("16", "hcp=1.5", "HOME_(+1.5)", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_HOME_PLUS_1_5, OutcomePosition.PRIMARY),
    ASIAN_HANDICAP_AWAY_MINUS_1_5("16", "hcp=1.5", "AWAY_(-1.5)", MarketCategory.ASIAN_HANDICAP_FULLTIME, OutcomeType.ASIAN_HANDICAP_AWAY_MINUS_1_5, OutcomePosition.OPPOSITE),

    // ... Add rest of your enum constants here with OutcomePosition parameter

    // ========================================================================
// TABLE TENNIS
// ========================================================================


    //=====MATCH WINNER========
    TT_HOME("186", null, "HOME", MarketCategory.MATCH_RESULT, OutcomeType.HOME, OutcomePosition.PRIMARY),
    TT_AWAY("186", null, "AWAY", MarketCategory.MATCH_RESULT, OutcomeType.AWAY, OutcomePosition.OPPOSITE),

    //=======TOTAL POINT======
    TT_OVER_95_5("238", "total=95.5", "OVER_95.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_95_5, OutcomePosition.OVER),
    TT_UNDER_95_5("238", "total=95.5", "UNDER_95.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_95_5, OutcomePosition.UNDER),
    TT_OVER_94_5("238", "total=94.5", "OVER_94.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_94_5, OutcomePosition.OVER),
    TT_UNDER_94_5("238", "total=94.5", "UNDER_94.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_94_5, OutcomePosition.UNDER),
    TT_OVER_93_5("238", "total=93.5", "OVER_93.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_93_5, OutcomePosition.OVER),
    TT_UNDER_93_5("238", "total=93.5", "UNDER_93.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_93_5, OutcomePosition.UNDER),
    TT_OVER_92_5("238", "total=92.5", "OVER_92.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_92_5, OutcomePosition.OVER),
    TT_UNDER_92_5("238", "total=92.5", "UNDER_92.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_92_5, OutcomePosition.UNDER),
    TT_OVER_91_5("238", "total=91.5", "OVER_91.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_91_5, OutcomePosition.OVER),
    TT_UNDER_91_5("238", "total=91.5", "UNDER_91.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_91_5, OutcomePosition.UNDER),
    TT_OVER_90_5("238", "total=90.5", "OVER_90.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_90_5, OutcomePosition.OVER),
    TT_UNDER_90_5("238", "total=90.5", "UNDER_90.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_90_5, OutcomePosition.UNDER),
    TT_OVER_89_5("238", "total=89.5", "OVER_89.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_89_5, OutcomePosition.OVER),
    TT_UNDER_89_5("238", "total=89.5", "UNDER_89.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_89_5, OutcomePosition.UNDER),
    TT_OVER_88_5("238", "total=88.5", "OVER_88.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_88_5, OutcomePosition.OVER),
    TT_UNDER_88_5("238", "total=88.5", "UNDER_88.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_88_5, OutcomePosition.UNDER),
    TT_OVER_87_5("238", "total=87.5", "OVER_87.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_87_5, OutcomePosition.OVER),
    TT_UNDER_87_5("238", "total=87.5", "UNDER_87.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_87_5, OutcomePosition.UNDER),
    TT_OVER_86_5("238", "total=86.5", "OVER_86.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_86_5, OutcomePosition.OVER),
    TT_UNDER_86_5("238", "total=86.5", "UNDER_86.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_86_5, OutcomePosition.UNDER),
    TT_OVER_85_5("238", "total=85.5", "OVER_85.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_85_5, OutcomePosition.OVER),
    TT_UNDER_85_5("238", "total=85.5", "UNDER_85.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_85_5, OutcomePosition.UNDER),
    TT_OVER_84_5("238", "total=84.5", "OVER_84.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_84_5, OutcomePosition.OVER),
    TT_UNDER_84_5("238", "total=84.5", "UNDER_84.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_84_5, OutcomePosition.UNDER),
    TT_OVER_83_5("238", "total=83.5", "OVER_83.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_83_5, OutcomePosition.OVER),
    TT_UNDER_83_5("238", "total=83.5", "UNDER_83.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_83_5, OutcomePosition.UNDER),
    TT_OVER_82_5("238", "total=82.5", "OVER_82.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_82_5, OutcomePosition.OVER),
    TT_UNDER_82_5("238", "total=82.5", "UNDER_82.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_82_5, OutcomePosition.UNDER),
    TT_OVER_81_5("238", "total=81.5", "OVER_81.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_81_5, OutcomePosition.OVER),
    TT_UNDER_81_5("238", "total=81.5", "UNDER_81.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_81_5, OutcomePosition.UNDER),
    TT_OVER_80_5("238", "total=80.5", "OVER_80.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_80_5, OutcomePosition.OVER),
    TT_UNDER_80_5("238", "total=80.5", "UNDER_80.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_80_5, OutcomePosition.UNDER),
    TT_OVER_79_5("238", "total=79.5", "OVER_79.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_79_5, OutcomePosition.OVER),
    TT_UNDER_79_5("238", "total=79.5", "UNDER_79.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_79_5, OutcomePosition.UNDER),
    TT_OVER_78_5("238", "total=78.5", "OVER_78.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_78_5, OutcomePosition.OVER),
    TT_UNDER_78_5("238", "total=78.5", "UNDER_78.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_78_5, OutcomePosition.UNDER),
    TT_OVER_77_5("238", "total=77.5", "OVER_77.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_77_5, OutcomePosition.OVER),
    TT_UNDER_77_5("238", "total=77.5", "UNDER_77.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_77_5, OutcomePosition.UNDER),
    TT_OVER_76_5("238", "total=76.5", "OVER_76.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_76_5, OutcomePosition.OVER),
    TT_UNDER_76_5("238", "total=76.5", "UNDER_76.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_76_5, OutcomePosition.UNDER),
    TT_OVER_75_5("238", "total=75.5", "OVER_75.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_75_5, OutcomePosition.OVER),
    TT_UNDER_75_5("238", "total=75.5", "UNDER_75.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_75_5, OutcomePosition.UNDER),
    TT_OVER_74_5("238", "total=74.5", "OVER_74.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_74_5, OutcomePosition.OVER),
    TT_UNDER_74_5("238", "total=74.5", "UNDER_74.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_74_5, OutcomePosition.UNDER),
    TT_OVER_73_5("238", "total=73.5", "OVER_73.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_73_5, OutcomePosition.OVER),
    TT_UNDER_73_5("238", "total=73.5", "UNDER_73.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_73_5, OutcomePosition.UNDER),
    TT_OVER_72_5("238", "total=72.5", "OVER_72.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_72_5, OutcomePosition.OVER),
    TT_UNDER_72_5("238", "total=72.5", "UNDER_72.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_72_5, OutcomePosition.UNDER),
    TT_OVER_71_5("238", "total=71.5", "OVER_71.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_71_5, OutcomePosition.OVER),
    TT_UNDER_71_5("238", "total=71.5", "UNDER_71.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_71_5, OutcomePosition.UNDER),
    TT_OVER_70_5("238", "total=70.5", "OVER_70.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_70_5, OutcomePosition.OVER),
    TT_UNDER_70_5("238", "total=70.5", "UNDER_70.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_70_5, OutcomePosition.UNDER),
    TT_OVER_69_5("238", "total=69.5", "OVER_69.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_69_5, OutcomePosition.OVER),
    TT_UNDER_69_5("238", "total=69.5", "UNDER_69.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_69_5, OutcomePosition.UNDER),
    TT_OVER_68_5("238", "total=68.5", "OVER_68.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_68_5, OutcomePosition.OVER),
    TT_UNDER_68_5("238", "total=68.5", "UNDER_68.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_68_5, OutcomePosition.UNDER),
    TT_OVER_67_5("238", "total=67.5", "OVER_67.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_67_5, OutcomePosition.OVER),
    TT_UNDER_67_5("238", "total=67.5", "UNDER_67.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_67_5, OutcomePosition.UNDER),
    TT_OVER_66_5("238", "total=66.5", "OVER_66.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_66_5, OutcomePosition.OVER),
    TT_UNDER_66_5("238", "total=66.5", "UNDER_66.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_66_5, OutcomePosition.UNDER),
    TT_OVER_65_5("238", "total=65.5", "OVER_65.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_65_5, OutcomePosition.OVER),
    TT_UNDER_65_5("238", "total=65.5", "UNDER_65.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_65_5, OutcomePosition.UNDER),
    TT_OVER_64_5("238", "total=64.5", "OVER_64.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_64_5, OutcomePosition.OVER),
    TT_UNDER_64_5("238", "total=64.5", "UNDER_64.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_64_5, OutcomePosition.UNDER),
    TT_OVER_63_5("238", "total=63.5", "OVER_63.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_63_5, OutcomePosition.OVER),
    TT_UNDER_63_5("238", "total=63.5", "UNDER_63.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_63_5, OutcomePosition.UNDER),
    TT_OVER_62_5("238", "total=62.5", "OVER_62.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_62_5, OutcomePosition.OVER),
    TT_UNDER_62_5("238", "total=62.5", "UNDER_62.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_62_5, OutcomePosition.UNDER),
    TT_OVER_61_5("238", "total=61.5", "OVER_61.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_61_5, OutcomePosition.OVER),
    TT_UNDER_61_5("238", "total=61.5", "UNDER_61.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_61_5, OutcomePosition.UNDER),
    TT_OVER_60_5("238", "total=60.5", "OVER_60.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.OVER_60_5, OutcomePosition.OVER),
    TT_UNDER_60_5("238", "total=60.5", "UNDER_60.5", MarketCategory.OVER_UNDER_TOTAL, OutcomeType.UNDER_60_5, OutcomePosition.UNDER),

    //==========POINT HANDICAP================
//-------> NEGATIVE HANDICAP
    TT_POINT_HCP_MINUS_0_5_HOME("237", "hcp=-0.5", "HOME_(-0.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_0_5, OutcomePosition.PRIMARY),
    TT_POINT_HCP_PLUS_0_5_AWAY("237", "hcp=-0.5", "AWAY_(+0.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_0_5, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_MINUS_1_0_HOME("237", "hcp=-1.0", "HOME_(-1.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_1_0, OutcomePosition.PRIMARY),
    TT_POINT_HCP_PLUS_1_0_AWAY("237", "hcp=-1.0", "AWAY_(+1.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_1_0, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_MINUS_1_5_HOME("237", "hcp=-1.5", "HOME_(-1.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_1_5, OutcomePosition.PRIMARY),
    TT_POINT_HCP_PLUS_1_5_AWAY("237", "hcp=-1.5", "AWAY_(+1.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_1_5, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_MINUS_2_0_HOME("237", "hcp=-2.0", "HOME_(-2.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_2_0, OutcomePosition.PRIMARY),
    TT_POINT_HCP_PLUS_2_0_AWAY("237", "hcp=-2.0", "AWAY_(+2.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_2_0, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_MINUS_2_5_HOME("237", "hcp=-2.5", "HOME_(-2.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_2_5, OutcomePosition.PRIMARY),
    TT_POINT_HCP_PLUS_2_5_AWAY("237", "hcp=-2.5", "AWAY_(+2.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_2_5, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_MINUS_3_0_HOME("237", "hcp=-3.0", "HOME_(-3.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_3_0, OutcomePosition.PRIMARY),
    TT_POINT_HCP_PLUS_3_0_AWAY("237", "hcp=-3.0", "AWAY_(+3.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_3_0, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_MINUS_3_5_HOME("237", "hcp=-3.5", "HOME_(-3.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_3_5, OutcomePosition.PRIMARY),
    TT_POINT_HCP_PLUS_3_5_AWAY("237", "hcp=-3.5", "AWAY_(+3.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_3_5, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_MINUS_4_0_HOME("237", "hcp=-4.0", "HOME_(-4.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_4_0, OutcomePosition.PRIMARY),
    TT_POINT_HCP_PLUS_4_0_AWAY("237", "hcp=-4.0", "AWAY_(+4.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_4_0, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_MINUS_4_5_HOME("237", "hcp=-4.5", "HOME_(-4.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_4_5, OutcomePosition.PRIMARY),
    TT_POINT_HCP_PLUS_4_5_AWAY("237", "hcp=-4.5", "AWAY_(+4.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_4_5, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_MINUS_5_0_HOME("237", "hcp=-5.0", "HOME_(-5.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_5_0, OutcomePosition.PRIMARY),
    TT_POINT_HCP_PLUS_5_0_AWAY("237", "hcp=-5.0", "AWAY_(+5.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_5_0, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_MINUS_5_5_HOME("237", "hcp=-5.5", "HOME_(-5.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_5_5, OutcomePosition.PRIMARY),
    TT_POINT_HCP_PLUS_5_5_AWAY("237", "hcp=-5.5", "AWAY_(+5.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_5_5, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_MINUS_6_0_HOME("237", "hcp=-6.0", "HOME_(-6.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_6_0, OutcomePosition.PRIMARY),
    TT_POINT_HCP_PLUS_6_0_AWAY("237", "hcp=-6.0", "AWAY_(+6.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_6_0, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_MINUS_6_5_HOME("237", "hcp=-6.5", "HOME_(-6.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_MINUS_6_5, OutcomePosition.PRIMARY),
    TT_POINT_HCP_PLUS_6_5_AWAY("237", "hcp=-6.5", "AWAY_(+6.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_PLUS_6_5, OutcomePosition.OPPOSITE),

    //--------> POSITIVE HANDICAP--------
    TT_POINT_HCP_PLUS_0_5_HOME("237", "hcp=0.5", "HOME_(+0.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_PLUS_0_5, OutcomePosition.PRIMARY),
    TT_POINT_HCP_MINUS_0_5_AWAY("237", "hcp=0.5", "AWAY_(-0.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_MINUS_0_5, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_PLUS_1_0_HOME("237", "hcp=1.0", "HOME_(+1.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_PLUS_1_0, OutcomePosition.PRIMARY),
    TT_POINT_HCP_MINUS_1_0_AWAY("237", "hcp=1.0", "AWAY_(-1.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_MINUS_1_0, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_PLUS_1_5_HOME("237", "hcp=1.5", "HOME_(+1.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_PLUS_1_5, OutcomePosition.PRIMARY),
    TT_POINT_HCP_MINUS_1_5_AWAY("237", "hcp=1.5", "AWAY_(-1.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_MINUS_1_5, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_PLUS_2_0_HOME("237", "hcp=2.0", "HOME_(+2.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_PLUS_2_0, OutcomePosition.PRIMARY),
    TT_POINT_HCP_MINUS_2_0_AWAY("237", "hcp=2.0", "AWAY_(-2.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_MINUS_2_0, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_PLUS_2_5_HOME("237", "hcp=2.5", "HOME_(+2.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_PLUS_2_5, OutcomePosition.PRIMARY),
    TT_POINT_HCP_MINUS_2_5_AWAY("237", "hcp=2.5", "AWAY_(-2.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_MINUS_2_5, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_PLUS_3_0_HOME("237", "hcp=3.0", "HOME_(+3.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_PLUS_3_0, OutcomePosition.PRIMARY),
    TT_POINT_HCP_MINUS_3_0_AWAY("237", "hcp=3.0", "AWAY_(-3.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_MINUS_3_0, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_PLUS_3_5_HOME("237", "hcp=3.5", "HOME_(+3.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_PLUS_3_5, OutcomePosition.PRIMARY),
    TT_POINT_HCP_MINUS_3_5_AWAY("237", "hcp=3.5", "AWAY_(-3.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_MINUS_3_5, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_PLUS_4_0_HOME("237", "hcp=4.0", "HOME_(+4.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_PLUS_4_0, OutcomePosition.PRIMARY),
    TT_POINT_HCP_MINUS_4_0_AWAY("237", "hcp=4.0", "AWAY_(-4.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_MINUS_4_0, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_PLUS_4_5_HOME("237", "hcp=4.5", "HOME_(+4.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_PLUS_4_5, OutcomePosition.PRIMARY),
    TT_POINT_HCP_MINUS_4_5_AWAY("237", "hcp=4.5", "AWAY_(-4.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_MINUS_4_5, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_PLUS_5_0_HOME("237", "hcp=5.0", "HOME_(+5.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_PLUS_5_0, OutcomePosition.PRIMARY),
    TT_POINT_HCP_MINUS_5_0_AWAY("237", "hcp=5.0", "AWAY_(-5.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_MINUS_5_0, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_PLUS_5_5_HOME("237", "hcp=5.5", "HOME_(+5.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_PLUS_5_5, OutcomePosition.PRIMARY),
    TT_POINT_HCP_MINUS_5_5_AWAY("237", "hcp=5.5", "AWAY_(-5.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_MINUS_5_5, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_PLUS_6_0_HOME("237", "hcp=6.0", "HOME_(+6.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_PLUS_6_0, OutcomePosition.PRIMARY),
    TT_POINT_HCP_MINUS_6_0_AWAY("237", "hcp=6.0", "AWAY_(-6.0)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_MINUS_6_0, OutcomePosition.OPPOSITE),
    TT_POINT_HCP_PLUS_6_5_HOME("237", "hcp=6.5", "HOME_(+6.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_HOME_PLUS_6_5, OutcomePosition.PRIMARY),
    TT_POINT_HCP_MINUS_6_5_AWAY("237", "hcp=6.5", "AWAY_(-6.5)", MarketCategory.POINT_HANDICAP, OutcomeType.ASIAN_HANDICAP_AWAY_MINUS_6_5, OutcomePosition.OPPOSITE),

//=======GAME TOTAL POINTS================================

    //========>>FIRST GAME POINT========================
    TT_1stGPTotal_OVER_12_5("247", "total=12.5|gamenr=1", "OVER_12.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_12_5, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_12_5("247", "total=12.5|gamenr=1", "UNDER_12.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_12_5, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_13_0("247", "total=13.0|gamenr=1", "OVER_13.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_13_0, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_13_0("247", "total=13.0|gamenr=1", "UNDER_13.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_13_0, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_13_5("247", "total=13.5|gamenr=1", "OVER_13.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_13_5, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_13_5("247", "total=13.5|gamenr=1", "UNDER_13.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_13_5, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_14_0("247", "total=14.0|gamenr=1", "OVER_14.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_14_0, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_14_0("247", "total=14.0|gamenr=1", "UNDER_14.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_14_0, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_14_5("247", "total=14.5|gamenr=1", "OVER_14.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_14_5, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_14_5("247", "total=14.5|gamenr=1", "UNDER_14.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_14_5, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_15_0("247", "total=15.0|gamenr=1", "OVER_15.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_15_0, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_15_0("247", "total=15.0|gamenr=1", "UNDER_15.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_15_0, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_15_5("247", "total=15.5|gamenr=1", "OVER_15.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_15_5, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_15_5("247", "total=15.5|gamenr=1", "UNDER_15.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_15_5, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_16_0("247", "total=16.0|gamenr=1", "OVER_16.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_16_0, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_16_0("247", "total=16.0|gamenr=1", "UNDER_16.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_16_0, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_16_5("247", "total=16.5|gamenr=1", "OVER_16.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_16_5, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_16_5("247", "total=16.5|gamenr=1", "UNDER_16.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_16_5, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_17_0("247", "total=17.0|gamenr=1", "OVER_17.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_17_0, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_17_0("247", "total=17.0|gamenr=1", "UNDER_17.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_17_0, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_17_5("247", "total=17.5|gamenr=1", "OVER_17.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_17_5, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_17_5("247", "total=17.5|gamenr=1", "UNDER_17.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_17_5, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_18_0("247", "total=18.0|gamenr=1", "OVER_18.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_18_0, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_18_0("247", "total=18.0|gamenr=1", "UNDER_18.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_18_0, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_18_5("247", "total=18.5|gamenr=1", "OVER_18.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_18_5, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_18_5("247", "total=18.5|gamenr=1", "UNDER_18.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_18_5, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_19_0("247", "total=19.0|gamenr=1", "OVER_19.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_19_0, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_19_0("247", "total=19.0|gamenr=1", "UNDER_19.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_19_0, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_19_5("247", "total=19.5|gamenr=1", "OVER_19.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_19_5, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_19_5("247", "total=19.5|gamenr=1", "UNDER_19.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_19_5, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_20_0("247", "total=20.0|gamenr=1", "OVER_20.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_20_0, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_20_0("247", "total=20.0|gamenr=1", "UNDER_20.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_20_0, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_20_5("247", "total=20.5|gamenr=1", "OVER_20.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_20_5, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_20_5("247", "total=20.5|gamenr=1", "UNDER_20.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_20_5, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_21_0("247", "total=21.0|gamenr=1", "OVER_21.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_21_0, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_21_0("247", "total=21.0|gamenr=1", "UNDER_21.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_21_0, OutcomePosition.UNDER),
    TT_1stGPTotal_OVER_21_5("247", "total=21.5|gamenr=1", "OVER_21.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_21_5, OutcomePosition.OVER),
    TT_1stGPTotal_UNDER_21_5("247", "total=21.5|gamenr=1", "UNDER_21.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_21_5, OutcomePosition.UNDER),

    //=======SECOND GAME POINT====
    TT_2ndGPTotal_OVER_12_5("247", "total=12.5|gamenr=2", "OVER_12.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_12_5, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_12_5("247", "total=12.5|gamenr=2", "UNDER_12.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_12_5, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_13_0("247", "total=13.0|gamenr=2", "OVER_13.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_13_0, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_13_0("247", "total=13.0|gamenr=2", "UNDER_13.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_13_0, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_13_5("247", "total=13.5|gamenr=2", "OVER_13.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_13_5, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_13_5("247", "total=13.5|gamenr=2", "UNDER_13.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_13_5, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_14_0("247", "total=14.0|gamenr=2", "OVER_14.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_14_0, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_14_0("247", "total=14.0|gamenr=2", "UNDER_14.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_14_0, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_14_5("247", "total=14.5|gamenr=2", "OVER_14.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_14_5, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_14_5("247", "total=14.5|gamenr=2", "UNDER_14.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_14_5, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_15_0("247", "total=15.0|gamenr=2", "OVER_15.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_15_0, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_15_0("247", "total=15.0|gamenr=2", "UNDER_15.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_15_0, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_15_5("247", "total=15.5|gamenr=2", "OVER_15.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_15_5, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_15_5("247", "total=15.5|gamenr=2", "UNDER_15.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_15_5, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_16_0("247", "total=16.0|gamenr=2", "OVER_16.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_16_0, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_16_0("247", "total=16.0|gamenr=2", "UNDER_16.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_16_0, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_16_5("247", "total=16.5|gamenr=2", "OVER_16.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_16_5, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_16_5("247", "total=16.5|gamenr=2", "UNDER_16.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_16_5, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_17_0("247", "total=17.0|gamenr=2", "OVER_17.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_17_0, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_17_0("247", "total=17.0|gamenr=2", "UNDER_17.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_17_0, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_17_5("247", "total=17.5|gamenr=2", "OVER_17.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_17_5, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_17_5("247", "total=17.5|gamenr=2", "UNDER_17.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_17_5, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_18_0("247", "total=18.0|gamenr=2", "OVER_18.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_18_0, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_18_0("247", "total=18.0|gamenr=2", "UNDER_18.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_18_0, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_18_5("247", "total=18.5|gamenr=2", "OVER_18.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_18_5, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_18_5("247", "total=18.5|gamenr=2", "UNDER_18.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_18_5, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_19_0("247", "total=19.0|gamenr=2", "OVER_19.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_19_0, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_19_0("247", "total=19.0|gamenr=2", "UNDER_19.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_19_0, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_19_5("247", "total=19.5|gamenr=2", "OVER_19.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_19_5, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_19_5("247", "total=19.5|gamenr=2", "UNDER_19.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_19_5, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_20_0("247", "total=20.0|gamenr=2", "OVER_20.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_20_0, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_20_0("247", "total=20.0|gamenr=2", "UNDER_20.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_20_0, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_20_5("247", "total=20.5|gamenr=2", "OVER_20.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_20_5, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_20_5("247", "total=20.5|gamenr=2", "UNDER_20.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_20_5, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_21_0("247", "total=21.0|gamenr=2", "OVER_21.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_21_0, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_21_0("247", "total=21.0|gamenr=2", "UNDER_21.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_21_0, OutcomePosition.UNDER),
    TT_2ndGPTotal_OVER_21_5("247", "total=21.5|gamenr=2", "OVER_21.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_21_5, OutcomePosition.OVER),
    TT_2ndGPTotal_UNDER_21_5("247", "total=21.5|gamenr=2", "UNDER_21.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_21_5, OutcomePosition.UNDER),

    //========THIRD GAME POINT=======
    TT_3rdGPTotal_OVER_12_5("247", "total=12.5|gamenr=3", "OVER_12.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_12_5, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_12_5("247", "total=12.5|gamenr=3", "UNDER_12.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_12_5, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_13_0("247", "total=13.0|gamenr=3", "OVER_13.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_13_0, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_13_0("247", "total=13.0|gamenr=3", "UNDER_13.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_13_0, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_13_5("247", "total=13.5|gamenr=3", "OVER_13.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_13_5, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_13_5("247", "total=13.5|gamenr=3", "UNDER_13.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_13_5, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_14_0("247", "total=14.0|gamenr=3", "OVER_14.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_14_0, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_14_0("247", "total=14.0|gamenr=3", "UNDER_14.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_14_0, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_14_5("247", "total=14.5|gamenr=3", "OVER_14.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_14_5, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_14_5("247", "total=14.5|gamenr=3", "UNDER_14.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_14_5, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_15_0("247", "total=15.0|gamenr=3", "OVER_15.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_15_0, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_15_0("247", "total=15.0|gamenr=3", "UNDER_15.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_15_0, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_15_5("247", "total=15.5|gamenr=3", "OVER_15.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_15_5, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_15_5("247", "total=15.5|gamenr=3", "UNDER_15.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_15_5, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_16_0("247", "total=16.0|gamenr=3", "OVER_16.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_16_0, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_16_0("247", "total=16.0|gamenr=3", "UNDER_16.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_16_0, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_16_5("247", "total=16.5|gamenr=3", "OVER_16.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_16_5, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_16_5("247", "total=16.5|gamenr=3", "UNDER_16.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_16_5, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_17_0("247", "total=17.0|gamenr=3", "OVER_17.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_17_0, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_17_0("247", "total=17.0|gamenr=3", "UNDER_17.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_17_0, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_17_5("247", "total=17.5|gamenr=3", "OVER_17.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_17_5, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_17_5("247", "total=17.5|gamenr=3", "UNDER_17.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_17_5, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_18_0("247", "total=18.0|gamenr=3", "OVER_18.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_18_0, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_18_0("247", "total=18.0|gamenr=3", "UNDER_18.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_18_0, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_18_5("247", "total=18.5|gamenr=3", "OVER_18.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_18_5, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_18_5("247", "total=18.5|gamenr=3", "UNDER_18.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_18_5, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_19_0("247", "total=19.0|gamenr=3", "OVER_19.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_19_0, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_19_0("247", "total=19.0|gamenr=3", "UNDER_19.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_19_0, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_19_5("247", "total=19.5|gamenr=3", "OVER_19.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_19_5, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_19_5("247", "total=19.5|gamenr=3", "UNDER_19.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_19_5, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_20_0("247", "total=20.0|gamenr=3", "OVER_20.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_20_0, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_20_0("247", "total=20.0|gamenr=3", "UNDER_20.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_20_0, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_20_5("247", "total=20.5|gamenr=3", "OVER_20.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_20_5, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_20_5("247", "total=20.5|gamenr=3", "UNDER_20.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_20_5, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_21_0("247", "total=21.0|gamenr=3", "OVER_21.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_21_0, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_21_0("247", "total=21.0|gamenr=3", "UNDER_21.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_21_0, OutcomePosition.UNDER),
    TT_3rdGPTotal_OVER_21_5("247", "total=21.5|gamenr=3", "OVER_21.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_21_5, OutcomePosition.OVER),
    TT_3rdGPTotal_UNDER_21_5("247", "total=21.5|gamenr=3", "UNDER_21.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_21_5, OutcomePosition.UNDER),
    //=========FOURTH GAME POINT==========
    TT_4thGPTotal_OVER_12_5("247", "total=12.5|gamenr=4", "OVER_12.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_12_5, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_12_5("247", "total=12.5|gamenr=4", "UNDER_12.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_12_5, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_13_0("247", "total=13.0|gamenr=4", "OVER_13.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_13_0, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_13_0("247", "total=13.0|gamenr=4", "UNDER_13.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_13_0, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_13_5("247", "total=13.5|gamenr=4", "OVER_13.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_13_5, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_13_5("247", "total=13.5|gamenr=4", "UNDER_13.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_13_5, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_14_0("247", "total=14.0|gamenr=4", "OVER_14.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_14_0, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_14_0("247", "total=14.0|gamenr=4", "UNDER_14.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_14_0, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_14_5("247", "total=14.5|gamenr=4", "OVER_14.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_14_5, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_14_5("247", "total=14.5|gamenr=4", "UNDER_14.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_14_5, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_15_0("247", "total=15.0|gamenr=4", "OVER_15.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_15_0, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_15_0("247", "total=15.0|gamenr=4", "UNDER_15.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_15_0, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_15_5("247", "total=15.5|gamenr=4", "OVER_15.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_15_5, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_15_5("247", "total=15.5|gamenr=4", "UNDER_15.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_15_5, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_16_0("247", "total=16.0|gamenr=4", "OVER_16.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_16_0, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_16_0("247", "total=16.0|gamenr=4", "UNDER_16.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_16_0, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_16_5("247", "total=16.5|gamenr=4", "OVER_16.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_16_5, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_16_5("247", "total=16.5|gamenr=4", "UNDER_16.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_16_5, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_17_0("247", "total=17.0|gamenr=4", "OVER_17.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_17_0, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_17_0("247", "total=17.0|gamenr=4", "UNDER_17.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_17_0, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_17_5("247", "total=17.5|gamenr=4", "OVER_17.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_17_5, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_17_5("247", "total=17.5|gamenr=4", "UNDER_17.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_17_5, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_18_0("247", "total=18.0|gamenr=4", "OVER_18.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_18_0, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_18_0("247", "total=18.0|gamenr=4", "UNDER_18.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_18_0, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_18_5("247", "total=18.5|gamenr=4", "OVER_18.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_18_5, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_18_5("247", "total=18.5|gamenr=4", "UNDER_18.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_18_5, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_19_0("247", "total=19.0|gamenr=4", "OVER_19.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_19_0, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_19_0("247", "total=19.0|gamenr=4", "UNDER_19.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_19_0, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_19_5("247", "total=19.5|gamenr=4", "OVER_19.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_19_5, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_19_5("247", "total=19.5|gamenr=4", "UNDER_19.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_19_5, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_20_0("247", "total=20.0|gamenr=4", "OVER_20.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_20_0, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_20_0("247", "total=20.0|gamenr=4", "UNDER_20.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_20_0, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_20_5("247", "total=20.5|gamenr=4", "OVER_20.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_20_5, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_20_5("247", "total=20.5|gamenr=4", "UNDER_20.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_20_5, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_21_0("247", "total=21.0|gamenr=4", "OVER_21.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_21_0, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_21_0("247", "total=21.0|gamenr=4", "UNDER_21.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_21_0, OutcomePosition.UNDER),
    TT_4thGPTotal_OVER_21_5("247", "total=21.5|gamenr=4", "OVER_21.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_21_5, OutcomePosition.OVER),
    TT_4thGPTotal_UNDER_21_5("247", "total=21.5|gamenr=4", "UNDER_21.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_21_5, OutcomePosition.UNDER),
    //=========FIFTH GAME POINT==========================
    TT_5thGPTotal_OVER_12_5("247", "total=12.5|gamenr=5", "OVER_12.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_12_5, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_12_5("247", "total=12.5|gamenr=5", "UNDER_12.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_12_5, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_13_0("247", "total=13.0|gamenr=5", "OVER_13.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_13_0, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_13_0("247", "total=13.0|gamenr=5", "UNDER_13.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_13_0, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_13_5("247", "total=13.5|gamenr=5", "OVER_13.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_13_5, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_13_5("247", "total=13.5|gamenr=5", "UNDER_13.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_13_5, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_14_0("247", "total=14.0|gamenr=5", "OVER_14.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_14_0, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_14_0("247", "total=14.0|gamenr=5", "UNDER_14.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_14_0, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_14_5("247", "total=14.5|gamenr=5", "OVER_14.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_14_5, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_14_5("247", "total=14.5|gamenr=5", "UNDER_14.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_14_5, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_15_0("247", "total=15.0|gamenr=5", "OVER_15.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_15_0, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_15_0("247", "total=15.0|gamenr=5", "UNDER_15.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_15_0, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_15_5("247", "total=15.5|gamenr=5", "OVER_15.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_15_5, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_15_5("247", "total=15.5|gamenr=5", "UNDER_15.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_15_5, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_16_0("247", "total=16.0|gamenr=5", "OVER_16.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_16_0, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_16_0("247", "total=16.0|gamenr=5", "UNDER_16.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_16_0, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_16_5("247", "total=16.5|gamenr=5", "OVER_16.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_16_5, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_16_5("247", "total=16.5|gamenr=5", "UNDER_16.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_16_5, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_17_0("247", "total=17.0|gamenr=5", "OVER_17.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_17_0, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_17_0("247", "total=17.0|gamenr=5", "UNDER_17.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_17_0, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_17_5("247", "total=17.5|gamenr=5", "OVER_17.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_17_5, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_17_5("247", "total=17.5|gamenr=5", "UNDER_17.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_17_5, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_18_0("247", "total=18.0|gamenr=5", "OVER_18.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_18_0, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_18_0("247", "total=18.0|gamenr=5", "UNDER_18.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_18_0, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_18_5("247", "total=18.5|gamenr=5", "OVER_18.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_18_5, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_18_5("247", "total=18.5|gamenr=5", "UNDER_18.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_18_5, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_19_0("247", "total=19.0|gamenr=5", "OVER_19.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_19_0, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_19_0("247", "total=19.0|gamenr=5", "UNDER_19.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_19_0, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_19_5("247", "total=19.5|gamenr=5", "OVER_19.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_19_5, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_19_5("247", "total=19.5|gamenr=5", "UNDER_19.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_19_5, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_20_0("247", "total=20.0|gamenr=5", "OVER_20.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_20_0, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_20_0("247", "total=20.0|gamenr=5", "UNDER_20.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_20_0, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_20_5("247", "total=20.5|gamenr=5", "OVER_20.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_20_5, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_20_5("247", "total=20.5|gamenr=5", "UNDER_20.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_20_5, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_21_0("247", "total=21.0|gamenr=5", "OVER_21.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_21_0, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_21_0("247", "total=21.0|gamenr=5", "UNDER_21.0", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_21_0, OutcomePosition.UNDER),
    TT_5thGPTotal_OVER_21_5("247", "total=21.5|gamenr=5", "OVER_21.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.OVER_21_5, OutcomePosition.OVER),
    TT_5thGPTotal_UNDER_21_5("247", "total=21.5|gamenr=5", "UNDER_21.5", MarketCategory.TABLE_TENNIS_GAME_POINT, OutcomeType.UNDER_21_5, OutcomePosition.UNDER),
    stGP_HCP_PLUS_0_5_AWAY("246", "hcp=-0.5|gamenr=1", "AWAY(+0.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_0_5, OutcomePosition.OPPOSITE),
    //-----> POSITIVE HCP---
    TT_1stGP_HCP_PLUS_4_5_HOME("246", "hcp=4.5|gamenr=1", "HOME_(+4.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_4_5, OutcomePosition.PRIMARY),
    TT_1stGP_HCP_MINUS_4_5_AWAY("246", "hcp=4.5|gamenr=1", "AWAY_(-4.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_4_5, OutcomePosition.OPPOSITE),
    TT_1stGP_HCP_PLUS_4_0_HOME("246", "hcp=4.0|gamenr=1", "HOME_(+4.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_4_0, OutcomePosition.PRIMARY),
    TT_1stGP_HCP_MINUS_4_0_AWAY("246", "hcp=4.0|gamenr=1", "AWAY_(-4.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_4_0, OutcomePosition.OPPOSITE),
    TT_1stGP_HCP_PLUS_3_5_HOME("246", "hcp=3.5|gamenr=1", "HOME_(+3.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_3_5, OutcomePosition.PRIMARY),
    TT_1stGP_HCP_MINUS_3_5_AWAY("246", "hcp=3.5|gamenr=1", "AWAY_(-3.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_3_5, OutcomePosition.OPPOSITE),
    TT_1stGP_HCP_PLUS_3_0_HOME("246", "hcp=3.0|gamenr=1", "HOME_(+3.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_3_0, OutcomePosition.PRIMARY),
    TT_1stGP_HCP_MINUS_3_0_AWAY("246", "hcp=3.0|gamenr=1", "AWAY_(-3.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_3_0, OutcomePosition.OPPOSITE),
    TT_1stGP_HCP_PLUS_2_5_HOME("246", "hcp=2.5|gamenr=1", "HOME_(+2.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_2_5, OutcomePosition.PRIMARY),
    TT_1stGP_HCP_MINUS_2_5_AWAY("246", "hcp=2.5|gamenr=1", "AWAY_(-2.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_2_5, OutcomePosition.OPPOSITE),
    TT_1stGP_HCP_PLUS_2_0_HOME("246", "hcp=2.0|gamenr=1", "HOME_(+2.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_2_0, OutcomePosition.PRIMARY),
    TT_1stGP_HCP_MINUS_2_0_AWAY("246", "hcp=2.0|gamenr=1", "AWAY_(-2.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_2_0, OutcomePosition.OPPOSITE),
    TT_1stGP_HCP_PLUS_1_5_HOME("246", "hcp=1.5|gamenr=1", "HOME_(+1.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_1_5, OutcomePosition.PRIMARY),
    TT_1stGP_HCP_MINUS_1_5_AWAY("246", "hcp=1.5|gamenr=1", "AWAY_(-1.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_1_5, OutcomePosition.OPPOSITE),
    TT_1stGP_HCP_PLUS_1_0_HOME("246", "hcp=1.0|gamenr=1", "HOME_(+1.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_1_0, OutcomePosition.PRIMARY),
    TT_1stGP_HCP_MINUS_1_0_AWAY("246", "hcp=1.0|gamenr=1", "AWAY_(-1.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_1_0, OutcomePosition.OPPOSITE),
    TT_1stGP_HCP_PLUS_0_5_HOME("246", "hcp=0.5|gamenr=1", "HOME_(+0.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_0_5, OutcomePosition.PRIMARY),
    TT_1stGP_HCP_MINUS_0_5_AWAY("246", "hcp=0.5|gamenr=1", "AWAY_(-0.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_0_5, OutcomePosition.OPPOSITE),
    //=======SECOND GAME POINT HANDICAP=========
//------> NEGATIVE HCP
    TT_2ndGP_HCP_MINUS_4_5_HOME("246", "hcp=-4.5|gamenr=2", "HOME_(-4.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_4_5, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_PLUS_4_5_AWAY("246", "hcp=-4.5|gamenr=2", "AWAY_(+4.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_4_5, OutcomePosition.OPPOSITE),
    TT_2ndGP_HCP_MINUS_4_0_HOME("246", "hcp=-4.0|gamenr=2", "HOME_(-4.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_4_0, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_PLUS_4_0_AWAY("246", "hcp=-4.0|gamenr=2", "AWAY_(+4.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_4_0, OutcomePosition.OPPOSITE),
    TT_2ndGP_HCP_MINUS_3_5_HOME("246", "hcp=-3.5|gamenr=2", "HOME_(-3.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_3_5, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_PLUS_3_5_AWAY("246", "hcp=-3.5|gamenr=2", "AWAY_(+3.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_3_5, OutcomePosition.OPPOSITE),
    TT_2ndGP_HCP_MINUS_3_0_HOME("246", "hcp=-3.0|gamenr=2", "HOME_(-3.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_3_0, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_PLUS_3_0_AWAY("246", "hcp=-3.0|gamenr=2", "AWAY_(+3.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_3_0, OutcomePosition.OPPOSITE),
    TT_2ndGP_HCP_MINUS_2_5_HOME("246", "hcp=-2.5|gamenr=2", "HOME_(-2.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_2_5, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_PLUS_2_5_AWAY("246", "hcp=-2.5|gamenr=2", "AWAY_(+2.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_2_5, OutcomePosition.OPPOSITE),
    TT_2ndGP_HCP_MINUS_2_0_HOME("246", "hcp=-2.0|gamenr=2", "HOME_(-2.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_2_0, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_PLUS_2_0_AWAY("246", "hcp=-2.0|gamenr=2", "AWAY_(+2.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_2_0, OutcomePosition.OPPOSITE),
    TT_2ndGP_HCP_MINUS_1_5_HOME("246", "hcp=-1.5|gamenr=2", "HOME_(-1.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_1_5, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_PLUS_1_5_AWAY("246", "hcp=-1.5|gamenr=2", "AWAY_(+1.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_1_5, OutcomePosition.OPPOSITE),
    TT_2ndGP_HCP_MINUS_1_0_HOME("246", "hcp=-1.0|gamenr=2", "HOME_(-1.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_1_0, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_PLUS_1_0_AWAY("246", "hcp=-1.0|gamenr=2", "AWAY_(+1.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_1_0, OutcomePosition.OPPOSITE),
    TT_2ndGP_HCP_MINUS_0_5_HOME("246", "hcp=-0.5|gamenr=2", "HOME_(-0.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_0_5, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_PLUS_0_5_AWAY("246", "hcp=-0.5|gamenr=2", "AWAY_(+0.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_0_5, OutcomePosition.OPPOSITE),
    //-----> POSITIVE HCP---
    TT_2ndGP_HCP_PLUS_4_5_HOME("246", "hcp=4.5|gamenr=2", "HOME_(+4.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_4_5, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_MINUS_4_5_AWAY("246", "hcp=4.5|gamenr=2", "AWAY_(-4.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_4_5, OutcomePosition.OPPOSITE),
    TT_2ndGP_HCP_PLUS_4_0_HOME("246", "hcp=4.0|gamenr=2", "HOME_(+4.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_4_0, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_MINUS_4_0_AWAY("246", "hcp=4.0|gamenr=2", "AWAY_(-4.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_4_0, OutcomePosition.OPPOSITE),
    TT_2ndGP_HCP_PLUS_3_5_HOME("246", "hcp=3.5|gamenr=2", "HOME_(+3.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_3_5, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_MINUS_3_5_AWAY("246", "hcp=3.5|gamenr=2", "AWAY_(-3.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_3_5, OutcomePosition.OPPOSITE),
    TT_2ndGP_HCP_PLUS_3_0_HOME("246", "hcp=3.0|gamenr=2", "HOME_(+3.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_3_0, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_MINUS_3_0_AWAY("246", "hcp=3.0|gamenr=2", "AWAY_(-3.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_3_0, OutcomePosition.OPPOSITE),
    TT_2ndGP_HCP_PLUS_2_5_HOME("246", "hcp=2.5|gamenr=2", "HOME_(+2.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_2_5, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_MINUS_2_5_AWAY("246", "hcp=2.5|gamenr=2", "AWAY_(-2.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_2_5, OutcomePosition.OPPOSITE),
    TT_2ndGP_HCP_PLUS_2_0_HOME("246", "hcp=2.0|gamenr=2", "HOME_(+2.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_2_0, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_MINUS_2_0_AWAY("246", "hcp=2.0|gamenr=2", "AWAY_(-2.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_2_0, OutcomePosition.OPPOSITE),
    TT_2ndGP_HCP_PLUS_1_5_HOME("246", "hcp=1.5|gamenr=2", "HOME_(+1.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_1_5, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_MINUS_1_5_AWAY("246", "hcp=1.5|gamenr=2", "AWAY_(-1.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_1_5, OutcomePosition.OPPOSITE),
    TT_2ndGP_HCP_PLUS_1_0_HOME("246", "hcp=1.0|gamenr=2", "HOME_(+1.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_1_0, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_MINUS_1_0_AWAY("246", "hcp=1.0|gamenr=2", "AWAY_(-1.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_1_0, OutcomePosition.OPPOSITE),
    TT_2ndGP_HCP_PLUS_0_5_HOME("246", "hcp=0.5|gamenr=2", "HOME_(+0.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_0_5, OutcomePosition.PRIMARY),
    TT_2ndGP_HCP_MINUS_0_5_AWAY("246", "hcp=0.5|gamenr=2", "AWAY_(-0.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_0_5, OutcomePosition.OPPOSITE),
    //=========THIRD GAME POINT HANDICAP===========
//------> NEGATIVE HCP
    TT_3rdGP_HCP_MINUS_4_5_HOME("246", "hcp=-4.5|gamenr=3", "HOME_(-4.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_4_5, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_PLUS_4_5_AWAY("246", "hcp=-4.5|gamenr=3", "AWAY_(+4.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_4_5, OutcomePosition.OPPOSITE),
    TT_3rdGP_HCP_MINUS_4_0_HOME("246", "hcp=-4.0|gamenr=3", "HOME_(-4.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_4_0, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_PLUS_4_0_AWAY("246", "hcp=-4.0|gamenr=3", "AWAY_(+4.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_4_0, OutcomePosition.OPPOSITE),
    TT_3rdGP_HCP_MINUS_3_5_HOME("246", "hcp=-3.5|gamenr=3", "HOME_(-3.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_3_5, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_PLUS_3_5_AWAY("246", "hcp=-3.5|gamenr=3", "AWAY_(+3.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_3_5, OutcomePosition.OPPOSITE),
    TT_3rdGP_HCP_MINUS_3_0_HOME("246", "hcp=-3.0|gamenr=3", "HOME_(-3.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_3_0, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_PLUS_3_0_AWAY("246", "hcp=-3.0|gamenr=3", "AWAY_(+3.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_3_0, OutcomePosition.OPPOSITE),
    TT_3rdGP_HCP_MINUS_2_5_HOME("246", "hcp=-2.5|gamenr=3", "HOME_(-2.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_2_5, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_PLUS_2_5_AWAY("246", "hcp=-2.5|gamenr=3", "AWAY_(+2.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_2_5, OutcomePosition.OPPOSITE),
    TT_3rdGP_HCP_MINUS_2_0_HOME("246", "hcp=-2.0|gamenr=3", "HOME_(-2.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_2_0, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_PLUS_2_0_AWAY("246", "hcp=-2.0|gamenr=3", "AWAY_(+2.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_2_0, OutcomePosition.OPPOSITE),
    TT_3rdGP_HCP_MINUS_1_5_HOME("246", "hcp=-1.5|gamenr=3", "HOME_(-1.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_1_5, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_PLUS_1_5_AWAY("246", "hcp=-1.5|gamenr=3", "AWAY_(+1.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_1_5, OutcomePosition.OPPOSITE),
    TT_3rdGP_HCP_MINUS_1_0_HOME("246", "hcp=-1.0|gamenr=3", "HOME_(-1.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_1_0, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_PLUS_1_0_AWAY("246", "hcp=-1.0|gamenr=3", "AWAY_(+1.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_1_0, OutcomePosition.OPPOSITE),
    TT_3rdGP_HCP_MINUS_0_5_HOME("246", "hcp=-0.5|gamenr=3", "HOME_(-0.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_0_5, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_PLUS_0_5_AWAY("246", "hcp=-0.5|gamenr=3", "AWAY_(+0.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_0_5, OutcomePosition.OPPOSITE),
    //-----> POSITIVE HCP---
    TT_3rdGP_HCP_PLUS_4_5_HOME("246", "hcp=4.5|gamenr=3", "HOME_(+4.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_4_5, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_MINUS_4_5_AWAY("246", "hcp=4.5|gamenr=3", "AWAY_(-4.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_4_5, OutcomePosition.OPPOSITE),
    TT_3rdGP_HCP_PLUS_4_0_HOME("246", "hcp=4.0|gamenr=3", "HOME_(+4.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_4_0, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_MINUS_4_0_AWAY("246", "hcp=4.0|gamenr=3", "AWAY_(-4.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_4_0, OutcomePosition.OPPOSITE),
    TT_3rdGP_HCP_PLUS_3_5_HOME("246", "hcp=3.5|gamenr=3", "HOME_(+3.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_3_5, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_MINUS_3_5_AWAY("246", "hcp=3.5|gamenr=3", "AWAY_(-3.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_3_5, OutcomePosition.OPPOSITE),
    TT_3rdGP_HCP_PLUS_3_0_HOME("246", "hcp=3.0|gamenr=3", "HOME_(+3.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_3_0, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_MINUS_3_0_AWAY("246", "hcp=3.0|gamenr=3", "AWAY_(-3.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_3_0, OutcomePosition.OPPOSITE),
    TT_3rdGP_HCP_PLUS_2_5_HOME("246", "hcp=2.5|gamenr=3", "HOME_(+2.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_2_5, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_MINUS_2_5_AWAY("246", "hcp=2.5|gamenr=3", "AWAY_(-2.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_2_5, OutcomePosition.OPPOSITE),
    TT_3rdGP_HCP_PLUS_2_0_HOME("246", "hcp=2.0|gamenr=3", "HOME_(+2.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_2_0, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_MINUS_2_0_AWAY("246", "hcp=2.0|gamenr=3", "AWAY_(-2.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_2_0, OutcomePosition.OPPOSITE),
    TT_3rdGP_HCP_PLUS_1_5_HOME("246", "hcp=1.5|gamenr=3", "HOME_(+1.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_1_5, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_MINUS_1_5_AWAY("246", "hcp=1.5|gamenr=3", "AWAY_(-1.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_1_5, OutcomePosition.OPPOSITE),
    TT_3rdGP_HCP_PLUS_1_0_HOME("246", "hcp=1.0|gamenr=3", "HOME_(+1.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_1_0, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_MINUS_1_0_AWAY("246", "hcp=1.0|gamenr=3", "AWAY_(-1.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_1_0, OutcomePosition.OPPOSITE),
    TT_3rdGP_HCP_PLUS_0_5_HOME("246", "hcp=0.5|gamenr=3", "HOME_(+0.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_0_5, OutcomePosition.PRIMARY),
    TT_3rdGP_HCP_MINUS_0_5_AWAY("246", "hcp=0.5|gamenr=3", "AWAY_(-0.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_0_5, OutcomePosition.OPPOSITE),

    //========FOURTH GAME POINT HANDICAP=========
//------> NEGATIVE HCP
    TT_4thGP_HCP_MINUS_4_5_HOME("246", "hcp=-4.5|gamenr=4", "HOME_(-4.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_4_5, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_PLUS_4_5_AWAY("246", "hcp=-4.5|gamenr=4", "AWAY_(+4.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_4_5, OutcomePosition.OPPOSITE),
    TT_4thGP_HCP_MINUS_4_0_HOME("246", "hcp=-4.0|gamenr=4", "HOME_(-4.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_4_0, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_PLUS_4_0_AWAY("246", "hcp=-4.0|gamenr=4", "AWAY_(+4.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_4_0, OutcomePosition.OPPOSITE),
    TT_4thGP_HCP_MINUS_3_5_HOME("246", "hcp=-3.5|gamenr=4", "HOME_(-3.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_3_5, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_PLUS_3_5_AWAY("246", "hcp=-3.5|gamenr=4", "AWAY_(+3.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_3_5, OutcomePosition.OPPOSITE),
    TT_4thGP_HCP_MINUS_3_0_HOME("246", "hcp=-3.0|gamenr=4", "HOME_(-3.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_3_0, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_PLUS_3_0_AWAY("246", "hcp=-3.0|gamenr=4", "AWAY_(+3.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_3_0, OutcomePosition.OPPOSITE),
    TT_4thGP_HCP_MINUS_2_5_HOME("246", "hcp=-2.5|gamenr=4", "HOME_(-2.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_2_5, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_PLUS_2_5_AWAY("246", "hcp=-2.5|gamenr=4", "AWAY_(+2.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_2_5, OutcomePosition.OPPOSITE),
    TT_4thGP_HCP_MINUS_2_0_HOME("246", "hcp=-2.0|gamenr=4", "HOME_(-2.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_2_0, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_PLUS_2_0_AWAY("246", "hcp=-2.0|gamenr=4", "AWAY_(+2.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_2_0, OutcomePosition.OPPOSITE),
    TT_4thGP_HCP_MINUS_1_5_HOME("246", "hcp=-1.5|gamenr=4", "HOME_(-1.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_1_5, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_PLUS_1_5_AWAY("246", "hcp=-1.5|gamenr=4", "AWAY_(+1.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_1_5, OutcomePosition.OPPOSITE),
    TT_4thGP_HCP_MINUS_1_0_HOME("246", "hcp=-1.0|gamenr=4", "HOME_(-1.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_1_0, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_PLUS_1_0_AWAY("246", "hcp=-1.0|gamenr=4", "AWAY_(+1.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_1_0, OutcomePosition.OPPOSITE),
    TT_4thGP_HCP_MINUS_0_5_HOME("246", "hcp=-0.5|gamenr=4", "HOME_(-0.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_MINUS_0_5, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_PLUS_0_5_AWAY("246", "hcp=-0.5|gamenr=4", "AWAY_(+0.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_PLUS_0_5, OutcomePosition.OPPOSITE),

    //-----> POSITIVE HCP---
    TT_4thGP_HCP_PLUS_4_5_HOME("246", "hcp=4.5|gamenr=4", "HOME_(+4.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_4_5, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_MINUS_4_5_AWAY("246", "hcp=4.5|gamenr=4", "AWAY_(-4.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_4_5, OutcomePosition.OPPOSITE),
    TT_4thGP_HCP_PLUS_4_0_HOME("246", "hcp=4.0|gamenr=4", "HOME_(+4.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_4_0, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_MINUS_4_0_AWAY("246", "hcp=4.0|gamenr=4", "AWAY_(-4.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_4_0, OutcomePosition.OPPOSITE),
    TT_4thGP_HCP_PLUS_3_5_HOME("246", "hcp=3.5|gamenr=4", "HOME_(+3.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_3_5, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_MINUS_3_5_AWAY("246", "hcp=3.5|gamenr=4", "AWAY_(-3.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_3_5, OutcomePosition.OPPOSITE),
    TT_4thGP_HCP_PLUS_3_0_HOME("246", "hcp=3.0|gamenr=4", "HOME_(+3.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_3_0, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_MINUS_3_0_AWAY("246", "hcp=3.0|gamenr=4", "AWAY_(-3.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_3_0, OutcomePosition.OPPOSITE),
    TT_4thGP_HCP_PLUS_2_5_HOME("246", "hcp=2.5|gamenr=4", "HOME_(+2.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_2_5, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_MINUS_2_5_AWAY("246", "hcp=2.5|gamenr=4", "AWAY_(-2.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_2_5, OutcomePosition.OPPOSITE),
    TT_4thGP_HCP_PLUS_2_0_HOME("246", "hcp=2.0|gamenr=4", "HOME_(+2.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_2_0, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_MINUS_2_0_AWAY("246", "hcp=2.0|gamenr=4", "AWAY_(-2.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_2_0, OutcomePosition.OPPOSITE),
    TT_4thGP_HCP_PLUS_1_5_HOME("246", "hcp=1.5|gamenr=4", "HOME_(+1.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_1_5, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_MINUS_1_5_AWAY("246", "hcp=1.5|gamenr=4", "AWAY_(-1.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_1_5, OutcomePosition.OPPOSITE),
    TT_4thGP_HCP_PLUS_1_0_HOME("246", "hcp=1.0|gamenr=4", "HOME_(+1.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_1_0, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_MINUS_1_0_AWAY("246", "hcp=1.0|gamenr=4", "AWAY_(-1.0)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_1_0, OutcomePosition.OPPOSITE),
    TT_4thGP_HCP_PLUS_0_5_HOME("246", "hcp=0.5|gamenr=4", "HOME_(+0.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_HOME_PLUS_0_5, OutcomePosition.PRIMARY),
    TT_4thGP_HCP_MINUS_0_5_AWAY("246", "hcp=0.5|gamenr=4", "AWAY_(-0.5)", MarketCategory.GAME_POINT_HANDICAP, OutcomeType.GAME_POINT_HANDICAP_AWAY_MINUS_0_5, OutcomePosition.OPPOSITE);


    private final String marketId;
    private final String specifier;
    private final String normalizedName;
    private final MarketCategory category;
    private final OutcomeType outcomeType;
    private final OutcomePosition position;
    private final String providerKey;

    private static final Map<String, MSportMarketType> BY_PROVIDER_KEY;
    private static final Map<String, List<MSportMarketType>> BY_MARKET_LINE;

    static {
        BY_PROVIDER_KEY = Arrays.stream(values())
                .collect(Collectors.toMap(
                        MSportMarketType::getProviderKey,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));

        BY_MARKET_LINE = Arrays.stream(values())
                .collect(Collectors.groupingBy(MSportMarketType::getMarketLineKey));
    }

    MSportMarketType(String marketId, String specifier, String normalizedName,
                     MarketCategory category, OutcomeType outcomeType, OutcomePosition position) {
        this.marketId = marketId;
        this.specifier = specifier;
        this.normalizedName = normalizedName;
        this.category = category;
        this.outcomeType = outcomeType;
        this.position = position;
        this.providerKey = buildProviderKey(marketId, specifier, normalizedName);
    }

    @Override
    public String getCanonicalMarketKey() {
        String lineValue = extractLineValue();
        String positionStr = position.name();

        if (lineValue != null && !lineValue.isEmpty()) {
            return category.name() + ":" + lineValue + ":" + positionStr;
        }
        return category.name() + "::" + positionStr;
    }

    /**
     * Get market line key (marketId + specifier) for grouping same betting lines
     */
    public String getMarketLineKey() {
        return specifier == null ? marketId : marketId + ":" + specifier;
    }

    /**
     * Find the opposite outcome for this market (if exists)
     */
    public Optional<MSportMarketType> findOpposite() {
        List<MSportMarketType> sameMarketLine = BY_MARKET_LINE.get(this.getMarketLineKey());
        if (sameMarketLine == null) {
            return Optional.empty();
        }

        return sameMarketLine.stream()
                .filter(m -> m != this && this.canFormArbitrageWith(m))
                .findFirst();
    }

    /**
     * Get all outcomes for the same market line
     */
    public List<MSportMarketType> getSameMarketLineOutcomes() {
        return BY_MARKET_LINE.getOrDefault(this.getMarketLineKey(), Collections.emptyList());
    }

    // Static lookup methods
    public static MSportMarketType fromProviderKey(String providerKey) {
        MSportMarketType market = BY_PROVIDER_KEY.get(providerKey);
        if (market == null) {
            throw new IllegalArgumentException("Unknown MSport market key: " + providerKey);
        }
        return market;
    }

    public static Optional<MSportMarketType> safeFromProviderKey(String providerKey) {
        return Optional.ofNullable(BY_PROVIDER_KEY.get(providerKey));
    }

    public static boolean isKnownMarket(String providerKey) {
        return BY_PROVIDER_KEY.containsKey(providerKey);
    }

    public static Optional<MarketCategory> getCategoryForProviderKey(String providerKey) {
        return safeFromProviderKey(providerKey).map(MSportMarketType::getCategory);
    }

    public static String getNormalizedNameSafe(String providerKey) {
        return safeFromProviderKey(providerKey)
                .map(MSportMarketType::getNormalizedName)
                .orElse(providerKey);
    }

    public static boolean isOverUnderMarket(String providerKey) {
        return safeFromProviderKey(providerKey)
                .map(m -> m.getCategory() == MarketCategory.OVER_UNDER_TOTAL)
                .orElse(false);
    }

    /**
     * Generate provider key from raw data
     */
    public static String generateProviderKey(String marketId, String specifier, String outcomeDesc) {
        return buildProviderKey(marketId, specifier, outcomeDesc);
    }

    /**
     * Check if two market types can form an arbitrage (static version)
     */
    public static boolean canFormArbitrage(MSportMarketType market1, MarketType market2) {
        return market1.canFormArbitrageWith(market2);
    }

    private static String buildProviderKey(String marketId, String specifier, String outcomeDesc) {
        if (specifier != null && !specifier.isEmpty()) {
            return marketId + ":" + specifier + ":" + outcomeDesc;
        }
        return marketId + ":" + outcomeDesc;
    }
}