package com.mouse.bet.utils;


import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mouse.bet.model.sporty.SportyEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

public class JsonParserTest {
    private ObjectMapper mapper;

    @BeforeEach
    void setup() {
        mapper = new ObjectMapper()
                // tolerate extra fields the POJO may not model yet
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    void extractEventIds_blank_returnsEmpty() {
        assertThat(JsonParser.extractEventIds(null)).isEmpty();
        assertThat(JsonParser.extractEventIds("")).isEmpty();
        assertThat(JsonParser.extractEventIds("   ")).isEmpty();
    }

    @Test
    void extractEventIds_singleEventId_returnsListWithOne() {
        String json = "{ \"eventId\": \"sr:match:63014347\" }";
        List<String> ids = JsonParser.extractEventIds(json);
        assertThat(ids).containsExactly("sr:match:63014347");
    }

    @Test
    void extractEventIds_multipleEventIds_deduplicatesAndPreservesOrder() {
        String json = """
            {
              "data": [
                {"eventId":"sr:match:100"}, {"eventId":"sr:match:200"},
                {"foo":1}, {"eventId":"sr:match:100"}
              ]
            }
            """;
        List<String> ids = JsonParser.extractEventIds(json);
        assertThat(ids).containsExactly("sr:match:100", "sr:match:200");
    }

    @Test
    void extractEventIds_fallback_whenNoEventId_usesGenericIdWithSrMatch() {
        String json = """
            {
              "data": [
                {"id":"sr:match:777"}, {"id":"abc:not-a-match:zzz"}
              ]
            }
            """;
        List<String> ids = JsonParser.extractEventIds(json);
        assertThat(ids).containsExactly("sr:match:777");
    }

    @Test
    void extractEventIds_fallback_notTriggered_ifAtLeastOneEventIdPresent() {
        String json = """
            {
              "data": [
                {"eventId":"sr:match:1"}, {"id":"sr:match:2"}
              ]
            }
            """;
        // eventId present => we don't scan generic id fallback
        List<String> ids = JsonParser.extractEventIds(json);
        assertThat(ids).containsExactly("sr:match:1");
    }

    @Test
    void extractEventIds_nestedAndNewlines_foundAll() {
        String json = """
            {
              "data": {
                "page": 1,
                "items": [
                  { "meta": { "x":1 }, 
                    "event": { "eventId"   :   "sr:match:300" } 
                  },
                  { "eventId":"sr:match:400" }
                ]
              }
            }
            """;
        List<String> ids = JsonParser.extractEventIds(json);
        assertThat(ids).containsExactly("sr:match:300", "sr:match:400");
    }

    @Test
    void extractEventIds_ignoresNonMatchIds() {
        String json = """
            {
              "items": [
                {"id":"not-a-match:123"},
                {"id":"somethingelse"},
                {"identifier":"sr:match:999"}
              ]
            }
            """;
        // No "eventId" and no generic id matching sr:match:* => empty
        List<String> ids = JsonParser.extractEventIds(json);
        assertThat(ids).isEmpty();
    }

    @Test
    void extractEventIds_caseInsensitiveKey_works() {
        String json = "{ \"EVENTID\": \"sr:match:42\" }";
        List<String> ids = JsonParser.extractEventIds(json);
        assertThat(ids).containsExactly("sr:match:42");
    }

    @Test
    void extractEventIds_whitespaceVariations_ok() {
        String json = "{\n \"eventId\"  :   \"sr:match:55\"  \n}";
        List<String> ids = JsonParser.extractEventIds(json);
        assertThat(ids).containsExactly("sr:match:55");
    }

    @Test
    void extractEventIds_handlesLargePayloadQuickly() {
        // Basic smoke test for performance; not asserting time, just correctness on many entries
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < 200; i++) {
            sb.append("{\"eventId\":\"sr:match:").append(i).append("\"},");
        }
        sb.append("{\"foo\":1}]}");
        List<String> ids = JsonParser.extractEventIds(sb.toString());
        assertThat(ids).hasSize(200);
        assertThat(ids.get(0)).isEqualTo("sr:match:0");
        assertThat(ids.get(199)).isEqualTo("sr:match:199");
    }

    @Test
    void deserializeSportyEvent_validSingleEvent_returnsMappedEvent() {
        String json = """
        {
          "bizCode": 10000,
          "message": "0#0",
          "data": {
            "eventId": "sr:match:63014347",
            "homeTeamName": "Shanghai Port FC",
            "awayTeamName": "Machida Zelvia",
            "sport": { "id": "sr:sport:1", "name": "Football" },
            "markets": [
              {
                "id": "1",
                "name": "1X2",
                "outcomes": [
                  { "id": "1", "desc": "Home", "odds": "5.60" },
                  { "id": "2", "desc": "Draw", "odds": "3.95" },
                  { "id": "3", "desc": "Away", "odds": "1.60" }
                ]
              }
            ]
          }
        }
        """;

        SportyEvent ev = JsonParser.deserializeSportyEvent(json, mapper);

        assertThat(ev).isNotNull();
        assertThat(ev.getEventId()).isEqualTo("sr:match:63014347");
        assertThat(ev.getHomeTeamName()).isEqualTo("Shanghai Port FC");
        assertThat(ev.getAwayTeamName()).isEqualTo("Machida Zelvia");

        // If your POJO models sport/markets, you can assert deeper:
        // assertThat(ev.getSport()).isNotNull();
        // assertThat(ev.getSport().getId()).isEqualTo("sr:sport:1");
        // assertThat(ev.getMarkets()).isNotEmpty();
        // assertThat(ev.getMarkets().get(0).getName()).isEqualTo("1X2");
    }

    @Test
    void deserializeSportyEvent_blankOrNull_returnsNull() {
        assertThat(JsonParser.deserializeSportyEvent(null, mapper)).isNull();
        assertThat(JsonParser.deserializeSportyEvent("", mapper)).isNull();
        assertThat(JsonParser.deserializeSportyEvent("   ", mapper)).isNull();
    }

    @Test
    void deserializeSportyEvent_missingDataNode_returnsNull() {
        String json = """
        { "bizCode": 10000, "message": "0#0" }
        """;
        SportyEvent ev = JsonParser.deserializeSportyEvent(json, mapper);
        assertThat(ev).isNull();
    }

    @Test
    void deserializeSportyEvent_dataNull_returnsNull() {
        String json = """
        { "bizCode": 10000, "message": "0#0", "data": null }
        """;
        SportyEvent ev = JsonParser.deserializeSportyEvent(json, mapper);
        assertThat(ev).isNull();
    }

    @Test
    void deserializeSportyEvent_emptyDataObject_returnsNonNullWithNullFields() {
        String json = """
        { "bizCode": 10000, "message": "0#0", "data": {} }
        """;
        SportyEvent ev = JsonParser.deserializeSportyEvent(json, mapper);
        assertThat(ev).isNotNull();
        // Fields may be null/defaults depending on your POJO
        assertThat(ev.getEventId()).isNull();
        assertThat(ev.getHomeTeamName()).isNull();
        assertThat(ev.getAwayTeamName()).isNull();
    }

    @Test
    void deserializeSportyEvent_malformedJson_returnsNull() {
        String json = "{ \"bizCode\": 10000, \"data\": { \"eventId\": \"sr:match:1\" "; // missing closing braces
        SportyEvent ev = JsonParser.deserializeSportyEvent(json, mapper);
        assertThat(ev).isNull();
    }

    @Test
    void deserializeSportyEvent_ignoresUnknownFields_whenConfigured() {
        String json = """
        {
          "bizCode": 10000,
          "message": "0#0",
          "data": {
            "eventId": "sr:match:999",
            "homeTeamName": "Foo",
            "awayTeamName": "Bar",
            "extraFieldWeDontModel": { "deep": true }
          }
        }
        """;
        SportyEvent ev = JsonParser.deserializeSportyEvent(json, mapper);
        assertThat(ev).isNotNull();
        assertThat(ev.getEventId()).isEqualTo("sr:match:999");
        assertThat(ev.getHomeTeamName()).isEqualTo("Foo");
        assertThat(ev.getAwayTeamName()).isEqualTo("Bar");
    }
}
