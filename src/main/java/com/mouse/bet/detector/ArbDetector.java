package com.mouse.bet.detector;

import com.mouse.bet.finance.WalletService;

/**
 * This class holds cached events that is passed to it in the event-pool
 * it is use as a tool to detect an arbitrage opportunity from the pool of events
 * it  uses a calculator to detect an opportunity and a wallet service to check if
 * the bookies involved has sufficient balance to execute or bet on the arb.
 * if balance not sufficient, indicate not able to bet...
 *
 */
public class ArbDetector {
    private final WalletService walletService;


    public ArbDetector(WalletService walletService) {
        this.walletService = walletService;
    }
}
