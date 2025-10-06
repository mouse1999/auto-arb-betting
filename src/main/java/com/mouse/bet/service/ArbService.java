package com.mouse.bet.service;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.enums.Status;
import com.mouse.bet.repository.ArbRepository;
import com.mouse.bet.utils.ArbCalculator;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
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
     * this saves an arb to the database, if it already exist, update it
     */
    @Transactional
    public Arb saveArb(Arb arb) {
        if (arb == null || arb.getEventId() == null) {
            log.warn("Cannot save null arb or arb without eventId");
            return null;
        }

        Optional<Arb> existingArb = arbRepository.findByEventId(arb.getEventId());

        if (existingArb.isPresent()) {
            Arb existing = existingArb.get();
            log.debug("Updating existing arb for eventId: {}", arb.getEventId());

            // Update odds and track changes
            if (arb.getLegA() != null && arb.getLegB() != null) {
                BigDecimal newOddsA = arb.getLegA().getOdds();
                BigDecimal newOddsB = arb.getLegB().getOdds();

                // Check if odds have changed
                boolean oddsChanged = false;
                if (existing.getLegA() != null && !newOddsA.equals(existing.getLegA().getOdds())) {
                    oddsChanged = true;
                }
                if (existing.getLegB() != null && !newOddsB.equals(existing.getLegB().getOdds())) {
                    oddsChanged = true;
                }

                if (oddsChanged) {
                    existing.updateOdds(newOddsA, newOddsB, "Market update");
                }
            }

            // Update key fields
            existing.setLeague(arb.getLeague());
            existing.setHomeTeam(arb.getHomeTeam());
            existing.setAwayTeam(arb.getAwayTeam());
            existing.setMarketType(arb.getMarketType());
            existing.setPeriod(arb.getPeriod());
            existing.setSelectionKey(arb.getSelectionKey());
            existing.setEventStartTime(arb.getEventStartTime());

            // Update leg details
            existing.setLegA(arb.getLegA());
            existing.setLegB(arb.getLegB());

            // Update stakes and profit
            existing.setStakeA(arb.getStakeA());
            existing.setStakeB(arb.getStakeB());
            existing.setExpectedProfit(arb.getExpectedProfit());

            // Update confidence and metrics
            existing.setConfidenceScore(arb.getConfidenceScore());
            existing.setVolatilitySigma(arb.getVolatilitySigma());
            existing.setVelocityPctPerSec(arb.getVelocityPctPerSec());
            existing.setMeanOddsLegA(arb.getMeanOddsLegA());
            existing.setMeanOddsLegB(arb.getMeanOddsLegB());

            // Update timing
            existing.setExpiresAt(arb.getExpiresAt());
            existing.setPredictedHoldUpMs(arb.getPredictedHoldUpMs());
            existing.markSeen(Instant.now());

            // Update status if provided
            if (arb.getStatus() != null) {
                existing.setStatus(arb.getStatus());
            }
            existing.setActive(arb.isActive());
            existing.setShouldBet(arb.isShouldBet());

            // Track peak profit
            if (arb.getExpectedProfit() != null) {
                BigDecimal profitPct = calculateProfitPercentage(arb);
                existing.updatePeakProfit(profitPct);
            }

            return arbRepository.save(existing);
        } else {
            // New arb - set initial timestamps
            log.debug("Creating new arb for eventId: {}", arb.getEventId());
            Instant now = Instant.now();
            arb.markSeen(now);

            if (arb.getStatus() == null) {
                arb.setStatus(Status.ACTIVE);
            }

            // Capture initial snapshot
            if (arb.getLegA() != null && arb.getLegB() != null) {
                arb.captureSnapshot("Initial creation");
            }

            // Track initial peak profit
            if (arb.getExpectedProfit() != null) {
                BigDecimal profitPct = calculateProfitPercentage(arb);
                arb.updatePeakProfit(profitPct);
            }

            return arbRepository.save(arb);
        }
    }


    /**
     * this gets arb by its event ID
     * @param eventId
     * @return
     */
    @Transactional
    public Arb getArbByEventId(String eventId) {
        if (eventId == null || eventId.trim().isEmpty()) {
            log.warn("Cannot fetch arb with null or empty eventId");
            return null;
        }

        return arbRepository.findByEventId(eventId).orElse(null);
    }

    public void deleteArb(String eventId) {

    }

    /**
     * Helper method to calculate profit percentage
     */
    private BigDecimal calculateProfitPercentage(Arb arb) {
        if (arb.getStakeA() == null || arb.getStakeB() == null ||
                arb.getExpectedProfit() == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal arbitragePercentage = ArbCalculator.calculateArbitragePercentage(arb.getStakeA(), arb.getStakeB());
        return ArbCalculator.calculateProfitPercentage(arbitragePercentage);
    }

}
