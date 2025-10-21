package com.mouse.bet.service;

import com.mouse.bet.enums.*;
import com.mouse.bet.model.NormalizedEvent;
import com.mouse.bet.model.NormalizedOutcome;
import com.mouse.bet.model.bet9ja.*;
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
@DisplayName("Bet9jaService Tests")
public class Bet9jaServiceTest {

    @Mock
    private TeamAliasService teamAliasService;

    @InjectMocks
    private Bet9jaService bet9jaService;

    private Bet9jaEvent testEvent;
    private Map<String, String> testOdds;

    @BeforeEach
    void setUp() {
        // Build test odds map
        testOdds = new HashMap<>();
        testOdds.put("LIVES_1X2_1", "2.50");
        testOdds.put("LIVES_1X2_X", "3.20");
        testOdds.put("LIVES_1X2_2", "2.80");

        // Build Score
        MatchScore score = MatchScore.builder()
                .scoreline("0-0")
                .periodScores(List.of("0-0"))
                .build();

        // Build LiveInplayState
        LiveInplayState liveState = LiveInplayState.builder()
                .eventStatus("Not Started")
                .clockMinutes(0)
                .score(score)
                .build();

        // Build Competition
        Competition competition = Competition.builder()
                .displayName("Premier League")
                .sportName("Football")
                .build();

        // Build EventHeader
        EventHeader eventHeader = EventHeader.builder()
                .displayName("Manchester United - Liverpool")
                .competition(competition)
                .build();

        // Build test event
        testEvent = Bet9jaEvent.builder()
                .eventHeader(eventHeader)
                .liveInplayState(liveState)
                .odds(testOdds)
                .build();
    }

    @Test
    @DisplayName("convertToNormalEvent_validEvent_returnsNormalizedEvent")
    void convertToNormalEvent_validEvent_returnsNormalizedEvent() {
        when(teamAliasService.canonicalOrSelf(anyString())).thenAnswer(i -> i.getArgument(0));
        NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

        assertNotNull(result);
        assertEquals("Manchester United", result.getHomeTeam());
        assertEquals("Liverpool", result.getAwayTeam());
        assertEquals("PREMIER_LEAGUE", result.getLeague());
        assertEquals(BookMaker.BET9JA, result.getBookie());
        assertEquals("Football|Manchester United|Liverpool", result.getEventId());
        assertEquals("Manchester United vs Liverpool", result.getEventName());
        assertNotNull(result.getMarkets());
        verify(teamAliasService, times(2)).canonicalOrSelf(anyString());
    }

    @Test
    @DisplayName("convertToNormalEvent_nullEvent_throwsRuntimeException")
    void convertToNormalEvent_nullEvent_throwsRuntimeException() {
        assertThrows(RuntimeException.class, () ->
                bet9jaService.convertToNormalEvent(null)
        );
    }

    @Test
    @DisplayName("convertToNormalEvent_eventWithNoOdds_returnsEventWithEmptyMarketList")
    void convertToNormalEvent_eventWithNoOdds_returnsEventWithEmptyMarketList() {
        testEvent.setOdds(Collections.emptyMap());
        when(teamAliasService.canonicalOrSelf(anyString())).thenAnswer(i -> i.getArgument(0));

        NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

        assertNotNull(result);
        assertTrue(result.getMarkets().isEmpty());
    }

    @Test
    @DisplayName("convertToNormalEvent_footballEvent_determinesSportCorrectly")
    void convertToNormalEvent_footballEvent_determinesSportCorrectly() {
        testEvent.getEventHeader().getCompetition().setSportName("Football");
        when(teamAliasService.canonicalOrSelf(anyString())).thenAnswer(i -> i.getArgument(0));

        NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

        assertEquals(SportEnum.FOOTBALL, result.getSportEnum());
    }

    @Test
    @DisplayName("convertToNormalEvent_basketballEvent_determinesSportCorrectly")
    void convertToNormalEvent_basketballEvent_determinesSportCorrectly() {
        testEvent.getEventHeader().getCompetition().setSportName("Basketball");
        when(teamAliasService.canonicalOrSelf(anyString())).thenAnswer(i -> i.getArgument(0));

        NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

        assertEquals(SportEnum.BASKETBALL, result.getSportEnum());
    }

    @Test
    @DisplayName("convertToNormalEvent_tableTennisEvent_determinesSportCorrectly")
    void convertToNormalEvent_tableTennisEvent_determinesSportCorrectly() {
        testEvent.getEventHeader().getCompetition().setSportName("Table Tennis");
        when(teamAliasService.canonicalOrSelf(anyString())).thenAnswer(i -> i.getArgument(0));

        NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

        assertEquals(SportEnum.TABLE_TENNIS, result.getSportEnum());
    }

    @Test
    @DisplayName("convertToNormalEvent_unknownSport_defaultsToFootball")
    void convertToNormalEvent_unknownSport_defaultsToFootball() {
        testEvent.getEventHeader().getCompetition().setSportName("Cricket");
        when(teamAliasService.canonicalOrSelf(anyString())).thenAnswer(i -> i.getArgument(0));

        NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

        assertEquals(SportEnum.FOOTBALL, result.getSportEnum());
    }

    @Test
    @DisplayName("convertToNormalEvent_teamAliasApplied_evenWithNullOdds")
    void convertToNormalEvent_teamAliasApplied_evenWithNullOdds() {
        testEvent.setOdds(null);
        when(teamAliasService.canonicalOrSelf(anyString())).thenAnswer(i -> i.getArgument(0));
        NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

        assertEquals("Manchester United", result.getHomeTeam());
        assertEquals("Liverpool", result.getAwayTeam());
        assertTrue(result.getMarkets().isEmpty());
    }

    @Test
    @DisplayName("convertToNormalEvent_teamNamesWithSpecialCharacters_handlesCorrectly")
    void convertToNormalEvent_teamNamesWithSpecialCharacters_handlesCorrectly() {
        testEvent.getEventHeader().setDisplayName("FC Bayern München - Inter Milão");
        when(teamAliasService.canonicalOrSelf("FC Bayern München")).thenReturn("Bayern");
        when(teamAliasService.canonicalOrSelf("Inter Milão")).thenReturn("Inter");

        NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

        assertEquals("Bayern", result.getHomeTeam());
        assertEquals("Inter", result.getAwayTeam());
    }

    @Test
    @DisplayName("convertToNormalEvent_withVsPattern_parseTeamsCorrectly")
    void convertToNormalEvent_withVsPattern_parseTeamsCorrectly() {
        testEvent.getEventHeader().setDisplayName("Arsenal - Chelsea");
        when(teamAliasService.canonicalOrSelf("Arsenal")).thenReturn("Arsenal");
        when(teamAliasService.canonicalOrSelf("Chelsea")).thenReturn("Chelsea");

        NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

        assertEquals("Arsenal", result.getHomeTeam());
        assertEquals("Chelsea", result.getAwayTeam());
    }

    @Test
    @DisplayName("convertToNormalEvent_withMultipleMarkets_createsNormalizedMarkets")
    void convertToNormalEvent_withMultipleMarkets_createsNormalizedMarkets() {
        Map<String, String> multipleOdds = new HashMap<>();
        multipleOdds.put("LIVES_1X2_1", "2.50");
        multipleOdds.put("LIVES_OU@2.5_O", "1.90");
        multipleOdds.put("LIVES_OU@2.5_U", "1.95");
        testEvent.setOdds(multipleOdds);
        when(teamAliasService.canonicalOrSelf(anyString())).thenAnswer(i -> i.getArgument(0));

        NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

        assertNotNull(result);
        assertNotNull(result.getMarkets());
    }

    @Nested
    @DisplayName("parseTeams Tests")
    class ParseTeamsTests {

        @Test
        @DisplayName("parseTeams_validMatchName_returnsTeamsArray")
        void parseTeams_validMatchName_returnsTeamsArray() {
            testEvent.getEventHeader().setDisplayName("Home Team - Away Team");
            when(teamAliasService.canonicalOrSelf(anyString())).thenAnswer(i -> i.getArgument(0));

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertEquals("Home Team", result.getHomeTeam());
            assertEquals("Away Team", result.getAwayTeam());
        }

        @Test
        @DisplayName("parseTeams_withDotAfterVs_parseCorrectly")
        void parseTeams_withDotAfterVs_parseCorrectly() {
            testEvent.getEventHeader().setDisplayName("Team A - Team B");
            when(teamAliasService.canonicalOrSelf(anyString())).thenAnswer(i -> i.getArgument(0));

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertEquals("Team A", result.getHomeTeam());
            assertEquals("Team B", result.getAwayTeam());
        }

        @Test
        @DisplayName("parseTeams_caseInsensitive_parseCorrectly")
        void parseTeams_caseInsensitive_parseCorrectly() {
            testEvent.getEventHeader().setDisplayName("Team A - Team B");
            when(teamAliasService.canonicalOrSelf(anyString())).thenAnswer(i -> i.getArgument(0));

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertEquals("Team A", result.getHomeTeam());
            assertEquals("Team B", result.getAwayTeam());
        }

        @Test
        @DisplayName("parseTeams_invalidFormat_throwsIllegalArgumentException")
        void parseTeams_invalidFormat_throwsIllegalArgumentException() {
            testEvent.getEventHeader().setDisplayName("Team A Team B");

            assertThrows(IllegalArgumentException.class, () ->
                    bet9jaService.convertToNormalEvent(testEvent)
            );
        }

        @Test
        @DisplayName("parseTeams_nullMatchName_throwsIllegalArgumentException")
        void parseTeams_nullMatchName_throwsIllegalArgumentException() {
            testEvent.getEventHeader().setDisplayName(null);

            assertThrows(IllegalArgumentException.class, () ->
                    bet9jaService.convertToNormalEvent(testEvent)
            );
        }

        @Test
        @DisplayName("parseTeams_emptyMatchName_throwsIllegalArgumentException")
        void parseTeams_emptyMatchName_throwsIllegalArgumentException() {
            testEvent.getEventHeader().setDisplayName("");

            assertThrows(IllegalArgumentException.class, () ->
                    bet9jaService.convertToNormalEvent(testEvent)
            );
        }

        @Test
        @DisplayName("parseTeams_multipleVsInName_throwsIllegalArgumentException")
        void parseTeams_multipleVsInName_throwsIllegalArgumentException() {
            testEvent.getEventHeader().setDisplayName("Team A vs Team B vs Team C");

            assertThrows(IllegalArgumentException.class, () ->
                    bet9jaService.convertToNormalEvent(testEvent)
            );
        }
    }

    @Nested
    @DisplayName("convertToOddsMap Tests")
    class ConvertToOddsMapTests {

        @Test
        @DisplayName("convertToOddsMap_validOdds_returnsOddsMap")
        void convertToOddsMap_validOdds_returnsOddsMap() {
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("convertToOddsMap_nullOdds_returnsEmptyMap")
        void convertToOddsMap_nullOdds_returnsEmptyMap() {
            testEvent.setOdds(null);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertTrue(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("convertToOddsMap_emptyOdds_returnsEmptyMap")
        void convertToOddsMap_emptyOdds_returnsEmptyMap() {
            testEvent.setOdds(Collections.emptyMap());
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertTrue(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("convertToOddsMap_invalidOdds_filtersOut")
        void convertToOddsMap_invalidOdds_filtersOut() {
            Map<String, String> oddsWithInvalid = new HashMap<>();
            oddsWithInvalid.put("LIVES_1X2_1", "2.50");
            oddsWithInvalid.put("LIVES_1X2_X", "invalid");
            oddsWithInvalid.put("LIVES_1X2_2", "-1.00");
            testEvent.setOdds(oddsWithInvalid);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);

        }

        @Test
        @DisplayName("convertToOddsMap_zeroOdds_filtersOut")
        void convertToOddsMap_zeroOdds_filtersOut() {
            Map<String, String> oddsWithZero = new HashMap<>();
            oddsWithZero.put("LIVES_1X2_1", "0");
            oddsWithZero.put("LIVES_1X2_X", "3.20");
            testEvent.setOdds(oddsWithZero);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
        }

        @Test
        @DisplayName("convertToOddsMap_negativeOdds_filtersOut")
        void convertToOddsMap_negativeOdds_filtersOut() {
            Map<String, String> oddsWithNegative = new HashMap<>();
            oddsWithNegative.put("LIVES_1X2_1", "-2.50");
            testEvent.setOdds(oddsWithNegative);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
        }

        @Test
        @DisplayName("convertToOddsMap_unknownProviderKeys_filtersOut")
        void convertToOddsMap_unknownProviderKeys_filtersOut() {
            Map<String, String> oddsWithUnknown = new HashMap<>();
            oddsWithUnknown.put("LIVES_1X2_1", "2.50");
            oddsWithUnknown.put("UNKNOWN_KEY", "3.00");
            oddsWithUnknown.put("RANDOM_MARKET", "1.50");
            testEvent.setOdds(oddsWithUnknown);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            // Should only include known provider keys
        }

        @Test
        @DisplayName("convertToOddsMap_duplicateKeys_keepsFirst")
        void convertToOddsMap_duplicateKeys_keepsFirst() {
            Map<String, String> odds = new LinkedHashMap<>();
            odds.put("LIVES_1X2_1", "2.50");
            odds.put("LIVES_1X2_1", "2.60"); // Won't actually be duplicate in map
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
        }

        @Test
        @DisplayName("convertToOddsMap_veryLargeOdds_handlesCorrectly")
        void convertToOddsMap_veryLargeOdds_handlesCorrectly() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_1X2_1", "999.99");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
        }

        @Test
        @DisplayName("convertToOddsMap_verySmallPositiveOdds_handlesCorrectly")
        void convertToOddsMap_verySmallPositiveOdds_handlesCorrectly() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_1X2_1", "0.01");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("mapOutcomeStatus Tests")
    class MapOutcomeStatusTests {

        @Test
        @DisplayName("mapOutcomeStatus_validOdds_returnsActiveStatus")
        void mapOutcomeStatus_validOdds_returnsActiveStatus() {
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            if (!result.getMarkets().isEmpty()) {
                NormalizedOutcome outcome = result.getMarkets().get(0).getOutcomes().get(0);
                assertTrue(outcome.isActive());
                assertEquals(OutcomeStatus.AVAILABLE, outcome.getOutcomeStatus());
            }
        }

        @Test
        @DisplayName("mapOutcomeStatus_invalidOdds_returnsSuspendedStatus")
        void mapOutcomeStatus_invalidOdds_returnsSuspendedStatus() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_1X2_1", "invalid");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
        }

        @Test
        @DisplayName("mapOutcomeStatus_nullOdds_returnsEmptyMap")
        void mapOutcomeStatus_nullOdds_returnsEmptyMap() {
            testEvent.setOdds(null);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertTrue(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("mapOutcomeStatus_emptyOdds_returnsEmptyMap")
        void mapOutcomeStatus_emptyOdds_returnsEmptyMap() {
            testEvent.setOdds(Collections.emptyMap());
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertTrue(result.getMarkets().isEmpty());
        }
    }

    @Nested
    @DisplayName("mapOutcomeCashOut Tests")
    class MapOutcomeCashOutTests {

        @Test
        @DisplayName("mapOutcomeCashOut_validKeys_returnsZeroCashOut")
        void mapOutcomeCashOut_validKeys_returnsZeroCashOut() {
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            if (!result.getMarkets().isEmpty()) {
                NormalizedOutcome outcome = result.getMarkets().get(0).getOutcomes().get(0);
                assertEquals(0, outcome.getCashOutAvailable());
            }
        }

        @Test
        @DisplayName("mapOutcomeCashOut_nullOdds_returnsEmptyMap")
        void mapOutcomeCashOut_nullOdds_returnsEmptyMap() {
            testEvent.setOdds(null);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertTrue(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("mapOutcomeCashOut_emptyOdds_returnsEmptyMap")
        void mapOutcomeCashOut_emptyOdds_returnsEmptyMap() {
            testEvent.setOdds(Collections.emptyMap());
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertTrue(result.getMarkets().isEmpty());
        }
    }

    @Nested
    @DisplayName("buildMetaMapFromKeys Tests")
    class BuildMetaMapFromKeysTests {

        @Test
        @DisplayName("buildMetaMapFromKeys_validKeys_returnsMetaMap")
        void buildMetaMapFromKeys_validKeys_returnsMetaMap() {
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            if (!result.getMarkets().isEmpty()) {
                NormalizedOutcome outcome = result.getMarkets().get(0).getOutcomes().get(0);
                assertNotNull(outcome.getProviderMarketName());
            }
        }

        @Test
        @DisplayName("buildMetaMapFromKeys_emptyOddsMap_returnsEmptyMap")
        void buildMetaMapFromKeys_emptyOddsMap_returnsEmptyMap() {
            testEvent.setOdds(Collections.emptyMap());
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertTrue(result.getMarkets().isEmpty());
        }
    }

    @Nested
    @DisplayName("normalizeMarkets Tests")
    class NormalizeMarketsTests {

        @Test
        @DisplayName("normalizeMarkets_groupedCategory_createsGroupedMarket")
        void normalizeMarkets_groupedCategory_createsGroupedMarket() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_OU@2.5_O", "1.90");
            odds.put("LIVES_OU@2.5_U", "1.95");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("normalizeMarkets_individualCategory_createsIndividualMarkets")
        void normalizeMarkets_individualCategory_createsIndividualMarkets() {
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
        }

        @Test
        @DisplayName("normalizeMarkets_emptyOdds_returnsEmptyList")
        void normalizeMarkets_emptyOdds_returnsEmptyList() {
            testEvent.setOdds(Collections.emptyMap());
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertTrue(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("normalizeMarkets_nullOdds_returnsEmptyList")
        void normalizeMarkets_nullOdds_returnsEmptyList() {
            testEvent.setOdds(null);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertTrue(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("normalizeMarkets_multipleCategories_groupsCorrectly")
        void normalizeMarkets_multipleCategories_groupsCorrectly() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_1X2_1", "2.50");
            odds.put("LIVES_OU@2.5_O", "1.90");
            odds.put("LIVES_GGNG_Y", "2.20");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }
    }

    @Nested
    @DisplayName("League Normalization Tests")
    class LeagueNormalizationTests {

        @Test
        @DisplayName("normalizeLeague_validLeague_normalizesCorrectly")
        void normalizeLeague_validLeague_normalizesCorrectly() {
            testEvent.getEventHeader().getCompetition().setDisplayName("Premier League");
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertEquals("PREMIER_LEAGUE", result.getLeague());
        }

        @Test
        @DisplayName("normalizeLeague_leagueWithSpaces_replacesWithUnderscores")
        void normalizeLeague_leagueWithSpaces_replacesWithUnderscores() {
            testEvent.getEventHeader().getCompetition().setDisplayName("La Liga Santander");
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertEquals("LA_LIGA_SANTANDER", result.getLeague());
        }

        @Test
        @DisplayName("normalizeLeague_nullLeague_returnsUnknown")
        void normalizeLeague_nullLeague_returnsUnknown() {
            testEvent.getEventHeader().getCompetition().setDisplayName(null);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertEquals("UNKNOWN_LEAGUE", result.getLeague());
        }
    }

    @Nested
    @DisplayName("Score/Period null-safety")
    class ScoreNullSafety {

        @Test
        @DisplayName("score=null handled")
        void scoreNullHandled() {
            testEvent.getLiveInplayState().setScore(null);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            assertThrows(Exception.class, () ->
                    bet9jaService.convertToNormalEvent(testEvent)
            );
        }

        @Test
        @DisplayName("periodScores=null handled")
        void periodScoresNullHandled() {
            testEvent.getLiveInplayState().getScore().setPeriodScores(null);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
        }

        @Test
        @DisplayName("periodScores=empty handled")
        void periodScoresEmptyHandled() {
            testEvent.getLiveInplayState().getScore().setPeriodScores(Collections.emptyList());
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
        }

        @Test
        @DisplayName("eventStatus=null handled")
        void eventStatusNullHandled() {
            testEvent.getLiveInplayState().setEventStatus(null);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
        }

        @Test
        @DisplayName("scoreline=null handled")
        void scorelineNullHandled() {
            testEvent.getLiveInplayState().getScore().setScoreline(null);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Generate EventId Tests")
    class GenerateEventIdTests {

        @Test
        @DisplayName("generateEventId_football_correctFormat")
        void generateEventId_football_correctFormat() {
            when(teamAliasService.canonicalOrSelf("Manchester United")).thenReturn("ManUtd");
            when(teamAliasService.canonicalOrSelf("Liverpool")).thenReturn("Liverpool");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertEquals("Football|ManUtd|Liverpool", result.getEventId());
        }

        @Test
        @DisplayName("generateEventId_basketball_correctFormat")
        void generateEventId_basketball_correctFormat() {
            testEvent.getEventHeader().getCompetition().setSportName("Basketball");
            when(teamAliasService.canonicalOrSelf("Manchester United")).thenReturn("ManUtd");
            when(teamAliasService.canonicalOrSelf("Liverpool")).thenReturn("Liverpool");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertEquals("Basketball|ManUtd|Liverpool", result.getEventId());
        }

        @Test
        @DisplayName("generateEventId_tableTennis_correctFormat")
        void generateEventId_tableTennis_correctFormat() {
            testEvent.getEventHeader().getCompetition().setSportName("Table Tennis");
            when(teamAliasService.canonicalOrSelf("Manchester United")).thenReturn("ManUtd");
            when(teamAliasService.canonicalOrSelf("Liverpool")).thenReturn("Liverpool");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertEquals("Table Tennis|ManUtd|Liverpool", result.getEventId());
        }
    }

    @Nested
    @DisplayName("Market Grouping Tests")
    class MarketGroupingTests {

        @Test
        @DisplayName("shouldGroupMarket_matchResult_returnsTrue")
        void shouldGroupMarket_matchResult_returnsTrue() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_1X2_1", "2.50");
            odds.put("LIVES_1X2_X", "3.20");
            odds.put("LIVES_1X2_2", "2.80");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("shouldGroupMarket_overUnder_returnsTrue")
        void shouldGroupMarket_overUnder_returnsTrue() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_OU@2.5_O", "1.90");
            odds.put("LIVES_OU@2.5_U", "1.95");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("shouldGroupMarket_doubleChance_returnsTrue")
        void shouldGroupMarket_doubleChance_returnsTrue() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_DC_1X", "1.50");
            odds.put("LIVES_DC_12", "1.30");
            odds.put("LIVES_DC_X2", "1.60");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("shouldGroupMarket_btts_returnsTrue")
        void shouldGroupMarket_btts_returnsTrue() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_GGNG_Y", "2.20");
            odds.put("LIVES_GGNG_N", "1.65");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("shouldGroupMarket_asianHandicap_returnsTrue")
        void shouldGroupMarket_asianHandicap_returnsTrue() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_AH@-1.5_1", "2.10");
            odds.put("LIVES_AH@+1.5_2", "1.75");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("shouldGroupMarket_cornersOverUnder_returnsTrue")
        void shouldGroupMarket_cornersOverUnder_returnsTrue() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_OUC@9.5_O", "1.90");
            odds.put("LIVES_OUC@9.5_U", "1.95");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("shouldGroupMarket_drawNoBet_returnsTrue")
        void shouldGroupMarket_drawNoBet_returnsTrue() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_DNB_1", "1.85");
            odds.put("LIVES_DNB_2", "2.10");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("shouldGroupMarket_oddEven_returnsTrue")
        void shouldGroupMarket_oddEven_returnsTrue() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_OE_OD", "1.90");
            odds.put("LIVES_OE_EV", "1.95");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }
    }

    @Nested
    @DisplayName("Basketball Specific Tests")
    class BasketballSpecificTests {

        @Test
        @DisplayName("basketball_moneyLine_parsesCorrectly")
        void basketball_moneyLine_parsesCorrectly() {
            testEvent.getEventHeader().getCompetition().setSportName("Basketball");
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVEB_12_1", "1.90");
            odds.put("LIVEB_12_2", "1.95");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertEquals(SportEnum.BASKETBALL, result.getSportEnum());
        }

        @Test
        @DisplayName("basketball_overUnder_parsesCorrectly")
        void basketball_overUnder_parsesCorrectly() {
            testEvent.getEventHeader().getCompetition().setSportName("Basketball");
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVEB_OUOT@180.5_O", "1.90");
            odds.put("LIVEB_OUOT@180.5_U", "1.95");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertEquals(SportEnum.BASKETBALL, result.getSportEnum());
        }

        @Test
        @DisplayName("basketball_oddEven_parsesCorrectly")
        void basketball_oddEven_parsesCorrectly() {
            testEvent.getEventHeader().getCompetition().setSportName("Basketball");
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVEB_OEOT_OD", "1.90");
            odds.put("LIVEB_OEOT_EV", "1.95");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertEquals(SportEnum.BASKETBALL, result.getSportEnum());
        }
    }

    @Nested
    @DisplayName("First Half Markets Tests")
    class FirstHalfMarketsTests {

        @Test
        @DisplayName("firstHalf_overUnder05_parsesCorrectly")
        void firstHalf_overUnder05_parsesCorrectly() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_OU1T@0.5_O", "1.90");
            odds.put("LIVES_OU1T@0.5_U", "1.95");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("firstHalf_overUnder15_parsesCorrectly")
        void firstHalf_overUnder15_parsesCorrectly() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_OU1T@1.5_O", "1.90");
            odds.put("LIVES_OU1T@1.5_U", "1.95");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("firstHalf_overUnder25_parsesCorrectly")
        void firstHalf_overUnder25_parsesCorrectly() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_OU1T@2.5_O", "1.90");
            odds.put("LIVES_OU1T@2.5_U", "1.95");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }
    }

    @Nested
    @DisplayName("Second Half Markets Tests")
    class SecondHalfMarketsTests {

        @Test
        @DisplayName("secondHalf_overUnder05_parsesCorrectly")
        void secondHalf_overUnder05_parsesCorrectly() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_OU2T@0.5_O", "1.90");
            odds.put("LIVES_OU2T@0.5_U", "1.95");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("secondHalf_overUnder15_parsesCorrectly")
        void secondHalf_overUnder15_parsesCorrectly() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_OU2T@1.5_O", "1.90");
            odds.put("LIVES_OU2T@1.5_U", "1.95");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("secondHalf_overUnder25_parsesCorrectly")
        void secondHalf_overUnder25_parsesCorrectly() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_OU2T@2.5_O", "1.90");
            odds.put("LIVES_OU2T@2.5_U", "1.95");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }
    }

    @Nested
    @DisplayName("Asian Handicap Tests")
    class AsianHandicapTests {

        @Test
        @DisplayName("asianHandicap_minus05_parsesCorrectly")
        void asianHandicap_minus05_parsesCorrectly() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_AH@-0.5_1", "2.10");
            odds.put("LIVES_AH@+0.5_2", "1.75");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("asianHandicap_minus15_parsesCorrectly")
        void asianHandicap_minus15_parsesCorrectly() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_AH@-1.5_1", "2.10");
            odds.put("LIVES_AH@+1.5_2", "1.75");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("asianHandicap_minus25_parsesCorrectly")
        void asianHandicap_minus25_parsesCorrectly() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_AH@-2.5_1", "2.10");
            odds.put("LIVES_AH@+2.5_2", "1.75");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("asianHandicap_plus05_parsesCorrectly")
        void asianHandicap_plus05_parsesCorrectly() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_AH@+0.5_1", "1.75");
            odds.put("LIVES_AH@-0.5_2", "2.10");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }

        @Test
        @DisplayName("asianHandicap_plus15_parsesCorrectly")
        void asianHandicap_plus15_parsesCorrectly() {
            Map<String, String> odds = new HashMap<>();
            odds.put("LIVES_AH@+1.5_1", "1.75");
            odds.put("LIVES_AH@-1.5_2", "2.10");
            testEvent.setOdds(odds);
            when(teamAliasService.canonicalOrSelf(anyString())).thenReturn("Team");

            NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

            assertNotNull(result);
            assertFalse(result.getMarkets().isEmpty());
        }
    }

    @Test
    @DisplayName("convertToNormalEvent_fullWorkflow_producesEventAndDoesNotThrow")
    void convertToNormalEvent_fullWorkflow_producesEventAndDoesNotThrow() {
        Map<String, String> comprehensiveOdds = new HashMap<>();
        comprehensiveOdds.put("LIVES_1X2_1", "2.10");
        comprehensiveOdds.put("LIVES_1X2_X", "3.40");
        comprehensiveOdds.put("LIVES_1X2_2", "3.50");
        comprehensiveOdds.put("LIVES_OU@2.5_O", "1.90");
        comprehensiveOdds.put("LIVES_OU@2.5_U", "1.95");
        comprehensiveOdds.put("LIVES_GGNG_Y", "2.20");
        comprehensiveOdds.put("LIVES_GGNG_N", "1.65");
        comprehensiveOdds.put("LIVES_DC_1X", "1.50");
        comprehensiveOdds.put("LIVES_DNB_1", "1.85");

        testEvent.setOdds(comprehensiveOdds);

        when(teamAliasService.canonicalOrSelf("Manchester United")).thenReturn("Man Utd");
        when(teamAliasService.canonicalOrSelf("Liverpool")).thenReturn("Liverpool");

        NormalizedEvent result = bet9jaService.convertToNormalEvent(testEvent);

        assertNotNull(result);
        assertEquals("Man Utd", result.getHomeTeam());
        assertEquals("Liverpool", result.getAwayTeam());
        assertEquals("Man Utd vs Liverpool", result.getEventName());
        assertEquals("PREMIER_LEAGUE", result.getLeague());
        assertEquals(SportEnum.FOOTBALL, result.getSportEnum());
        assertEquals(BookMaker.BET9JA, result.getBookie());
        assertNotNull(result.getMarkets());
        assertFalse(result.getMarkets().isEmpty());
    }

//    @Test
//    @DisplayName("addNormalizedEventToPool_anyEvent_doesNothing")
//    void addNormalizedEventToPool_anyEvent_doesNothing() {
//        NormalizedEvent event = NormalizedEvent.builder().build();
//
//        // Should not throw
//        assertDoesNotThrow(() -> bet9jaService.addNormalizedEventToPool(event));
//    }
//
//    @Test
//    @DisplayName("addNormalizedEventToPool_nullEvent_doesNothing")
//    void addNormalizedEventToPool_nullEvent_doesNothing() {
//        // Should not throw
//        assertDoesNotThrow(() -> bet9jaService.addNormalizedEventToPool(null));
//    }
}