package com.mouse.bet.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mouse.bet.model.Bet9jaEvent;
import com.mouse.bet.model.MSportEvent;
import com.mouse.bet.model.sporty.SportyEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class JsonParser {

    public static List<SportyEvent> deserializeSportyEvents(String jsonString, ObjectMapper objectMapper){
        List<SportyEvent> events = new ArrayList<>();

        try {
            JsonNode rootNode = objectMapper.readTree(jsonString);
            JsonNode dataNode = rootNode.get("data");

            if (dataNode != null && dataNode.isArray()) {
                for (JsonNode tournamentNode : dataNode) {
                    JsonNode eventsNode = tournamentNode.get("events");
                    if (eventsNode != null && eventsNode.isArray()) {
                        for (JsonNode eventNode : eventsNode) {
                            SportyEvent event = objectMapper.treeToValue(eventNode, SportyEvent.class);
                            events.add(event);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Error deserializing JSON: " + e.getMessage());
        }

        return events;

    }

    public static Bet9jaEvent deserializeBet9jaEvent(){
        return null;

    }

    public static MSportEvent deserializeMSportEvent(){
        return null;

    }
}
