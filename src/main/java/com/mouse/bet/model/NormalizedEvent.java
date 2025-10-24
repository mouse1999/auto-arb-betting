package com.mouse.bet.model;

import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.enums.SportEnum;
import com.mouse.bet.interfaces.Event;
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
    private String eventId;
    private String homeTeam;
    private String awayTeam;
    private String league;
    private Instant startTime;
    private String eventName;
    private Event event;
    private SportEnum sportEnum;
    private long estimateStartTime;

    @Builder.Default
    private Instant lastUpdated = Instant.now();

    private BookMaker bookie;
    private List<NormalizedMarket> markets;
}
