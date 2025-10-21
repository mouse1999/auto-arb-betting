package com.mouse.bet.tasks;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.model.arb.LegResult;
import com.mouse.bet.service.ArbService;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Phaser;

/**
 * A unit of work for a particular bookmaker: executes a single BetLeg with retry logic.
 * Arrives at the Phaser ONLY when completely finished (success or retries exhausted).
 */
@Slf4j
@Builder
@Getter
public class LegTask  {

    private final String arbId;
    private final Arb arb;
    private final BetLeg leg;
    private final BookMaker bookmaker;
    private final int maxRetries;
    private final Duration retryBackoff;
    private final Phaser barrier;
    private final ConcurrentMap<BookMaker, LegResult> results;

}
