package com.mouse.bet.model.sporty;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Builder
public class Market {
    private String id;
    private String specifier; // Present in some markets
    private int product;
    private String desc;
    private int status;
    private Integer cashOutStatus; // Can be null or not present
    private String group;
    private String groupId;
    private String marketGuide;
    private String title;
    private String name;
    private int favourite;
    private List<Outcome> outcomes; // Assuming 'Outcome' is a defined class
    private int farNearOdds;
    private String sourceType;
    private String availableScore;
    private long lastOddsChangeTime;
    private Boolean banned; // Boolean type based on JSON value
    private String suspendedReason; // Can be null or not present

}
