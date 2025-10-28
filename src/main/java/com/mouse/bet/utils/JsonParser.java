package com.mouse.bet.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mouse.bet.model.bet9ja.Bet9jaEvent;
import com.mouse.bet.model.msport.MSportEvent;
import com.mouse.bet.model.sporty.SportyEvent;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

@Slf4j
@NoArgsConstructor
public class JsonParser {

    private static final Pattern ID_PATTERN = Pattern.compile(
            "\"ID\"\\s*:\\s*(?:\"(\\d+)\"|(\\d+))"
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
    private static final int BUFFER_SIZE = 32768;


    public static SportyEvent deserializeSportyEvent(String jsonString, ObjectMapper objectMapper) {
        SportyEvent event = null;

        try {
            JsonNode rootNode = objectMapper.readTree(jsonString);
            JsonNode dataNode = rootNode.get("data");

            if (dataNode == null || dataNode.isNull()) {
                log.warn("No 'data' node found in JSON");
                return event;
            }
            event = objectMapper.treeToValue(dataNode, SportyEvent.class);

        } catch (Exception e) {
            log.error("Error deserializing JSON: {}", e.getMessage(), e);
        }

        return event;
    }


    public static Bet9jaEvent deserializeBet9jaEvent(String json, ObjectMapper objectMapper){
        if (json == null || json.isBlank() || objectMapper == null) return null;
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null) return null;
            JsonNode d = root.path("D");
            if (d.isMissingNode() || d.isNull()) return null;
            return objectMapper.readerFor(Bet9jaEvent.class).readValue(d);
        } catch (Exception e) {
            // Log if you want; returning null keeps the callsite simple
            return null;
        }

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

        if (json == null) return List.of();

        Set<String> ids = new LinkedHashSet<>();

        Matcher matcher = ID_PATTERN.matcher(json);

        while (matcher.find()) {
            String val = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (val != null) {
                ids.add(val);
            }
        }

        return new ArrayList<>(ids);
    }

    public static List<String> extractEventIds(String json) {
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

    /**
     * Ultra-fast GZIP decompression for large responses
     * Optimized for speed with minimal overhead
     */
    public static String decompressGzipToJson(byte[] gzipBytes) throws IOException {
        if (gzipBytes == null || gzipBytes.length == 0) {
            return "";
        }

        // Estimate decompressed size
        int estimatedSize = gzipBytes.length * 4;

        // 32KB buffer for maximum throughput
        byte[] buffer = new byte[BUFFER_SIZE];

        try (GZIPInputStream gzipStream = new GZIPInputStream(
                new ByteArrayInputStream(gzipBytes), BUFFER_SIZE);
             ByteArrayOutputStream output = new ByteArrayOutputStream(estimatedSize)) {

            int len;
            while ((len = gzipStream.read(buffer)) > 0) {
                output.write(buffer, 0, len);
            }

            return output.toString(StandardCharsets.UTF_8);

        } catch (IOException e) {
            log.error("GZIP decompression failed: {}", e.getMessage());
            throw e;
        }
    }




}
