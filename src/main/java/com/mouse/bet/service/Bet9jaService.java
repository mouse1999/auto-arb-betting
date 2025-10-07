package com.mouse.bet.service;

import com.mouse.bet.enums.Bet9jaMarketType;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.enums.MarketCategory;
import com.mouse.bet.enums.OutcomeStatus;
import com.mouse.bet.model.NormalizedEvent;
import com.mouse.bet.interfaces.OddService;
import com.mouse.bet.model.NormalizedMarket;
import com.mouse.bet.model.NormalizedOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Slf4j
public class Bet9jaService implements OddService<Bet9jaEvent> {

    private final TeamAliasService teamAliasService;
    private static final Pattern TEAM_SPLIT_PATTERN = Pattern.compile("\\s+vs\\.?\\s+", Pattern.CASE_INSENSITIVE);
    @Override
    public NormalizedEvent convertToNormalEvent(Bet9jaEvent event) {
        log.info("Normalizing Bet9jaEvent: {}", event);

        if (event == null) {
            throw new Bet9jaNormalizingException("Event cannot be null");
        }


            String league = normalizeLeague(event.getGroupNameOrLeagueName());
            String[] teams = parseTeams(event.getMatchName());
            String homeTeam = teamAliasService.canonicalOrSelf(normalizeTeamName(teams[0]));
            String awayTeam = teamAliasService.canonicalOrSelf(normalizeTeamName(teams[1]));
            Instant eventStartTime = normalizeTimestamp(event.getStartDate());
            String eventId = generateEventId(eventStartTime, homeTeam, awayTeam);

            log.info("Normalized - league: '{}', home: '{}', away: '{}', eventId: '{}'",
                    league, homeTeam, awayTeam, eventId);

            // Convert raw odds to maps
            Map<String, String> rawOddsMap = convertToOddsMap(event.getOdds());
            Map<String, Integer> statusMap = mapOutcomeStatus(event.getOdds());
            Map<String, Integer> cashOutMap = mapOutcomeCashOut(event.getOdds());

            log.info("Converted maps - odds: {}, status: {}, cashout: {}",
                    rawOddsMap.size(), statusMap.size(), cashOutMap.size());

            // Normalize markets with all data
            List<NormalizedMarket> markets = normalizeMarkets(
                    rawOddsMap,
                    statusMap,
                    cashOutMap,
                    BookMaker.BET9JA,
                    eventId,
                    league,
                    homeTeam,
                    awayTeam,
                    event
            );

            log.info("Normalized {} markets for Bet9jaEvent", markets.size());

            return NormalizedEvent.builder()
                    .eventId(eventId)
                    .eventName(homeTeam + " vs " + awayTeam)
                    .homeTeam(homeTeam)
                    .awayTeam(awayTeam)
                    .league(league)
                    .sport(determineSport(event))
                    .estimateStartTime(eventStartTime.toEpochMilli())
                    .bookie(BookMaker.BET9JA)
                    .markets(markets)
                    .build();

    }

    /**
     * Convert raw odds to map of provider keys to odds values
     */
    private Map<String, String> convertToOddsMap(Map<String, String> rawOdds) {
        log.info("Converting odds to map...");

        if (rawOdds == null || rawOdds.isEmpty()) {
            log.info("Raw odds is null or empty, returning empty map");
            return Map.of();
        }

        Map<String, String> oddsMap = rawOdds.entrySet().stream()
                .filter(entry -> isValidOdds(entry.getValue()))
                .filter(entry -> Bet9jaMarketType.safeFromProviderKey(entry.getKey()).isPresent())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a
                ));

        log.info("Converted odds map size: {}", oddsMap.size());
        return oddsMap;
    }

    /**
     * Map outcome status (active/suspended)
     */
    private Map<String, Integer> mapOutcomeStatus(Map<String, String> rawOdds) {
        log.info("Mapping outcome status...");

        if (rawOdds == null) {
            return Map.of();
        }

        // Bet9ja specific: if odds exist and valid, assume active (1), else suspended (0)
        Map<String, Integer> statusMap = rawOdds.entrySet().stream()
                .filter(entry -> Bet9jaMarketType.safeFromProviderKey(entry.getKey()).isPresent())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> isValidOdds(entry.getValue()) ? 1 : 0,
                        (a, b) -> a
                ));

        log.info("Mapped status for {} outcomes", statusMap.size());
        return statusMap;
    }

    /**
     * Map cashout availability
     */
    private Map<String, Integer> mapOutcomeCashOut(Map<String, String> rawOdds) {
        log.info("Mapping cashout availability...");

        if (rawOdds == null) {
            return Map.of();
        }

        // Bet9ja specific: default cashout to 0 (not available) unless specified
        Map<String, Integer> cashOutMap = rawOdds.keySet().stream()
                .filter(key -> Bet9jaMarketType.safeFromProviderKey(key).isPresent())
                .collect(Collectors.toMap(
                        key -> key,
                        key -> 0, // Default: cashout not available
                        (a, b) -> a
                ));

        log.info("Mapped cashout for {} outcomes", cashOutMap.size());
        return cashOutMap;
    }

    /**
     * Normalize markets with full outcome data
     */
    private List<NormalizedMarket> normalizeMarkets(
            Map<String, String> rawOdds,
            Map<String, Integer> statusMap,
            Map<String, Integer> cashOutMap,
            BookMaker bookmaker,
            String eventId,
            String leagueName,
            String homeTeam,
            String awayTeam,
            Bet9jaEvent event) {

        log.info("Normalizing markets - bookmaker='{}', eventId='{}', league='{}'",
                bookmaker, eventId, leagueName);

        if (rawOdds == null || rawOdds.isEmpty()) {
            log.info("Raw odds empty, returning empty list");
            return List.of();
        }

        // Group by market category
        Map<MarketCategory, List<String>> groupedByCategory = rawOdds.keySet().stream()
                .filter(key -> Bet9jaMarketType.safeFromProviderKey(key).isPresent())
                .collect(Collectors.groupingBy(key ->
                        Bet9jaMarketType.fromProviderKey(key).getCategory()
                ));

        log.info("Grouped into {} categories", groupedByCategory.size());

        // Create normalized markets
        List<NormalizedMarket> markets = groupedByCategory.entrySet().stream()
                .flatMap(entry -> {
                    MarketCategory category = entry.getKey();
                    List<String> marketKeys = entry.getValue();
                    log.debug("Processing category '{}' with {} keys", category, marketKeys.size());

                    return (shouldGroupMarket(category)
                            ? createGroupedMarket(category, marketKeys, rawOdds, statusMap,
                            cashOutMap, eventId, leagueName, homeTeam, awayTeam, event)
                            : createIndividualMarkets(marketKeys, rawOdds, statusMap,
                            cashOutMap, eventId, leagueName, homeTeam, awayTeam, event)
                    ).stream();
                })
                .collect(Collectors.toList());

        log.info("Normalized {} markets", markets.size());
        return markets;
    }

    /**
     * Create grouped market (multiple outcomes in one market)
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
            Bet9jaEvent event) {

        log.info("Creating grouped market for category '{}'", category);

        List<NormalizedOutcome> outcomes = marketKeys.stream()
                .map(key -> {
                    Bet9jaMarketType marketType = Bet9jaMarketType.fromProviderKey(key);
                    String odds = rawOdds.get(key);
                    Integer status = statusMap.getOrDefault(key, 0);
                    Integer cashOut = cashOutMap.getOrDefault(key, 0);

                    return createNormalizedOutcome(
                            marketType, key, odds, status, cashOut,
                            eventId, leagueName, homeTeam, awayTeam, event
                    );
                })
                .collect(Collectors.toList());

        return List.of(new NormalizedMarket(category, outcomes));
    }

    /**
     * Create individual markets (one outcome per market)
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
            Bet9jaEvent event) {

        log.info("Creating individual markets, count: {}", marketKeys.size());

        return marketKeys.stream()
                .map(key -> {
                    Bet9jaMarketType marketType = Bet9jaMarketType.fromProviderKey(key);
                    String odds = rawOdds.get(key);
                    Integer status = statusMap.getOrDefault(key, 0);
                    Integer cashOut = cashOutMap.getOrDefault(key, 0);

                    NormalizedOutcome outcome = createNormalizedOutcome(
                            marketType, key, odds, status, cashOut,
                            eventId, leagueName, homeTeam, awayTeam, event
                    );

                    return new NormalizedMarket(marketType.getCategory(), List.of(outcome));
                })
                .collect(Collectors.toList());
    }

    /**
     * Create fully populated NormalizedOutcome
     */
    private NormalizedOutcome createNormalizedOutcome(
            Bet9jaMarketType marketType,
            String providerKey,
            String odds,
            Integer status,
            Integer cashOut,
            String eventId,
            String leagueName,
            String homeTeam,
            String awayTeam,
            Bet9jaEvent event) {

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

        return NormalizedOutcome.builder()
                .outcomeId(providerKey)
                .eventId(eventId)
                .marketType(marketType)
                .league(leagueName)
                .odds(oddsValue)
                .bookmaker(BookMaker.BET9JA)
                .homeTeam(homeTeam)
                .awayTeam(awayTeam)
                .eventName(homeTeam + " vs " + awayTeam)
                .outcomeDescription(marketType.getNormalizedName())
                .isActive(status == 1)
                .marketName(marketType.getNormalizedName())
                .eventStartTime(event.getStartDate())
                .sport(determineSport(event))
                .outcomeStatus(outcomeStatus)
                .cashOutAvailable(cashOut)
                .build();
    }

    // Helper methods

    private String[] parseTeams(String matchName) {
        if (matchName == null || matchName.trim().isEmpty()) {
            throw new IllegalArgumentException("Match name cannot be null or empty");
        }

        log.info("Parsing teams from: '{}'", matchName);
        String[] teams = TEAM_SPLIT_PATTERN.split(matchName.trim());

        if (teams.length != 2) {
            throw new IllegalArgumentException("Invalid match name format: " + matchName);
        }

        log.info("Parsed teams: '{}' vs '{}'", teams[0], teams[1]);
        return teams;
    }

    private String normalizeLeague(String leagueName) {
        if (leagueName == null) {
            return "UNKNOWN_LEAGUE";
        }
        return leagueName.trim().toUpperCase().replaceAll("\\s+", "_");
    }

    private String normalizeTeamName(String teamName) {
        if (teamName == null) {
            return "UNKNOWN_TEAM";
        }
        return teamName.trim();
    }

    private Instant normalizeTimestamp(Long timestamp) {
        if (timestamp == null) {
            return Instant.now();
        }
        return Instant.ofEpochMilli(timestamp).truncatedTo(ChronoUnit.MINUTES);
    }

    private String generateEventId(Instant time, String home, String away) {
        return String.format("%s|%s|%s", time.toString(), home, away);
    }

    private boolean isValidOdds(String odds) {
        try {
            BigDecimal value = new BigDecimal(odds);
            return value.compareTo(BigDecimal.ZERO) > 0;
        } catch (Exception e) {
            log.warn("Invalid odds '{}': {}", odds, e.getMessage());
            return false;
        }
    }

    private boolean shouldGroupMarket(MarketCategory category) {
        return EnumSet.of(
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
    }

    private Sport determineSport(Bet9jaEvent event) {
        // Implement sport detection logic based on Bet9ja event data
        // For now, default to FOOTBALL
        return Sport.FOOTBALL;
    }

    @Override
    public void addNormalizedEventToPool(NormalizedEvent normalizedEvent) {

    }
}
