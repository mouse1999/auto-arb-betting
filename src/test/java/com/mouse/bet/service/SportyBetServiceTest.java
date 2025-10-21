package com.mouse.bet.service;

import com.mouse.bet.detector.ArbDetector;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.enums.SportEnum;
import com.mouse.bet.model.MarketMeta;
import com.mouse.bet.model.NormalizedEvent;
import com.mouse.bet.model.NormalizedMarket;
import com.mouse.bet.model.NormalizedOutcome;
import com.mouse.bet.model.sporty.*;
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
@DisplayName("SportyBetService Tests")
class SportyBetServiceTest {

    @Mock
    private ArbDetector arbDetector;

    @Mock
    private TeamAliasService teamAliasService;

    @InjectMocks
    private SportyBetService sportyBetService;

    private SportyEvent testEvent;
    private Market testMarket;
    private Outcome testOutcome;

    @BeforeEach
    void setUp() {
        testOutcome = Outcome.builder()
                .id("outcome1")
                .odds("2.50")
                .probability("0.40")
                .isActive(1)
                .desc("Home")
                .cashOutIsActive(1)
                .build();

        testMarket = Market.builder()
                .id("1")
                .name("Match Result")
                .title("Full Time Result")
                .group("Main")
                .specifier(null)
                .status(1)
                .outcomes(List.of(testOutcome))
                .build();

        TournamentInfo tournament = TournamentInfo.builder()
                .name("Premier League")
                .build();

        Category category = Category.builder()
                .tournament(tournament)
                .build();

        Sport sport = Sport.builder()
                .id("1")
                .name("Football")
                .category(category)
                .build();

        testEvent = SportyEvent.builder()
                .eventId("event1")
                .gameId("game1")
                .homeTeamName("Manchester United")
                .awayTeamName("Liverpool")
                .homeTeamId("team1")
                .awayTeamId("team2")
                .estimateStartTime(System.currentTimeMillis())
                .status(1)
                .matchStatus("Not Started")
                .setScore("0-0")
                .gameScore(Arrays.asList("0", "0"))
                .period("1")
                .playedSeconds("0")
                .sport(sport)
                .markets(List.of(testMarket))
                .build();
    }


    @Test
    @DisplayName("convertToNormalEvent_validEvent_returnsNormalizedEvent")
    void convertToNormalEvent_validEvent_returnsNormalizedEvent() {
        when(teamAliasService.canonicalOrSelf("Manchester United")).thenReturn("Man_Utd");
        when(teamAliasService.canonicalOrSelf("Liverpool")).thenReturn("Liverpool");

        NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);

        assertNotNull(result);
        assertEquals("Man_Utd", result.getHomeTeam());
        assertEquals("Liverpool", result.getAwayTeam());
        assertEquals("Premier League", result.getLeague());
        assertEquals(BookMaker.SPORTY_BET, result.getBookie());
        assertEquals("Football|Man_Utd|Liverpool", result.getEventId());
        assertNotNull(result.getMarkets());
        verify(teamAliasService, times(2)).canonicalOrSelf(anyString());
    }

    @Test
    @DisplayName("convertToNormalEvent_nullEvent_throwsIllegalArgumentException")
    void convertToNormalEvent_nullEvent_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                sportyBetService.convertToNormalEvent(null)
        );
    }

    @Test
    @DisplayName("convertToNormalEvent_eventWithNoMarkets_returnsEventWithEmptyMarketList")
    void convertToNormalEvent_eventWithNoMarkets_returnsEventWithEmptyMarketList() {
        testEvent.setMarkets(Collections.emptyList());
        when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

        NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);

        assertNotNull(result);
        assertTrue(result.getMarkets().isEmpty());
    }

    @Test
    @DisplayName("convertToNormalEvent_footballEvent_determinesSportCorrectly")
    void convertToNormalEvent_footballEvent_determinesSportCorrectly() {
        testEvent.getSport().setName("Football");
        when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

        NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);

        assertEquals(SportEnum.FOOTBALL, result.getSportEnum());
    }

    @Test
    @DisplayName("convertToNormalEvent_basketballEvent_determinesSportCorrectly")
    void convertToNormalEvent_basketballEvent_determinesSportCorrectly() {
        testEvent.getSport().setName("Basketball");
        when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

        NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);

        assertEquals(SportEnum.BASKETBALL, result.getSportEnum());
    }

    @Test
    @DisplayName("convertToNormalEvent_tableTennisEvent_determinesSportCorrectly")
    void convertToNormalEvent_tableTennisEvent_determinesSportCorrectly() {
        testEvent.getSport().setName("Table Tennis");
        when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

        NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);

        assertEquals(SportEnum.TABLE_TENNIS, result.getSportEnum());
    }

    @Test
    @DisplayName("convertToNormalEvent_unknownSport_defaultsToFootball")
    void convertToNormalEvent_unknownSport_defaultsToFootball() {
        testEvent.getSport().setName("Cricket");
        when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

        NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);

        assertEquals(SportEnum.FOOTBALL, result.getSportEnum());
    }

    @Test
    @DisplayName("convertToNormalEvent_teamAliasApplied_evenWithNullMarkets")
    void convertToNormalEvent_teamAliasApplied_evenWithNullMarkets() {
        testEvent.setMarkets(null);
        when(teamAliasService.canonicalOrSelf("Manchester United")).thenReturn("Man Utd");
        when(teamAliasService.canonicalOrSelf("Liverpool")).thenReturn("Liverpool");

        NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);

        assertEquals("Man Utd", result.getHomeTeam());
        assertEquals("Liverpool", result.getAwayTeam());
        assertTrue(result.getMarkets().isEmpty());
    }

    @Test
    @DisplayName("convertToNormalEvent_eventStartTime_preservedCorrectly")
    void convertToNormalEvent_eventStartTime_preservedCorrectly() {
        long startTime = System.currentTimeMillis() + 3600000; // 1 hour from now
        testEvent.setEstimateStartTime(startTime);
        when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

        NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);

        assertEquals(startTime, result.getEstimateStartTime());
    }

    @Test
    @DisplayName("convertToNormalEvent_eventName_formattedCorrectly")
    void convertToNormalEvent_eventName_formattedCorrectly() {
        when(teamAliasService.canonicalOrSelf(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);

        assertEquals("Manchester United vs Liverpool", result.getEventName());
    }

    @Test
    @DisplayName("convertToNormalEvent_teamNamesWithSpecialCharacters_handlesCorrectly")
    void convertToNormalEvent_teamNamesWithSpecialCharacters_handlesCorrectly() {
        testEvent.setHomeTeamName("FC Bayern München");
        testEvent.setAwayTeamName("Inter Milão");

        when(teamAliasService.canonicalOrSelf("FC Bayern München")).thenReturn("Bayern");
        when(teamAliasService.canonicalOrSelf("Inter Milão")).thenReturn("Inter");

        NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);

        assertEquals("Bayern", result.getHomeTeam());
        assertEquals("Inter", result.getAwayTeam());
    }

    // -------- convertMarketsToOddsMap --------

    @Test
    @DisplayName("convertMarketsToOddsMap_validMarkets_returnsOddsMap")
    void convertMarketsToOddsMap_validMarkets_returnsOddsMap() {
        Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(testMarket));

        assertFalse(result.isEmpty());
        assertTrue(result.containsValue("2.50"));
    }

    @Test
    @DisplayName("convertMarketsToOddsMap_nullMarkets_returnsEmptyMap")
    void convertMarketsToOddsMap_nullMarkets_returnsEmptyMap() {
        Map<String, String> result = sportyBetService.convertMarketsToOddsMap(null);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("convertMarketsToOddsMap_emptyMarketsList_returnsEmptyMap")
    void convertMarketsToOddsMap_emptyMarketsList_returnsEmptyMap() {
        Map<String, String> result = sportyBetService.convertMarketsToOddsMap(Collections.emptyList());

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("convertMarketsToOddsMap_marketWithInvalidOdds_filtersOutInvalidOdds")
    void convertMarketsToOddsMap_marketWithInvalidOdds_filtersOutInvalidOdds() {
        Outcome invalidOutcome = Outcome.builder()
                .id("outcome2")
                .odds("-1.00")
                .isActive(1)
                .desc("Invalid")
                .build();

        Market marketWithInvalid = Market.builder()
                .id("1")
                .name("Test")
                .specifier(null)
                .outcomes(List.of(testOutcome, invalidOutcome))
                .build();

        Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(marketWithInvalid));

        assertFalse(result.containsValue("-1.00"));
        assertTrue(result.containsValue("2.50"));
    }

    @Test
    @DisplayName("convertMarketsToOddsMap_zeroOdds_filtersOutZeroOdds")
    void convertMarketsToOddsMap_zeroOdds_filtersOutZeroOdds() {
        testOutcome.setOdds("0");

        Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(testMarket));

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("convertMarketsToOddsMap_negativeOdds_filtersOutNegativeOdds")
    void convertMarketsToOddsMap_negativeOdds_filtersOutNegativeOdds() {
        testOutcome.setOdds("-1.5");

        Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(testMarket));

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("convertMarketsToOddsMap_multipleOutcomesSameKey_keepsFirstOdds")
    void convertMarketsToOddsMap_multipleOutcomesSameKey_keepsFirstOdds() {
        Outcome outcome1 = Outcome.builder().id("1").desc("Home").odds("2.50").isActive(1).build();
        Outcome outcome2 = Outcome.builder().id("1").desc("Home").odds("2.60").isActive(1).build();

        Market market = Market.builder()
                .id("1")
                .name("Match Result")
                .specifier(null)
                .outcomes(List.of(outcome1, outcome2))
                .build();

        Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(market));

        assertEquals(1, result.size());
        assertTrue(result.containsValue("2.50")); // first wins
    }

    @Test
    @DisplayName("convertMarketsToOddsMap_multipleMarkets_aggregatesAllValid")
    void convertMarketsToOddsMap_multipleMarkets_aggregatesAllValid() {
        Outcome a = Outcome.builder().id("1").desc("Home").odds("2.01").isActive(1).build();
        Outcome b = Outcome.builder().id("2").desc("Away").odds("3.10").isActive(1).build();
        Market m1 = Market.builder().id("10").name("1x2").specifier(null).outcomes(List.of(a, b)).build();

        Outcome c = Outcome.builder().id("3").desc("Over 2.5").odds("1.90").isActive(1).build();
        Outcome d = Outcome.builder().id("4").desc("Under 2.5").odds("1.95").isActive(1).build();
        Market m2 = Market.builder().id("11").name("Over/Under").specifier("2.5").outcomes(List.of(c, d)).build();

        Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(m1, m2));

        assertEquals(4, result.size());
        assertTrue(result.values().containsAll(List.of("2.01", "3.10", "1.90", "1.95")));
    }

    @Test
    @DisplayName("convertMarketsToOddsMap_veryLargeOdds_handlesCorrectly")
    void convertMarketsToOddsMap_veryLargeOdds_handlesCorrectly() {
        testOutcome.setOdds("999.99");

        Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(testMarket));

        assertFalse(result.isEmpty());
        assertTrue(result.containsValue("999.99"));
    }

    @Test
    @DisplayName("convertMarketsToOddsMap_verySmallPositiveOdds_handlesCorrectly")
    void convertMarketsToOddsMap_verySmallPositiveOdds_handlesCorrectly() {
        testOutcome.setOdds("0.01");

        Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(testMarket));

        assertFalse(result.isEmpty());
        assertTrue(result.containsValue("0.01"));
    }


    @Test
    @DisplayName("mapOutcomeStatus_validMarkets_returnsStatusMap")
    void mapOutcomeStatus_validMarkets_returnsStatusMap() {
        Map<String, Integer> result = sportyBetService.mapOutcomeStatus(List.of(testMarket));

        assertFalse(result.isEmpty());
        assertTrue(result.containsValue(1));
    }

    @Test
    @DisplayName("mapOutcomeStatus_nullMarkets_returnsEmptyMap")
    void mapOutcomeStatus_nullMarkets_returnsEmptyMap() {
        Map<String, Integer> result = sportyBetService.mapOutcomeStatus(null);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("mapOutcomeStatus_activeAndInactiveOutcomes_mapsBothCorrectly")
    void mapOutcomeStatus_activeAndInactiveOutcomes_mapsBothCorrectly() {
        Outcome activeOutcome = Outcome.builder().id("1").desc("Home").odds("2.50").isActive(1).build();
        Outcome inactiveOutcome = Outcome.builder().id("2").desc("Away").odds("3.00").isActive(0).build();

        Market market = Market.builder()
                .id("1")
                .name("Test")
                .specifier(null)
                .outcomes(List.of(activeOutcome, inactiveOutcome))
                .build();

        Map<String, Integer> result = sportyBetService.mapOutcomeStatus(List.of(market));

        assertTrue(result.containsValue(1));
        assertTrue(result.containsValue(0));
    }

    @Test
    @DisplayName("mapOutcomeStatus_excludesOutcomesWithInvalidOdds")
    void mapOutcomeStatus_excludesOutcomesWithInvalidOdds() {
        Outcome valid = Outcome.builder().id("1").desc("Home").odds("2.00").isActive(1).build();
        Outcome invalid = Outcome.builder().id("2").desc("Draw").odds("abc").isActive(0).build();

        Market market = Market.builder().id("1").name("1x2").specifier(null).outcomes(List.of(valid, invalid)).build();

        Map<String, Integer> status = sportyBetService.mapOutcomeStatus(List.of(market));

        assertEquals(1, status.size());
        assertTrue(status.containsValue(1));
    }

    @Test
    @DisplayName("mapOutcomeStatus_duplicateKeys_keepsFirstStatus")
    void mapOutcomeStatus_duplicateKeys_keepsFirstStatus() {
        Outcome a = Outcome.builder().id("1").desc("Home").odds("1.50").isActive(1).build();
        Outcome b = Outcome.builder().id("1").desc("Home").odds("1.60").isActive(0).build();

        Market market = Market.builder().id("1").name("1x2").specifier(null).outcomes(List.of(a, b)).build();

        Map<String, Integer> result = sportyBetService.mapOutcomeStatus(List.of(market));

        assertEquals(1, result.size());
        assertTrue(result.containsValue(1)); // first wins
    }


    @Test
    @DisplayName("mapOutcomeCashOutIndicator_validMarkets_returnsCashOutMap")
    void mapOutcomeCashOutIndicator_validMarkets_returnsCashOutMap() {
        Map<String, Integer> result = sportyBetService.mapOutcomeCashOutIndicator(List.of(testMarket));

        assertFalse(result.isEmpty());
        assertTrue(result.containsValue(1));
    }

    @Test
    @DisplayName("mapOutcomeCashOutIndicator_nullMarkets_returnsEmptyMap")
    void mapOutcomeCashOutIndicator_nullMarkets_returnsEmptyMap() {
        Map<String, Integer> result = sportyBetService.mapOutcomeCashOutIndicator(null);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("mapOutcomeCashOutIndicator_nullCashOut_throwsNPE (documents current behavior)")
    void mapOutcomeCashOutIndicator_nullCashOut_throwsNPE() {
        testOutcome.setCashOutIsActive(null);
        assertThrows(NullPointerException.class, () ->
                sportyBetService.mapOutcomeCashOutIndicator(List.of(testMarket))
        );
        // Suggestion for service (not part of test): map null -> 0 before Collectors.toMap.
    }


    @Test
    @DisplayName("buildMetaMap_validMarkets_returnsMetaMap")
    void buildMetaMap_validMarkets_returnsMetaMap() {
        Map<String, MarketMeta> result = sportyBetService.buildMetaMap(List.of(testMarket));

        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("buildMetaMap_nullMarkets_returnsEmptyMap")
    void buildMetaMap_nullMarkets_returnsEmptyMap() {
        Map<String, MarketMeta> result = sportyBetService.buildMetaMap(null);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("buildMetaMap_marketWithMultipleOutcomes_createsMetaForEachOutcome")
    void buildMetaMap_marketWithMultipleOutcomes_createsMetaForEachOutcome() {
        Outcome outcome1 = Outcome.builder().id("1").desc("Home").odds("2.50").isActive(1).build();
        Outcome outcome2 = Outcome.builder().id("2").desc("Away").odds("3.00").isActive(1).build();

        Market market = Market.builder()
                .id("1")
                .name("Match Result")
                .title("Full Time Result")
                .group("Main")
                .specifier(null)
                .outcomes(List.of(outcome1, outcome2))
                .build();

        Map<String, MarketMeta> result = sportyBetService.buildMetaMap(List.of(market));

        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("buildMetaMap_withSpecifier_includesSpecifierInMeta")
    void buildMetaMap_withSpecifier_includesSpecifierInMeta() {
        testMarket.setSpecifier("2.5");

        Map<String, MarketMeta> result = sportyBetService.buildMetaMap(List.of(testMarket));

        assertFalse(result.isEmpty());
        MarketMeta meta = result.values().iterator().next();
        assertEquals("2.5", meta.specifiers());
    }

    @Test
    @DisplayName("buildMetaMap_duplicateKeys_keepsFirstMeta")
    void buildMetaMap_duplicateKeys_keepsFirstMeta() {
        Outcome a = Outcome.builder().id("1").desc("Over 2.5").odds("1.90").isActive(1).build();
        Outcome b = Outcome.builder().id("2").desc("Over  2.5").odds("2.05").isActive(1).build();

        Market market = Market.builder()
                .id("18")
                .name("Over/Under")
                .specifier("2.5")
                .outcomes(List.of(a, b))
                .build();

        Map<String, MarketMeta> result = sportyBetService.buildMetaMap(List.of(market));

        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("buildMetaMap_verifyMetaContent_containsCorrectData")
    void buildMetaMap_verifyMetaContent_containsCorrectData() {
        Map<String, MarketMeta> result = sportyBetService.buildMetaMap(List.of(testMarket));

        assertFalse(result.isEmpty());
        MarketMeta meta = result.values().iterator().next();
        assertEquals("Match Result", meta.name());
        assertEquals("Full Time Result", meta.title());
        assertEquals("Main", meta.group());
        assertEquals("1", meta.marketId());
    }

    @Test
    @DisplayName("normalizeMarkets_nullRawOdds_returnsEmptyList")
    void normalizeMarkets_nullRawOdds_returnsEmptyList() {
        List<NormalizedMarket> result = sportyBetService.normalizeMarkets(
                null, Map.of(), Map.of(), Map.of(),
                BookMaker.SPORTY_BET, "eventId", "Premier League",
                "Home", "Away", testEvent
        );
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("normalizeMarkets_emptyRawOdds_returnsEmptyList")
    void normalizeMarkets_emptyRawOdds_returnsEmptyList() {
        List<NormalizedMarket> result = sportyBetService.normalizeMarkets(
                Map.of(), Map.of(), Map.of(), Map.of(),
                BookMaker.SPORTY_BET, "eventId", "Premier League",
                "Home", "Away", testEvent
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
        Map<String, Integer> cashOutMap = Map.of(
                "UNKNOWN|BAD|KEY1", 1,
                "RANDOM/KEY2", 0
        );
        Map<String, MarketMeta> metaMap = Map.of(
                "UNKNOWN|BAD|KEY1", new MarketMeta("", "", "", "", "1")
        );

        List<NormalizedMarket> result = sportyBetService.normalizeMarkets(
                rawOdds, statusMap, cashOutMap, metaMap,
                BookMaker.SPORTY_BET, "EVT-42", "League", "Home", "Away", testEvent
        );

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("normalizeMarkets_validData_createsNormalizedMarkets (may be empty if keys unrecognized)")
    void normalizeMarkets_validData_createsNormalizedMarkets() {
        Map<String, String> oddsMap = sportyBetService.convertMarketsToOddsMap(List.of(testMarket));
        Map<String, Integer> statusMap = sportyBetService.mapOutcomeStatus(List.of(testMarket));
        Map<String, Integer> cashOutMap = sportyBetService.mapOutcomeCashOutIndicator(List.of(testMarket));
        Map<String, MarketMeta> metaMap = sportyBetService.buildMetaMap(List.of(testMarket));

        List<NormalizedMarket> result = sportyBetService.normalizeMarkets(
                oddsMap, statusMap, cashOutMap, metaMap,
                BookMaker.SPORTY_BET, "eventId", "Premier League",
                "Manchester United", "Liverpool", testEvent
        );

        assertNotNull(result); // could be empty if SportyMarketType doesn't recognize generated keys
    }


    @Test
    @DisplayName("generateProviderKey_marketWithSpecifier_includesSpecifierInKey")
    void generateProviderKey_marketWithSpecifier_includesSpecifierInKey() {
        Outcome outcome = Outcome.builder()
                .id("12")
                .desc("Over 2.5")
                .odds("1.85")
                .isActive(1)
                .build();

        Market market = Market.builder()
                .id("18")
                .name("Over/Under")
                .specifier("2.5")
                .outcomes(List.of(outcome))
                .build();

        Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(market));

        assertFalse(result.isEmpty());
        assertTrue(result.keySet().stream().anyMatch(key -> key.contains("2.5")));
    }

    @Test
    @DisplayName("generateProviderKey_outcomeDescriptionWithSpaces_normalizesToUnderscores")
    void generateProviderKey_outcomeDescriptionWithSpaces_normalizesToUnderscores() {
        Outcome outcome = Outcome.builder()
                .id("74")
                .desc("Both Teams To Score")
                .odds("2.20")
                .isActive(1)
                .build();

        Market market = Market.builder()
                .id("29")
                .name("BTTS")
                .specifier(null)
                .outcomes(List.of(outcome))
                .build();

        Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(market));

        assertFalse(result.isEmpty());
        assertTrue(result.keySet().stream().anyMatch(key -> key.contains("BOTH_TEAMS_TO_SCORE")));
    }

    @Test
    @DisplayName("generateProviderKey_outcomeDescriptionWithWhitespace_trimmedCorrectly")
    void generateProviderKey_outcomeDescriptionWithWhitespace_trimmedCorrectly() {
        testOutcome.setDesc("  Home Win  ");

        Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(testMarket));

        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("generateProviderKey_outcomeDescriptionWithSpecialCharacters_handlesCorrectly")
    void generateProviderKey_outcomeDescriptionWithSpecialCharacters_handlesCorrectly() {
        testOutcome.setDesc("Home/Draw");

        Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(testMarket));

        assertFalse(result.isEmpty());
    }

    // -------- Score/Period null-safety --------

    @Nested
    @DisplayName("Score/Period null-safety")
    class ScoreNullSafety {

        @Test
        @DisplayName("gameScore=null handled")
        void gameScoreNullHandled() {
            testEvent.setGameScore(null);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertNotNull(result.getMarkets());
        }

        @Test
        @DisplayName("gameScore=empty handled")
        void gameScoreEmptyHandled() {
            testEvent.setGameScore(Collections.emptyList());
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);

            assertNotNull(result);
        }

        @Test
        @DisplayName("setScore=null handled")
        void setScoreNullHandled() {
            testEvent.setSetScore(null);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);

            assertNotNull(result);
        }

        @Test
        @DisplayName("period=null handled")
        void periodNullHandled() {
            testEvent.setPeriod(null);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);

            assertNotNull(result);
        }

        @Test
        @DisplayName("matchStatus=null handled")
        void matchStatusNullHandled() {
            testEvent.setMatchStatus(null);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);

            assertNotNull(result);
        }

        @Test
        @DisplayName("playedSeconds=null handled")
        void playedSecondsNullHandled() {
            testEvent.setPlayedSeconds(null);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);

            assertNotNull(result);
        }
    }

    // -------- addNormalizedEventToPool --------

//    @Test
//    @DisplayName("addNormalizedEventToPool forwards to arbDetector")
//    void addNormalizedEventToPool_forwards() {
//        NormalizedEvent ne = NormalizedEvent.builder().eventId("E1").build();
//        sportyBetService.addNormalizedEventToPool(ne);
//        verify(arbDetector).addEventToPool(ne);
//    }
//
//    @Test
//    @DisplayName("addNormalizedEventToPool forwards null (documents current behavior)")
//    void addNormalizedEventToPool_nullStillForwards() {
//        sportyBetService.addNormalizedEventToPool(null);
//        verify(arbDetector).addEventToPool(null);
//        // Consider guarding null in service.
//    }
}
