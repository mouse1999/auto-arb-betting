package com.mouse.bet.model.sporty;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Builder
public class Outcome {
    private String id;
    private String odds;
    private String probability;
    private String voidProbability;
    private int isActive;
    private String desc;
    private Integer cashOutIsActive; // Can be null or not present
    private Integer isWinning; // Can be null or not present
    private Integer refundFactor; // Can be null or not present


}
