package com.mouse.bet.service;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.enums.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Lightweight facade that feeds arbs into your existing ArbOrchestrator.
 * It does NOT do locking or dispatching — that's correctly handled by ArbOrchestrator.
 * This service only responsibility is: "Get me the next profitable arb".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArbPollingService {

    private final ArbService arbService;

    private final Random random = new Random();

    // Configurable via @Value or constructor
    private final BigDecimal minProfitPercentage = BigDecimal.TEN; // 10%
    private final int FETCH_LIMIT = 5;



    /**
     * Called periodically by ArbOrchestrator when it's idle.
     * Returns one high-quality arb, or empty.
     */
    public Optional<Arb> fetchNextArbCandidate() {
        try {
            List<Arb> candidates = arbService.fetchTopArbsByMetrics(minProfitPercentage, FETCH_LIMIT);

            if (candidates.isEmpty()) {
                log.info("No arb candidates above {}% profit", minProfitPercentage);
                return Optional.empty();
            }

            // Optional: randomize to avoid always picking same event
            Arb selected = candidates.get(random.nextInt(candidates.size()));

            log.info("Selected arb candidate | ArbId: {} | Profit: {}% | Legs: {}",
                    selected.getArbId(),
                    selected.getProfitPercentage(),
                    selected.getLegs().size());

            return Optional.of(selected);

        } catch (Exception e) {
            log.error("Error fetching next arb candidate", e);
            return Optional.empty();
        }
    }

    /**
     * Helper: release arb if orchestrator rejects it early
     */
    public void releaseArb(Arb arb) {
        if (arb == null) return;
        try {
            arb.setStatus(Status.EXPIRED); // or whatever your "free" status is
            arbService.saveArb(arb);
            log.debug("Arb released back to pool: {}", arb.getArbId());
        } catch (Exception e) {
            log.warn("Failed to release arb {}", arb.getArbId(), e);
        }
    }

    /**
     * Get stats (for monitoring)
     */
//    public ArbPollingStats getStats() {
//        try {
//            long available = arbService.countByStatus(Status.ACTIVE);
//            long inProgress = arbService.countByStatus(com.mouse.bet.enums.Status.IN_PROGRESS);
//            long completed = arbService.countByStatus(com.mouse.bet.enums.Status.COMPLETED);
//            long failed = arbService.countByStatus(com.mouse.bet.enums.Status.FAILED);
//
//            return new ArbPollingStats(available, inProgress, completed, failed);
//        } catch (Exception e) {
//            log.error("Failed to get polling stats", e);
//            return new ArbPollingStats(0, 0, 0, 0);
//        }
//    }

    public record ArbPollingStats(
            long available,
            long inProgress,
            long completed,
            long failed
    ) {}
}