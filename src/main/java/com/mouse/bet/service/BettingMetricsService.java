package com.mouse.bet.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class BettingMetricsService {

    private final AtomicInteger totalArbs = new AtomicInteger(0);
    private final AtomicInteger successfulArbs = new AtomicInteger(0);
    private final AtomicInteger failedArbs = new AtomicInteger(0);
    private final AtomicInteger rollbackTriggered = new AtomicInteger(0);
    private final AtomicInteger rollbackSucceeded = new AtomicInteger(0);
    private final AtomicInteger rollbackFailed = new AtomicInteger(0);

    public void recordArbAttempt() {
        totalArbs.incrementAndGet();
    }

    public void recordArbSuccess() {
        successfulArbs.incrementAndGet();
    }

    public void recordArbFailure() {
        failedArbs.incrementAndGet();
    }

    public void recordRollbackTriggered() {
        rollbackTriggered.incrementAndGet();
    }

    public void recordRollbackResult(boolean success) {
        if (success) {
            rollbackSucceeded.incrementAndGet();
        } else {
            rollbackFailed.incrementAndGet();
        }
    }

    public Map<String, Object> getMetrics() {
        int total = totalArbs.get();
        int rollbacks = rollbackTriggered.get();

        return Map.of(
                "totalArbs", total,
                "successfulArbs", successfulArbs.get(),
                "failedArbs", failedArbs.get(),
                "successRate", total > 0 ? (successfulArbs.get() * 100.0 / total) : 0,
                "rollbackTriggered", rollbacks,
                "rollbackSucceeded", rollbackSucceeded.get(),
                "rollbackFailed", rollbackFailed.get(),
                "rollbackRate", total > 0 ? (rollbacks * 100.0 / total) : 0,
                "rollbackSuccessRate", rollbacks > 0 ? (rollbackSucceeded.get() * 100.0 / rollbacks) : 0
        );
    }

    @Scheduled(fixedRate = 60000) // Every minute
    public void logMetrics() {
        Map<String, Object> metrics = getMetrics();
        log.info("📊 Betting Metrics: {}", metrics);

        // Alert if rollback rate is high
        double rollbackRate = (double) metrics.get("rollbackRate");
        if (rollbackRate > 5.0) {
            log.warn("⚠️ HIGH ROLLBACK RATE: {}%", String.format("%.2f", rollbackRate));
        }
    }
}
