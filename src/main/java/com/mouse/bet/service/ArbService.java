package com.mouse.bet.service;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.entity.ArbSnapshot;
import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.ChangeReason;
import com.mouse.bet.model.OddsChange;
import com.mouse.bet.repository.ArbRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing arbitrage opportunities
 * Contains all business logic related to arb lifecycle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArbService {

    private final ArbRepository arbRepository;
    private final ArbFilter arbFilter;
    private final ArbContinuityService continuityService;
    // Minimum session duration for reliable metrics (in seconds)
    @Value("${arb.min.reliable.session.seconds:10}")
    private int minReliableSessionSeconds;
    @Value("${arb.max.continuity.breaks:2}")
    private int maxContinuityBreaks; //

    // Emoji constants
    private static final String EMOJI_SAVE = "💾";
    private static final String EMOJI_NEW = "🆕";
    private static final String EMOJI_UPDATE = "🔄";
    private static final String EMOJI_SUCCESS = "✅";
    private static final String EMOJI_WARNING = "⚠️";
    private static final String EMOJI_ERROR = "❌";
    private static final String EMOJI_CHART = "📊";
    private static final String EMOJI_MONEY = "💰";
    private static final String EMOJI_CLOCK = "⏰";
    private static final String EMOJI_FIRE = "🔥";
    private static final String EMOJI_TARGET = "🎯";
    private static final String EMOJI_SEARCH = "🔍";
    private static final String EMOJI_TROPHY = "🏆";
    private static final String EMOJI_CHANGE = "📈";

    /**
     * Save or update an arb
     */
    @Transactional
    public void saveArb(Arb incoming) {
        log.info("{} {} Starting arb save | ArbId: {}",
                EMOJI_SAVE, EMOJI_TARGET, incoming.getArbId());

        Instant now = Instant.now();

        if (incoming.getCreatedAt() == null) {
            incoming.setCreatedAt(now);
        }
        if (incoming.getLastUpdatedAt() == null) {
            incoming.setLastUpdatedAt(now);
        }

        // Update payouts
        incoming.getLegA().ifPresent(BetLeg::updatePotentialPayout);
        incoming.getLegB().ifPresent(BetLeg::updatePotentialPayout);

        // Find existing or create new
        Arb result = arbRepository.findById(incoming.getArbId())
                .map(existing -> {
                    // CHECK CONTINUITY BEFORE UPDATE
                    boolean continuityMaintained = continuityService.checkAndHandleContinuity(existing, now);

                    if (!continuityMaintained) {
                        log.warn("{} {} Treating as NEW arb due to continuity break | ArbId: {}",
                                EMOJI_WARNING, EMOJI_NEW, existing.getArbId());
                        // Reset and treat as new session
                        return updateExistingArbAfterBreak(existing, incoming, now);
                    }

                    // Normal update with continuity maintained
                    return updateExistingArb(existing, incoming, now);
                })
                .orElseGet(() -> createNewArb(incoming, now));

        Arb persisted = arbRepository.save(result);

        log.info("{} {} Arb saved | ArbId: {} | Status: {} | Profit: {}% | Session: {}s | Breaks: {}",
                EMOJI_SUCCESS, EMOJI_SAVE,
                persisted.getArbId(),
                persisted.getStatus(),
                persisted.getProfitPercentage(),
                persisted.getCurrentSessionDurationSeconds(),
                persisted.getContinuityBreakCount());
    }

    /**
     * Update existing arb
     */
    /**
     * Update existing arb (with continuity maintained)
     */
    private Arb updateExistingArb(Arb existing, Arb incoming, Instant now) {
        log.info("{} {} Updating arb (continuous) | ArbId: {} | Session: {}s",
                EMOJI_UPDATE, EMOJI_FIRE,
                existing.getArbId(),
                existing.getCurrentSessionDurationSeconds());

        mergeScalarFields(existing, incoming);
        existing.markSeen(now);

        BigDecimal oldA = existing.getLegA().map(BetLeg::getOdds).orElse(null);
        BigDecimal oldB = existing.getLegB().map(BetLeg::getOdds).orElse(null);

        upsertLeg(existing, incoming.getLegA().orElse(null), true);
        upsertLeg(existing, incoming.getLegB().orElse(null), false);

        BigDecimal newA = existing.getLegA().map(BetLeg::getOdds).orElse(null);
        BigDecimal newB = existing.getLegB().map(BetLeg::getOdds).orElse(null);

        handleOddsChanges(existing, oldA, oldB, newA, newB);

        if (incoming.getProfitPercentage() != null) {
            BigDecimal oldPeak = existing.getPeakProfitPercentage();
            existing.updatePeakProfit(incoming.getProfitPercentage());
            if (oldPeak == null || incoming.getProfitPercentage().compareTo(oldPeak) > 0) {
                log.info("{} {} New peak | Old: {}% | New: {}%",
                        EMOJI_TROPHY, EMOJI_FIRE, oldPeak, incoming.getProfitPercentage());
            }
        }

        arbFilter.recomputeMetrics(existing);

        return existing;
    }


    /**
     * Update existing arb after continuity break
     * Treat similar to new arb but keep the entity
     */
    private Arb updateExistingArbAfterBreak(Arb existing, Arb incoming, Instant now) {
        log.info("{} {} Resetting arb after break | ArbId: {} | OldSession: {}s",
                EMOJI_NEW, EMOJI_FIRE,
                existing.getArbId(),
                existing.getCurrentSessionDurationSeconds());

        // Merge current data
        mergeScalarFields(existing, incoming);
        existing.markSeen(now);

        // Update legs
        upsertLeg(existing, incoming.getLegA().orElse(null), true);
        upsertLeg(existing, incoming.getLegB().orElse(null), false);

        // Record initial odds for new session
        BigDecimal a = existing.getLegA().map(BetLeg::getOdds).orElse(null);
        BigDecimal b = existing.getLegB().map(BetLeg::getOdds).orElse(null);

        if (a != null || b != null) {
            try {
                captureAndRecordOddsChange(existing, null, null, a, b, ChangeReason.CONTINUITY_BREAK_RESET);
            } catch (Exception e) {
                log.error("{} Odds recording failed: {}", EMOJI_ERROR, e.getMessage());
                captureSnapshotSafely(existing, ChangeReason.CONTINUITY_BREAK_NO_ODDS);
            }
        }

        // Reset peak profit for new session
        if (incoming.getProfitPercentage() != null) {
            existing.setPeakProfitPercentage(incoming.getProfitPercentage());
            existing.setPeakProfitAt(now);
        }

        // Recompute metrics (will be limited due to new session)
        arbFilter.recomputeMetrics(existing);

        log.info("{} {} New session established | ArbId: {} | StartedAt: {}",
                EMOJI_SUCCESS, EMOJI_NEW,
                existing.getArbId(),
                existing.getCurrentSessionStartedAt());

        return existing;
    }

    /**
     * Create new arb
     */
    private Arb createNewArb(Arb fresh, Instant now) {
        log.info("{} {} Creating NEW arb | ArbId: {} | Profit: {}%",
                EMOJI_NEW, EMOJI_FIRE, fresh.getArbId(), fresh.getProfitPercentage());

        // Initialize continuity tracking
        continuityService.initializeContinuity(fresh, now);

        fresh.markSeen(now);

        BigDecimal a = fresh.getLegA().map(BetLeg::getOdds).orElse(null);
        BigDecimal b = fresh.getLegB().map(BetLeg::getOdds).orElse(null);

        if (a != null || b != null) {
            try {
                captureAndRecordOddsChange(fresh, null, null, a, b, ChangeReason.CREATED);
                log.info("{} {} Initial odds recorded | ArbId: {}",
                        EMOJI_SUCCESS, EMOJI_NEW, fresh.getArbId());
            } catch (Exception e) {
                log.error("{} {} Odds recording failed | Error: {}",
                        EMOJI_ERROR, EMOJI_WARNING, e.getMessage());
                captureSnapshotSafely(fresh, ChangeReason.CREATED_NO_ODDS_HISTORY);
            }
        } else {
            captureSnapshotSafely(fresh, ChangeReason.CREATED_NO_ODDS);
        }

        if (fresh.getProfitPercentage() != null) {
            fresh.updatePeakProfit(fresh.getProfitPercentage());
        }

        arbFilter.recomputeMetrics(fresh);

        return fresh;
    }

    /**
     * Handle odds changes
     */
    private void handleOddsChanges(Arb arb, BigDecimal oldA, BigDecimal oldB,
                                   BigDecimal newA, BigDecimal newB) {
        boolean aChanged = hasChanged(oldA, newA);
        boolean bChanged = hasChanged(oldB, newB);

        if (aChanged || bChanged) {
            log.info("{} {} Odds changed | ArbId: {} | A: {} -> {} | B: {} -> {}",
                    EMOJI_CHANGE, EMOJI_FIRE, arb.getArbId(), oldA, newA, oldB, newB);

            try {
                captureAndRecordOddsChange(arb, oldA, oldB, newA, newB, ChangeReason.SERVICE_UPSERT);
                log.info("{} Odds history updated | Changes: {}", EMOJI_SUCCESS, arb.getOddsChangeCount());
            } catch (Exception e) {
                log.error("{} {} Odds update failed | Error: {}", EMOJI_ERROR, EMOJI_WARNING, e.getMessage());
                captureSnapshotSafely(arb, ChangeReason.UPSERT_FALLBACK_SNAPSHOT);
            }
        } else {
            captureSnapshotSafely(arb, ChangeReason.NO_ODDS_CHANGE);
        }
    }

    /**
     * Capture snapshot and record odds change
     */
    private void captureAndRecordOddsChange(Arb arb,
                                            BigDecimal oldA, BigDecimal oldB,
                                            BigDecimal newA, BigDecimal newB,
                                            ChangeReason reason) {
        // Capture snapshot
        captureSnapshotSafely(arb, reason);

        // Record odds change
        OddsChange change = OddsChange.builder()
                .timestamp(Instant.now())
                .oldOddsA(oldA)
                .newOddsA(newA)
                .oldOddsB(oldB)
                .newOddsB(newB)
                .deltaA(calculateDelta(oldA, newA))
                .deltaB(calculateDelta(oldB, newB))
                .changeReason(reason)
                .build();

        arb.recordOddsChange(System.currentTimeMillis(), change);

        // Update legs odds
        arb.getLegA().ifPresent(leg -> {
            leg.setOdds(newA);
            leg.updatePotentialPayout();
        });
        arb.getLegB().ifPresent(leg -> {
            leg.setOdds(newB);
            leg.updatePotentialPayout();
        });

        arb.updateOddsRange(newA, newB);
    }

    /**
     * Capture snapshot safely
     */
    private void captureSnapshotSafely(Arb arb, ChangeReason reason) {
        try {
            Optional<BetLeg> legA = arb.getLegA();
            Optional<BetLeg> legB = arb.getLegB();

            if (legA.isEmpty() || legB.isEmpty()) return;

            ArbSnapshot snapshot = ArbSnapshot.builder()
                    .capturedAt(Instant.now())
                    .oddsLegA(legA.get().getOdds())
                    .oddsLegB(legB.get().getOdds())
                    .stakeA(arb.getStakeA())
                    .stakeB(arb.getStakeB())
                    .expectedProfit(arb.getProfitPercentage())
                    .confidenceScore(arb.getConfidenceScore())
                    .volatilitySigma(arb.getVolatilitySigma())
                    .velocityPctPerSec(arb.getVelocityPctPerSec())
                    .status(arb.getStatus())
                    .changeReason(reason)
                    .build();

            arb.addSnapshot(snapshot);
            log.debug("{} Snapshot captured | Reason: {}", EMOJI_SUCCESS, reason);
        } catch (Exception e) {
            log.warn("{} {} Snapshot failed | Reason: {} | Error: {}",
                    EMOJI_WARNING, EMOJI_ERROR, reason, e.getMessage());
        }
    }

    /**
     * Check if value has changed
     */
    private boolean hasChanged(BigDecimal oldVal, BigDecimal newVal) {
        if (oldVal == null && newVal == null) return false;
        if (oldVal == null || newVal == null) return true;
        return oldVal.compareTo(newVal) != 0;
    }

    /**
     * Calculate delta between two values
     */
    private BigDecimal calculateDelta(BigDecimal oldValue, BigDecimal newValue) {
        if (oldValue == null || newValue == null) return BigDecimal.ZERO;
        return newValue.subtract(oldValue);
    }

    /**
     * Merge scalar fields
     */
    private void mergeScalarFields(Arb target, Arb source) {
        target.setSportEnum(source.getSportEnum());
        target.setLeague(source.getLeague());
        target.setPeriod(source.getPeriod());
        target.setSelectionKey(source.getSelectionKey());
        target.setEventStartTime(source.getEventStartTime());
        target.setSetScore(source.getSetScore());
        target.setGameScore(source.getGameScore());
        target.setMatchStatus(source.getMatchStatus());
        target.setPlayedSeconds(source.getPlayedSeconds());
        target.setExpiresAt(source.getExpiresAt());
        target.setPredictedHoldUpMs(source.getPredictedHoldUpMs());
        target.setStatus(source.getStatus() != null ? source.getStatus() : target.getStatus());
        target.setActive(source.isActive());
        target.setShouldBet(source.isShouldBet());
        target.setStakeA(source.getStakeA());
        target.setStakeB(source.getStakeB());
        target.setProfitPercentage(source.getProfitPercentage());
    }

    /**
     * Upsert a bet leg
     */
    private void upsertLeg(Arb arb, BetLeg incomingLeg, boolean isPrimary) {
        if (incomingLeg == null) return;

        String legLabel = isPrimary ? "LegA" : "LegB";
        incomingLeg.setPrimaryLeg(isPrimary);

        Optional<BetLeg> maybeExisting = isPrimary ? arb.getLegA() : arb.getLegB();

        if (maybeExisting.isEmpty()) {
            log.debug("{} {} Creating {} | Bookmaker: {} | Odds: {}",
                    EMOJI_NEW, EMOJI_SUCCESS, legLabel,
                    incomingLeg.getBookmaker(), incomingLeg.getOdds());

            BetLeg clone = incomingLeg.toBuilder()
                    .id(null)
                    .version(null)
                    .arb(null)
                    .build();
            clone.setPrimaryLeg(isPrimary);

            arb.attachLeg(clone);
            clone.updatePotentialPayout();
            return;
        }

        BetLeg existingLeg = maybeExisting.get();
        log.debug("{} Merging {} | Old odds: {} | New odds: {}",
                EMOJI_UPDATE, legLabel, existingLeg.getOdds(), incomingLeg.getOdds());

        mergeLegFields(existingLeg, incomingLeg);
    }

    /**
     * Merge leg fields
     */
    private void mergeLegFields(BetLeg target, BetLeg source) {
        target.setBookmaker(source.getBookmaker());
        target.setEventId(source.getEventId());
        target.setHomeTeam(source.getHomeTeam());
        target.setAwayTeam(source.getAwayTeam());
        target.setLeague(source.getLeague());
        target.setSportEnum(source.getSportEnum());
        target.setOdds(source.getOdds());
        target.setRawStake(source.getRawStake());
        target.setStake(source.getStake());
        target.updatePotentialPayout();

        if (source.getStatus() != null) {
            target.setStatus(source.getStatus());
        }
        target.setBetId(source.getBetId());
        target.setPlacedAt(source.getPlacedAt());
        target.setSettledAt(source.getSettledAt());
        target.setActualPayout(source.getActualPayout());
        target.setErrorMessage(source.getErrorMessage());
        target.setAttemptCount(source.getAttemptCount() != null ? source.getAttemptCount() : target.getAttemptCount());
        target.setLastAttemptAt(source.getLastAttemptAt());
        target.setPlacedOdds(source.getPlacedOdds());
        target.setMatchStatus(source.getMatchStatus());
        target.setOutcomeDescription(source.getOutcomeDescription());
        target.setOutcomeId(source.getOutcomeId());
        target.setPeriod(source.getPeriod());
        target.setCashOutAvailable(source.getCashOutAvailable());
        target.setProfitPercent(source.getProfitPercent());
        target.setBalanceBeforeBet(source.getBalanceBeforeBet());
        target.setBalanceAfterBet(source.getBalanceAfterBet());
    }

    /**
     * Fetch top arbs by metrics (with continuity validation)
     */
//    public List<Arb> fetchTopArbsByMetrics(BigDecimal minProfit, int limit) {
//        log.info("{} {} Fetching top arbs | minProfit={}%, limit={}",
//                EMOJI_SEARCH, EMOJI_TROPHY, minProfit, limit);
//
//        Instant now = Instant.now();
//        Instant freshCutoff = now.minusSeconds(3); // Only very fresh arbs (updated in last 5s)
//
//        Page<Arb> page = arbRepository.findLiveArbsForBetting(
//                now,
//                minProfit,
//                freshCutoff,
//                PageRequest.of(0,
//                        Math.max(limit * 5, 100), // safety margin for scoring
//                        Sort.by(
//                                Sort.Order.desc("profitPercentage"),
//                                Sort.Order.desc("lastUpdatedAt")
//                        ))
//        );
//
//        List<Arb> candidates = page.getContent();
//        long totalMatching = page.getTotalElements();
//
//        log.info("{} Found {} candidates (total in DB: {})",
//                EMOJI_CHART, candidates.size(), totalMatching);
//
//        if (candidates.isEmpty()) {
//            log.warn("{} No fresh live arbs found (updated within last 5s)", EMOJI_WARNING);
//            return Collections.emptyList();
//        }
//
//        // Final business filters: shouldBet + reliable session duration
//        List<Arb> topArbs = candidates.stream()
//                .filter(arb -> {
//                    if (!arb.isShouldBet()) {
//                        log.debug("{} Skipped {} | shouldBet=false", EMOJI_WARNING, arb.getArbId());
//                        return false;
//                    }
//                    return true;
//                })
////                .filter(arb -> {
////                    long sessionSeconds = arb.getCurrentSessionDurationSeconds();
////                    if (sessionSeconds < minReliableSessionSeconds) {
////                        log.info("{} Skipped {} | session={}s (need >= {}s)",
////                                EMOJI_WARNING, arb.getArbId(), sessionSeconds, minReliableSessionSeconds);
////                        return false;
////                    }
////                    return true;
////                })
//                .sorted(Comparator.comparingDouble(this::calculateScore).reversed())
////                .filter(item -> {
////                    Duration diff = Duration.between(item.getLastUpdatedAt(), item.getLastSeenAt());
////                    return diff.getSeconds() > 5;
////                })
//                .limit(limit)
//                .toList();
//
//        log.info("{} {} Selected {} top arbs (from {} candidates)",
//                EMOJI_SUCCESS, EMOJI_FIRE, topArbs.size(), candidates.size());
//
//        if (!topArbs.isEmpty()) {
//            Arb best = topArbs.get(0);
//            double score = calculateScore(best);
//
//            log.info("{} {} BEST ARB | ID={} | Profit={} | Score={} | Session={}s | Breaks={} | Sport={}",
//                    EMOJI_TROPHY, EMOJI_FIRE,
//                    best.getArbId(),
//                    best.getProfitPercentage(),
//                    score,
//                    best.getCurrentSessionDurationSeconds(),
//                    best.getContinuityBreakCount(),
//                    best.getSportEnum());
//        } else {
//            log.info("{} No arbs passed final filters (shouldBet + reliable session)", EMOJI_WARNING);
//        }
//
//        return topArbs;
//    }



    /**
     * Calculate composite score for ranking
     */
    private double calculateScore(Arb arb) {
        double profit = nz(arb.getProfitPercentage());
        double conf = nz(arb.getConfidenceScore());
        double vel = nz(arb.getVelocityPctPerSec());
        double vol = nz(arb.getVolatilitySigma());
        double meanDev = arbFilter.calculateMeanDeviation(arb);
        double recency = arbFilter.calculateRecencyBoost(arb);

        return 1.00 * profit
                + 0.60 * conf
                - 0.25 * vel
                - 0.20 * vol
                - 0.15 * meanDev
                + 0.05 * recency;
    }

    /**
     * Null-safe double conversion
     */
    private double nz(Double d) {
        return d == null ? 0.0 : d;
    }

    /**
     * Null-safe BigDecimal to double conversion
     */
    private double nz(BigDecimal b) {
        return b == null ? 0.0 : b.doubleValue();
    }


}