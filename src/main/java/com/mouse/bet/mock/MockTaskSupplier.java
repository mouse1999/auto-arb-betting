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
                .homeTeam("Bahcesehir Koleji")
                .awayTeam("FC Universitatea Cluj")
                .isPrimaryLeg(true)
                .sportEnum(SportEnum.BASKETBALL)
                .odds(BigDecimal.valueOf(1.5))
                .providerMarketName("Over 176.5")
                .providerMarketTitle("O/U (incl. OT)")
                .stake(BigDecimal.TEN)

                .build();



        legTask = LegTask.builder()
                .arbId("arb123")
                .bookmaker(BookMaker.SPORTY_BET)
                .retryBackoff(Duration.ofSeconds(3))
                .leg(leg)
                .build();


    }


    public LegTask poll() {
        return legTask;
    }

    public void consume() {
        this.legTask = null;
    }


}

