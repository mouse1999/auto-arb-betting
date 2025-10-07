package com.mouse.bet.model.msport;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MSportEvent {
    private List<List<String>> allScores;
    private String awayTeam;
    private String awayTeamIcon;
    private String awayTeamId;
    private String bookingStatus;
    private String category;
    private String categoryIcon;
    private String categoryId;
    private String eventId;
    private int hasLive;
    private int hasLiveCoverage;
    private int hasLiveWidget;
    private int hasVoucher;
    private String homeTeam;
    private String homeTeamIcon;
    private String homeTeamId;
    // Note: 'howToPlay' is null, so it's best modeled as an object (e.g., Object or String)
    private Object howToPlay;
    private List<MsMarket> markets;
}
