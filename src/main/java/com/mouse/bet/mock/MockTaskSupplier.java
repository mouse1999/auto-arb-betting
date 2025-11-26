package com.mouse.bet.mock;

import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.enums.SportEnum;
import com.mouse.bet.tasks.LegTask;

import java.math.BigDecimal;
import java.time.Duration;

public class MockTaskSupplier {

    private LegTask legTask;
    private BetLeg leg;

    public MockTaskSupplier() {
        init();

    }

    private void init() {
        leg = BetLeg.builder()
                .stake(BigDecimal.TEN)
                .profitPercent(BigDecimal.valueOf(12))
                .league("TT_cup")
                .homeTeam("Misiak, Mateusz")
                .awayTeam("Trela, Mateusz")
                .isPrimaryLeg(true)
                .sportEnum(SportEnum.TABLE_TENNIS)
                .odds(BigDecimal.valueOf(2.0))
                .providerMarketName("Away")
                .providerMarketTitle("Winner")
                .stake(BigDecimal.TEN)

                .build();

        legTask = LegTask.builder()
                .arbId("arb123")
                .bookmaker(BookMaker.SPORTY_BET)
                .retryBackoff(Duration.ofSeconds(3))
                .leg(leg)
                .build();

        // Misiak, Mateusz vs Trela, Mateusz
// SportyTV Match Tracker

    }

    public LegTask poll() {
        return legTask;
    }

    public void consume() {
        this.legTask = null;
    }


}
