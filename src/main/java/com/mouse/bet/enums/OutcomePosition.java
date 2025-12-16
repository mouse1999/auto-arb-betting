package com.mouse.bet.enums;

public enum OutcomePosition {
    PRIMARY,        // Primary outcome (e.g., Home in 1X2, Yes in BTTS)
    OPPOSITE,       // Direct opposite (e.g., Away in DNB, No in BTTS)
    OPPOSING_HOME,  // Away in 1X2 markets (3-way)
    OVER,           // Over in O/U markets
    UNDER,          // Under in O/U markets
    SINGLE
}
