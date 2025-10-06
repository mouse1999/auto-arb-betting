package com.mouse.bet.utils;

import com.mouse.bet.config.ArbConfig;
import com.mouse.bet.entity.Arb;
import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.BetLegStatus;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.enums.MarketCategory;
import com.mouse.bet.enums.OutcomeType;
import com.mouse.bet.finance.WalletService;
import com.mouse.bet.interfaces.NormalizedEvent;
import com.mouse.bet.model.NormalizedMarket;
import com.mouse.bet.model.NormalizedOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArbFactory {

    private final ArbConfig arbConfig;
    private final WalletService walletService;

    /**
     * Find arbitrage opportunities from a list of normalized events
     */
    public List<Arb> findOpportunities(List<NormalizedEvent> events) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("Events list cannot be null or empty");
        }
        log.info("Starting arbitrage opportunity search with {} events", events.size());

        // Group events by bookmaker, keeping only the most recent
        Map<BookMaker, NormalizedEvent> latestByBookmaker = events.stream()
                .collect(Collectors.toMap(
                        NormalizedEvent::getBookie,
                        Function.identity(),
                        (existing, replacement) -> {
                            boolean replaced = replacement.getLastUpdated().isAfter(existing.getLastUpdated());
                            log.trace("Comparing events for bookmaker {}: keeping {}, replaced={}",
                                    existing.getBookie(), (replaced ? "newer replacement" : "existing"), replaced);
                            return replaced ? replacement : existing;
                        }
                ));

        log.info("Found latest events from {} bookmakers: {}",
                latestByBookmaker.size(), latestByBookmaker.keySet());

        // Process latest events
        List<Arb> opportunities = latestByBookmaker.values().stream()
                .flatMap(e -> {
                    log.debug("Processing markets for eventId={} bookmaker={} with {} markets",
                            e.getEventId(), e.getBookie(), e.getMarkets().size());
                    return e.getMarkets().stream();
                })
                .collect(Collectors.groupingBy(
                        NormalizedMarket::getMarketCategory,
                        Collectors.flatMapping(m -> {
                            log.debug("Flattening outcomes for market category={} with {} outcomes",
                                    m.getMarketCategory(), m.getOutcomes().size());
                            return m.getOutcomes().stream();
                        }, Collectors.toList())
                ))
                .entrySet().stream()
                .flatMap(entry -> {
                    MarketCategory category = entry.getKey();
                    List<NormalizedOutcome> outcomes = entry.getValue();

                    log.debug("Processing category={} with {} outcomes", category, outcomes.size());

                    Map<OutcomeType, List<NormalizedOutcome>> outcomesByType = outcomes.stream()
                            .collect(Collectors.groupingBy(o -> o.getMarketType().getOutcomeType()));

                    Set<OutcomeType> processedTypes = new HashSet<>();

                    return outcomesByType.entrySet().stream()
                            .flatMap(typeEntry -> {
                                OutcomeType mainType = typeEntry.getKey();
                                log.debug("Processing mainType={} with {} outcomes", mainType, typeEntry.getValue().size());

                                if (processedTypes.contains(mainType)) {
                                    log.debug("Skipping already processed outcomeType={}", mainType);
                                    return Stream.empty();
                                }
                                if (!mainType.hasOpposite()) {
                                    log.debug("Skipping mainType={} because it has no opposite", mainType);
                                    return Stream.empty();
                                }

                                OutcomeType oppositeType = mainType.getOpposite();
                                processedTypes.add(oppositeType);

                                List<NormalizedOutcome> oppositeOutcomes =
                                        outcomesByType.getOrDefault(oppositeType, Collections.emptyList());
                                log.debug("Found {} opposite outcomes for type={}", oppositeOutcomes.size(), oppositeType);

                                return typeEntry.getValue().stream()
                                        .flatMap(mainOutcome -> {
                                            log.debug("Checking mainOutcome: [{} @ {}] for event '{} - {} vs {}' (bookmaker={})",
                                                    mainOutcome.getMarketType(),
                                                    mainOutcome.getOdds(),
                                                    mainOutcome.getEventName(),
                                                    mainOutcome.getHomeTeam(),
                                                    mainOutcome.getAwayTeam(),
                                                    mainOutcome.getBookmaker());

                                            return oppositeOutcomes.stream()
                                                    .peek(opposite -> log.trace("Against oppositeOutcome: [{} @ {}] for event '{} - {} vs {}' (bookmaker={})",
                                                            opposite.getMarketType(),
                                                            opposite.getOdds(),
                                                            opposite.getEventName(),
                                                            opposite.getHomeTeam(),
                                                            opposite.getAwayTeam(),
                                                            opposite.getBookmaker()))
                                                    .filter(opposite -> !mainOutcome.getBookmaker().equals(opposite.getBookmaker()))
                                                    .filter(opposite -> {
                                                        boolean arb = isArbitrage(mainOutcome.getOdds(), opposite.getOdds());
                                                        log.info("Arbitrage check: [{} @ {}] (bookie: {}) vs [{} @ {}] (bookie: {}) => {}",
                                                                mainOutcome.getMarketType(), mainOutcome.getOdds(), mainOutcome.getBookmaker(),
                                                                opposite.getMarketType(), opposite.getOdds(), opposite.getBookmaker(),
                                                                arb);
                                                        return arb;
                                                    })
                                                    .map(opposite -> {
                                                        Arb arb = createArb(category, mainOutcome, opposite);
                                                        log.info("Created arb for event '{}': [{} @ {} ({}), {} @ {} ({})], profit={}%",
                                                                mainOutcome.getEventName(),
                                                                mainOutcome.getMarketType(), mainOutcome.getOdds(), mainOutcome.getBookmaker(),
                                                                opposite.getMarketType(), opposite.getOdds(), opposite.getBookmaker(),
                                                                arb.getProfitPercentage());
                                                        return arb;
                                                    });
                                        });
                            });
                })
                .collect(Collectors.toList());

        log.info("Found {} arbitrage opportunities", opportunities.size());
        return opportunities;
    }

    /**
     * Create an Arb entity from matched outcomes
     */
    private Arb createArb(
            MarketCategory category,
            NormalizedOutcome mainOutcome,
            NormalizedOutcome oppositeOutcome
    ) {
        BigDecimal profit = calculateProfitPercent(mainOutcome.getOdds(), oppositeOutcome.getOdds());
        String eventId = mainOutcome.getEventId();
        String leagueName = mainOutcome.getLeague();
        BigDecimal stakeA = ArbCalculator.stakeForBookieA(mainOutcome.getOdds(), oppositeOutcome.getOdds(), arbConfig.getTOTAL_STAKE());
        BigDecimal stakeB = ArbCalculator.stakeForBookieB(mainOutcome.getOdds(), oppositeOutcome.getOdds(), arbConfig.getTOTAL_STAKE());
        boolean shouldBet = checkBothBookMakerFunds(mainOutcome.getBookmaker(), oppositeOutcome.getBookmaker(), stakeA, stakeB);


        log.debug("Creating arb for eventId={} league={} category={} between {}@{} and {}@{} profit={}%",
                eventId,
                leagueName,
                category,
                mainOutcome.getBookmaker(), mainOutcome.getOdds(),
                oppositeOutcome.getBookmaker(), oppositeOutcome.getOdds(),
                profit);

        BetLeg legA = BetLeg.builder()
                .sport(mainOutcome.getSport())
                .attemptCount(0)
                .balanceBeforeBet(walletService.getBalance(mainOutcome.getBookmaker()))
                .isPrimaryLeg(true)
                .stake(ArbCalculator.roundStakeForAntiDetection(stakeA))
                .rawStake(stakeA)
                .potentialPayout(mainOutcome.getOdds().multiply(stakeA))
                .awayTeam(mainOutcome.getAwayTeam())
                .bookmaker(mainOutcome.getBookmaker())
                .odds(mainOutcome.getOdds())
                .marketType(mainOutcome.getMarketType())
                .eventId(eventId)
                .homeTeam(mainOutcome.getHomeTeam())
                .league(leagueName)
                .status(BetLegStatus.PENDING)
                .cashOutAvailable(mainOutcome.getCashOutAvailable())
                .outcomeDescription(mainOutcome.getOutcomeDescription())
                .outcomeId(mainOutcome.getOutcomeId())
                .period(mainOutcome.getPeriod())
                .matchStatus(mainOutcome.getMatchStatus())
                .profitPercent(profit)
                .build();

        BetLeg legB = BetLeg.builder()
                .sport(oppositeOutcome.getSport())
                .attemptCount(0)
                .balanceBeforeBet(walletService.getBalance(oppositeOutcome.getBookmaker()))
                .isPrimaryLeg(false)
                .stake(ArbCalculator.roundStakeForAntiDetection(stakeB))
                .rawStake(stakeB)
                .potentialPayout(oppositeOutcome.getOdds().multiply(stakeB))
                .awayTeam(oppositeOutcome.getAwayTeam())
                .bookmaker(oppositeOutcome.getBookmaker())
                .odds(oppositeOutcome.getOdds())
                .marketType(oppositeOutcome.getMarketType())
                .eventId(eventId)
                .homeTeam(oppositeOutcome.getHomeTeam())
                .league(leagueName)
                .status(BetLegStatus.PENDING)
                .cashOutAvailable(oppositeOutcome.getCashOutAvailable())
                .outcomeDescription(oppositeOutcome.getOutcomeDescription())
                .outcomeId(oppositeOutcome.getOutcomeId())
                .period(oppositeOutcome.getPeriod())
                .matchStatus(oppositeOutcome.getMatchStatus())
                .profitPercent(profit)
                .build();


        return  Arb.builder()
                .sport(mainOutcome.getSport() == oppositeOutcome.getSport()
                        ? mainOutcome.getSport():
                        oppositeOutcome.getSport())
                .league(leagueName)
                .eventId(eventId)
                .homeTeam(mainOutcome.getHomeTeam())
                .awayTeam(mainOutcome.getAwayTeam())
                .marketType(mainOutcome.getMarketType())
                .period(mainOutcome.getPeriod())
                .eventStartTime(Instant.ofEpochMilli(mainOutcome.getEventStartTime()))

                .setScore(mainOutcome.getSetScore())
                .gameScore(mainOutcome.getGameScore())
                .matchStatus(mainOutcome.getMatchStatus())
                .playedSeconds(mainOutcome.getPlayedSeconds())
                .stakeA(stakeA)
                .stakeB(stakeB)
                .profitPercentage(profit)
                .shouldBet(shouldBet)
                .legA(legA)
                .legB(legB)
                .build();
    }

    /**
     * Check if two odds form an arbitrage opportunity
     */
    private boolean isArbitrage(BigDecimal oddsA, BigDecimal oddsB) {
        BigDecimal totalImpliedProbability = BigDecimal.ONE.divide(oddsA, 4, RoundingMode.HALF_UP)
                .add(BigDecimal.ONE.divide(oddsB, 4, RoundingMode.HALF_UP));
        boolean isArb = totalImpliedProbability.compareTo(BigDecimal.ONE) < 0;
        log.trace("isArbitrage: oddsA={} oddsB={} totalImplied={} => {}", oddsA, oddsB, totalImpliedProbability, isArb);
        return isArb;
    }

    /**
     * Calculate profit percentage from two odds
     */
    private BigDecimal calculateProfitPercent(BigDecimal oddsA, BigDecimal oddsB) {
        BigDecimal arbPercent = ArbCalculator.calculateArbitragePercentage(oddsA, oddsB);

        return ArbCalculator.calculateProfitPercentage(arbPercent);
    }

    private boolean checkBothBookMakerFunds(BookMaker bookMakerA,
                                            BookMaker bookMakerB,
                                            BigDecimal stakeA,
                                            BigDecimal stakeB) {
        return walletService.canAfford(bookMakerA, stakeA) && walletService.canAfford(bookMakerB, stakeB);


    }
}