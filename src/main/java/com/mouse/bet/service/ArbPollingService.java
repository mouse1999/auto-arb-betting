package com.mouse.bet.service;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.enums.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    @Value("${arb.min.profit.percentage:4.0}")
    private BigDecimal minProfitPercentage;
    @Value("${arb.fetch.limit:10}")
    private int fetchLimit;



    /**
     * Called periodically by ArbOrchestrator when it's idle.
     * Returns one high-quality arb, or empty.
     */
    public Optional<Arb> fetchNextArbCandidate() {
        try {
            log.info("Fetching next arb candidate | minProfit={}%, limit={}",
                    minProfitPercentage, fetchLimit);

            List<Arb> candidates = arbService.fetchTopArbsByMetrics(minProfitPercentage, fetchLimit);

            if (candidates.isEmpty()) {
                log.info("No arb candidates found above {}% profit", minProfitPercentage);
                return Optional.empty();
            }

            // Randomly select one to avoid always picking the same (best) one
            Arb selected = candidates.get(random.nextInt(candidates.size()));

            log.info("Selected arb candidate | ArbId={} | Profit={} | Sport={} | League={} | ShouldBet={}",
                    selected.getArbId(),
                    selected.getProfitPercentage(),
                    selected.getSportEnum(),
                    selected.getLeague(),
                    selected.isShouldBet());

            return Optional.of(selected);

        } catch (Exception e) {
            log.error("Failed to fetch next arb candidate", e);
            return Optional.empty();
        }
    }

    /**
     * Helper: release arb if orchestrator rejects it early
     */
    public void killArb(Arb arb) {
        if (arb == null) return;
        try {
            arb.setStatus(Status.EXPIRED);
            arb.setActive(false);
            arbService.saveArb(arb);
            log.debug("Arb killed back : {}", arb.getArbId());
        } catch (Exception e) {
            log.warn("Failed to kill arb {}", arb.getArbId(), e);
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