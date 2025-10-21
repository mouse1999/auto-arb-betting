package com.mouse.bet.model.bet9ja;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventHeader {
    @JsonProperty("INCTYPE") private int incrementType;
    @JsonProperty("ST") private int stateCode;
    @JsonProperty("GID") private long groupId;
    @JsonProperty("C") private int categoryCode;
    @JsonProperty("STARTDATE") private String startDate;          // keep as String, or map to Instant
    @JsonProperty("STREAM") private int streamCode;
    @JsonProperty("EXTID") private String externalId;
    @JsonProperty("SGID") private String sportGroupKey;           // e.g., "3000001_43143"
    @JsonProperty("DS") private String displayName;               // e.g., "Tahta - El Qusiya SC"
    @JsonProperty("SID") private long sportId;                    // 3000001
    @JsonProperty("WID") private Long widgetId;                   // nullable
    @JsonProperty("PRV") private int providerCode;
    @JsonProperty("ID") private long id;                          // main event id
    @JsonProperty("PL") private int priorityLevel;
    @JsonProperty("STREAMPRV") private int streamProviderCode;
    @JsonProperty("TYPE") private int typeCode;
    @JsonProperty("ENDVISIBILITYDATE") private String endVisibilityDate; // keep as String, or Instant
    @JsonProperty("ISSTREAMINGVIDEO") private boolean streamingVideo;
    @JsonProperty("COMP") private Competition competition;        // league/competition info
    @JsonProperty("GN") private String groupName;                 // e.g., "Egypt - Egypt Second Division B"
    @JsonProperty("GP") private int groupPriority;
}

