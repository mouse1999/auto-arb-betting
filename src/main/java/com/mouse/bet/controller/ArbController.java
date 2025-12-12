package com.mouse.bet.controller;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.service.ArbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/arbs")
public class ArbController {

    private final ArbService arbService;

    /**
     * Get the best live arbitrage opportunities.
     * Returns arbs sorted by composite score (profit + confidence + velocity + low volatility).
     *
     * @param minProfit Minimum profit percentage (e.g. 1.5 = 1.5%). Default: 1.0
     * @param limit     Maximum number of arbs to return. Default: 10
     * @return JSON with arbs list + metadata
     */
    @GetMapping("/top")
    public ResponseEntity<Map<String, Object>> getTopArbs(
            @RequestParam(defaultValue = "1.0") BigDecimal minProfit,
            @RequestParam(defaultValue = "10") int limit
    ) {
        log.info("GET /api/v1/arbs/top - minProfit={}%, limit={}", minProfit, limit);

        List<Arb> topArbs = arbService.fetchTopArbsByMetrics(minProfit, limit);

        Map<String, Object> response = new HashMap<>();
        response.put("arbs", topArbs);
        response.put("count", topArbs.size());
        response.put("requestedLimit", limit);
        response.put("minProfitRequested", minProfit);
        response.put("timestamp", Instant.now());

        if (!topArbs.isEmpty()) {
            Arb best = topArbs.get(0);
            response.put("bestArb", Map.of(
                    "arbId", best.getArbId(),
                    "profitPercentage", best.getProfitPercentage(),
                    "sessionDurationSeconds", best.getCurrentSessionDurationSeconds(),
                    "continuityBreakCount", best.getContinuityBreakCount(),
                    "sport", best.getSportEnum(),
                    "league", best.getLeague(),
                    "shouldBet", best.isShouldBet()
            ));
        } else {
            response.put("message", "No matching arbitrage opportunities found");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("timestamp", Instant.now());
        return ResponseEntity.ok(status);
    }

    // Optional: debug endpoint (no validation)
    @GetMapping("/debug/top")
    public ResponseEntity<List<Arb>> debugTopArbs(
            @RequestParam(defaultValue = "0.1") BigDecimal minProfit,
            @RequestParam(defaultValue = "20") int limit
    ) {
        log.warn("DEBUG endpoint called - minProfit={}, limit={}", minProfit, limit);
        return ResponseEntity.ok(arbService.fetchTopArbsByMetrics(minProfit, limit));
    }
}