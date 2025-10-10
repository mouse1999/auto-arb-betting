package com.mouse.bet.service;

import com.mouse.bet.detector.ArbDetector;

import com.mouse.bet.enums.*;
import com.mouse.bet.model.NormalizedEvent;
import com.mouse.bet.interfaces.OddService;
import com.mouse.bet.model.NormalizedMarket;
import com.mouse.bet.model.NormalizedOutcome;
import com.mouse.bet.model.msport.MSportEvent;
import com.mouse.bet.model.msport.MsMarket;
import com.mouse.bet.model.sporty.Market;
import com.mouse.bet.model.sporty.Outcome;
import com.mouse.bet.model.sporty.SportyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
@Slf4j
public class MSportService implements OddService<MSportEvent> {

    private final ArbDetector arbDetector;
    private final TeamAliasService teamAliasService;
    @Override
    public NormalizedEvent convertToNormalEvent(MSportEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("SportyEvent cannot be null");
        }


        String homeTeam = teamAliasService.canonicalOrSelf(event.getHomeTeam());
        String awayTeam = teamAliasService.canonicalOrSelf(event.getAwayTeam());
        String eventId = generateEventId(event); // Implement this based on your logic
        String league = event.getTournament();

        log.info("Converting SportyEvent - league: '{}', home: '{}', away: '{}'", league, homeTeam, awayTeam);

        // Get all three maps: odds, status, and cashout
        Map<String, String> rawOddsMap = convertMarketsToOddsMap(event.getMarkets());
        Map<String, Integer> statusMap = mapOutcomeStatus(event.getMarkets());


        log.info("Converted maps - odds and status");

        // Pass all three maps to normalization
        List<NormalizedMarket> markets = normalizeMarkets(
                rawOddsMap,
                statusMap,
                BookMaker.SPORTY_BET,
                eventId,
                homeTeam,
                awayTeam,
                league,
                event
        );
        return NormalizedEvent.builder()
                .eventId(eventId)
                .eventName(homeTeam + " vs " + awayTeam)
                .homeTeam(homeTeam)
                .awayTeam(awayTeam)
                .league(league)
                .sport(determineSport(event))
                .bookie(BookMaker.SPORTY_BET)
                .markets(markets)
                .build();
    }

    public Map<String, String> convertMarketsToOddsMap(List<MsMarket> markets) {
        log.info("Converting markets to odds map...");
        if (markets == null) {
            log.info("Markets list is null, returning empty map");
            return Map.of();
        }

        Map<String, String> oddsMap = markets.stream()
                .flatMap(market -> market.getOutcomes().stream()
                        .filter(outcome -> isValidOdds(outcome.getOdds()))
                        .map(outcome -> {
                            String key = generateProviderKey(market, outcome);
                            log.debug("Mapping odds - key: '{}', odds: '{}'", key, outcome.getOdds());
                            return Map.entry(key, outcome.getOdds());
                        }))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a
                ));

        log.info("Converted odds map size: {}", oddsMap.size());
        return oddsMap;
    }

    public Map<String, Integer> mapOutcomeStatus(List<MsMarket> markets) {
        log.info("Mapping each outcome status...");
        if (markets == null) {
            log.info("Markets list is null, returning empty map");
            return Map.of();
        }

        Map<String, Integer> statusMap = markets.stream()
                .flatMap(market -> market.getOutcomes().stream()
                        .filter(outcome -> isValidOdds(outcome.getOdds()))
                        .map(outcome -> {
                            String key = generateProviderKey(market, outcome);
                            log.debug("Mapping status - key: '{}', status: '{}'", key, outcome.getIsActive());
                            return Map.entry(key, outcome.getIsActive());
                        }))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a
                ));

        log.info("Converted status map size: {}", statusMap.size());
        return statusMap;
    }

    /**
     * Updated method signature to accept all three maps
     */
    public List<NormalizedMarket> normalizeMarkets(
            Map<String, String> rawOdds,
            Map<String, Integer> statusMap,
            BookMaker bookmaker,
            String eventId,
            String homeTeam,
            String awayTeam,
            String league,
            MSportEvent event) {

        log.info("Normalizing markets - bookmaker='{}', eventId='{}', league='{}', home='{}', away='{}'",
                bookmaker, eventId, league, homeTeam, awayTeam);

        if (rawOdds == null || rawOdds.isEmpty()) {
            log.info("Raw odds map is empty or null, returning empty list");
            return List.of();
        }

        // Group by category
        Map<MarketCategory, List<String>> groupedByCategory = rawOdds.keySet()
                .stream()
                .filter(key -> MSportMarketType.safeFromProviderKey(key).isPresent())
                .collect(Collectors.groupingBy(key ->
                        SportyMarketType.fromProviderKey(key).getCategory()
                ));

        log.info("Grouped markets into {} categories", groupedByCategory.size());

        // Create normalized markets
        List<NormalizedMarket> normalizedMarkets = groupedByCategory.entrySet().stream()
                .flatMap(categoryEntry -> {
                    MarketCategory category = categoryEntry.getKey();
                    List<String> marketKeys = categoryEntry.getValue();
                    log.info("Processing category '{}', keys count: {}", category, marketKeys.size());

                    return (shouldGroupMarket(category)
                            ? createGroupedMarket(
                            category, marketKeys, rawOdds, statusMap,
                            eventId, league, event)
                            : createIndividualMarkets(
                            marketKeys, rawOdds, statusMap,
                            eventId, league, event)
                    ).stream();
                })
                .collect(Collectors.toList());

        log.info("Normalized markets count: {}", normalizedMarkets.size());
        return normalizedMarkets;
    }

    /**
     * Updated to include all outcome data
     */
    private List<NormalizedMarket> createIndividualMarkets(
            List<String> marketKeys,
            Map<String, String> rawOdds,
            Map<String, Integer> statusMap,
            String eventId,
            String leagueName,

            MSportEvent event) {

        log.info("Creating individual markets, keys count: {}", marketKeys.size());

        List<NormalizedMarket> markets = marketKeys.stream()
                .map(key -> {
                    SportyMarketType marketType = SportyMarketType.fromProviderKey(key);
                    String odds = rawOdds.get(key);
                    Integer status = statusMap.getOrDefault(key, 0);

                    log.debug("Creating individual market - key: '{}', odds: '{}', status: {}",
                            key, odds, status);

                    NormalizedOutcome outcome = createNormalizedOutcome(
                            marketType, key, odds, status,
                            eventId, leagueName, event
                    );

                    return new NormalizedMarket(
                            marketType.getCategory(),
                            List.of(outcome)
                    );
                })
                .collect(Collectors.toList());

        log.info("Created {} individual markets", markets.size());
        return markets;
    }

    /**
     * Updated to include all outcome data
     */
    private List<NormalizedMarket> createGroupedMarket(
            MarketCategory category,
            List<String> marketKeys,
            Map<String, String> rawOdds,
            Map<String, Integer> statusMap,
            String eventId,
            String leagueName,
            MSportEvent event) {

        log.info("Creating grouped market for category '{}', keys count: {}", category, marketKeys.size());

        List<NormalizedOutcome> outcomes = marketKeys.stream()
                .map(key -> {
                    SportyMarketType marketType = SportyMarketType.fromProviderKey(key);
                    String odds = rawOdds.get(key);
                    Integer status = statusMap.getOrDefault(key, 0);

                    log.debug("Adding grouped outcome - key: '{}', odds: '{}', status: {}",
                            key, odds, status);

                    return createNormalizedOutcome(
                            marketType, key, odds, status,
                            eventId, leagueName, event
                    );
                })
                .collect(Collectors.toList());

        return List.of(new NormalizedMarket(category, outcomes));
    }

    /**
     * Helper method to create a fully populated NormalizedOutcome
     */
    private NormalizedOutcome createNormalizedOutcome(
            SportyMarketType marketType,
            String providerKey,
            String odds,
            Integer status,
            String eventId,
            String leagueName,
            MSportEvent event) {

        // Parse odds safely
        BigDecimal oddsValue;
        try {
            oddsValue = new BigDecimal(odds);
        } catch (NumberFormatException e) {
            log.error("Failed to parse odds '{}' for key '{}'", odds, providerKey);
            oddsValue = BigDecimal.ZERO;
        }


        OutcomeStatus outcomeStatus = (status == 1)
                ? OutcomeStatus.AVAILABLE
                : OutcomeStatus.SUSPENDED;

        // Extract outcome description from provider key
        String outcomeDesc = extractOutcomeDescription(providerKey, marketType);

        return NormalizedOutcome.builder()
                // Core identifiers
                .outcomeId(providerKey)
                .eventId(eventId)
                .marketType(marketType)
                .league(leagueName)
                .odds(oddsValue)
                .bookmaker(BookMaker.M_SPORT)
                .homeTeam(event.getHomeTeam())
                .awayTeam(event.getAwayTeam())
                .outcomeDescription(outcomeDesc) // Winning Team (Away)
                .isActive(true)
                .marketName(marketType.getNormalizedName())
                .sport(determineSport(event))
                .outcomeStatus(outcomeStatus)
                .matchStatus(extractMatchStatus(event))
                .setScore(String.valueOf(event.getAllScores()))
                .gameScore(extractScore(event))
                .period(extractPeriod(event))
                .playedSeconds(event.getPlayedTime())
                .build();
    }

    private String extractPeriod(MSportEvent event) {
        return event.getAllScores().size() + "";
    }

    /**
     * Extracts the match period/status from the scores list based on size rules.
     * - If the outer list contains one item, returns the period of that item (index 0 of inner list).
     * - If the outer list contains two or more items, returns the period of the second item (index 1 of outer list).
     * * @param event The MSportEvent containing the scores list.
     * @return The period string (e.g., "h1" or "ft"), or null if the list is empty/null or index is out of bounds.
     */
    private String extractMatchStatus(MSportEvent event) {
        List<List<String>> gameScoreList = event.getAllScores();
        
        if (gameScoreList == null || gameScoreList.isEmpty()) {
            return null;
        }

        List<String> periodEntry = getStrings(gameScoreList);

        if (periodEntry != null && !periodEntry.isEmpty()) {
            return periodEntry.get(0);
        }

        return null;
    }

    private static List<String> getStrings(List<List<String>> gameScoreList) {
        int listSize = gameScoreList.size();

        int periodIndexToExtract;
        if (listSize == 1) {
            periodIndexToExtract = 0;
        } else {
            periodIndexToExtract = 1;

        }
        return gameScoreList.get(periodIndexToExtract);
    }

    /**
     * Extracts specific score strings (the second element of the inner list)
     * from the provided list of score lists, where inner lists are formatted as [period, score].
     * * - If the outer list is empty or null, returns null.
     * - If the list contains one item, returns a list containing the score string of that one item.
     * - If the list contains multiple items (>= 2), returns a list containing the score strings of the first item and the second item.
     *
     * @param event The MSportEvent containing the scores list.
     * @return A List<String> of extracted score strings (e.g., ["0:0", "1:0"]), or null if the input list is empty/null.
     */
    private List<String> extractScore(MSportEvent event) {

        List<List<String>> gameScoreList = event.getAllScores();
        List<String> resultScores = new ArrayList<>();

        if (gameScoreList == null || gameScoreList.isEmpty()) {
            return null;
        }
        if (gameScoreList.size() == 1) {

            List<String> scoreEntry = gameScoreList.get(0);
            if (scoreEntry.size() > 1) {
                resultScores.add(scoreEntry.get(1));
            }
        }
        else {

            List<String> firstScoreEntry = gameScoreList.get(0);
            if (firstScoreEntry.size() > 1) {
                resultScores.add(firstScoreEntry.get(1));
            }

            List<String> secondScoreEntry = gameScoreList.get(1);
            if (secondScoreEntry.size() > 1) {
                resultScores.add(secondScoreEntry.get(1));
            }
        }
        return resultScores;
    }

    /**
     * Extract outcome description from provider key
     */
    private String extractOutcomeDescription(String providerKey, SportyMarketType marketType) {
        return  marketType.getNormalizedName();
        //TODO
    }

    /**
     * Generate event ID based on your business logic
     */
    private String generateEventId(SportyEvent event) {
        return null;

    }

    private boolean isValidOdds(String odds) {
        try {
            BigDecimal value = new BigDecimal(odds);
            boolean valid = value.compareTo(BigDecimal.ZERO) > 0;
            log.debug("Odds '{}' validation: {}", odds, valid);
            return valid;
        } catch (Exception e) {
            log.warn("Invalid odds '{}': {}", odds, e.getMessage());
            return false;
        }
    }

    private String generateProviderKey(Market market, Outcome outcome) {
        String key = SportyMarketType.generateProviderKey(
                market.getId(),
                market.getSpecifier(),
                outcome.getDesc().trim().toUpperCase().replaceAll("\\s+", "_")
        );
        log.debug("Generated provider key: {}", key);
        return key;
    }

    private boolean shouldGroupMarket(MarketCategory category) {
        boolean shouldGroup = EnumSet.of(
                MarketCategory.OVER_UNDER_TOTAL,
                MarketCategory.DOUBLE_CHANCE,
                MarketCategory.BTTS,
                MarketCategory.ASIAN_HANDICAP_FULLTIME,
                MarketCategory.CORNERS_OVER_UNDER_FULLTIME,
                MarketCategory.DRAW_NO_BET,
                MarketCategory.OVER_UNDER_1STHALF,
                MarketCategory.OVER_UNDER_2NDHALF,
                MarketCategory.ODD_EVEN,
                MarketCategory.MATCH_RESULT,
                MarketCategory.BASKETBALL_MATCH_WINNER
        ).contains(category);

        log.debug("Should group market '{}': {}", category, shouldGroup);
        return shouldGroup;
    }

    private Sport determineSport(MSportEvent event) {

        return switch (event.getSport()) {
            case "Basketball" -> Sport.BASKETBALL;
            case "Table Tennis" -> Sport.TABLE_TENNIS;
            default -> Sport.FOOTBALL;
        };
    }




    @Override
    public void addNormalizedEventToPool(NormalizedEvent normalizedEvent) {

    }

}
