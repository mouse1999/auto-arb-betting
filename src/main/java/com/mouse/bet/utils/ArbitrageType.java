package com.mouse.bet.utils;

import lombok.Getter;

@Getter
public enum ArbitrageType {
    NONE("No Arbitrage"),
    OVER_UNDER("Over/Under"),
    ASIAN_HANDICAP("Asian Handicap"),
    MATCH_RESULT("Match Result (1X2)"),
    MATCH_WINNER("Match Winner (2-Way)"),
    DRAW_NO_BET("Draw No Bet"),
    BOTH_TEAMS_TO_SCORE("Both Teams to Score"),
    DOUBLE_CHANCE("Double Chance"),
    ODD_EVEN("Odd/Even"),
    OTHER("Other Market"),
    POINT_HANDICAP("Point handicap"),
    GAME_POINT_HANDICAP("Game handicap");

    private final String displayName;

    ArbitrageType(String displayName) {
        this.displayName = displayName;
    }

}
