package com.mouse.bet.model.bet9ja;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.mouse.bet.deserializer.OddsMapDeserializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Bet9jaEvent {
    @JsonProperty("A")  private LiveInplayState liveInplayState; // formerly ASection
    @JsonProperty("O")
    @JsonDeserialize(using = OddsMapDeserializer.class)
    private Map<String, String> odds;    // key: market code, value: {v}
    @JsonProperty("AA") private EventHeader eventHeader;
}
