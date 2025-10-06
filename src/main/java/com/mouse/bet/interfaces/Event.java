package com.mouse.bet.interfaces;


import com.mouse.bet.model.sporty.Market;
import com.mouse.bet.model.sporty.Sport;

import java.util.List;

public interface Event {
    String getEventId();
     Sport getSport();
    String getHomeTeamName();
    String getAwayTeamName();
    List<Market> getMarkets();
    long getEstimateStartTime();
}
