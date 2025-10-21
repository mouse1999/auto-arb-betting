package com.mouse.bet.service;

import com.mouse.bet.detector.ArbDetector;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.enums.MarketCategory;
import com.mouse.bet.enums.SportEnum;
import com.mouse.bet.model.MarketMeta;
import com.mouse.bet.model.NormalizedEvent;
import com.mouse.bet.model.NormalizedMarket;
import com.mouse.bet.model.NormalizedOutcome;
import com.mouse.bet.model.msport.MSportEvent;
import com.mouse.bet.model.msport.MsMarket;
import com.mouse.bet.model.msport.MsOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MSportService Tests")
class MSportServiceTest {

    @Mock
    private ArbDetector arbDetector;

    @Mock
    private TeamAliasService teamAliasService;

    @InjectMocks
    private MSportService mSportService;

    private MSportEvent testEvent;
    private MsMarket testMarket;
    private MsOutcome testOutcome;

    @BeforeEach
    void setUp() {
        testOutcome = MsOutcome.builder()
                .id("1")
                .description("Home")
                .odds("2.50")
                .isActive(1)
                .probability("0.40")
                .build();

        testMarket = MsMarket.builder()
                .id(1)
                .name("1x2")
                .description("Match Result")
                .title("1,X,2")
                .group("Main")
                .specifiers("")
                .status(0)
                .outcomes(List.of(testOutcome))
                .build();

        testEvent = MSportEvent.builder()
                .eventId("sr:match:12345")
                .homeTeam("Manchester United")
                .awayTeam("Liverpool")
                .tournament("Premier League")
                .sport("Soccer")
                .startTime(System.currentTimeMillis())
                .status(0)
                .scoreOfWholeMatch("0:0")
                .allScores(List.of(List.of("HT", "0:0"), List.of("FT", "0:0")))
                .playedTime("45'")
                .markets(List.of(testMarket))
                .build();
    }

    @Test
    @DisplayName("convertToNormalEvent_validEvent_returnsNormalizedEvent")
    void convertToNormalEvent_validEvent_returnsNormalizedEvent() {

        when(teamAliasService.canonicalOrSelf(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));




    NormalizedEvent result = mSportService.convertToNormalEvent(testEvent);
        NormalizedMarket market = result.getMarkets().get(0);
        NormalizedOutcome outcome = market.getOutcomes().get(0);


        assertNotNull(result);
        assertEquals("Manchester United", result.getHomeTeam());
        assertEquals("Liverpool", result.getAwayTeam());
        assertEquals("Premier League", result.getLeague());
        assertEquals(BookMaker.SPORTY_BET, result.getBookie());
        assertEquals("Football|Manchester United|Liverpool", result.getEventId());
        assertEquals("HOME", outcome.getOutcomeDescription());
        assertEquals("1x2", outcome.getProviderMarketName());
        assertEquals(MarketCategory.MATCH_RESULT, outcome.getMarketType().getCategory());
        assertNotNull(result.getMarkets());
        verify(teamAliasService, times(2)).canonicalOrSelf(anyString());
    }

    @Test
    @DisplayName("convertToNormalEvent_nullEvent_throwsIllegalArgumentException")
    void convertToNormalEvent_nullEvent_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> mSportService.convertToNormalEvent(null));
    }

    @Test
    @DisplayName("convertToNormalEvent_eventWithNoMarkets_returnsEventWithEmptyMarketList")
    void convertToNormalEvent_eventWithNoMarkets_returnsEventWithEmptyMarketList() {
        testEvent.setMarkets(Collections.emptyList());
        when(teamAliasService.canonicalOrSelf(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));


        NormalizedEvent result = mSportService.convertToNormalEvent(testEvent);

        assertNotNull(result);
        assertTrue(result.getMarkets().isEmpty());
    }

    @Test
    @DisplayName("convertToNormalEvent_footballEvent_determinesSportCorrectly")
    void convertToNormalEvent_footballEvent_determinesSportCorrectly() {
        testEvent.setSport("Soccer");
        when(teamAliasService.canonicalOrSelf(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));


        NormalizedEvent result = mSportService.convertToNormalEvent(testEvent);

        assertEquals(SportEnum.FOOTBALL, result.getSportEnum());
    }

    @Test
    @DisplayName("convertToNormalEvent_basketballEvent_determinesSportCorrectly")
    void convertToNormalEvent_basketballEvent_determinesSportCorrectly() {
        testEvent.setSport("Basketball");
        when(teamAliasService.canonicalOrSelf(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NormalizedEvent result = mSportService.convertToNormalEvent(testEvent);

        assertEquals(SportEnum.BASKETBALL, result.getSportEnum());
    }

    @Test
    @DisplayName("convertToNormalEvent_tableTennisEvent_determinesSportCorrectly")
    void convertToNormalEvent_tableTennisEvent_determinesSportCorrectly() {
        testEvent.setSport("Table Tennis");
        when(teamAliasService.canonicalOrSelf(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NormalizedEvent result = mSportService.convertToNormalEvent(testEvent);

        assertEquals(SportEnum.TABLE_TENNIS, result.getSportEnum());
    }

    @Test
    @DisplayName("convertToNormalEvent_unknownSport_defaultsToFootball")
    void convertToNormalEvent_unknownSport_defaultsToFootball() {
        testEvent.setSport("Cricket");
        when(teamAliasService.canonicalOrSelf(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NormalizedEvent result = mSportService.convertToNormalEvent(testEvent);
        assertEquals(SportEnum.FOOTBALL, result.getSportEnum());
    }


    @Test
    @DisplayName("convertToNormalEvent_teamAliasApplied_evenWithNullMarkets")
    void convertToNormalEvent_teamAliasApplied_evenWithNullMarkets() {
        testEvent.setMarkets(null);
        when(teamAliasService.canonicalOrSelf(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NormalizedEvent result = mSportService.convertToNormalEvent(testEvent);
        assertEquals("Manchester United", result.getHomeTeam());
        assertEquals("Liverpool", result.getAwayTeam());
        assertTrue(result.getMarkets().isEmpty());
    }

    @Test
    @DisplayName("convertMarketsToOddsMap_validMarkets_returnsOddsMap")
    void convertMarketsToOddsMap_validMarkets_returnsOddsMap() {
        Map<String, String> result = mSportService.convertMarketsToOddsMap(List.of(testMarket));
        assertFalse(result.isEmpty());
        assertTrue(result.containsValue("2.50"));
    }

    @Test
    @DisplayName("convertMarketsToOddsMap_nullMarkets_returnsEmptyMap")
    void convertMarketsToOddsMap_nullMarkets_returnsEmptyMap() {
        Map<String, String> result = mSportService.convertMarketsToOddsMap(null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("convertMarketsToOddsMap_emptyMarketsList_returnsEmptyMap")
    void convertMarketsToOddsMap_emptyMarketsList_returnsEmptyMap() {
        Map<String, String> result = mSportService.convertMarketsToOddsMap(Collections.emptyList());
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("convertMarketsToOddsMap_marketWithInvalidOdds_filtersOutInvalidOdds")
    void convertMarketsToOddsMap_marketWithInvalidOdds_filtersOutInvalidOdds() {
        MsOutcome invalidOutcome = MsOutcome.builder()
                .id("2")
                .description("Invalid")
                .odds("-1.00")
                .isActive(1)
                .build();

        MsMarket marketWithInvalid = MsMarket.builder()
                .id(1)
                .name("Test")
                .specifiers("")
                .outcomes(List.of(testOutcome, invalidOutcome))
                .build();

        Map<String, String> result = mSportService.convertMarketsToOddsMap(List.of(marketWithInvalid));

        assertFalse(result.containsValue("-1.00"));
        assertTrue(result.containsValue("2.50"));
    }

    @Test
    @DisplayName("convertMarketsToOddsMap_multipleOutcomesSameKey_keepsFirstOdds")
    void convertMarketsToOddsMap_multipleOutcomesSameKey_keepsFirstOdds() {
        MsOutcome outcome1 = MsOutcome.builder().id("1").description("Home").odds("2.50").isActive(1).build();
        MsOutcome outcome2 = MsOutcome.builder().id("1").description("Home").odds("2.60").isActive(1).build();

        MsMarket market = MsMarket.builder()
                .id(1)
                .name("1x2")
                .specifiers("")
                .outcomes(List.of(outcome1, outcome2))
                .build();

        Map<String, String> result = mSportService.convertMarketsToOddsMap(List.of(market));
        assertEquals(1, result.size());
        assertTrue(result.containsValue("2.50")); // first wins
    }

    @Test
    @DisplayName("convertMarketsToOddsMap_multipleMarkets_aggregatesAllValid")
    void convertMarketsToOddsMap_multipleMarkets_aggregatesAllValid() {
        MsOutcome a = MsOutcome.builder().id("1").description("Home").odds("2.01").isActive(1).build();
        MsOutcome b = MsOutcome.builder().id("2").description("Away").odds("3.10").isActive(1).build();
        MsMarket m1 = MsMarket.builder().id(10).name("1x2").specifiers("").outcomes(List.of(a, b)).build();

        MsOutcome c = MsOutcome.builder().id("3").description("Over 2.5").odds("1.90").isActive(1).build();
        MsOutcome d = MsOutcome.builder().id("4").description("Under 2.5").odds("1.95").isActive(1).build();
        MsMarket m2 = MsMarket.builder().id(11).name("Over/Under").specifiers("total=2.5").outcomes(List.of(c, d)).build();

        Map<String, String> result = mSportService.convertMarketsToOddsMap(List.of(m1, m2));
        assertEquals(4, result.size());
        assertTrue(result.values().containsAll(List.of("2.01", "3.10", "1.90", "1.95")));
    }

    @Test
    @DisplayName("mapOutcomeStatus_validMarkets_returnsStatusMap")
    void mapOutcomeStatus_validMarkets_returnsStatusMap() {
        Map<String, Integer> result = mSportService.mapOutcomeStatus(List.of(testMarket));
        assertFalse(result.isEmpty());
        assertTrue(result.containsValue(1));
    }

    @Test
    @DisplayName("mapOutcomeStatus_nullMarkets_returnsEmptyMap")
    void mapOutcomeStatus_nullMarkets_returnsEmptyMap() {
        Map<String, Integer> result = mSportService.mapOutcomeStatus(null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("mapOutcomeStatus_activeAndInactiveOutcomes_mapsBothCorrectly")
    void mapOutcomeStatus_activeAndInactiveOutcomes_mapsBothCorrectly() {
        MsOutcome activeOutcome = MsOutcome.builder().id("1").description("Home").odds("2.50").isActive(1).build();
        MsOutcome inactiveOutcome = MsOutcome.builder().id("2").description("Away").odds("3.00").isActive(0).build();

        MsMarket market = MsMarket.builder()
                .id(1)
                .name("Test")
                .specifiers("")
                .outcomes(List.of(activeOutcome, inactiveOutcome))
                .build();

        Map<String, Integer> result = mSportService.mapOutcomeStatus(List.of(market));
        assertTrue(result.containsValue(1));
        assertTrue(result.containsValue(0));
    }

    @Test
    @DisplayName("mapOutcomeStatus_excludesOutcomesWithInvalidOdds")
    void mapOutcomeStatus_excludesOutcomesWithInvalidOdds() {
        MsOutcome valid = MsOutcome.builder().id("1").description("Home").odds("2.00").isActive(1).build();
        MsOutcome invalid = MsOutcome.builder().id("2").description("Draw").odds("abc").isActive(0).build();

        MsMarket market = MsMarket.builder().id(1).name("1x2").specifiers("").outcomes(List.of(valid, invalid)).build();

        Map<String, Integer> status = mSportService.mapOutcomeStatus(List.of(market));
        assertEquals(1, status.size());
        assertTrue(status.containsValue(1));
    }

    @Test
    @DisplayName("mapOutcomeStatus_duplicateKeys_keepsFirstStatus")
    void mapOutcomeStatus_duplicateKeys_keepsFirstStatus() {
        MsOutcome a = MsOutcome.builder().id("1").description("Home").odds("1.50").isActive(1).build();
        MsOutcome b = MsOutcome.builder().id("1").description("Home").odds("1.60").isActive(0).build();

        MsMarket market = MsMarket.builder().id(1).name("1x2").specifiers("").outcomes(List.of(a, b)).build();

        Map<String, Integer> result = mSportService.mapOutcomeStatus(List.of(market));
        assertEquals(1, result.size());
        assertTrue(result.containsValue(1)); // first wins
    }


    @Test
    @DisplayName("buildMetaMap_validMarkets_returnsMetaMap")
    void buildMetaMap_validMarkets_returnsMetaMap() {
        Map<String, ?> result = mSportService.buildMetaMap(List.of(testMarket));
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("buildMetaMap_nullMarkets_returnsEmptyMap")
    void buildMetaMap_nullMarkets_returnsEmptyMap() {
        Map<String, ?> result = mSportService.buildMetaMap(null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("buildMetaMap_marketWithMultipleOutcomes_createsMetaForEachOutcome")
    void buildMetaMap_marketWithMultipleOutcomes_createsMetaForEachOutcome() {
        MsOutcome outcome1 = MsOutcome.builder().id("1").description("Home").odds("2.50").isActive(1).build();
        MsOutcome outcome2 = MsOutcome.builder().id("2").description("Away").odds("3.00").isActive(1).build();

        MsMarket market = MsMarket.builder()
                .id(1)
                .name("1x2")
                .title("1,X,2")
                .group("Main")
                .specifiers("")
                .outcomes(List.of(outcome1, outcome2))
                .build();

        Map<String, ?> result = mSportService.buildMetaMap(List.of(market));
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("buildMetaMap_duplicateKeys_keepsFirstMeta")
    void buildMetaMap_duplicateKeys_keepsFirstMeta() {
        MsOutcome a = MsOutcome.builder().id("1").description("Over 2.5").odds("1.90").isActive(1).build();
        MsOutcome b = MsOutcome.builder().id("2").description("Over  2.5").odds("2.05").isActive(1).build();

        MsMarket market = MsMarket.builder()
                .id(18)
                .name("Over/Under")
                .specifiers("total=2.5")
                .outcomes(List.of(a, b))
                .build();

        Map<String, ?> result = mSportService.buildMetaMap(List.of(market));
        assertEquals(1, result.size());
    }


    @Test
    @DisplayName("normalizeMarkets_nullRawOdds_returnsEmptyList")
    void normalizeMarkets_nullRawOdds_returnsEmptyList() {
        List<NormalizedMarket> result = mSportService.normalizeMarkets(
                null, Map.of(), Map.of(), BookMaker.M_SPORT,
                "event123", "Home", "Away", "League", testEvent
        );
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("normalizeMarkets_emptyRawOdds_returnsEmptyList")
    void normalizeMarkets_emptyRawOdds_returnsEmptyList() {
        List<NormalizedMarket> result = mSportService.normalizeMarkets(
                Map.of(), Map.of(), Map.of(), BookMaker.M_SPORT,
                "event123", "Home", "Away", "League", testEvent
        );
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("normalizeMarkets_withUnrecognizedProviderKeys_returnsEmpty")
    void normalizeMarkets_withUnrecognizedProviderKeys_returnsEmpty() {
        Map<String, String> rawOdds = Map.of(
                "UNKNOWN|BAD|KEY1", "2.0",
                "RANDOM/KEY2", "1.5"
        );
        Map<String, Integer> statusMap = Map.of(
                "UNKNOWN|BAD|KEY1", 1,
                "RANDOM/KEY2", 0
        );
        Map<String, MarketMeta> metaMap = Map.of(
                "UNKNOWN|BAD|KEY1", new MarketMeta("","", "", "", "1")
        );

        List<NormalizedMarket> result = mSportService.normalizeMarkets(
                rawOdds, statusMap, metaMap, BookMaker.M_SPORT,
                "EVT-42", "Home", "Away", "League", testEvent
        );
        assertNotNull(result);
        assertTrue(result.isEmpty()); // keys will be dropped by MSportMarketType.safeFromProviderKey
    }

    @Test
    @DisplayName("generateProviderKey_marketWithSpecifier_includesSpecifierInKey")
    void generateProviderKey_marketWithSpecifier_includesSpecifierInKey() {
        MsOutcome outcome = MsOutcome.builder()
                .id("12")
                .description("Over 2.5")
                .odds("1.85")
                .isActive(1)
                .build();

        MsMarket market = MsMarket.builder()
                .id(18)
                .name("Over/Under")
                .specifiers("total=2.5")
                .outcomes(List.of(outcome))
                .build();

        Map<String, String> result = mSportService.convertMarketsToOddsMap(List.of(market));
        assertFalse(result.isEmpty());
        assertTrue(result.keySet().stream().anyMatch(key -> key.contains("total=2.5")));
    }

    @Test
    @DisplayName("generateProviderKey_outcomeDescriptionWithSpaces_normalizesToUnderscores")
    void generateProviderKey_outcomeDescriptionWithSpaces_normalizesToUnderscores() {
        MsOutcome outcome = MsOutcome.builder()
                .id("74")
                .description("Both Teams To Score")
                .odds("2.20")
                .isActive(1)
                .build();

        MsMarket market = MsMarket.builder()
                .id(29)
                .name("BTTS")
                .specifiers("")
                .outcomes(List.of(outcome))
                .build();

        Map<String, String> result = mSportService.convertMarketsToOddsMap(List.of(market));
        assertFalse(result.isEmpty());
        assertTrue(result.keySet().stream().anyMatch(key -> key.contains("BOTH_TEAMS_TO_SCORE")));
    }


    @Nested
    @DisplayName("Score/Period null-safety")
    class ScoreNullSafety {

        @Test
        @DisplayName("scores=null handled")
        void scoresNullHandled() {
            testEvent.setAllScores(null);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");
            NormalizedEvent result = mSportService.convertToNormalEvent(testEvent);
            assertNotNull(result);
            assertTrue(result.getMarkets() != null);
        }

        @Test
        @DisplayName("scores=empty handled")
        void scoresEmptyHandled() {
            testEvent.setAllScores(Collections.emptyList());
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");
            NormalizedEvent result = mSportService.convertToNormalEvent(testEvent);
            assertNotNull(result);
        }

        @Test
        @DisplayName("scores single entry handled")
        void scoresSingleEntryHandled() {
            testEvent.setAllScores(List.of(List.of("HT", "1:0")));
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");
            NormalizedEvent result = mSportService.convertToNormalEvent(testEvent);
            assertNotNull(result);
        }

        @Test
        @DisplayName("scores two entries handled")
        void scoresTwoEntriesHandled() {
            testEvent.setAllScores(List.of(List.of("HT", "1:0"), List.of("FT", "2:1")));
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");
            NormalizedEvent result = mSportService.convertToNormalEvent(testEvent);
            assertNotNull(result);
        }
    }

    @Test
    @DisplayName("convertToNormalEvent_fullWorkflow_producesEventAndDoesNotThrow")
    void convertToNormalEvent_fullWorkflow_producesEventAndDoesNotThrow() {
        MsOutcome homeOutcome = MsOutcome.builder()
                .id("1").description("Home").odds("2.10").isActive(1).probability("0.45").build();

        MsOutcome awayOutcome = MsOutcome.builder()
                .id("3").description("Away").odds("3.50").isActive(1).probability("0.28").build();

        MsMarket matchWinnerMarket = MsMarket.builder()
                .id(1).name("1x2").description("Match Winner")
                .title("1,X,2").group("Main").specifiers("")
                .status(0)
                .outcomes(List.of(homeOutcome, awayOutcome))
                .build();

        testEvent.setMarkets(List.of(matchWinnerMarket));

        when(teamAliasService.canonicalOrSelf("Manchester United")).thenReturn("Man Utd");
        when(teamAliasService.canonicalOrSelf("Liverpool")).thenReturn("Liverpool");

        NormalizedEvent result = mSportService.convertToNormalEvent(testEvent);

        assertNotNull(result);
        assertEquals("Man Utd", result.getHomeTeam());
        assertEquals("Liverpool", result.getAwayTeam());
        assertEquals("Man Utd vs Liverpool", result.getEventName());
        assertNotNull(result.getMarkets());
    }



    @Test
    @DisplayName("convertToNormalEvent_teamNamesWithSpecialCharacters_handlesCorrectly")
    void convertToNormalEvent_teamNamesWithSpecialCharacters_handlesCorrectly() {
        testEvent.setHomeTeam("FC Bayern München");
        testEvent.setAwayTeam("Inter Milão");

        when(teamAliasService.canonicalOrSelf("FC Bayern München")).thenReturn("Bayern");
        when(teamAliasService.canonicalOrSelf("Inter Milão")).thenReturn("Inter");

        NormalizedEvent result = mSportService.convertToNormalEvent(testEvent);

        assertEquals("Bayern", result.getHomeTeam());
        assertEquals("Inter", result.getAwayTeam());
    }
}
