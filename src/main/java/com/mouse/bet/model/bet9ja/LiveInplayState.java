package com.mouse.bet.model.bet9ja;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@NoArgsConstructor
@AllArgsConstructor
class LiveInplayState {
    @JsonProperty("A")  private boolean active;
    @JsonProperty("E")  private boolean enabled;
    @JsonProperty("MKNUM") private int marketCount;
    @JsonProperty("STREAM") private int streamCode;
    @JsonProperty("EXTID") private String externalId;
    @JsonProperty("ISSTREAMINGVIDEO") private boolean streamingVideo;
    @JsonProperty("ES") private String eventStatus;       // e.g., "1st Half"
    @JsonProperty("BS") private String bookStatus;        // e.g., "1st Half"
    @JsonProperty("R")  private MatchScore score;         // nested score/result
    @JsonProperty("WID") private Long widgetId;           // nullable
    @JsonProperty("T")  private int clockMinutes;         // e.g., 24
    @JsonProperty("PRV") private int providerCode;
    @JsonProperty("ID") private long id;                  // event id (duplicate of AA.ID)
    @JsonProperty("STREAMPRV") private int streamProviderCode;
    @JsonProperty("T2") private String clockLabel;        // e.g., "24'"
}