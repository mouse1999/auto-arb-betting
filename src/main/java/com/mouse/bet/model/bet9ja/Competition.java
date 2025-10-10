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
public class Competition {
    @JsonProperty("SPORT") private String sportName;     // "Soccer"
    @JsonProperty("SGID") private String sportGroupKey;  // "3000001_43143"
    @JsonProperty("G_PRIORITY") private int groupPriority;
    @JsonProperty("DS") private String displayName;      // league name
    @JsonProperty("SID") private long sportId;
    @JsonProperty("AP") private int availabilityPolicy;
    @JsonProperty("BTID") private String backendTournamentId;
    @JsonProperty("S_PRIORITY") private int sportPriority;
    @JsonProperty("INT_SID") private long internalSportId;
    @JsonProperty("CATEGORY") private String categoryName;
    @JsonProperty("PARENT") private String parent;       // null in sample
    @JsonProperty("ID") private long id;                 // competition/group id
    @JsonProperty("PL") private String pl;               // null in sample
}
