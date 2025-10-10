package com.mouse.bet.model.sporty;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mouse.bet.interfaces.Event;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SportyEvent implements Event {
    private String eventId;
    private String gameId;
    private String productStatus;
    private long estimateStartTime;
    private int status; //meaning
    private String setScore;
    private List<String> gameScore;
    private String period;
    private String matchStatus; //meaning
    private String playedSeconds;
    private String homeTeamId;
    private String homeTeamName;
    private String awayTeamName;
    private String awayTeamId;
    private Sport sport;
    private int totalMarketSize;
    private List<Market> markets;

}
