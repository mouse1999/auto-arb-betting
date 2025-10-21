package com.mouse.bet.model.bet9ja;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchScore {
    @JsonProperty("SS") private List<String> periodScores; // empty in sample
    @JsonProperty("S")  private String scoreline;          // "0-0"
    @JsonProperty("EXTRA") private String extraInfo;
    @JsonProperty("SRV")  private int serve;               // -1 in soccer feeds
    @JsonProperty("GS")   private String gameState;
}