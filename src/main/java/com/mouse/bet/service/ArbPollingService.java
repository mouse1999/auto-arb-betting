package com.mouse.bet.service;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.manager.ArbOrchestrator;
import com.mouse.bet.repository.ArbRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
@RequiredArgsConstructor
public class ArbPollingService {

    private final ArbRepository arbRepository;
    private final ArbOrchestrator arbOrchestrator;
    private final ArbService arbService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollingThread;

    @Value("${arb.min.profit:2.0}")
    private double minProfit;

    @Value("${arb.poll.interval.ms:200}")
    private long pollIntervalMs;

    @Value("${arb.lookback.seconds:2}")
    private int lookbackSeconds;

    @PostConstruct
    public void startPolling() {
        if (running.compareAndSet(false, true)) {
            pollingThread = new Thread(this::pollArbsInfinitely, "ArbPoller");
            pollingThread.setDaemon(false); // Keep app alive
            pollingThread.start();
            log.info("🚀 Arb polling started - Interval: {}ms, Lookback: {}s",
                    pollIntervalMs, lookbackSeconds);
        }
    }

    private void pollArbsInfinitely() {
        log.info("📊 Starting infinite arb polling loop...");

        while (running.get()) {
            try {
                long startTime = System.currentTimeMillis();

                // Calculate cutoff time
                Instant cutoff = Instant.now().minus(2, ChronoUnit.SECONDS);


                // Query database
                List<Arb> freshArbs = arbRepository.findFreshArbs(
                        minProfit,
                        cutoff,
                        100  // limit
                );



                long queryTime = System.currentTimeMillis() - startTime;

                if (!freshArbs.isEmpty()) {
                    log.info("🎯 Found {} fresh arbs (query: {}ms)",
                            freshArbs.size(), queryTime);

                    // Process arbs
                    for (Arb arb : freshArbs) {
                        arbOrchestrator.tryLoadArb(arb);
                    }
                }

                // Sleep to maintain consistent polling rate
                long elapsed = System.currentTimeMillis() - startTime;
                long sleepTime = Math.max(0, pollIntervalMs - elapsed);

                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                } else {
                    log.warn("⚠️ Query took {}ms (longer than {}ms interval)",
                            elapsed, pollIntervalMs);
                }

            } catch (InterruptedException e) {
                log.info("🛑 Polling interrupted");
                Thread.currentThread().interrupt();
                break;

            } catch (Exception e) {
                log.error("❌ Error in polling loop: {}", e.getMessage(), e);

                // Brief pause before retry to avoid tight error loop
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("🏁 Arb polling stopped");
    }


    public void killArb(Arb arb){
        if (arb != null) {
            arbService.saveArb(arb);
        }else {
            log.warn("arb is null and cannot be save");
        }

    }

    @PreDestroy
    public void stopPolling() {
        log.info("🛑 Stopping arb polling...");
        running.set(false);

        if (pollingThread != null) {
            pollingThread.interrupt();
            try {
                pollingThread.join(5000); // Wait max 5 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}