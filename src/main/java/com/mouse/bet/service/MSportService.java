package com.mouse.bet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mouse.bet.detector.ArbDetector;

import com.mouse.bet.enums.*;
import com.mouse.bet.model.MarketMeta;
import com.mouse.bet.model.NormalizedEvent;
import com.mouse.bet.interfaces.OddService;
import com.mouse.bet.model.NormalizedMarket;
import com.mouse.bet.model.NormalizedOutcome;
import com.mouse.bet.model.msport.MSportEvent;
import com.mouse.bet.model.msport.MsMarket;
import com.mouse.bet.model.msport.MsOutcome;
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
    private final ObjectMapper objectMapper;


    @Override
    public NormalizedEvent convertToNormalEvent(MSportEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("SportyEvent cannot be null");
        }


        String homeTeam = teamAliasService.canonicalOrSelf(event.getHomeTeam());
        String awayTeam = teamAliasService.canonicalOrSelf(event.getAwayTeam());
        String eventId = generateEventId(homeTeam, awayTeam, determineSport(event)); // Implement this based on your logic
        String league = event.getTournament();

        log.info("Converting SportyEvent - league: '{}', home: '{}', away: '{}'", league, homeTeam, awayTeam);

        // Get all three maps: odds, status, and cashout
        Map<String, String> rawOddsMap = convertMarketsToOddsMap(event.getMarkets());
        Map<String, Integer> statusMap = mapOutcomeStatus(event.getMarkets());
        Map<String, MarketMeta> metaMap = buildMetaMap(event.getMarkets());


        log.info("Converted maps - odds={}, status={}, meta={}", rawOddsMap.size(), statusMap.size(), metaMap.size());

        List<NormalizedMarket> markets = normalizeMarkets(
                rawOddsMap,
                statusMap,
                metaMap,
                BookMaker.M_SPORT,
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
                .sportEnum(determineSport(event))
                .bookie(BookMaker.M_SPORT)
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


    /** Build meta map keyed by providerKey (marketId + specifiers + outcomeDesc). */
    public Map<String, MarketMeta> buildMetaMap(List<MsMarket> markets) {
        if (markets == null) return Map.of();
        return markets.stream()
                .flatMap(market -> market.getOutcomes().stream().map(outcome -> {
                    String key = generateProviderKey(market, outcome);
                    return Map.entry(key, new MarketMeta(
                            market.getDescription(), // the name feild of each market appears to be the title on the frontend
                            outcome.getDescription(), // name of the outcome to be chosen appears to be description
                            market.getGroup(),
                            market.getSpecifiers(),
                            market.getId()+""
                    ));
                }))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    /**
     * Updated method signature to accept all three maps
     */
    public List<NormalizedMarket> normalizeMarkets(
            Map<String, String> rawOdds,
            Map<String, Integer> statusMap,
            Map<String, MarketMeta> metaMap,
            BookMaker bookmaker,
            String eventId,
            String homeTeam,
            String awayTeam,
            String league,
            MSportEvent event) {

        log.info("Normalizing markets - bookmaker='{}', eventId='{}', league='{}', home='{}', away='{}'",
                bookmaker, eventId, league, homeTeam, awayTeam);

        if (rawOdds == null || rawOdds.isEmpty()) return List.of();

        // Group by category from MSportMarketType (NOT SportyMarketType)
        Map<MarketCategory, List<String>> groupedByCategory = rawOdds.keySet().stream()
                .filter(key -> MSportMarketType.safeFromProviderKey(key).isPresent())
                .collect(Collectors.groupingBy(key -> MSportMarketType.fromProviderKey(key).getCategory()));

        log.info("Grouped markets into {} categories", groupedByCategory.size());

        List<NormalizedMarket> normalizedMarkets = groupedByCategory.entrySet().stream()
                .flatMap(entry -> {
                    MarketCategory category = entry.getKey();
                    List<String> marketKeys = entry.getValue();
                    return (shouldGroupMarket(category)
                            ? createGroupedMarket(category, marketKeys, rawOdds, statusMap, metaMap, eventId, league, event)
                            : createIndividualMarkets(marketKeys, rawOdds, statusMap, metaMap, eventId, league, event)
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
            Map<String, MarketMeta> metaMap,
            String eventId,
            String leagueName,
            MSportEvent event) {

        return marketKeys.stream().map(key -> {
            MSportMarketType marketType = MSportMarketType.fromProviderKey(key);
            String odds  = rawOdds.get(key);
            Integer st   = statusMap.getOrDefault(key, 0);
            MarketMeta mm = metaMap.get(key);

            NormalizedOutcome outcome = createNormalizedOutcome(
                    marketType, key, odds, st, eventId, leagueName, event, mm
            );

            return new NormalizedMarket(marketType.getCategory(), List.of(outcome));
        }).collect(Collectors.toList());
    }

    /**
     * Updated to include all outcome data
     */
    private List<NormalizedMarket> createGroupedMarket(
            MarketCategory category,
            List<String> marketKeys,
            Map<String, String> rawOdds,
            Map<String, Integer> statusMap,
            Map<String, MarketMeta> metaMap,
            String eventId,
            String leagueName,
            MSportEvent event) {

        List<NormalizedOutcome> outcomes = marketKeys.stream().map(key -> {
            MSportMarketType marketType = MSportMarketType.fromProviderKey(key);
            String odds  = rawOdds.get(key);
            Integer st   = statusMap.getOrDefault(key, 0);
            MarketMeta mm = metaMap.get(key);

            return createNormalizedOutcome(
                    marketType, key, odds, st, eventId, leagueName, event, mm
            );
        }).collect(Collectors.toList());

        return List.of(new NormalizedMarket(category, outcomes));
    }

    /**
     * Helper method to create a fully populated NormalizedOutcome
     */
    private NormalizedOutcome createNormalizedOutcome(
            MSportMarketType marketType,
            String providerKey,
            String odds,
            Integer status,
            String eventId,
            String leagueName,
            MSportEvent event,
            MarketMeta meta) {

        BigDecimal oddsValue;
        try {
            oddsValue = new BigDecimal(odds);
        } catch (NumberFormatException e) {
            log.error("Failed to parse odds '{}' for key '{}'", odds, providerKey);
            oddsValue = BigDecimal.ZERO;
        }

        OutcomeStatus outcomeStatus = (status != null && status == 1)
                ? OutcomeStatus.AVAILABLE
                : OutcomeStatus.SUSPENDED;

        String outcomeDesc = extractOutcomeDescription(providerKey, marketType);

        return NormalizedOutcome.builder()
                .outcomeId(providerKey)
                .eventId(event.getEventId())
                .normalEventId(eventId)
                .marketType(marketType)
                .league(leagueName)
                .odds(oddsValue)
                .bookmaker(BookMaker.M_SPORT)
                .homeTeam(event.getHomeTeam())
                .awayTeam(event.getAwayTeam())
                .outcomeDescription(outcomeDesc)
                .isActive(true)
                .sportEnum(determineSport(event))
                .outcomeStatus(outcomeStatus)
                .matchStatus(event.getStatusDescription())
                .setScore(event.getScoreOfWholeMatch())
                .gameScore(getGameScoreList(event.getScoreOfSection()))
                .period(event.getStatusDescription())
                .playedSeconds(event.getPlayedTime())
                .providerMarketName(meta != null ? meta.outcomeName()  : null)
                .providerMarketTitle(meta != null ? meta.desc() : null)
                .marketId(String.valueOf(meta != null ? meta.marketId() : null))
                .build();
    }

    /**
     * Parses a JSON array string like "[\"11:5\",\"7:10\"]" into a List<String>
     *
     * @param inputString JSON-formatted string of game scores
     * @return List of score strings (e.g., ["11:5", "7:10"]), empty list if invalid
     */
    private List<String> getGameScoreList(String inputString) {
        if (inputString == null || inputString.isBlank()) {
            log.warn("Input string is null or blank");
            return new ArrayList<>();
        }

        String trimmed = inputString.trim();

        // Basic format check
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            log.warn("Input does not start/end with []: {}", inputString);
            return new ArrayList<>();
        }

        try {
            return objectMapper.readValue(
                    trimmed,
                    new TypeReference<List<String>>() {}
            );
        } catch (JsonProcessingException e) {
            log.error("Failed to parse JSON score list: {}", inputString, e);
            return new ArrayList<>();
        }
    }


    /**
     * Extract outcome description from provider key
     */
    private String extractOutcomeDescription(String providerKey, MSportMarketType marketType) {
        return  marketType.getNormalizedName();
        //TODO
    }

    /**
     * Generate event ID based on your business logic
     */
    private String generateEventId(String homeTeam, String awayTeam, SportEnum sportEnum) {
        return sportEnum.getName() + "|" + homeTeam + "|" + awayTeam;

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

    private String generateProviderKey(MsMarket market, MsOutcome outcome) {
        String key = MSportMarketType.generateProviderKey(
                market.getId()+"",
                market.getSpecifiers(),
                outcome.getDescription().trim().toUpperCase().replaceAll("\\s+", "_")
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
                MarketCategory.BASKETBALL_MATCH_WINNER,

                MarketCategory.GAME_POINT_HANDICAP,
                MarketCategory.POINT_HANDICAP,
                MarketCategory.TABLE_TENNIS_GAME_POINT
        ).contains(category);

        log.debug("Should group market '{}': {}", category, shouldGroup);
        return shouldGroup;
    }

    private SportEnum determineSport(MSportEvent event) {

        return switch (event.getSport()) {
            case "Basketball" -> SportEnum.BASKETBALL;
            case "Table Tennis" -> SportEnum.TABLE_TENNIS; //TODO
            default -> SportEnum.FOOTBALL;
        };
    }




    @Override
    public void addNormalizedEventToPool(NormalizedEvent normalizedEvent) {

        if (normalizedEvent == null) {
            throw new IllegalArgumentException("");
        }
        arbDetector.addEventToPool(normalizedEvent);


    }

}
