package com.mouse.bet.model;

import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.enums.OutcomeStatus;
import com.mouse.bet.enums.SportEnum;
import com.mouse.bet.interfaces.MarketType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class NormalizedOutcome {
    private MarketType marketType;
    private String eventId;
    private String normalEventId;
    private String league;
    private BigDecimal odds;
    private BookMaker bookmaker;
    private String eventName;
    private String homeTeam;
    private String awayTeam;
    private Instant showedTimestamp = Instant.now();
    private String outcomeId;              // Maps to 'id' - unique identifier for this outcome
    private String outcomeDescription;     // Maps to 'desc' - e.g., "Home Win", "Over 2.5", "Draw"// Maps to 'probability' - bookmaker's implied probability
    private boolean isActive;              // Maps to 'isActive' - whether outcome is currently available for betting
    private Integer cashOutAvailable;
    private String providerMarketName;
    private String providerMarketTitle;
    // Human-readable market name (e.g., "Match Winner", "Total Goals")
    private long eventStartTime;  // When the event starts - crucial for filtering stale opportunities
    private SportEnum sportEnum;
    private OutcomeStatus outcomeStatus;
    private int status;
    private String setScore;
    private List<String> gameScore;
    private String period;
    private String matchStatus;
    private String playedSeconds;
    private String marketId;
    private String navigationLink;

}
