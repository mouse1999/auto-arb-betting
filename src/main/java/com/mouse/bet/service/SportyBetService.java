package com.mouse.bet.service;

import com.mouse.bet.detector.ArbDetector;
import com.mouse.bet.enums.*;
import com.mouse.bet.interfaces.NormalizedEvent;
import com.mouse.bet.interfaces.OddService;
import com.mouse.bet.model.NormalizedMarket;
import com.mouse.bet.model.NormalizedOutcome;
import com.mouse.bet.model.sporty.Market;
import com.mouse.bet.model.sporty.Outcome;
import com.mouse.bet.model.sporty.SportyEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service
@Slf4j
public class SportyBetService implements OddService<SportyEvent> {
    private final ArbDetector arbDetector;
    private final TeamAliasService teamAliasService;

    public SportyBetService(ArbDetector arbDetector, TeamAliasService teamAliasService) {
        this.arbDetector = arbDetector;
        this.teamAliasService = teamAliasService;
    }


    @Override
    public NormalizedEvent convertToNormalEvent(SportyEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("SportyEvent cannot be null");
        }

        String league = event.getSport().getCategory().getTournament().getName();
        String homeTeam = teamAliasService.canonicalOrSelf(event.getHomeTeamName());
        String awayTeam = teamAliasService.canonicalOrSelf(event.getAwayTeamName());
        String eventId = generateEventId(event); // Implement this based on your logic

        log.info("Converting SportyEvent - league: '{}', home: '{}', away: '{}'", league, homeTeam, awayTeam);

        // Get all three maps: odds, status, and cashout
        Map<String, String> rawOddsMap = convertMarketsToOddsMap(event.getMarkets());
        Map<String, Integer> statusMap = mapOutcomeStatus(event.getMarkets());
        Map<String, Integer> cashOutMap = mapOutcomeCashOutIndicator(event.getMarkets());

        log.info("Converted maps - odds: {}, status: {}, cashout: {}",
                rawOddsMap.size(), statusMap.size(), cashOutMap.size());

        // Pass all three maps to normalization
        List<NormalizedMarket> markets = normalizeMarkets(
                rawOddsMap,
                statusMap,
                cashOutMap,
                BookMaker.SPORTY_BET,
                eventId,
                league,
                homeTeam,
                awayTeam,
                event
        );

        log.info("Normalized {} markets for SportyEvent", markets.size());

        return NormalizedEvent.builder()
                .eventId(eventId)
                .eventName(homeTeam + " vs " + awayTeam)
                .homeTeam(homeTeam)
                .awayTeam(awayTeam)
                .league(league)
                .sport(Sport.fromName(event.getSport().getName()).get())
                .estimateStartTime(event.getEstimateStartTime())
                .bookie(BookMaker.SPORTY_BET)
                .markets(markets)
                .build();
    }

    public Map<String, String> convertMarketsToOddsMap(List<Market> markets) {
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

    public Map<String, Integer> mapOutcomeStatus(List<Market> markets) {
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

    public Map<String, Integer> mapOutcomeCashOutIndicator(List<Market> markets) {
        log.info("Mapping each outcomeCashOutIndicator...");
        if (markets == null) {
            log.info("Markets list is null, returning empty map");
            return Map.of();
        }

        Map<String, Integer> cashoutMap = markets.stream()
                .flatMap(market -> market.getOutcomes().stream()
                        .filter(outcome -> isValidOdds(outcome.getOdds()))
                        .map(outcome -> {
                            String key = generateProviderKey(market, outcome);
                            log.debug("Mapping cashout - key: '{}', cashout: '{}'", key, outcome.getCashOutIsActive());
                            return Map.entry(key, (outcome.getCashOutIsActive()));
                        }))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a
                ));

        log.info("Converted cashout map size: {}", cashoutMap.size());
        return cashoutMap;
    }

    /**
     * Updated method signature to accept all three maps
     */
    public List<NormalizedMarket> normalizeMarkets(
            Map<String, String> rawOdds,
            Map<String, Integer> statusMap,
            Map<String, Integer> cashOutMap,
            BookMaker bookmaker,
            String eventId,
            String leagueName,
            String homeTeam,
            String awayTeam,
            SportyEvent event) {

        log.info("Normalizing markets - bookmaker='{}', eventId='{}', league='{}', home='{}', away='{}'",
                bookmaker, eventId, leagueName, homeTeam, awayTeam);

        if (rawOdds == null || rawOdds.isEmpty()) {
            log.info("Raw odds map is empty or null, returning empty list");
            return List.of();
        }

        // Group by category
        Map<MarketCategory, List<String>> groupedByCategory = rawOdds.keySet()
                .stream()
                .filter(key -> SportyMarketType.safeFromProviderKey(key).isPresent())
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
                            category, marketKeys, rawOdds, statusMap, cashOutMap,
                            eventId, leagueName, homeTeam, awayTeam, event)
                            : createIndividualMarkets(
                            marketKeys, rawOdds, statusMap, cashOutMap,
                            eventId, leagueName, homeTeam, awayTeam, event)
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
            Map<String, Integer> cashOutMap,
            String eventId,
            String leagueName,
            String homeTeam,
            String awayTeam,
            SportyEvent event) {

        log.info("Creating individual markets, keys count: {}", marketKeys.size());

        List<NormalizedMarket> markets = marketKeys.stream()
                .map(key -> {
                    SportyMarketType marketType = SportyMarketType.fromProviderKey(key);
                    String odds = rawOdds.get(key);
                    Integer status = statusMap.getOrDefault(key, 0);
                    Integer cashOut = cashOutMap.getOrDefault(key, 0);

                    log.debug("Creating individual market - key: '{}', odds: '{}', status: {}, cashout: {}",
                            key, odds, status, cashOut);

                    NormalizedOutcome outcome = createNormalizedOutcome(
                            marketType, key, odds, status, cashOut,
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
            Map<String, Integer> cashOutMap,
            String eventId,
            String leagueName,
            String homeTeam,
            String awayTeam,
            SportyEvent event) {

        log.info("Creating grouped market for category '{}', keys count: {}", category, marketKeys.size());

        List<NormalizedOutcome> outcomes = marketKeys.stream()
                .map(key -> {
                    SportyMarketType marketType = SportyMarketType.fromProviderKey(key);
                    String odds = rawOdds.get(key);
                    Integer status = statusMap.getOrDefault(key, 0);
                    Integer cashOut = cashOutMap.getOrDefault(key, 0);

                    log.debug("Adding grouped outcome - key: '{}', odds: '{}', status: {}, cashout: {}",
                            key, odds, status, cashOut);

                    return createNormalizedOutcome(
                            marketType, key, odds, status, cashOut,
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
            Integer cashOut,
            String eventId,
            String leagueName,
            SportyEvent event) {

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
                .bookmaker(BookMaker.SPORTY_BET)
                .homeTeam(event.getHomeTeamName())
                .awayTeam(event.getAwayTeamName())
                .outcomeDescription(outcomeDesc) // Winning Team (Away)
                .isActive(true)
                .marketName(marketType.getNormalizedName())
                .eventStartTime(event.getEstimateStartTime()) // Example timestamp
                .sport(Sport.fromName(event.getSport().getName()).get())
                .outcomeStatus(outcomeStatus)
                .matchStatus(event.getMatchStatus())
                .setScore(event.getSetScore())
                .gameScore(event.getGameScore())
                .period(event.getPeriod())
                .matchStatus(event.getMatchStatus())
                .playedSeconds(event.getPlayedSeconds())
                .cashOutAvailable(cashOut)
                .build();
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
                MarketCategory.MATCH_RESULT
        ).contains(category);

        log.debug("Should group market '{}': {}", category, shouldGroup);
        return shouldGroup;
    }

    @Override
    public void addNormalizedEventToPool(NormalizedEvent normalizedEvent) {
        if(normalizedEvent == null) {


        }
        arbDetector.addEventToPool(normalizedEvent);
        //check if the event was missed in the previous execution

    }
}
