package com.mouse.bet.interfaces;

import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.enums.Sport;
import com.mouse.bet.model.NormalizedMarket;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class NormalizedEvent {
    String eventId;
    private String homeTeam;
    String awayTeam;
    String league;
    private Instant startTime;
    private String eventName;
    private Event event;
    private Sport sport;
    private long estimateStartTime;
    private Instant lastUpdated = Instant.now();
    private BookMaker bookie;
    private List<NormalizedMarket> markets;

}
