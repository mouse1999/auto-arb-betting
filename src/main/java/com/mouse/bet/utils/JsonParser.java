package com.mouse.bet.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mouse.bet.model.bet9ja.Bet9jaEvent;
import com.mouse.bet.model.msport.MSportEvent;
import com.mouse.bet.model.sporty.SportyEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class JsonParser {

    private static final Pattern ID_PATTERN = Pattern.compile(
            "\"ID\"\\s*:\\s*\"?(\\d+)\"?"
    );

    private static final Pattern EVENT_ID_PATTERN = Pattern.compile(
            "\"eventId\"\\s*:\\s*\"([A-Za-z0-9:_\\-]+)\"",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    // Optional fallback if some payloads only expose sr:match:* under a generic "id"
    private static final Pattern FALLBACK_MATCH_ID_PATTERN = Pattern.compile(
            "\"id\"\\s*:\\s*\"(sr:match:[A-Za-z0-9:_\\-]+)\"",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );


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

    /**
     * This returns list of live event IDs from the json Input
     *
     * @param json The JSON string to extract IDs from
     * @return List of ID values as strings
     */
    public static List<String> extractBet9jaEventIdsAsStrings(String json) {
        List<String> ids = new ArrayList<>();
        Matcher matcher = ID_PATTERN.matcher(json);

        while (matcher.find()) {
            ids.add(matcher.group(1));
        }

        if(ids.isEmpty()) {
            log.info("No IDs found in JSON");
        }
        return ids;
    }

    private List<String> extractEventIdsForSporty(String json) {
        if (json == null || json.isBlank())
            return List.of();

        // Use LinkedHashSet to preserve first-seen order and avoid duplicates
        Set<String> ids = new LinkedHashSet<>();

        Matcher m = EVENT_ID_PATTERN.matcher(json);
        while (m.find()) {
            ids.add(m.group(1));
        }

        // Fallback: sometimes the feed may put sr:match:* under "id" instead of "eventId"
        if (ids.isEmpty()) {
            Matcher m2 = FALLBACK_MATCH_ID_PATTERN.matcher(json);
            while (m2.find()) {
                ids.add(m2.group(1));
            }
        }

        return new ArrayList<>(ids);
    }




}
