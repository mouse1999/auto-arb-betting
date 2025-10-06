package com.mouse.bet.model.sporty;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@Setter
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class Category {
    private String id;
    private String name;
    private TournamentInfo tournament; // Renamed to avoid conflict with the top-level Tournament class

}

