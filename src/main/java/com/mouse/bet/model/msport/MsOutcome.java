package com.mouse.bet.model.msport;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class MsOutcome {
    @JsonProperty("id")
    private String id;

    @JsonProperty("description")
    private String description;  // e.g., "Home", "Over 2.5", "Draw"

    @JsonProperty("odds")
    private String odds;

    @JsonProperty("probability")
    private String probability;

    @JsonProperty("isActive")
    private Integer isActive;  // 1 = Active, 0 = Suspended

    /**
     * Check if outcome is active and can be bet on
     */
    public boolean isActive() {
        return isActive != null && isActive == 1;
    }

    /**
     * Check if outcome is suspended
     */
    public boolean isSuspended() {
        return isActive != null && isActive == 0;
    }


}
