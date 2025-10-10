package com.mouse.bet.model.msport;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class MSportEvent {
    @JsonProperty("eventId")
    private String eventId;

    @JsonProperty("homeTeam")
    private String homeTeam;

    @JsonProperty("awayTeam")
    private String awayTeam;

    @JsonProperty("homeTeamId")
    private String homeTeamId;

    @JsonProperty("awayTeamId")
    private String awayTeamId;

    @JsonProperty("homeTeamIcon")
    private String homeTeamIcon;

    @JsonProperty("awayTeamIcon")
    private String awayTeamIcon;

    @JsonProperty("sport")
    private String sport;

    @JsonProperty("sportId")
    private String sportId;

    @JsonProperty("category")
    private String category;

    @JsonProperty("categoryId")
    private String categoryId;

    @JsonProperty("categoryIcon")
    private String categoryIcon;

    @JsonProperty("tournament")
    private String tournament;

    @JsonProperty("tournamentId")
    private String tournamentId;

    @JsonProperty("seasonId")
    private String seasonId;

    @JsonProperty("seasonName")
    private String seasonName;

    @JsonProperty("startTime")
    private Long startTime;  // Unix timestamp in milliseconds

    @JsonProperty("status")
    private Integer status;  // 0 = Not started, 1 = Live, 2 = Ended

    @JsonProperty("statusDescription")
    private String statusDescription;

    @JsonProperty("bookingStatus")
    private String bookingStatus;

    @JsonProperty("scoreOfWholeMatch")
    private String scoreOfWholeMatch;

    @JsonProperty("allScores")
    private List<List<String>> allScores;  // [[period, score], ...]

    @JsonProperty("playedTime")
    private String playedTime;

    @JsonProperty("totalPlayedTime")
    private String totalPlayedTime;

    @JsonProperty("stoppageTime")
    private String stoppageTime;

    @JsonProperty("stoppagePlayedTime")
    private String stoppagePlayedTime;

    @JsonProperty("sectionRemainTime")
    private String sectionRemainTime;

    @JsonProperty("scoreInCurrentSection")
    private String scoreInCurrentSection;

    @JsonProperty("scoreOfSection")
    private String scoreOfSection;

    @JsonProperty("soccerCornerScore")
    private String soccerCornerScore;

    @JsonProperty("soccerBookingsScore")
    private String soccerBookingsScore;

    @JsonProperty("roundType")
    private String roundType;

    @JsonProperty("roundNumber")
    private Integer roundNumber;

    @JsonProperty("hasLive")
    private Integer hasLive;

    @JsonProperty("hasLiveCoverage")
    private Integer hasLiveCoverage;

    @JsonProperty("hasLiveWidget")
    private Integer hasLiveWidget;

    @JsonProperty("hasVoucher")
    private Integer hasVoucher;

    @JsonProperty("howToPlay")
    private String howToPlay;

    @JsonProperty("server")
    private String server;

    @JsonProperty("videoInfo")
    private String videoInfo;

    @JsonProperty("marketsCount")
    private Integer marketsCount;

    @JsonProperty("markets")
    private List<MsMarket> markets;


}
