package com.mouse.bet.enums;

public enum BetLegStatus {

    /**
     * Bet has not been placed yet
     */
    PENDING,

    /**
     * Bet is being placed (in progress)
     */
    PLACING,

    /**
     * Bet has been successfully placed
     */
    PLACED,

    /**
     * Bet placement failed
     */
    FAILED,

    /**
     * Bet is waiting to be settled
     */
    AWAITING_SETTLEMENT,

    /**
     * Bet won
     */
    WON,

    /**
     * Bet lost
     */
    LOST,

    /**
     * Bet was voided/cancelled by bookmaker
     */
    VOID,

    /**
     * Bet was cancelled before placement
     */
    CANCELLED,

    /**
     * Bet is being retried after failure
     */
    RETRYING;
}
