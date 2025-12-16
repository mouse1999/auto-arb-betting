//package com.mouse.bet.service;
//
//import com.mouse.bet.detector.ArbDetector;
//import com.mouse.bet.enums.*;
//import com.mouse.bet.interfaces.OddService;
//import com.mouse.bet.model.MarketMeta;
//import com.mouse.bet.model.NormalizedEvent;
//import com.mouse.bet.model.NormalizedMarket;
//import com.mouse.bet.model.NormalizedOutcome;
//import com.mouse.bet.model.bet9ja.Bet9jaEvent;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Service;
//
//import java.math.BigDecimal;
//import java.util.EnumSet;
//import java.util.List;
//import java.util.Map;
//import java.util.regex.Pattern;
//import java.util.stream.Collectors;
//
//@RequiredArgsConstructor
//@Slf4j
//@Service
//public class Bet9jaService implements OddService<Bet9jaEvent> {
//
//    private final ArbDetector arbDetector;
//
//    private final TeamAliasService teamAliasService;
//
//    /** e.g. "Team A vs Team B" or "Team A vs. Team B" */
//    private static final Pattern TEAM_SPLIT_PATTERN = Pattern.compile("\\s+-\\.?\\s+", Pattern.CASE_INSENSITIVE);
//
//    @Override
//    public NormalizedEvent convertToNormalEvent(Bet9jaEvent event) {
//        log.info("Normalizing Bet9jaEvent: {}", event);
//        if (event == null) throw new RuntimeException("Bet9jaEvent cannot be null");
//
//        String leagueRaw = event.getEventHeader().getCompetition().getDisplayName();
//        String league = normalizeLeague(leagueRaw);
//
//        String[] teams = parseTeams(event.getEventHeader().getDisplayName());
//        String homeTeam = teamAliasService.canonicalOrSelf(normalizeTeamName(teams[0]));
//        String awayTeam = teamAliasService.canonicalOrSelf(normalizeTeamName(teams[1]));
//
//        SportEnum sportEnum = determineSport(event);
//        String eventId = generateEventId(homeTeam, awayTeam, sportEnum);
//
//        log.info("Normalized - league='{}', home='{}', away='{}', eventId='{}'", league, homeTeam, awayTeam, eventId);
//
//
//        Map<String, String> rawOddsMap = convertToOddsMap(event.getOdds());
//        Map<String, Integer> statusMap = mapOutcomeStatus(event.getOdds());
//        Map<String, Integer> cashOutMap = mapOutcomeCashOut(event.getOdds());
//        Map<String, MarketMeta> metaMap = buildMetaMapFromKeys(rawOddsMap);
//
//        log.info("Converted maps - odds: {}, status: {}, cashout: {}, meta: {}",
//                rawOddsMap.size(), statusMap.size(), cashOutMap.size(), metaMap.size());
//
//        List<NormalizedMarket> markets = normalizeMarkets(
//                rawOddsMap, statusMap, cashOutMap, metaMap,
//                BookMaker.BET9JA, eventId, league, homeTeam, awayTeam, event
//        );
//        log.info("Normalized {} markets for Bet9jaEvent", markets.size());
//
//        return NormalizedEvent.builder()
//                .eventId(eventId)
//                .eventName(homeTeam + " vs " + awayTeam)
//                .homeTeam(homeTeam)
//                .awayTeam(awayTeam)
//                .league(league)
//                .sportEnum(sportEnum)
//                .bookie(BookMaker.BET9JA)
//                .markets(markets)
//                .build();
//    }
//
//
//
//    /** Convert raw Bet9ja odds map to a cleaned odds map (valid odds + known provider keys). */
//    private Map<String, String> convertToOddsMap(Map<String, String> rawOdds) {
//        log.info("Converting odds to map...");
//        if (rawOdds == null || rawOdds.isEmpty()) return Map.of();
//
//        Map<String, String> oddsMap = rawOdds.entrySet().stream()
//                .filter(e -> isValidOdds(e.getValue()))
//                .filter(e -> Bet9jaMarketType.safeFromProviderKey(e.getKey()).isPresent())
//                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
//
//        log.info("Converted odds map size: {}", oddsMap.size());
//        return oddsMap;
//    }
//
//    /** Map outcome status for keys we can parse; valid odds → 1 (ACTIVE), else 0 (SUSPENDED). */
//    private Map<String, Integer> mapOutcomeStatus(Map<String, String> rawOdds) {
//        log.info("Mapping outcome status...");
//        if (rawOdds == null || rawOdds.isEmpty()) return Map.of();
//
//        Map<String, Integer> statusMap = rawOdds.entrySet().stream()
//                .filter(e -> Bet9jaMarketType.safeFromProviderKey(e.getKey()).isPresent())
//                .collect(Collectors.toMap(
//                        Map.Entry::getKey,
//                        e -> isValidOdds(e.getValue()) ? 1 : 0,
//                        (a, b) -> a
//                ));
//
//        log.info("Mapped status for {} outcomes", statusMap.size());
//        return statusMap;
//    }
//
//    /** Map cashout availability (Bet9ja: default 0 unless you later add a source for it). */
//    private Map<String, Integer> mapOutcomeCashOut(Map<String, String> rawOdds) {
//        log.info("Mapping cashout availability...");
//        if (rawOdds == null || rawOdds.isEmpty()) return Map.of();
//
//        Map<String, Integer> cashOutMap = rawOdds.keySet().stream()
//                .filter(key -> Bet9jaMarketType.safeFromProviderKey(key).isPresent())
//                .collect(Collectors.toMap(key -> key, key -> 0, (a, b) -> a));
//
//        log.info("Mapped cashout for {} outcomes", cashOutMap.size());
//        return cashOutMap;
//    }
//
//    /**
//     * Build a minimal MarketMeta per provider key. We infer name/group from Bet9jaMarketType since
//     * Bet9ja odds arrive as flat keys (no Market objects).
//     */
//    private Map<String, MarketMeta> buildMetaMapFromKeys(Map<String, String> oddsMap) {
//        if (oddsMap == null || oddsMap.isEmpty()) return Map.of();
//
//        return oddsMap.keySet().stream()
//                .map(key -> {
//                    Bet9jaMarketType mt = Bet9jaMarketType.fromProviderKey(key);
//                    // name/title from normalized market type; group is category name; no specifier/marketId known here
//                    MarketMeta meta = new MarketMeta(
//                            mt.getNormalizedName(),
//                            mt.getNormalizedName(),
//                            mt.getCategory().name(),
//                            null,
//                            null
//                    );
//                    return Map.entry(key, meta);
//                })
//                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
//    }
//
//    private List<NormalizedMarket> normalizeMarkets(
//            Map<String, String> rawOdds,
//            Map<String, Integer> statusMap,
//            Map<String, Integer> cashOutMap,
//            Map<String, MarketMeta> metaMap,
//            BookMaker bookmaker,
//            String eventId,
//            String leagueName,
//            String homeTeam,
//            String awayTeam,
//            Bet9jaEvent event
//    ) {
//        log.info("Normalizing markets - bookmaker='{}', eventId='{}', league='{}'", bookmaker, eventId, leagueName);
//        if (rawOdds == null || rawOdds.isEmpty()) return List.of();
//
//        Map<MarketCategory, List<String>> groupedByCategory = rawOdds.keySet().stream()
//                .filter(key -> Bet9jaMarketType.safeFromProviderKey(key).isPresent())
//                .collect(Collectors.groupingBy(key -> Bet9jaMarketType.fromProviderKey(key).getCategory()));
//
//        log.info("Grouped into {} categories", groupedByCategory.size());
//
//        return groupedByCategory.entrySet().stream()
//                .flatMap(entry -> {
//                    MarketCategory category = entry.getKey();
//                    List<String> keys = entry.getValue();
//                    return (shouldGroupMarket(category)
//                            ? createGroupedMarket(category, keys, rawOdds, statusMap, cashOutMap,
//                            metaMap, eventId, leagueName, homeTeam, awayTeam, event)
//                            : createIndividualMarkets(keys, rawOdds, statusMap, cashOutMap,
//                            metaMap, eventId, leagueName, homeTeam, awayTeam, event)
//                    ).stream();
//                })
//                .collect(Collectors.toList());
//    }
//
//    private List<NormalizedMarket> createGroupedMarket(
//            MarketCategory category,
//            List<String> marketKeys,
//            Map<String, String> rawOdds,
//            Map<String, Integer> statusMap,
//            Map<String, Integer> cashOutMap,
//            Map<String, MarketMeta> metaMap,
//            String eventId,
//            String leagueName,
//            String homeTeam,
//            String awayTeam,
//            Bet9jaEvent event
//    ) {
//        List<NormalizedOutcome> outcomes = marketKeys.stream()
//                .map(key -> {
//                    Bet9jaMarketType mt = Bet9jaMarketType.fromProviderKey(key);
//                    String odds = rawOdds.get(key);
//                    Integer st = statusMap.getOrDefault(key, 0);
//                    Integer cash = cashOutMap.getOrDefault(key, 0);
//                    MarketMeta meta = metaMap.get(key);
//                    return createNormalizedOutcome(mt, key, odds, st, cash,
//                            eventId, leagueName, homeTeam, awayTeam, event, meta);
//                })
//                .collect(Collectors.toList());
//
//        return List.of(new NormalizedMarket(category, outcomes));
//    }
//
//    private List<NormalizedMarket> createIndividualMarkets(
//            List<String> marketKeys,
//            Map<String, String> rawOdds,
//            Map<String, Integer> statusMap,
//            Map<String, Integer> cashOutMap,
//            Map<String, MarketMeta> metaMap,
//            String eventId,
//            String leagueName,
//            String homeTeam,
//            String awayTeam,
//            Bet9jaEvent event
//    ) {
//        return marketKeys.stream()
//                .map(key -> {
//                    Bet9jaMarketType mt = Bet9jaMarketType.fromProviderKey(key);
//                    String odds = rawOdds.get(key);
//                    Integer st = statusMap.getOrDefault(key, 0);
//                    Integer cash = cashOutMap.getOrDefault(key, 0);
//                    MarketMeta meta = metaMap.get(key);
//
//                    NormalizedOutcome outcome = createNormalizedOutcome(
//                            mt, key, odds, st, cash, eventId, leagueName, homeTeam, awayTeam, event, meta
//                    );
//                    return new NormalizedMarket(mt.getCategory(), List.of(outcome));
//                })
//                .collect(Collectors.toList());
//    }
//
//    private NormalizedOutcome createNormalizedOutcome(
//            Bet9jaMarketType marketType,
//            String providerKey,
//            String odds,
//            Integer status,
//            Integer cashOut,
//            String eventId,
//            String leagueName,
//            String homeTeam,
//            String awayTeam,
//            Bet9jaEvent event,
//            MarketMeta meta
//    ) {
//        BigDecimal oddsValue;
//        try {
//            oddsValue = new BigDecimal(odds);
//        } catch (Exception e) {
//            log.error("Failed to parse odds '{}' for key '{}'", odds, providerKey);
//            oddsValue = BigDecimal.ZERO;
//        }
//
//        OutcomeStatus outcomeStatus = (status != null && status == 1)
//                ? OutcomeStatus.AVAILABLE
//                : OutcomeStatus.SUSPENDED;
//
//        // For Bet9ja, the outcome description usually lives inside the provider key; we present normalized name.
//        String outcomeDesc = marketType.getNormalizedName();
//
//        return NormalizedOutcome.builder()
//                .outcomeId(providerKey)
//                .eventId(eventId)
//                .marketType(marketType)
//                .league(leagueName)
//                .odds(oddsValue)
//                .bookmaker(BookMaker.BET9JA)
//                .homeTeam(homeTeam)
//                .awayTeam(awayTeam)
//                .eventName(event.getEventHeader().getDisplayName())
//                .outcomeDescription(outcomeDesc)
//                .isActive(status != null && status == 1)
//                .sportEnum(determineSport(event))
//                .outcomeStatus(outcomeStatus)
//                .cashOutAvailable(cashOut)
//
//                .matchStatus(event.getLiveInplayState().getEventStatus())
//                .setScore(event.getLiveInplayState().getScore().getScoreline())
//                .gameScore(event.getLiveInplayState().getScore().getPeriodScores())
////                .period(event.getPeriod())
//                .playedSeconds(String.valueOf(event.getLiveInplayState().getClockMinutes()))
//                // Provider meta (best-effort for Bet9ja)
//                .providerMarketName(meta != null ? meta.outcomeName() : null)
//                .providerMarketTitle(meta != null ? meta.desc() : null)
//                .marketId(meta != null ? String.valueOf(meta.marketId()) : null)
//                .build();
//    }
//
//
//    private String[] parseTeams(String matchName) {
//        if (matchName == null || matchName.trim().isEmpty()) {
//            throw new IllegalArgumentException("Match name cannot be null or empty");
//        }
//        String[] teams = TEAM_SPLIT_PATTERN.split(matchName.trim());
//        if (teams.length != 2) {
//            throw new IllegalArgumentException("Invalid match name format: " + matchName);
//        }
//        return teams;
//    }
//
//    private String normalizeLeague(String leagueName) {
//        if (leagueName == null) return "UNKNOWN_LEAGUE";
//        return leagueName.trim().toUpperCase().replaceAll("\\s+", "_");
//    }
//
//    private String normalizeTeamName(String teamName) {
//        if (teamName == null) return "UNKNOWN_TEAM";
//        return teamName.trim();
//    }
//
//    /** SPORT|HOME|AWAY (keeps your Bet9ja signature but more informative). */
//    private String generateEventId(String home, String away, SportEnum sportEnum) {
//        return sportEnum.getName() + "|" + home + "|" + away;
//    }
//
//    private boolean isValidOdds(String odds) {
//        try {
//            BigDecimal v = new BigDecimal(odds);
//            return v.compareTo(BigDecimal.ZERO) > 0;
//        } catch (Exception e) {
//            log.warn("Invalid odds '{}': {}", odds, e.getMessage());
//            return false;
//        }
//    }
//
//    private boolean shouldGroupMarket(MarketCategory category) {
//        return EnumSet.of(
//                MarketCategory.OVER_UNDER_TOTAL,
//                MarketCategory.DOUBLE_CHANCE,
//                MarketCategory.BTTS,
//                MarketCategory.ASIAN_HANDICAP_FULLTIME,
//                MarketCategory.CORNERS_OVER_UNDER_FULLTIME,
//                MarketCategory.DRAW_NO_BET,
//                MarketCategory.OVER_UNDER_1STHALF,
//                MarketCategory.OVER_UNDER_2NDHALF,
//                MarketCategory.ODD_EVEN,
//                MarketCategory.MATCH_RESULT,
//                MarketCategory.BASKETBALL_MATCH_WINNER,
//                MarketCategory.GAME_POINT_HANDICAP
////                MarketCategory.BASKETBALL_HANDICAP
//        ).contains(category);
//    }
//
//    private SportEnum determineSport(Bet9jaEvent event) {
//        String sportName = event.getEventHeader().getCompetition().getSportName();
//        return switch (sportName) {
//            case "Basketball" -> SportEnum.BASKETBALL;
//            case "Table Tennis" -> SportEnum.TABLE_TENNIS;
//            default -> SportEnum.FOOTBALL;
//        };
//    }
//
//
//    @Override
//    public void addNormalizedEventToPool(NormalizedEvent normalizedEvent) {
//        if(normalizedEvent == null) {
//            throw new IllegalArgumentException("");
//        }
//        arbDetector.addEventToPool(normalizedEvent);
//        //check if the event was missed in the previous execution
//
//    }
//}
