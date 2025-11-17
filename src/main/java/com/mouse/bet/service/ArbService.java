package com.mouse.bet.service;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.ChangeReason;
import com.mouse.bet.repository.ArbRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing arbitrage opportunities
 * - Persists and updates arbs in database
 * - Tracks odds changes and snapshots
 * - Provides ranked arbitrage recommendations
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArbService {

    private final ArbRepository arbRepository;

    // Emoji constants for logging
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
     * Save or update an Arb
     * - New arb: Initialize, compute metrics, create snapshot
     * - Existing arb: Merge fields, detect odds changes, update metrics
     */
    @Transactional
    public void saveArb(Arb incoming) {
        log.info("{} {} Starting arb save process | ArbId: {}",
                EMOJI_SAVE, EMOJI_TARGET, incoming.getArbId());

        Instant now = Instant.now();

        // Initialize timestamps if null
        if (incoming.getCreatedAt() == null) {
            incoming.setCreatedAt(now);
            log.info("{} Set createdAt: {}", EMOJI_CLOCK, now);
        }

        if (incoming.getLastUpdatedAt() == null) {
            incoming.setLastUpdatedAt(now);
            log.info("{} Set lastUpdatedAt: {}", EMOJI_CLOCK, now);
        }

        // Update potential payouts
        incoming.getLegA().ifPresent(leg -> {
            leg.updatePotentialPayout();
            log.debug("{} Updated payout for LegA: {}", EMOJI_MONEY, leg.getPotentialPayout());
        });

        incoming.getLegB().ifPresent(leg -> {
            leg.updatePotentialPayout();
            log.debug("{} Updated payout for LegB: {}", EMOJI_MONEY, leg.getPotentialPayout());
        });

        // Find existing or create new
        Arb result = arbRepository.findById(incoming.getArbId())
                .map(existing -> {
                    log.info("{} {} Updating existing arb | ArbId: {}",
                            EMOJI_UPDATE, EMOJI_FIRE, existing.getArbId());
                    return updateExistingArb(existing, incoming, now);
                })
                .orElseGet(() -> {
                    log.info("{} {} Creating new arb | ArbId: {} | Profit: {}%",
                            EMOJI_NEW, EMOJI_FIRE, incoming.getArbId(), incoming.getProfitPercentage());
                    return newArb(incoming, now);
                });

        // Persist to database
        Arb persisted = arbRepository.save(result);

        log.info("{} {} Arb persisted successfully | ArbId: {} | Status: {} | Active: {} | Legs: {} | OddsChanges: {} | Profit: {}%",
                EMOJI_SUCCESS, EMOJI_SAVE,
                persisted.getArbId(),
                persisted.getStatus(),
                persisted.isActive(),
                persisted.getLegs().size(),
                persisted.getOddsChangeCount(),
                persisted.getProfitPercentage());
    }

    /**
     * Update an existing arb with new data
     */
    private Arb updateExistingArb(Arb existing, Arb incoming, Instant now) {
        log.debug("{} Merging scalar fields | ArbId: {}", EMOJI_UPDATE, existing.getArbId());

        // Merge top-level fields
        mergeScalarFields(existing, incoming);

        // Mark as seen
        existing.markSeen(now);
        log.info("{} Marked as seen at: {}", EMOJI_CLOCK, now);

        // Track previous odds for change detection
        BigDecimal oldA = existing.getLegA().map(BetLeg::getOdds).orElse(null);
        BigDecimal oldB = existing.getLegB().map(BetLeg::getOdds).orElse(null);

        log.info("{} Previous odds | LegA: {} | LegB: {}", EMOJI_CHART, oldA, oldB);

        // Upsert legs
        upsertLeg(existing, incoming.getLegA().orElse(null), true);
        upsertLeg(existing, incoming.getLegB().orElse(null), false);

        // New odds after upsert
        BigDecimal newA = existing.getLegA().map(BetLeg::getOdds).orElse(null);
        BigDecimal newB = existing.getLegB().map(BetLeg::getOdds).orElse(null);

        log.info("{} New odds | LegA: {} | LegB: {}", EMOJI_CHART, newA, newB);

        // Handle odds changes
        handleOddsChanges(existing, oldA, oldB, newA, newB);

        // Update peak profit
        if (incoming.getProfitPercentage() != null) {
            BigDecimal oldPeak = existing.getPeakProfitPercentage();
            existing.updatePeakProfit(incoming.getProfitPercentage());

            if (oldPeak == null || incoming.getProfitPercentage().compareTo(oldPeak) > 0) {
                log.info("{} {} New peak profit achieved | Old: {}% | New: {}% | ArbId: {}",
                        EMOJI_TROPHY, EMOJI_FIRE, oldPeak, incoming.getProfitPercentage(), existing.getArbId());
            }
        }

        // Recompute stability metrics
        log.info("{} Recomputing derived metrics | ArbId: {}", EMOJI_CHART, existing.getArbId());
        ArbFilter.recomputeDerivedMetrics(existing);

        return existing;
    }

    /**
     * Create a new arb from incoming data
     */
    private Arb newArb(Arb fresh, Instant now) {
        fresh.markSeen(now);
        log.info("{} Marked new arb as seen at: {}", EMOJI_CLOCK, now);

        // Initial odds history & snapshot
        BigDecimal a = fresh.getLegA().map(BetLeg::getOdds).orElse(null);
        BigDecimal b = fresh.getLegB().map(BetLeg::getOdds).orElse(null);

        log.info("{} Initial odds | LegA: {} | LegB: {} | ArbId: {}",
                EMOJI_CHART, a, b, fresh.getArbId());

        if (a != null || b != null) {
            try {
                fresh.updateOdds(a, b, ChangeReason.CREATED);
                log.info("{} {} Initial odds history created | ArbId: {}",
                        EMOJI_SUCCESS, EMOJI_NEW, fresh.getArbId());
            } catch (Exception e) {
                log.error("{} {} Initial odds history failed | ArbId: {} | Error: {}",
                        EMOJI_ERROR, EMOJI_WARNING, fresh.getArbId(), e.getMessage());
                captureSnapshotSafely(fresh, ChangeReason.CREATED_NO_ODDS_HISTORY);
            }
        } else {
            log.warn("{} No odds available for new arb | ArbId: {}",
                    EMOJI_WARNING, fresh.getArbId());
            captureSnapshotSafely(fresh, ChangeReason.CREATED_NO_ODDS);
        }

        // Set initial peak profit
        if (fresh.getProfitPercentage() != null) {
            fresh.updatePeakProfit(fresh.getProfitPercentage());
            log.info("{} {} Initial peak profit set: {}% | ArbId: {}",
                    EMOJI_TROPHY, EMOJI_MONEY, fresh.getProfitPercentage(), fresh.getArbId());
        }

        // Compute stability metrics
        log.info("{} Computing initial stability metrics | ArbId: {}", EMOJI_CHART, fresh.getArbId());
        ArbFilter.recomputeDerivedMetrics(fresh);

        return fresh;
    }

    /**
     * Handle odds changes with proper auditing
     */
    private void handleOddsChanges(Arb existing, BigDecimal oldA, BigDecimal oldB,
                                   BigDecimal newA, BigDecimal newB) {
        boolean aChanged = hasOddsChanged(oldA, newA);
        boolean bChanged = hasOddsChanged(oldB, newB);

        if (aChanged || bChanged) {
            log.info("{} {} Odds change detected | ArbId: {} | LegA: {} -> {} | LegB: {} -> {}",
                    EMOJI_CHANGE, EMOJI_FIRE,
                    existing.getArbId(),
                    oldA, newA,
                    oldB, newB);

            try {
                existing.updateOdds(
                        newA != null ? newA : oldA,
                        newB != null ? newB : oldB,
                        ChangeReason.SERVICE_UPSERT
                );
                log.info("{} Odds history updated successfully | ArbId: {} | Changes: {}",
                        EMOJI_SUCCESS, existing.getArbId(), existing.getOddsChangeCount());
            } catch (Exception e) {
                log.error("{} {} Odds history update failed | ArbId: {} | Error: {}",
                        EMOJI_ERROR, EMOJI_WARNING, existing.getArbId(), e.getMessage());
                captureSnapshotSafely(existing, ChangeReason.UPSERT_FALLBACK_SNAPSHOT);
            }
        } else {
            log.info("{} No odds change detected | ArbId: {}", EMOJI_CHART, existing.getArbId());
            captureSnapshotSafely(existing, ChangeReason.NO_ODDS_CHANGE);
        }
    }

    /**
     * Check if odds value has changed
     */
    private boolean hasOddsChanged(BigDecimal oldVal, BigDecimal newVal) {
        if (oldVal == null && newVal == null) return false;
        if (oldVal == null || newVal == null) return true;
        return oldVal.compareTo(newVal) != 0;
    }

    /**
     * Merge scalar fields from source to target arb
     */
    private void mergeScalarFields(Arb target, Arb source) {
        log.trace("{} Merging scalar fields | ArbId: {}", EMOJI_UPDATE, target.getArbId());

        // Identity/Event meta
        target.setSportEnum(source.getSportEnum());
        target.setLeague(source.getLeague());
        target.setPeriod(source.getPeriod());
        target.setSelectionKey(source.getSelectionKey());
        target.setEventStartTime(source.getEventStartTime());

        // Live state
        target.setSetScore(source.getSetScore());
        target.setGameScore(source.getGameScore());
        target.setMatchStatus(source.getMatchStatus());
        target.setPlayedSeconds(source.getPlayedSeconds());

        // TTL / scheduling
        target.setExpiresAt(source.getExpiresAt());
        target.setPredictedHoldUpMs(source.getPredictedHoldUpMs());

        // Flags & metrics
        target.setStatus(source.getStatus() != null ? source.getStatus() : target.getStatus());
        target.setActive(source.isActive());
        target.setShouldBet(source.isShouldBet());

        // Stakes & profit
        target.setStakeA(source.getStakeA());
        target.setStakeB(source.getStakeB());
        target.setProfitPercentage(source.getProfitPercentage());
    }

    /**
     * Upsert a bet leg (either primary/A or secondary/B)
     */
    private void upsertLeg(Arb existing, BetLeg incomingLeg, boolean isPrimary) {
        if (incomingLeg == null) {
            log.trace("{} No incoming leg to upsert | isPrimary: {}", EMOJI_WARNING, isPrimary);
            return;
        }

        String legLabel = isPrimary ? "LegA" : "LegB";
        incomingLeg.setPrimaryLeg(isPrimary);

        Optional<BetLeg> maybeExisting = isPrimary ? existing.getLegA() : existing.getLegB();

        if (maybeExisting.isEmpty()) {
            log.info("{} {} Creating new {} | Bookmaker: {} | Odds: {}",
                    EMOJI_NEW, EMOJI_SUCCESS, legLabel,
                    incomingLeg.getBookmaker(), incomingLeg.getOdds());

            // Attach a detached clone, avoids cross-Arb ownership
            BetLeg clone = incomingLeg.toBuilder()
                    .id(null)
                    .version(null)
                    .arb(null)
                    .build();
            clone.setPrimaryLeg(isPrimary);

            existing.attachLeg(clone);
            clone.updatePotentialPayout();

            log.info("{} {} attached with payout: {}",
                    legLabel, EMOJI_MONEY, clone.getPotentialPayout());
            return;
        }

        // Merge into existing leg
        BetLeg existingLeg = maybeExisting.get();
        log.info("{} Merging into existing {} | Old odds: {} | New odds: {}",
                EMOJI_UPDATE, legLabel, existingLeg.getOdds(), incomingLeg.getOdds());

        mergeLegFields(existingLeg, incomingLeg);

        log.info("{} {} updated successfully | Payout: {}",
                legLabel, EMOJI_SUCCESS, existingLeg.getPotentialPayout());
    }

    /**
     * Merge fields from incoming leg to existing leg
     */
    private void mergeLegFields(BetLeg target, BetLeg source) {
        // Core identifiers
        target.setBookmaker(source.getBookmaker());
        target.setEventId(source.getEventId());
        target.setHomeTeam(source.getHomeTeam());
        target.setAwayTeam(source.getAwayTeam());
        target.setLeague(source.getLeague());
        target.setSportEnum(source.getSportEnum());

        // Prices & money
        target.setOdds(source.getOdds());
        target.setRawStake(source.getRawStake());
        target.setStake(source.getStake());
        target.updatePotentialPayout();

        // Lifecycle & placement
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

        // Additional fields
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
     * Safely capture snapshot, swallowing exceptions
     */
    private void captureSnapshotSafely(Arb arb, ChangeReason reason) {
        try {
            arb.captureSnapshot(reason);
            log.debug("{} Snapshot captured | Reason: {} | ArbId: {}",
                    EMOJI_SUCCESS, reason, arb.getArbId());
        } catch (Exception e) {
            log.warn("{} {} Snapshot capture failed | ArbId: {} | Reason: {} | Error: {}",
                    EMOJI_WARNING, EMOJI_ERROR, arb.getArbId(), reason, e.getMessage());
        }
    }

    /**
     * Fetch top arbitrage opportunities by ranking metrics
     */
    public List<Arb> fetchTopArbsByMetrics(BigDecimal minProfit, int limit) {
        log.info("{} {} Fetching top arbs | MinProfit: {}% | Limit: {}",
                EMOJI_SEARCH, EMOJI_TROPHY, minProfit, limit);

        Instant now = Instant.now();

        // Pull a wider pool, then rank locally
        List<Arb> pool = arbRepository.findActiveCandidates(
                now,
                minProfit,
                PageRequest.of(0, Math.max(limit * 5, 100), Sort.by(Sort.Order.desc("profitPercentage")))
        );

        log.info("{} Found {} candidates from database", EMOJI_CHART, pool.size());

        // Rank by soft score using computed metrics
        List<Arb> topArbs = pool.stream()
                .filter(Arb::isShouldBet)
                .sorted(Comparator.comparingDouble(this::score).reversed())
                .limit(limit)
                .toList();

        log.info("{} {} Top {} arbs selected after scoring",
                EMOJI_SUCCESS, EMOJI_FIRE, topArbs.size());

        if (!topArbs.isEmpty()) {
            Arb best = topArbs.get(0);
            log.info("{} {} BEST ARB | ArbId: {} | Profit: {}% | Score: {} | Confidence: {}",
                    EMOJI_TROPHY, EMOJI_FIRE,
                    best.getArbId(),
                    best.getProfitPercentage(),
                    String.format("%.2f", score(best)),
                    best.getConfidenceScore());
        }

        return topArbs;
    }

    /**
     * Calculate composite score for arb ranking
     * Higher score = better opportunity
     */
    private double score(Arb a) {
        double profit = nz(a.getProfitPercentage());
        double conf = nz(a.getConfidenceScore());            // higher is better
        double vel = nz(a.getVelocityPctPerSec());          // lower is better
        double vol = nz(a.getVolatilitySigma());            // lower is better
        double meanDev = meanDeviation(a);                   // lower is better
        double recency = recencyBoost(a);                    // small bonus for fresh data

        double compositeScore = 1.00 * profit
                + 0.60 * conf
                - 0.25 * vel
                - 0.20 * vol
                - 0.15 * meanDev
                + 0.05 * recency;

        log.info("{} Score calculated | ArbId: {} | Total: {:.2f} | Profit: {:.2f} | Conf: {:.2f} | Vel: {:.2f} | Vol: {:.2f}",
                EMOJI_CHART, a.getArbId(), compositeScore, profit, conf, vel, vol);

        return compositeScore;
    }

    /**
     * Calculate mean deviation from SMA for odds
     */
    private double meanDeviation(Arb a) {
        double devA = 0.0, devB = 0.0;
        int n = 0;

        BigDecimal legAOdds = a.getLegA().map(BetLeg::getOdds).orElse(null);
        if (legAOdds != null && a.getMeanOddsLegA() != null && a.getMeanOddsLegA() > 0) {
            devA = Math.abs(legAOdds.doubleValue() / a.getMeanOddsLegA() - 1.0) * 100.0;
            n++;
        }

        BigDecimal legBOdds = a.getLegB().map(BetLeg::getOdds).orElse(null);
        if (legBOdds != null && a.getMeanOddsLegB() != null && a.getMeanOddsLegB() > 0) {
            devB = Math.abs(legBOdds.doubleValue() / a.getMeanOddsLegB() - 1.0) * 100.0;
            n++;
        }

        return n == 0 ? 0.0 : (devA + devB) / n;
    }

    /**
     * Calculate recency boost based on last update time
     */
    private double recencyBoost(Arb a) {
        Instant lu = a.getLastUpdatedAt();
        if (lu == null) return 0.0;

        long ageSec = Math.max(0, Instant.now().getEpochSecond() - lu.getEpochSecond());

        if (ageSec <= 3) return 1.0;
        if (ageSec <= 10) return 0.5;
        return 0.0;
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

    /**
     * Retrieve arb by its unique identifier
     */
    public Arb getArbByNormalEventId(String normalEventId) {
        log.info("{} {} Fetching arb by ID: {}", EMOJI_SEARCH, EMOJI_TARGET, normalEventId);

        Arb arb = arbRepository.findByArbId(normalEventId).orElse(null);

        if (arb != null) {
            log.debug("{} Arb found | ArbId: {} | Profit: {}%",
                    EMOJI_SUCCESS, arb.getArbId(), arb.getProfitPercentage());
        } else {
            log.debug("{} Arb not found | ArbId: {}", EMOJI_WARNING, normalEventId);
        }

        return arb;
    }
}