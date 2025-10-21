package com.mouse.bet.model.sporty;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

@NoArgsConstructor
@Setter
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
@Builder
@AllArgsConstructor
public class Category {
    private String id;
    private String name;
    private TournamentInfo tournament; // Renamed to avoid conflict with the top-level Tournament class

}

