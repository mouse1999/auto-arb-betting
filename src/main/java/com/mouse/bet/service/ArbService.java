package com.mouse.bet.service;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.repository.ArbRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * This class helps to add arbs to caches and also provides methods to update it if it already existed
 * persist arb info in database
 * it depends on arbcalculator
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArbService {
    private final ArbRepository arbRepository;

    /**
     * Save or update an Arb (identified by normalEventId).
     * - If new: attach legs properly, compute payouts, mark seen, seed odds history + snapshot, compute metrics, save.
     * - If existing: merge scalar fields, upsert legs, detect odds changes, snapshot each update, compute metrics, save.
     */
    @Transactional
    public void saveArb(Arb incoming) {
//        Objects.requireNonNull(incoming, "arb must not be null");
//        Objects.requireNonNull(incoming.getNormalEventId(), "arb.normalEventId must not be null");

        Instant now = Instant.now();

        // Normalize incoming legs to respect A/B roles and bidirectional consistency
        normalizeIncomingLegs(incoming);

        // Ensure potential payouts are up-to-date on the payload
        incoming.getLegA().ifPresent(BetLeg::updatePotentialPayout);
        incoming.getLegB().ifPresent(BetLeg::updatePotentialPayout);

        Arb result = arbRepository.findById(incoming.getArbId())
                .map(existing -> updateExistingArb(existing, incoming, now))
                .orElseGet(() -> newArb(incoming, now));

        Arb persisted = arbRepository.save(result);

        if (log.isDebugEnabled()) {
            log.debug("Upserted Arb {} | status={} active={} legs={} changes={}",
                    persisted.getArbId(),
                    persisted.getStatus(),
                    persisted.isActive(),
                    persisted.getLegs().size(),
                    persisted.getOddsChangeCount());
        }

    }


    /**
     * Update an existing arb with new data
     */
    private Arb updateExistingArb(Arb existing, Arb incoming, Instant now) {
        // Merge top-level fields
        mergeScalarFields(existing, incoming);

        // Mark as seen
        existing.markSeen(now);

        // Track previous odds for change detection
        BigDecimal oldA = existing.getLegA().map(BetLeg::getOdds).orElse(null);
        BigDecimal oldB = existing.getLegB().map(BetLeg::getOdds).orElse(null);

        // Upsert legs
        upsertLeg(existing, incoming.getLegA().orElse(null), true);
        upsertLeg(existing, incoming.getLegB().orElse(null), false);

        // New odds after upsert
        BigDecimal newA = existing.getLegA().map(BetLeg::getOdds).orElse(null);
        BigDecimal newB = existing.getLegB().map(BetLeg::getOdds).orElse(null);

        // Handle odds changes
        handleOddsChanges(existing, oldA, oldB, newA, newB);

        // Update peak profit
        if (incoming.getProfitPercentage() != null) {
            existing.updatePeakProfit(incoming.getProfitPercentage());
        }

        // Recompute stability metrics from recent odds history
        ArbFilter.recomputeDerivedMetrics(existing);

        return existing;
    }

    /**
     * Create a new arb from incoming data
     */
    private Arb newArb(Arb fresh, Instant now) {
        fresh.markSeen(now);

        // Initial odds history & snapshot
        BigDecimal a = fresh.getLegA().map(BetLeg::getOdds).orElse(null);
        BigDecimal b = fresh.getLegB().map(BetLeg::getOdds).orElse(null);

        if (a != null || b != null) {
            try {
                fresh.updateOdds(a, b, "CREATED");
            } catch (Exception e) {
                log.warn("Initial odds history write failed for {}: {}", fresh.getArbId(), e.getMessage());
                captureSnapshotSafely(fresh, "CREATED_NO_ODDS_HISTORY");
            }
        } else {
            captureSnapshotSafely(fresh, "CREATED_NO_ODDS");
        }

        // Set initial peak profit
        if (fresh.getProfitPercentage() != null) {
            fresh.updatePeakProfit(fresh.getProfitPercentage());
        }

        // Compute stability metrics on creation
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
            try {
                existing.updateOdds(
                        newA != null ? newA : oldA,
                        newB != null ? newB : oldB,
                        "SERVICE_UPSERT"
                );
            } catch (Exception e) {
                log.warn("Odds history update failed for {}: {}", existing.getArbId(), e.getMessage());
                captureSnapshotSafely(existing, "UPSERT_FALLBACK_SNAPSHOT");
            }
        } else {
            captureSnapshotSafely(existing, "NO_ODDS_CHANGE");
        }
    }

    /**
     * Normalize incoming legs to ensure single A/B leg invariant
     */
    private void normalizeIncomingLegs(Arb arb) {
        List<BetLeg> copy = new ArrayList<>(arb.getLegs());
        arb.getLegs().clear();

        for (BetLeg leg : copy) {
            if (leg.isPrimaryLeg()) {
                arb.setLegA(leg);
            } else {
                arb.setLegB(leg);
            }
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

        // Flags & metrics (will be recomputed by ArbFilter)
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
        if (incomingLeg == null) return;

        incomingLeg.setPrimaryLeg(isPrimary);

        Optional<BetLeg> maybeExisting = isPrimary ? existing.getLegA() : existing.getLegB();
        if (maybeExisting.isEmpty()) {
            // attach a detached clone, avoids cross-Arb ownership
            BetLeg clone = incomingLeg.toBuilder()
                    .id(null).version(null)
                    .arb(null)
                    .build();
            clone.setPrimaryLeg(isPrimary);

            existing.attachLeg(clone);
            clone.updatePotentialPayout();
            return;
        }

        // merge into existing role
        BetLeg existingLeg = maybeExisting.get();
        mergeLegFields(existingLeg, incomingLeg);
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
    private void captureSnapshotSafely(Arb arb, String reason) {
        try {
            arb.captureSnapshot(reason);
        } catch (Exception e) {
            log.warn("Failed to capture snapshot for {} with reason '{}': {}",
                    arb.getArbId(), reason, e.getMessage());
        }
    }

    public List<Arb> fetchTopArbsByMetrics(BigDecimal minProfit, int limit) {
        var now = Instant.now();
        // Pull a wider pool, then rank locally
        var pool = arbRepository.findActiveCandidates(
                now, minProfit,
                PageRequest.of(0, Math.max(limit * 5, 100), Sort.by(Sort.Order.desc("profitPercentage")))
        );
        // Rank by soft score using your computed metrics
        return pool.stream()
                .filter(Arb::isShouldBet)
                .sorted(Comparator.comparingDouble(this::score).reversed())
                .limit(limit)
                .toList();
    }

    /** Higher score = better. No hard cutoffs here. */
    private double score(Arb a) {
        double profit = nz(a.getProfitPercentage());
        double conf   = nz(a.getConfidenceScore());            // higher is better
        double vel    = nz(a.getVelocityPctPerSec());          // lower is better
        double vol    = nz(a.getVolatilitySigma());            // lower is better
        double meanDev = meanDeviation(a);                     // lower is better (distance from SMA)
        double recency = recencyBoost(a);                      // small bonus for fresh data

        // Weights: profit leads; confidence helps; velocity/volatility/meanDev penalize; slight recency boost
        return 1.00 * profit
                + 0.60 * conf
                - 0.25 * vel
                - 0.20 * vol
                - 0.15 * meanDev
                + 0.05 * recency;
    }

    private double meanDeviation(Arb a) {
        double devA = 0.0, devB = 0.0; int n=0;
        var legA = a.getLegA().map(BetLeg::getOdds).orElse(null);
        if (legA != null && a.getMeanOddsLegA()!=null && a.getMeanOddsLegA()>0) {
            devA = Math.abs(legA.doubleValue()/a.getMeanOddsLegA() - 1.0)*100.0; n++;
        }
        var legB = a.getLegB().map(BetLeg::getOdds).orElse(null);
        if (legB != null && a.getMeanOddsLegB()!=null && a.getMeanOddsLegB()>0) {
            devB = Math.abs(legB.doubleValue()/a.getMeanOddsLegB() - 1.0)*100.0; n++;
        }
        return n==0?0.0:(devA+devB)/n; // % from SMA
    }

    private double recencyBoost(Arb a) {
        // tiny boost if updated in last 10s making most recent have an edge
        var lu = a.getLastUpdatedAt();
        if (lu == null) return 0.0;
        long ageSec = Math.max(0, Instant.now().getEpochSecond() - lu.getEpochSecond());
        if (ageSec <= 3)  return 1.0;
        if (ageSec <= 10) return 0.5;
        return 0.0;
    }

    private double nz(Double d) { return d==null?0.0:d; }
    private double nz(BigDecimal b) {
        return b==null?0.0:b.doubleValue();
    }

    public Arb getArbByNormalEventId(String normalEventId) {
        return arbRepository.findByArbId(normalEventId).orElse(null);
    }



}
