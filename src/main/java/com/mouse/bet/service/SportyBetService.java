package com.mouse.bet.service;

import com.mouse.bet.detector.ArbDetector;

import com.mouse.bet.enums.*;
import com.mouse.bet.model.MarketMeta;
import com.mouse.bet.model.NormalizedEvent;
import com.mouse.bet.interfaces.OddService;
import com.mouse.bet.model.NormalizedMarket;
import com.mouse.bet.model.NormalizedOutcome;
import com.mouse.bet.model.sporty.Market;
import com.mouse.bet.model.sporty.Outcome;
import com.mouse.bet.model.sporty.SportyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;


@Service
@Slf4j
@RequiredArgsConstructor
public class SportyBetService implements OddService<SportyEvent> {
    private final ArbDetector arbDetector;
    private final TeamAliasService teamAliasService;


    @Override
    public NormalizedEvent convertToNormalEvent(SportyEvent event) {
        if (event == null) throw new IllegalArgumentException("SportyEvent cannot be null");

        String league   = event.getSport().getCategory().getTournament().getName();
        String homeTeam = teamAliasService.canonicalOrSelf(event.getHomeTeamName());
        String awayTeam = teamAliasService.canonicalOrSelf(event.getAwayTeamName());
        String eventId  = generateEventId(homeTeam, awayTeam, determineSport(event)); // TODO: your canonical strategy

        log.info("Converting SportyEvent - league: '{}', home: '{}', away: '{}'", league, homeTeam, awayTeam);

        Map<String, String>  rawOddsMap = convertMarketsToOddsMap(event.getMarkets());
        Map<String, Integer> statusMap  = mapOutcomeStatus(event.getMarkets());
        Map<String, Integer> cashOutMap = mapOutcomeCashOutIndicator(event.getMarkets());
        Map<String, MarketMeta> metaMap = buildMetaMap(event.getMarkets());

        log.info("Converted maps - odds: {}, status: {}, cashout: {}, meta: {}",
                rawOddsMap.size(), statusMap.size(), cashOutMap.size(), metaMap.size());

        List<NormalizedMarket> markets = normalizeMarkets(
                rawOddsMap, statusMap, cashOutMap, metaMap,
                BookMaker.SPORTY_BET, eventId, league, homeTeam, awayTeam, event
        );

        log.info("Normalized {} markets for SportyEvent", markets.size());

        return NormalizedEvent.builder()
                .eventId(eventId)
                .eventName(homeTeam + " vs " + awayTeam)
                .homeTeam(homeTeam)
                .awayTeam(awayTeam)
                .league(league)
                .sportEnum(SportEnum.fromName(event.getSport().getName()).orElse(SportEnum.FOOTBALL))
                .estimateStartTime(event.getEstimateStartTime())
                .bookie(BookMaker.SPORTY_BET)
                .markets(markets)
                .build();
    }



    // ------------------ map builders ------------------

    public Map<String, String> convertMarketsToOddsMap(List<Market> markets) {
        log.info("Converting markets to odds map...");
        if (markets == null) return Map.of();

        Map<String, String> oddsMap = markets.stream()
                .flatMap(market -> market.getOutcomes().stream()
                        .filter(outcome -> isValidOdds(outcome.getOdds()))
                        .map(outcome -> Map.entry(generateProviderKey(market, outcome), outcome.getOdds())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

        log.info("Converted odds map size: {}", oddsMap.size());
        return oddsMap;
    }

    public Map<String, Integer> mapOutcomeStatus(List<Market> markets) {
        log.info("Mapping each outcome status...");
        if (markets == null) return Map.of();

        Map<String, Integer> statusMap = markets.stream()
                .flatMap(market -> market.getOutcomes().stream()
                        .filter(outcome -> isValidOdds(outcome.getOdds()))
                        .map(outcome -> Map.entry(generateProviderKey(market, outcome), outcome.getIsActive())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

        log.info("Converted status map size: {}", statusMap.size());
        return statusMap;
    }

    public Map<String, Integer> mapOutcomeCashOutIndicator(List<Market> markets) {
        log.info("Mapping each outcomeCashOutIndicator...");
        if (markets == null || markets.isEmpty()) return Map.of();

        Map<String, Integer> cashoutMap = markets.stream()
                .filter(Objects::nonNull)
                .filter(m -> m.getOutcomes() != null && !m.getOutcomes().isEmpty())
                .flatMap(m -> m.getOutcomes().stream()
                        .filter(Objects::nonNull)
                        .filter(o -> isValidOdds(o.getOdds()))
                        .map(o -> {
                            String key = generateProviderKey(m, o);
                            if (key == null) return null;                     // no null keys
                            Integer flag = o.getCashOutIsActive();             // may be null
                            return new java.util.AbstractMap.SimpleEntry<>(
                                    key, flag == null ? 0 : flag               // default missing to 0
                            );
                        }))
                .filter(Objects::nonNull)                                      // drop null entries
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,                                           // keep first on duplicate keys
                        LinkedHashMap::new                                      // preserve encounter order
                ));

        log.info("Converted cashout map size: {}", cashoutMap.size());
        return cashoutMap;
    }


    /** Build meta map keyed by providerKey (marketId + specifier + outcomeDesc). */
    public Map<String, MarketMeta> buildMetaMap(List<Market> markets) {
        if (markets == null) return Map.of();
        return markets.stream()
                .flatMap(market -> market.getOutcomes().stream().map(outcome -> {
                    String key = generateProviderKey(market, outcome);
                    return Map.entry(key, new MarketMeta(
                            market.getName(),
                            market.getTitle(),
                            market.getGroup(),
                            market.getSpecifier(),
                            market.getId()
                    ));
                }))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    // ------------------ normalization ------------------

    public List<NormalizedMarket> normalizeMarkets(
            Map<String, String> rawOdds,
            Map<String, Integer> statusMap,
            Map<String, Integer> cashOutMap,
            Map<String, MarketMeta> metaMap,
            BookMaker bookmaker,
            String eventId,
            String leagueName,
            String homeTeam,
            String awayTeam,
            SportyEvent event) {

        log.info("Normalizing markets - bookmaker='{}', eventId='{}', league='{}', home='{}', away='{}'",
                bookmaker, eventId, leagueName, homeTeam, awayTeam);

        if (rawOdds == null || rawOdds.isEmpty()) return List.of();

        Map<MarketCategory, List<String>> groupedByCategory = rawOdds.keySet().stream()
                .filter(key -> SportyMarketType.safeFromProviderKey(key).isPresent())
                .collect(Collectors.groupingBy(key -> SportyMarketType.fromProviderKey(key).getCategory()));

        log.info("Grouped markets into {} categories", groupedByCategory.size());

        return groupedByCategory.entrySet().stream()
                .flatMap(entry -> {
                    MarketCategory category = entry.getKey();
                    List<String> marketKeys = entry.getValue();
                    return (shouldGroupMarket(category)
                            ? createGroupedMarket(category, marketKeys, rawOdds, statusMap, cashOutMap, metaMap,
                            eventId, leagueName, event)
                            : createIndividualMarkets(marketKeys, rawOdds, statusMap, cashOutMap, metaMap,
                            eventId, leagueName, event)
                    ).stream();
                })
                .collect(Collectors.toList());
    }

    private List<NormalizedMarket> createIndividualMarkets(
            List<String> marketKeys,
            Map<String, String> rawOdds,
            Map<String, Integer> statusMap,
            Map<String, Integer> cashOutMap,
            Map<String, MarketMeta> metaMap,
            String eventId,
            String leagueName,
            SportyEvent event) {

        return marketKeys.stream().map(key -> {
            SportyMarketType marketType = SportyMarketType.fromProviderKey(key);
            String odds   = rawOdds.get(key);
            Integer st    = statusMap.getOrDefault(key, 0);
            Integer cash  = cashOutMap.getOrDefault(key, 0);
            MarketMeta mm = metaMap.get(key);

            NormalizedOutcome outcome = createNormalizedOutcome(
                    marketType, key, odds, st, cash, eventId, leagueName, event, mm
            );

            return new NormalizedMarket(marketType.getCategory(), List.of(outcome));
        }).collect(Collectors.toList());
    }

    private List<NormalizedMarket> createGroupedMarket(
            MarketCategory category,
            List<String> marketKeys,
            Map<String, String> rawOdds,
            Map<String, Integer> statusMap,
            Map<String, Integer> cashOutMap,
            Map<String, MarketMeta> metaMap,
            String eventId,
            String leagueName,
            SportyEvent event) {

        List<NormalizedOutcome> outcomes = marketKeys.stream().map(key -> {
            SportyMarketType marketType = SportyMarketType.fromProviderKey(key);
            String odds   = rawOdds.get(key);
            Integer st    = statusMap.getOrDefault(key, 0);
            Integer cash  = cashOutMap.getOrDefault(key, 0);
            MarketMeta mm = metaMap.get(key);

            return createNormalizedOutcome(
                    marketType, key, odds, st, cash, eventId, leagueName, event, mm
            );
        }).collect(Collectors.toList());

        return List.of(new NormalizedMarket(category, outcomes));
    }

    // ------------------ outcome factory ------------------

    private NormalizedOutcome createNormalizedOutcome(
            SportyMarketType marketType,
            String providerKey,
            String odds,
            Integer status,
            Integer cashOut,
            String eventId,
            String leagueName,
            SportyEvent event,
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
                .bookmaker(BookMaker.SPORTY_BET)
                .homeTeam(event.getHomeTeamName())
                .awayTeam(event.getAwayTeamName())
                .outcomeDescription(outcomeDesc)
//                .eventName(ev)
                .isActive(true)
                .eventStartTime(event.getEstimateStartTime())
                .sportEnum(determineSport(event))
                .outcomeStatus(outcomeStatus)
                .matchStatus(event.getMatchStatus())
                .setScore(event.getSetScore())
                .gameScore(event.getGameScore())
                .period(event.getPeriod())
                .playedSeconds(event.getPlayedSeconds())
                .cashOutAvailable(cashOut)
                .providerMarketName(meta != null ? meta.name()  : null)
                .providerMarketTitle(meta != null ? meta.title() : null)
                .marketId(String.valueOf(meta != null ? meta.marketId() : null))

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
    private String generateEventId(String homeTeam, String awayTeam, SportEnum sportEnum) {
        return sportEnum.getName() + "|" + homeTeam + "|" + awayTeam;

    }


    private SportEnum determineSport(SportyEvent event) {

        return switch (event.getSport().getName()) {
            case "Basketball" -> SportEnum.BASKETBALL;
            case "Table Tennis" -> SportEnum.TABLE_TENNIS; //TODO
            default -> SportEnum.FOOTBALL;
        };
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
                MarketCategory.GAME_POINT_HANDICAP
//                MarketCategory.BASKETBALL_HANDICAP
        ).contains(category);

        log.debug("Should group market '{}': {}", category, shouldGroup);
        return shouldGroup;
    }

    @Override
    public void addNormalizedEventToPool(NormalizedEvent normalizedEvent) {
        if(normalizedEvent == null) {
            throw new IllegalArgumentException("");
        }
        arbDetector.addEventToPool(normalizedEvent);
        //check if the event was missed in the previous execution

    }
}
