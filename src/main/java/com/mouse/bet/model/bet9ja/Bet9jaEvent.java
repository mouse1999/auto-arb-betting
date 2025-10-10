package com.mouse.bet.model.bet9ja;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@NoArgsConstructor
@AllArgsConstructor
public class Bet9jaEvent {
    @JsonProperty("A")  private LiveInplayState liveInplayState; // formerly ASection
    @JsonProperty("O")  private Map<String, String> odds;    // key: market code, value: {v}
    @JsonProperty("AA") private EventHeader eventHeader;
}
