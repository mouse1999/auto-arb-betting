//package com.mouse.bet.service;
//
//import com.mouse.bet.detector.ArbDetector;
//import com.mouse.bet.enums.BookMaker;
//import com.mouse.bet.enums.MarketCategory;
//import com.mouse.bet.enums.SportEnum;
//import com.mouse.bet.model.MarketMeta;
//import com.mouse.bet.model.NormalizedEvent;
//import com.mouse.bet.model.NormalizedMarket;
//import com.mouse.bet.model.NormalizedOutcome;
//import com.mouse.bet.model.sporty.*;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Nested;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.math.BigDecimal;
//import java.util.*;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.anyString;
//import static org.mockito.Mockito.*;
//
//@ExtendWith(MockitoExtension.class)
//@DisplayName("SportyBetService Tests")
//class SportyBetServiceTest {
//
//    @Mock
//    private ArbDetector arbDetector;
//
//    @Mock
//    private TeamAliasService teamAliasService;
//
//    @InjectMocks
//    private SportyBetService sportyBetService;
//
//    private SportyEvent testEvent;
//    private Market testMarket;
//    private Outcome testOutcome;
//
//    @BeforeEach
//    void setUp() {
//        testEvent = createMockSportyEvent();
//
//        // Extract test market and outcome for individual tests
//        testMarket = testEvent.getMarkets().get(0);
//        testOutcome = testMarket.getOutcomes().get(0);
//
//    }
//
//    @Nested
//    @DisplayName("convertToNormalEvent Tests")
//    class ConvertToNormalEventTests {
//
//        @Test
//        @DisplayName("validEvent_returnsNormalizedEvent")
//        void convertToNormalEvent_validEvent_returnsNormalizedEvent() {
//
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertNotNull(result);
//            assertEquals("Roma", result.getHomeTeam());
//            assertEquals("Viktoria Plzen", result.getAwayTeam());
//            assertEquals("UEFA Europa League", result.getLeague());
//            assertEquals(BookMaker.SPORTY_BET, result.getBookie());
//            assertEquals("Football|Roma|Viktoria Plzen", result.getEventId());
//            assertNotNull(result.getMarkets());
//            verify(teamAliasService, times(2)).canonicalOrSelf(anyString());
//        }
//
//        @Test
//        @DisplayName("withTeamAliases_appliesAliasesCorrectly")
//        void convertToNormalEvent_withTeamAliases_appliesAliasesCorrectly() {
//
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertEquals("Roma", result.getHomeTeam());
//            assertEquals("Viktoria Plzen", result.getAwayTeam());
//            assertEquals("Football|Roma|Viktoria Plzen", result.getEventId());
//        }
//
//        @Test
//        @DisplayName("nullEvent_throwsIllegalArgumentException")
//        void convertToNormalEvent_nullEvent_throwsIllegalArgumentException() {
//            assertThrows(IllegalArgumentException.class, () ->
//                    sportyBetService.convertToNormalEvent(null)
//            );
//        }
//
//        @Test
//        @DisplayName("eventWithNoMarkets_returnsEventWithEmptyMarketList")
//        void convertToNormalEvent_eventWithNoMarkets_returnsEventWithEmptyMarketList() {
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            testEvent.setMarkets(Collections.emptyList());
//
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertNotNull(result);
//            assertTrue(result.getMarkets().isEmpty());
//        }
//
//        @Test
//        @DisplayName("eventWithNullMarkets_returnsEventWithEmptyMarketList")
//        void convertToNormalEvent_eventWithNullMarkets_returnsEventWithEmptyMarketList() {
//
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            testEvent.setMarkets(null);
//
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertNotNull(result);
//            assertTrue(result.getMarkets().isEmpty());
//        }
//
//        @Test
//        @DisplayName("footballEvent_determinesSportCorrectly")
//        void convertToNormalEvent_footballEvent_determinesSportCorrectly() {
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            testEvent.getSport().setName("Football");
//
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertEquals(SportEnum.FOOTBALL, result.getSportEnum());
//        }
//
//        @Test
//        @DisplayName("basketballEvent_determinesSportCorrectly")
//        void convertToNormalEvent_basketballEvent_determinesSportCorrectly() {
//
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            testEvent.getSport().setName("Basketball");
//
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertEquals(SportEnum.BASKETBALL, result.getSportEnum());
//        }
//
//        @Test
//        @DisplayName("tableTennisEvent_determinesSportCorrectly")
//        void convertToNormalEvent_tableTennisEvent_determinesSportCorrectly() {
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            testEvent.getSport().setName("Table Tennis");
//
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertEquals(SportEnum.TABLE_TENNIS, result.getSportEnum());
//        }
//
//        @Test
//        @DisplayName("unknownSport_defaultsToFootball")
//        void convertToNormalEvent_unknownSport_defaultsToFootball() {
//
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            testEvent.getSport().setName("Cricket");
//
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertEquals(SportEnum.FOOTBALL, result.getSportEnum());
//        }
//
//        @Test
//        @DisplayName("eventStartTime_preservedCorrectly")
//        void convertToNormalEvent_eventStartTime_preservedCorrectly() {
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            long startTime = 1761246000000L;
//            testEvent.setEstimateStartTime(startTime);
//
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertEquals(startTime, result.getEstimateStartTime());
//        }
//
//        @Test
//        @DisplayName("eventName_formattedCorrectly")
//        void convertToNormalEvent_eventName_formattedCorrectly() {
//
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertEquals("Roma vs Viktoria Plzen", result.getEventName());
//        }
//
//        @Test
//        @DisplayName("marketsConverted_containsExpectedMarkets")
//        void convertToNormalEvent_marketsConverted_containsExpectedMarkets() {
//
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertNotNull(result.getMarkets());
//            assertTrue(result.getMarkets().size() > 0);
//        }
//
//        @Test
//        @DisplayName("outcomesHaveCorrectProperties")
//        void convertToNormalEvent_outcomesHaveCorrectProperties() {
//
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertFalse(result.getMarkets().isEmpty());
//
//            NormalizedMarket firstMarket = result.getMarkets().get(0);
//            assertNotNull(firstMarket.getOutcomes());
//            assertFalse(firstMarket.getOutcomes().isEmpty());
//
//            NormalizedOutcome firstOutcome = firstMarket.getOutcomes().get(0);
//
//            assertNotNull(firstOutcome.getOutcomeId());
//            assertNotNull(firstOutcome.getEventId());
//            assertNotNull(firstOutcome.getOdds());
//            assertTrue(firstOutcome.getOdds().compareTo(BigDecimal.ZERO) > 0);
//            assertEquals(BookMaker.SPORTY_BET, firstOutcome.getBookmaker());
//            assertEquals("Roma", firstOutcome.getHomeTeam());
//            assertEquals("Viktoria Plzen", firstOutcome.getAwayTeam());
//            assertEquals("UEFA Europa League", firstOutcome.getLeague());
//            assertEquals(SportEnum.FOOTBALL, firstOutcome.getSportEnum());
//            assertEquals(1761246000000L, firstOutcome.getEventStartTime());
//        }
//
//        @Test
//        @DisplayName("matchStatusPreserved")
//        void convertToNormalEvent_matchStatusPreserved() {
//
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            NormalizedOutcome firstOutcome = result.getMarkets().get(0).getOutcomes().get(0);
//
//            assertEquals("HT", firstOutcome.getMatchStatus());
//            assertEquals("0:2", firstOutcome.getSetScore());
//            assertEquals("1", firstOutcome.getPeriod());
//            assertEquals("45:00", firstOutcome.getPlayedSeconds());
//            assertNotNull(firstOutcome.getGameScore());
//            assertEquals(1, firstOutcome.getGameScore().size());
//            assertEquals("0:2", firstOutcome.getGameScore().get(0));
//        }
//
//        @Test
//        @DisplayName("validOddsOnly_filtersInvalidOdds")
//        void convertToNormalEvent_validOddsOnly_filtersInvalidOdds() {
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            result.getMarkets().forEach(market ->
//                    market.getOutcomes().forEach(outcome ->
//                            assertTrue(outcome.getOdds().compareTo(BigDecimal.ZERO) > 0)
//                    )
//            );
//        }
//
//        @Test
//        @DisplayName("cashOutStatus_mappedCorrectly")
//        void convertToNormalEvent_cashOutStatus_mappedCorrectly() {
//
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            boolean hasCashOutInfo = result.getMarkets().stream()
//                    .flatMap(m -> m.getOutcomes().stream())
//                    .anyMatch(o -> o.getCashOutAvailable() != null);
//
//            assertTrue(hasCashOutInfo);
//        }
//
//        @Test
//        @DisplayName("providerMetadata_setCorrectly")
//        void convertToNormalEvent_providerMetadata_setCorrectly() {
//
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            NormalizedOutcome firstOutcome = result.getMarkets().get(0).getOutcomes().get(0);
//
//            assertNotNull(firstOutcome.getProviderMarketName());
//            assertNotNull(firstOutcome.getProviderMarketTitle());
//            assertNotNull(firstOutcome.getMarketId());
//        }
//    }
//
//    @Nested
//    @DisplayName("convertMarketsToOddsMap Tests")
//    class ConvertMarketsToOddsMapTests {
//
//        @Test
//        @DisplayName("validMarkets_returnsOddsMap")
//        void convertMarketsToOddsMap_validMarkets_returnsOddsMap() {
//            Map<String, String> result = sportyBetService.convertMarketsToOddsMap(testEvent.getMarkets());
//
//            assertFalse(result.isEmpty());
//            assertTrue(result.values().stream().anyMatch(odds ->
//                    new BigDecimal(odds).compareTo(BigDecimal.ZERO) > 0
//            ));
//        }
//
//        @Test
//        @DisplayName("nullMarkets_returnsEmptyMap")
//        void convertMarketsToOddsMap_nullMarkets_returnsEmptyMap() {
//            Map<String, String> result = sportyBetService.convertMarketsToOddsMap(null);
//
//            assertTrue(result.isEmpty());
//        }
//
//        @Test
//        @DisplayName("emptyMarketsList_returnsEmptyMap")
//        void convertMarketsToOddsMap_emptyMarketsList_returnsEmptyMap() {
//            Map<String, String> result = sportyBetService.convertMarketsToOddsMap(Collections.emptyList());
//
//            assertTrue(result.isEmpty());
//        }
//
//        @Test
//        @DisplayName("marketWithInvalidOdds_filtersOutInvalidOdds")
//        void convertMarketsToOddsMap_marketWithInvalidOdds_filtersOutInvalidOdds() {
//            Outcome validOutcome = Outcome.builder()
//                    .id("1")
//                    .odds("2.50")
//                    .isActive(1)
//                    .desc("Valid")
//                    .build();
//
//            Outcome invalidOutcome = Outcome.builder()
//                    .id("2")
//                    .odds("-1.00")
//                    .isActive(1)
//                    .desc("Invalid")
//                    .build();
//
//            Market market = Market.builder()
//                    .id("1")
//                    .name("Test")
//                    .outcomes(List.of(validOutcome, invalidOutcome))
//                    .build();
//
//            Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(market));
//
//            assertFalse(result.containsValue("-1.00"));
//            assertTrue(result.containsValue("2.50"));
//        }
//
//        @Test
//        @DisplayName("zeroOdds_filtersOutZeroOdds")
//        void convertMarketsToOddsMap_zeroOdds_filtersOutZeroOdds() {
//            Outcome outcome = Outcome.builder()
//                    .id("1")
//                    .odds("0")
//                    .isActive(1)
//                    .desc("Zero")
//                    .build();
//
//            Market market = Market.builder()
//                    .id("1")
//                    .name("Test")
//                    .outcomes(List.of(outcome))
//                    .build();
//
//            Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(market));
//
//            assertTrue(result.isEmpty());
//        }
//
//        @Test
//        @DisplayName("multipleMarkets_aggregatesAllValid")
//        void convertMarketsToOddsMap_multipleMarkets_aggregatesAllValid() {
//            Map<String, String> result = sportyBetService.convertMarketsToOddsMap(testEvent.getMarkets());
//
//            // Should have odds from multiple markets (1X2, Over/Under, Double Chance)
//            assertTrue(result.size() >= 6); // At least 3 outcomes from 1X2, 2 from O/U, etc.
//        }
//
//        @Test
//        @DisplayName("veryLargeOdds_handlesCorrectly")
//        void convertMarketsToOddsMap_veryLargeOdds_handlesCorrectly() {
//            Outcome outcome = Outcome.builder()
//                    .id("1")
//                    .odds("999.99")
//                    .isActive(1)
//                    .desc("Large")
//                    .build();
//
//            Market market = Market.builder()
//                    .id("1")
//                    .name("Test")
//                    .outcomes(List.of(outcome))
//                    .build();
//
//            Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(market));
//
//            assertTrue(result.containsValue("999.99"));
//        }
//
//        @Test
//        @DisplayName("verySmallPositiveOdds_handlesCorrectly")
//        void convertMarketsToOddsMap_verySmallPositiveOdds_handlesCorrectly() {
//            Outcome outcome = Outcome.builder()
//                    .id("1")
//                    .odds("0.01")
//                    .isActive(1)
//                    .desc("Small")
//                    .build();
//
//            Market market = Market.builder()
//                    .id("1")
//                    .name("Test")
//                    .outcomes(List.of(outcome))
//                    .build();
//
//            Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(market));
//
//            assertTrue(result.containsValue("0.01"));
//        }
//    }
//
//    @Nested
//    @DisplayName("mapOutcomeStatus Tests")
//    class MapOutcomeStatusTests {
//
//        @Test
//        @DisplayName("validMarkets_returnsStatusMap")
//        void mapOutcomeStatus_validMarkets_returnsStatusMap() {
//            Map<String, Integer> result = sportyBetService.mapOutcomeStatus(testEvent.getMarkets());
//
//            assertFalse(result.isEmpty());
//        }
//
//        @Test
//        @DisplayName("nullMarkets_returnsEmptyMap")
//        void mapOutcomeStatus_nullMarkets_returnsEmptyMap() {
//            Map<String, Integer> result = sportyBetService.mapOutcomeStatus(null);
//
//            assertTrue(result.isEmpty());
//        }
//
//        @Test
//        @DisplayName("activeAndInactiveOutcomes_mapsBothCorrectly")
//        void mapOutcomeStatus_activeAndInactiveOutcomes_mapsBothCorrectly() {
//            Outcome activeOutcome = Outcome.builder()
//                    .id("1")
//                    .desc("Active")
//                    .odds("2.50")
//                    .isActive(1)
//                    .build();
//
//            Outcome inactiveOutcome = Outcome.builder()
//                    .id("2")
//                    .desc("Inactive")
//                    .odds("3.00")
//                    .isActive(0)
//                    .build();
//
//            Market market = Market.builder()
//                    .id("1")
//                    .name("Test")
//                    .outcomes(List.of(activeOutcome, inactiveOutcome))
//                    .build();
//
//            Map<String, Integer> result = sportyBetService.mapOutcomeStatus(List.of(market));
//
//            assertTrue(result.containsValue(1));
//            assertTrue(result.containsValue(0));
//        }
//
//        @Test
//        @DisplayName("excludesOutcomesWithInvalidOdds")
//        void mapOutcomeStatus_excludesOutcomesWithInvalidOdds() {
//            Outcome valid = Outcome.builder()
//                    .id("1")
//                    .desc("Valid")
//                    .odds("2.00")
//                    .isActive(1)
//                    .build();
//
//            Outcome invalid = Outcome.builder()
//                    .id("2")
//                    .desc("Invalid")
//                    .odds("abc")
//                    .isActive(0)
//                    .build();
//
//            Market market = Market.builder()
//                    .id("1")
//                    .name("Test")
//                    .outcomes(List.of(valid, invalid))
//                    .build();
//
//            Map<String, Integer> result = sportyBetService.mapOutcomeStatus(List.of(market));
//
//            assertEquals(1, result.size());
//        }
//    }
//
//    @Nested
//    @DisplayName("mapOutcomeCashOutIndicator Tests")
//    class MapOutcomeCashOutIndicatorTests {
//
//        @Test
//        @DisplayName("validMarkets_returnsCashOutMap")
//        void mapOutcomeCashOutIndicator_validMarkets_returnsCashOutMap() {
//            Map<String, Integer> result = sportyBetService.mapOutcomeCashOutIndicator(testEvent.getMarkets());
//
//            assertFalse(result.isEmpty());
//        }
//
//        @Test
//        @DisplayName("nullMarkets_returnsEmptyMap")
//        void mapOutcomeCashOutIndicator_nullMarkets_returnsEmptyMap() {
//            Map<String, Integer> result = sportyBetService.mapOutcomeCashOutIndicator(null);
//
//            assertTrue(result.isEmpty());
//        }
//
//        @Test
//        @DisplayName("nullCashOut_throwsNPE")
//        void mapOutcomeCashOutIndicator_nullCashOut_throwsNPE() {
//            testOutcome.setCashOutIsActive(null);
//
//            assertThrows(NullPointerException.class, () ->
//                    sportyBetService.mapOutcomeCashOutIndicator(List.of(testMarket))
//            );
//        }
//    }
//
//    @Nested
//    @DisplayName("buildMetaMap Tests")
//    class BuildMetaMapTests {
//
//        @Test
//        @DisplayName("validMarkets_returnsMetaMap")
//        void buildMetaMap_validMarkets_returnsMetaMap() {
//            Map<String, MarketMeta> result = sportyBetService.buildMetaMap(testEvent.getMarkets());
//
//            assertFalse(result.isEmpty());
//        }
//
//        @Test
//        @DisplayName("nullMarkets_returnsEmptyMap")
//        void buildMetaMap_nullMarkets_returnsEmptyMap() {
//            Map<String, MarketMeta> result = sportyBetService.buildMetaMap(null);
//
//            assertTrue(result.isEmpty());
//        }
//
//        @Test
//        @DisplayName("marketWithMultipleOutcomes_createsMetaForEachOutcome")
//        void buildMetaMap_marketWithMultipleOutcomes_createsMetaForEachOutcome() {
//            Map<String, MarketMeta> result = sportyBetService.buildMetaMap(List.of(testMarket));
//
//            // testMarket (1X2) has 3 outcomes
//            assertTrue(result.size() >= 3);
//        }
//
//        @Test
//        @DisplayName("withSpecifier_includesSpecifierInMeta")
//        void buildMetaMap_withSpecifier_includesSpecifierInMeta() {
//            // Over/Under market has specifier "total=2.5"
//            Market ouMarket = testEvent.getMarkets().get(1);
//
//            Map<String, MarketMeta> result = sportyBetService.buildMetaMap(List.of(ouMarket));
//
//            assertFalse(result.isEmpty());
//            MarketMeta meta = result.values().iterator().next();
//            assertEquals("total=2.5", meta.specifiers());
//        }
//
//        @Test
//        @DisplayName("verifyMetaContent_containsCorrectData")
//        void buildMetaMap_verifyMetaContent_containsCorrectData() {
//            Map<String, MarketMeta> result = sportyBetService.buildMetaMap(List.of(testMarket));
//
//            assertFalse(result.isEmpty());
//            MarketMeta meta = result.values().iterator().next();
//            assertEquals("1X2", meta.name());
//            assertEquals("1,X,2", meta.title());
//            assertEquals("Main", meta.group());
//            assertEquals("1", meta.marketId());
//        }
//    }
//
//    @Nested
//    @DisplayName("normalizeMarkets Tests")
//    class NormalizeMarketsTests {
//
//        @Test
//        @DisplayName("nullRawOdds_returnsEmptyList")
//        void normalizeMarkets_nullRawOdds_returnsEmptyList() {
//            List<NormalizedMarket> result = sportyBetService.normalizeMarkets(
//                    null, Map.of(), Map.of(), Map.of(),
//                    BookMaker.SPORTY_BET, "eventId", "UEFA Europa League",
//                    "Roma", "Viktoria Plzen", testEvent
//            );
//
//            assertTrue(result.isEmpty());
//        }
//
//        @Test
//        @DisplayName("emptyRawOdds_returnsEmptyList")
//        void normalizeMarkets_emptyRawOdds_returnsEmptyList() {
//            List<NormalizedMarket> result = sportyBetService.normalizeMarkets(
//                    Map.of(), Map.of(), Map.of(), Map.of(),
//                    BookMaker.SPORTY_BET, "eventId", "UEFA Europa League",
//                    "Roma", "Viktoria Plzen", testEvent
//            );
//
//            assertTrue(result.isEmpty());
//        }
//
//        @Test
//        @DisplayName("withUnrecognizedProviderKeys_returnsEmpty")
//        void normalizeMarkets_withUnrecognizedProviderKeys_returnsEmpty() {
//            Map<String, String> rawOdds = Map.of("UNKNOWN|BAD|KEY", "2.0");
//            Map<String, Integer> statusMap = Map.of("UNKNOWN|BAD|KEY", 1);
//            Map<String, Integer> cashOutMap = Map.of("UNKNOWN|BAD|KEY", 1);
//            Map<String, MarketMeta> metaMap = Map.of(
//                    "UNKNOWN|BAD|KEY", new MarketMeta("", "", "", "", "1")
//            );
//
//            List<NormalizedMarket> result = sportyBetService.normalizeMarkets(
//                    rawOdds, statusMap, cashOutMap, metaMap,
//                    BookMaker.SPORTY_BET, "EVT-42", "League", "Home", "Away", testEvent
//            );
//
//            assertNotNull(result);
//            assertTrue(result.isEmpty());
//        }
//
//        @Test
//        @DisplayName("validData_createsNormalizedMarkets")
//        void normalizeMarkets_validData_createsNormalizedMarkets() {
//            Map<String, String> oddsMap = sportyBetService.convertMarketsToOddsMap(testEvent.getMarkets());
//            Map<String, Integer> statusMap = sportyBetService.mapOutcomeStatus(testEvent.getMarkets());
//            Map<String, Integer> cashOutMap = sportyBetService.mapOutcomeCashOutIndicator(testEvent.getMarkets());
//            Map<String, MarketMeta> metaMap = sportyBetService.buildMetaMap(testEvent.getMarkets());
//
//            List<NormalizedMarket> result = sportyBetService.normalizeMarkets(
//                    oddsMap, statusMap, cashOutMap, metaMap,
//                    BookMaker.SPORTY_BET, "eventId", "UEFA Europa League",
//                    "Roma", "Viktoria Plzen", testEvent
//            );
//
//            assertNotNull(result);
//        }
//    }
//
//    @Nested
//    @DisplayName("Provider Key Generation Tests")
//    class ProviderKeyGenerationTests {
//
//        @Test
//        @DisplayName("marketWithSpecifier_includesSpecifierInKey")
//        void generateProviderKey_marketWithSpecifier_includesSpecifierInKey() {
//            Market ouMarket = testEvent.getMarkets().get(1); // Over/Under with specifier
//
//            Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(ouMarket));
//
//            assertFalse(result.isEmpty());
//            assertTrue(result.keySet().stream().anyMatch(key -> key.contains("2.5")));
//        }
//
//        @Test
//        @DisplayName("outcomeDescriptionWithSpaces_normalizesToUnderscores")
//        void generateProviderKey_outcomeDescriptionWithSpaces_normalizesToUnderscores() {
//            Outcome outcome = Outcome.builder()
//                    .id("74")
//                    .desc("Both Teams To Score")
//                    .odds("2.20")
//                    .isActive(1)
//                    .build();
//
//            Market market = Market.builder()
//                    .id("29")
//                    .name("BTTS")
//                    .outcomes(List.of(outcome))
//                    .build();
//
//            Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(market));
//
//            assertFalse(result.isEmpty());
//            assertTrue(result.keySet().stream()
//                    .anyMatch(key -> key.contains("BOTH_TEAMS_TO_SCORE")));
//        }
//
//        @Test
//        @DisplayName("outcomeDescriptionWithWhitespace_trimmedCorrectly")
//        void generateProviderKey_outcomeDescriptionWithWhitespace_trimmedCorrectly() {
//            Outcome outcome = Outcome.builder()
//                    .id("1")
//                    .desc("  Home Win  ")
//                    .odds("2.50")
//                    .isActive(1)
//                    .build();
//
//            Market market = Market.builder()
//                    .id("1")
//                    .name("Test")
//                    .outcomes(List.of(outcome))
//                    .build();
//
//            Map<String, String> result = sportyBetService.convertMarketsToOddsMap(List.of(market));
//
//            assertFalse(result.isEmpty());
//        }
//    }
//
//    @Nested
//    @DisplayName("Score/Period Null-Safety Tests")
//    class ScoreNullSafetyTests {
//
//        @Test
//        @DisplayName("gameScore_null_handled")
//        void gameScore_null_handled() {
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            testEvent.setGameScore(null);
//
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertNotNull(result);
//        }
//
//        @Test
//        @DisplayName("gameScore_empty_handled")
//        void gameScore_empty_handled() {
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            testEvent.setGameScore(Collections.emptyList());
//
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertNotNull(result);
//        }
//
//        @Test
//        @DisplayName("setScore_null_handled")
//        void setScore_null_handled() {
//
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            testEvent.setSetScore(null);
//
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertNotNull(result);
//        }
//
//        @Test
//        @DisplayName("period_null_handled")
//        void period_null_handled() {
//
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            testEvent.setPeriod(null);
//
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertNotNull(result);
//        }
//
//        @Test
//        @DisplayName("matchStatus_null_handled")
//        void matchStatus_null_handled() {
//
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            testEvent.setMatchStatus(null);
//
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertNotNull(result);
//        }
//
//        @Test
//        @DisplayName("playedSeconds_null_handled")
//        void playedSeconds_null_handled() {
//
//            when(teamAliasService.canonicalOrSelf(anyString()))
//                    .thenAnswer(invocation -> invocation.getArgument(0));
//            testEvent.setPlayedSeconds(null);
//
//            NormalizedEvent result = sportyBetService.convertToNormalEvent(testEvent);
//
//            assertNotNull(result);
//        }
//    }
//
//    @Nested
//    @DisplayName("addNormalizedEventToPool Tests")
//    class AddNormalizedEventToPoolTests {
//
//        @Test
//        @DisplayName("validEvent_forwardsToArbDetector")
//        void addNormalizedEventToPool_validEvent_forwardsToArbDetector() {
//            NormalizedEvent event = NormalizedEvent.builder()
//                    .eventId("E1")
//                    .build();
//
//            sportyBetService.addNormalizedEventToPool(event);
//
//            verify(arbDetector).addEventToPool(event);
//        }
//
//        @Test
//        @DisplayName("nullEvent_throwsIllegalArgumentException")
//        void addNormalizedEventToPool_nullEvent_throwsIllegalArgumentException() {
//            assertThrows(IllegalArgumentException.class, () ->
//                    sportyBetService.addNormalizedEventToPool(null)
//            );
//        }
//    }
//
//    // Helper method to create mock SportyEvent with complete realistic data
//    private SportyEvent createMockSportyEvent() {
//        TournamentInfo tournament = new TournamentInfo();
//        tournament.setId("sr:tournament:679");
//        tournament.setName("UEFA Europa League");
//
//        Category category = new Category();
//        category.setId("sr:category:393");
//        category.setName("International Clubs");
//        category.setTournament(tournament);
//
//        Sport sport = new Sport();
//        sport.setId("sr:sport:1");
//        sport.setName("Football");
//        sport.setCategory(category);
//
//        // Create Outcomes for 1X2 Market
//        Outcome homeOutcome = Outcome.builder()
//                .id("1")
//                .odds("5.30")
//                .probability("0.1741572245")
//                .voidProbability("0E-10")
//                .isActive(1)
//                .desc("Home")
//                .cashOutIsActive(1)
//                .build();
//
//        Outcome drawOutcome = Outcome.builder()
//                .id("2")
//                .odds("4.05")
//                .probability("0.2299046787")
//                .voidProbability("0E-10")
//                .isActive(1)
//                .desc("Draw")
//                .cashOutIsActive(1)
//                .build();
//
//        Outcome awayOutcome = Outcome.builder()
//                .id("3")
//                .odds("1.60")
//                .probability("0.5959380944")
//                .voidProbability("0E-10")
//                .isActive(1)
//                .desc("Away")
//                .cashOutIsActive(1)
//                .build();
//
//        // Create 1X2 Market
//        Market market1X2 = Market.builder()
//                .id("1")
//                .product(1)
//                .desc("1X2")
//                .status(0)
//                .group("Main")
//                .groupId("171121135724MGI11262166")
//                .marketGuide("Which team will win the match. Overtime not included.")
//                .title("1,X,2")
//                .name("1X2")
//                .favourite(0)
//                .outcomes(List.of(homeOutcome, drawOutcome, awayOutcome))
//                .farNearOdds(0)
//                .sourceType("BET_RADAR")
//                .availableScore("0:2")
//                .lastOddsChangeTime(1761249320445L)
//                .banned(false)
//                .cashOutStatus(1)
//                .build();
//
//        // Create Over/Under Market
//        Outcome overOutcome = Outcome.builder()
//                .id("12")
//                .odds("1.12")
//                .probability("0.8639548981")
//                .voidProbability("0E-10")
//                .isActive(1)
//                .desc("Over 2.5")
//                .cashOutIsActive(1)
//                .build();
//
//        Outcome underOutcome = Outcome.builder()
//                .id("13")
//                .odds("6.25")
//                .probability("0.1360451019")
//                .voidProbability("0E-10")
//                .isActive(1)
//                .desc("Under 2.5")
//                .cashOutIsActive(1)
//                .build();
//
//        Market marketOverUnder = Market.builder()
//                .id("18")
//                .specifier("total=2.5")
//                .product(1)
//                .desc("Over/Under")
//                .status(0)
//                .group("Main")
//                .groupId("171121135724MGI11262166")
//                .marketGuide("Predict whether the total number of goals scored in regular time is over/under a given line.")
//                .title("Goals,Over,Under")
//                .name("Over/Under")
//                .favourite(0)
//                .outcomes(List.of(overOutcome, underOutcome))
//                .farNearOdds(0)
//                .sourceType("BET_RADAR")
//                .availableScore("0:2")
//                .lastOddsChangeTime(1761249320445L)
//                .banned(false)
//                .cashOutStatus(1)
//                .build();
//
//        // Create Double Chance Market
//        Outcome dcHomeDrawOutcome = Outcome.builder()
//                .id("9")
//                .odds("2.30")
//                .probability("0.4040619032")
//                .voidProbability("0E-10")
//                .isActive(1)
//                .desc("Home or Draw")
//                .cashOutIsActive(1)
//                .build();
//
//        Outcome dcHomeAwayOutcome = Outcome.builder()
//                .id("10")
//                .odds("1.22")
//                .probability("0.7700953189")
//                .voidProbability("0E-10")
//                .isActive(1)
//                .desc("Home or Away")
//                .cashOutIsActive(1)
//                .build();
//
//        Outcome dcDrawAwayOutcome = Outcome.builder()
//                .id("11")
//                .odds("1.15")
//                .probability("0.8258427731")
//                .voidProbability("0E-10")
//                .isActive(1)
//                .desc("Draw or Away")
//                .cashOutIsActive(1)
//                .build();
//
//        Market marketDoubleChance = Market.builder()
//                .id("10")
//                .product(1)
//                .desc("Double Chance")
//                .status(0)
//                .group("Main")
//                .groupId("171121135724MGI11262166")
//                .marketGuide("Predict the match result combining two of the three possible outcomes.")
//                .title("1X,12,X2")
//                .name("Double Chance")
//                .favourite(0)
//                .outcomes(List.of(dcHomeDrawOutcome, dcHomeAwayOutcome, dcDrawAwayOutcome))
//                .farNearOdds(0)
//                .sourceType("BET_RADAR")
//                .availableScore("0:2")
//                .lastOddsChangeTime(1761249320445L)
//                .banned(false)
//                .cashOutStatus(1)
//                .build();
//
//        List<Market> markets = new ArrayList<>();
//        markets.add(market1X2);
//        markets.add(marketOverUnder);
//        markets.add(marketDoubleChance);
//
//        // Create and return SportyEvent
//        return SportyEvent.builder()
//                .eventId("sr:match:63377841")
//                .gameId("40614")
//                .productStatus("0#0")
//                .estimateStartTime(1761246000000L)
//                .status(1)
//                .setScore("0:2")
//                .gameScore(List.of("0:2"))
//                .period("1")
//                .matchStatus("HT")
//                .playedSeconds("45:00")
//                .homeTeamId("sr:competitor:2702")
//                .homeTeamName("Roma")
//                .awayTeamName("Viktoria Plzen")
//                .awayTeamId("sr:competitor:4502")
//                .sport(sport)
//                .totalMarketSize(3)
//                .markets(markets)
//                .build();
//    }
//}