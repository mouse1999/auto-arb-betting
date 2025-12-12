package com.mouse.bet.utils;

import com.mouse.bet.config.ArbConfig;
import com.mouse.bet.converter.ModelConverter;
import com.mouse.bet.entity.Arb;
import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.*;
import com.mouse.bet.finance.WalletService;
import com.mouse.bet.interfaces.MarketType;
import com.mouse.bet.model.NormalizedEvent;
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

    // Emoji Constants for Visual Clarity
    private static final String EMOJI_START = "🚀";
    private static final String EMOJI_FOUND = "💰";
    private static final String EMOJI_CHECK = "🔍";
    private static final String EMOJI_SUCCESS = "✅";
    private static final String EMOJI_FAIL = "❌";
    private static final String EMOJI_WARNING = "⚠️";
    private static final String EMOJI_INFO = "ℹ️";
    private static final String EMOJI_MONEY = "💸";
    private static final String EMOJI_CHART = "📊";
    private static final String EMOJI_TIMER = "⏱️";
    private static final String EMOJI_TARGET = "🎯";
    private static final String EMOJI_FIRE = "🔥";
    private static final String EMOJI_BOOK = "📖";
    private static final String EMOJI_VS = "⚔️";
    private static final String EMOJI_WALLET = "👛";
    private static final String EMOJI_SKIP = "⏭️";

    /**
     * Find arbitrage opportunities from a list of normalized events
     */
    public List<Arb> findOpportunities(List<NormalizedEvent> events) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("Events list cannot be null or empty");
        }
        log.info("{} Starting arbitrage opportunity search with {} events", EMOJI_START, events.size());

        // Group events by bookmaker, keeping only the most recent
        Map<BookMaker, NormalizedEvent> latestByBookmaker = events.stream()
                .collect(Collectors.toMap(
                        NormalizedEvent::getBookie,
                        Function.identity(),
                        (existing, replacement) -> {
                            boolean replaced = replacement.getLastUpdated().isAfter(existing.getLastUpdated());
                            log.trace("{} Comparing events for bookmaker {}: keeping {}, replaced={}",
                                    EMOJI_TIMER, existing.getBookie(), (replaced ? "newer replacement" : "existing"), replaced);
                            return replaced ? replacement : existing;
                        }
                ));

        log.info("{} Found latest events from {} bookmakers: {}",
                EMOJI_BOOK, latestByBookmaker.size(), latestByBookmaker.keySet());

        // Process latest events
        List<Arb> opportunities = latestByBookmaker.values().stream()
                .flatMap(e -> {
                    log.debug("{} Processing markets for eventId={} bookmaker={} with {} markets",
                            EMOJI_CHART, e.getEventId(), e.getBookie(), e.getMarkets().size());
                    return e.getMarkets().stream();
                })
                .collect(Collectors.groupingBy(
                        NormalizedMarket::getMarketCategory,
                        Collectors.flatMapping(m -> {
                            log.debug("{} Flattening outcomes for market category={} with {} outcomes",
                                    EMOJI_INFO, m.getMarketCategory(), m.getOutcomes().size());
                            return m.getOutcomes().stream();
                        }, Collectors.toList())
                ))
                .entrySet().stream()
                .flatMap(entry -> {
                    MarketCategory category = entry.getKey();
                    List<NormalizedOutcome> outcomes = entry.getValue();

                    log.debug("{} Processing category={} with {} outcomes",
                            EMOJI_TARGET, category, outcomes.size());

                    Map<OutcomeType, List<NormalizedOutcome>> outcomesByType = outcomes.stream()
                            .collect(Collectors.groupingBy(o -> o.getMarketType().getOutcomeType()));

                    Set<OutcomeType> processedTypes = new HashSet<>();

                    return outcomesByType.entrySet().stream()
                            .flatMap(typeEntry -> {
                                OutcomeType mainType = typeEntry.getKey();
                                log.debug("{} Processing mainType={} with {} outcomes",
                                        EMOJI_CHECK, mainType, typeEntry.getValue().size());

                                if (processedTypes.contains(mainType)) {
                                    log.debug("{} Skipping already processed outcomeType={}",
                                            EMOJI_SKIP, mainType);
                                    return Stream.empty();
                                }
                                if (!mainType.hasOpposite()) {
                                    log.debug("{} Skipping mainType={} because it has no opposite",
                                            EMOJI_SKIP, mainType);
                                    return Stream.empty();
                                }

                                OutcomeType oppositeType = mainType.getOpposite();
                                processedTypes.add(oppositeType);

                                List<NormalizedOutcome> oppositeOutcomes =
                                        outcomesByType.getOrDefault(oppositeType, Collections.emptyList());
                                log.debug("{} Found {} opposite outcomes for type={}",
                                        EMOJI_SUCCESS, oppositeOutcomes.size(), oppositeType);

                                return typeEntry.getValue().stream()
                                        .flatMap(mainOutcome -> {
                                            log.debug("{} Checking mainOutcome: [{} @ {}] for event '{} - {} vs {}' (bookmaker={})",
                                                    EMOJI_CHECK,
                                                    mainOutcome.getMarketType(),
                                                    mainOutcome.getOdds(),
                                                    mainOutcome.getEventName(),
                                                    mainOutcome.getHomeTeam(),
                                                    mainOutcome.getAwayTeam(),
                                                    mainOutcome.getBookmaker());

                                            return oppositeOutcomes.stream()
                                                    .peek(opposite -> log.debug("{} Against oppositeOutcome: [{} @ {}] for event '{} vs {}' (bookmaker={})",
                                                            EMOJI_VS,
                                                            opposite.getMarketType(),
                                                            opposite.getOdds(),
                                                            opposite.getHomeTeam(),
                                                            opposite.getAwayTeam(),
                                                            opposite.getBookmaker()))
                                                    .filter(opposite -> !mainOutcome.getBookmaker().equals(opposite.getBookmaker()))
                                                    .filter(opposite -> {
                                                        boolean arb = isArbitrage(mainOutcome.getOdds(), opposite.getOdds());
                                                        if (arb) {
                                                            log.info("{} {} ARBITRAGE DETECTED {} | Home: {} | Away: {} | League: {} | [{} @ {}] ({}) {} [{} @ {}] ({}) {}",
                                                                    EMOJI_FIRE, EMOJI_FOUND, EMOJI_FIRE,
                                                                    mainOutcome.getHomeTeam(),
                                                                    mainOutcome.getAwayTeam(),
                                                                    mainOutcome.getLeague(),
                                                                    mainOutcome.getMarketType(),
                                                                    mainOutcome.getOdds(),
                                                                    mainOutcome.getBookmaker(),
                                                                    EMOJI_VS,
                                                                    opposite.getMarketType(),
                                                                    opposite.getOdds(),
                                                                    opposite.getBookmaker(),
                                                                    EMOJI_SUCCESS);
                                                        } else {
                                                            log.trace("{} No arbitrage: [{} @ {}] vs [{} @ {}]",
                                                                    EMOJI_FAIL,
                                                                    mainOutcome.getMarketType(), mainOutcome.getOdds(),
                                                                    opposite.getMarketType(), opposite.getOdds());
                                                        }
                                                        return arb;
                                                    })
                                                    .map(opposite -> {
                                                        Arb arb = createArb(category, mainOutcome, opposite);
                                                        log.info("====================================START===========================================================================");
                                                        log.info("{} {} ARB CREATED {} | Event: '{}' | [{} @ {} ({})] {} [{} @ {} ({})] | Profit: {}% {} | Status: {}",
                                                                EMOJI_SUCCESS, EMOJI_MONEY, EMOJI_SUCCESS,
                                                                mainOutcome.getEventName(),
                                                                mainOutcome.getMarketType(),
                                                                mainOutcome.getOdds(),
                                                                mainOutcome.getBookmaker(),
                                                                EMOJI_VS,
                                                                opposite.getMarketType(),
                                                                opposite.getOdds(),
                                                                opposite.getBookmaker(),
                                                                arb.getProfitPercentage(),
                                                                EMOJI_CHART,
                                                                arb.getStatus());
                                                        log.info("=========================================END============================================================================");
                                                        log.info("{}", arb);
                                                        return arb;
                                                    });
                                        });
                            });
                })
                .collect(Collectors.toList());

        if (opportunities.isEmpty()) {
            log.info("{} {} No arbitrage opportunities found", EMOJI_WARNING, EMOJI_FAIL);
        } else {
            log.info("{} {} TOTAL ARBITRAGE OPPORTUNITIES FOUND: {} {}",
                    EMOJI_FIRE, EMOJI_FOUND, opportunities.size(), EMOJI_FIRE);
        }

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
        String eventId = mainOutcome.getNormalEventId();
        Instant now = Instant.now();
        String leagueName = mainOutcome.getLeague();
        BigDecimal rawStakeA = ArbCalculator.stakeForBookieA(mainOutcome.getOdds(), oppositeOutcome.getOdds(), arbConfig.getTOTAL_STAKE());
        BigDecimal rawStakeB = ArbCalculator.stakeForBookieB(mainOutcome.getOdds(), oppositeOutcome.getOdds(), arbConfig.getTOTAL_STAKE());
        boolean shouldBet = checkBothBookMakerFunds(mainOutcome.getBookmaker(), oppositeOutcome.getBookmaker(), rawStakeA, rawStakeB);
        if(profit.compareTo(BigDecimal.TEN) > 0) {
            log.info("{} {} Found arb greater than Ten percent", EMOJI_TARGET, EMOJI_TARGET);

        }

        log.debug("{} Creating arb | EventId: {} | League: {} | Category: {} | {} {}@{} {} {}@{} | Profit: {}%",
                EMOJI_TARGET,
                eventId,
                leagueName,
                category,
                mainOutcome.getBookmaker(), mainOutcome.getOdds(), rawStakeA,
//                EMOJI_VS,
                oppositeOutcome.getBookmaker(), oppositeOutcome.getOdds(), rawStakeB,
                profit);

        BetLeg legA = ModelConverter.fromOutcome(mainOutcome, ArbCalculator.roundStakeForAntiDetection(rawStakeA), rawStakeA, true);
        BetLeg legB = ModelConverter.fromOutcome(oppositeOutcome, ArbCalculator.roundStakeForAntiDetection(rawStakeB), rawStakeB, false);

        if (!shouldBet) {
            log.warn("{} {} INSUFFICIENT FUNDS | {} StakeA: {} | {} StakeB: {}",
                    EMOJI_WARNING, EMOJI_WALLET,
                    mainOutcome.getBookmaker(), rawStakeA,
                    oppositeOutcome.getBookmaker(), rawStakeB);
        } else {
            log.info("{} {} FUNDS AVAILABLE | {} StakeA: {} | {} StakeB: {}",
                    EMOJI_SUCCESS, EMOJI_MONEY,
                    mainOutcome.getBookmaker(), rawStakeA,
                    oppositeOutcome.getBookmaker(), rawStakeB);
        }

        Arb newArb = Arb.builder()
                .sportEnum(mainOutcome.getSportEnum())
                .league(leagueName)
                .arbId(createArbId(mainOutcome.getMarketType(), eventId))
                .period(mainOutcome.getPeriod())
                .status(shouldBet ? Status.ACTIVE: Status.INSUFFICIENT_BALANCE)
                .eventStartTime(Instant.ofEpochMilli(mainOutcome.getEventStartTime()))
                .setScore(mainOutcome.getSetScore())
                .gameScore(mainOutcome.getGameScore())
                .matchStatus(mainOutcome.getMatchStatus())
                .playedSeconds(mainOutcome.getPlayedSeconds())
                .createdAt(now)
                .lastUpdatedAt(now)
                .stakeA(rawStakeA)
                .stakeB(rawStakeB)
                .legs(List.of(legA, legB))
                .profitPercentage(profit)
                .shouldBet(true) //todo
                .build();
        legA.setArb(newArb);
        legB.setArb(newArb);

        return newArb;

    }

    /**
     * Check if two odds form an arbitrage opportunity
     */
    private boolean isArbitrage(BigDecimal oddsA, BigDecimal oddsB) {
        BigDecimal totalImpliedProbability = BigDecimal.ONE.divide(oddsA, 4, RoundingMode.HALF_UP)
                .add(BigDecimal.ONE.divide(oddsB, 4, RoundingMode.HALF_UP));
        boolean isArb = totalImpliedProbability.compareTo(BigDecimal.ONE) < 0;

        if (isArb) {
            log.trace("{} isArbitrage: oddsA={} oddsB={} totalImplied={} => {} {}",
                    EMOJI_SUCCESS, oddsA, oddsB, totalImpliedProbability, "ARBITRAGE", EMOJI_FOUND);
        } else {
            log.trace("{} isArbitrage: oddsA={} oddsB={} totalImplied={} => NO ARB",
                    EMOJI_FAIL, oddsA, oddsB, totalImpliedProbability);
        }

        return isArb;
    }

    /**
     * Calculate profit percentage from two odds
     */
    private BigDecimal calculateProfitPercent(BigDecimal oddsA, BigDecimal oddsB) {
        BigDecimal arbPercent = ArbCalculator.calculateArbitragePercentage(oddsA, oddsB);
        BigDecimal profit = ArbCalculator.calculateProfitPercentage(arbPercent);

        log.trace("{} Profit calculation: oddsA={} oddsB={} => arbPercent={}% profit={}%",
                EMOJI_CHART, oddsA, oddsB, arbPercent, profit);

        return profit;
    }

    /**
     * Check if both bookmakers have sufficient funds
     */
    private boolean checkBothBookMakerFunds(BookMaker bookMakerA,
                                            BookMaker bookMakerB,
                                            BigDecimal stakeA,
                                            BigDecimal stakeB) {
        boolean canAffordA = walletService.canAfford(bookMakerA, stakeA);
        boolean canAffordB = walletService.canAfford(bookMakerB, stakeB);

        if (canAffordA && canAffordB) {
            log.debug("{} {} Fund check passed | {}: {} | {}: {}",
                    EMOJI_SUCCESS, EMOJI_WALLET,
                    bookMakerA, stakeA, bookMakerB, stakeB);
        } else {
            log.warn("{} {} Fund check failed | {}: {} (affordable: {}) | {}: {} (affordable: {})",
                    EMOJI_WARNING, EMOJI_WALLET,
                    bookMakerA, stakeA, canAffordA,
                    bookMakerB, stakeB, canAffordB);
        }

        return canAffordA && canAffordB;
    }

    /**
     * Create unique arbitrage identifier
     */
    private String createArbId(MarketType marketType, String eventId) {
        String arbId = eventId + marketType.toString();
        log.info("{} Created arbId: {}", EMOJI_INFO, arbId);
        return arbId;
    }
}