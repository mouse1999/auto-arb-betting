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
public class MsMarket {
    @JsonProperty("id")
    private Integer id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("title")
    private String title;

    @JsonProperty("group")
    private String group;  // e.g., "Main", "Goals", "Specials"

    @JsonProperty("specifiers")
    private String specifiers;  // e.g., "total=2.5", "hcp=-0.5"

    @JsonProperty("status")
    private Integer status;  // 0 = Active, 1 = Suspended, etc.

    @JsonProperty("priority")
    private Integer priority;

    @JsonProperty("product")
    private Integer product;

    @JsonProperty("presentationType")
    private Integer presentationType;

    @JsonProperty("isShown")
    private Integer isShown;

    @JsonProperty("isFavourite")
    private Integer isFavourite;

    @JsonProperty("userFavourite")
    private Integer userFavourite;

    @JsonProperty("expand")
    private Integer expand;

    @JsonProperty("betAssistWidget")
    private String betAssistWidget;

    @JsonProperty("guidance")
    private String guidance;

//    @JsonProperty("marketLabels")
//    private List<String> marketLabels;

    @JsonProperty("relatedMarketId")
    private String relatedMarketId;

    @JsonProperty("relatedSpecifiers")
    private String relatedSpecifiers;

    @JsonProperty("outcomes")
    private List<MsOutcome> outcomes;

    /**
     * Check if market is active
     */
    public boolean isActive() {
        return status != null && status == 0;
    }

    /**
     * Check if market is suspended
     */
    public boolean isSuspended() {
        return status != null && status == 1;
    }

    /**
     * Get full market identifier with specifiers
     */
    public String getFullMarketId() {
        if (specifiers == null || specifiers.isBlank()) {
            return String.valueOf(id);
        }
        return id + "|" + specifiers;
        //TODO
    }

}
