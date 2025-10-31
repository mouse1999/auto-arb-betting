package com.mouse.bet.tasks;

import com.mouse.bet.config.ScraperConfig;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates multiple odds fetchers concurrently with health monitoring,
 * graceful shutdown, and per-scraper enable/disable configuration.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class Player implements ApplicationListener<ApplicationReadyEvent> {

    private final ScraperConfig scraperConfig;
    private final SportyBetOddsFetcher sportyBetOddsFetcher;
    private final Bet9jaOddsFetcher bet9jaOddsFetcher;

    // ==================== CONFIGURATION ====================
    private static final long HEALTH_CHECK_INTERVAL_SEC = 30;
    private static final long HEALTH_CHECK_DELAY_INTERVAL_SEC = 100;
    private static final long RESTART_DELAY_SEC = 60;
    private static final int MAX_RESTART_ATTEMPTS = 3;

    // ==================== STATE TRACKING ====================
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final ExecutorService orchestratorExecutor = Executors.newCachedThreadPool(
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r);
                    t.setName("scraper-orchestrator-" + counter.incrementAndGet());
                    t.setDaemon(false);
                    return t;
                }
            }
    );

    private final ScheduledExecutorService healthMonitor = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r);
                t.setName("scraper-health-monitor");
                t.setDaemon(true);
                return t;
            }
    );

    private final List<ScraperTask> activeTasks = new CopyOnWriteArrayList<>();

    // ==================== SCRAPER TASK WRAPPER ====================
    private static class ScraperTask {
        @Getter
        private final String name;
        @Getter
        private final Runnable fetcher;
        @Getter
        private final boolean enabled;
        @Getter
        private final Future<?> future;
        private final AtomicInteger restartCount = new AtomicInteger(0);
        private final AtomicBoolean isHealthy = new AtomicBoolean(true);
        @Getter
        private volatile long lastHeartbeat = System.currentTimeMillis();

        public ScraperTask(String name, Runnable fetcher, boolean enabled, Future<?> future) {
            this.name = name;
            this.fetcher = fetcher;
            this.enabled = enabled;
            this.future = future;
        }

        public int getRestartCount() { return restartCount.get(); }
        public int incrementRestartCount() { return restartCount.incrementAndGet(); }
        public boolean isHealthy() { return isHealthy.get(); }
        public void setHealthy(boolean healthy) { isHealthy.set(healthy); }

        public void updateHeartbeat() { lastHeartbeat = System.currentTimeMillis(); }

        public boolean isDone() {
            return future != null && future.isDone();
        }

        public boolean isCancelled() {
            return future != null && future.isCancelled();
        }
    }

    // ==================== APPLICATION STARTUP ====================
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("   ODDS SCRAPER ORCHESTRATOR - STARTING");
        log.info("═══════════════════════════════════════════════════════════");

        if (isRunning.compareAndSet(false, true)) {
            startAllScrapers();
            startHealthMonitoring();
        } else {
            log.warn("Orchestrator already running, skipping startup");
        }
    }

    // ==================== START ALL SCRAPERS ====================
    private void startAllScrapers() {
        log.info("Starting all enabled scrapers...");

        // ✅ SportyBet Scraper
        if (scraperConfig.isSportyBetEnabled()) {
            log.info("✅ SportyBet scraper is ENABLED");
            Future<?> future = orchestratorExecutor.submit(() -> {
                try {
                    sportyBetOddsFetcher.run();
                } catch (Exception e) {
                    log.error("SportyBet scraper crashed: {}", e.getMessage(), e);
                    throw e;
                }
            });
            activeTasks.add(new ScraperTask("SportyBet", sportyBetOddsFetcher, true, future));
        } else {
            log.warn("⚠️ SportyBet scraper is DISABLED in configuration");
        }

        // ✅ Bet9ja Scraper
        if (scraperConfig.isBet9jaEnabled()) {
            log.info("✅ Bet9ja scraper is ENABLED");
            Future<?> future = orchestratorExecutor.submit(() -> {
                try {
                    bet9jaOddsFetcher.run();
                } catch (Exception e) {
                    log.error("Bet9ja scraper crashed: {}", e.getMessage(), e);
                    throw e;
                }
            });
            activeTasks.add(new ScraperTask("Bet9ja", bet9jaOddsFetcher, true, future));
        } else {
            log.warn("⚠️ Bet9ja scraper is DISABLED in configuration");
        }

        // ✅ Add more scrapers here
        // if (scraperConfig.isBetKingEnabled()) {
        //     log.info("✅ BetKing scraper is ENABLED");
        //     Future<?> future = orchestratorExecutor.submit(() -> {
        //         try {
        //             betKingOddsFetcher.run();
        //         } catch (Exception e) {
        //             log.error("BetKing scraper crashed: {}", e.getMessage(), e);
        //             throw e;
        //         }
        //     });
        //     activeTasks.add(new ScraperTask("BetKing", betKingOddsFetcher, true, future));
        // } else {
        //     log.warn("⚠️ BetKing scraper is DISABLED in configuration");
        // }

        log.info("═══════════════════════════════════════════════════════════");
        log.info("   {} SCRAPERS STARTED SUCCESSFULLY", activeTasks.size());
        log.info("═══════════════════════════════════════════════════════════");
    }

    // ==================== HEALTH MONITORING ====================
    private void startHealthMonitoring() {
        log.info("Starting health monitoring (interval: {}s)", HEALTH_CHECK_INTERVAL_SEC);

        healthMonitor.scheduleAtFixedRate(() -> {
            try {
                checkScraperHealth();
            } catch (Exception e) {
                log.error("Health check error: {}", e.getMessage());
            }
        }, HEALTH_CHECK_DELAY_INTERVAL_SEC, HEALTH_CHECK_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private void checkScraperHealth() {
        log.debug("═══ Health Check ═══");

        int healthy = 0;
        int unhealthy = 0;
        int crashed = 0;

        for (ScraperTask task : activeTasks) {
            String status = getTaskStatus(task);

            if (task.isDone() && !task.isCancelled()) {
                // Task finished unexpectedly
                crashed++;
                log.error("❌ {} scraper CRASHED! Restart count: {}",
                        task.getName(), task.getRestartCount());

                // Attempt restart if under max attempts
                if (task.getRestartCount() < MAX_RESTART_ATTEMPTS) {
                    attemptRestart(task);
                } else {
                    log.error("❌ {} scraper exceeded max restart attempts ({}), giving up",
                            task.getName(), MAX_RESTART_ATTEMPTS);
                }
            } else if (task.isCancelled()) {
                log.warn("⚠️ {} scraper was CANCELLED", task.getName());
                unhealthy++;
            } else {
                // Task is running
                healthy++;
                log.debug("✅ {} scraper is healthy", task.getName());
            }
        }

        log.info("Health Summary — Healthy: {}, Unhealthy: {}, Crashed: {}, Total: {}",
                healthy, unhealthy, crashed, activeTasks.size());

        // ✅ Alert if too many scrapers are down
        if (crashed > 0 || unhealthy > activeTasks.size() / 2) {
            log.error("❌❌❌ CRITICAL: System health degraded! Crashed: {}, Unhealthy: {}",
                    crashed, unhealthy);
        }
    }

    private String getTaskStatus(ScraperTask task) {
        if (task.isDone()) {
            return task.isCancelled() ? "CANCELLED" : "CRASHED";
        }
        return task.isHealthy() ? "HEALTHY" : "UNHEALTHY";
    }

    private void attemptRestart(ScraperTask task) {
        log.info("🔄 Attempting to restart {} scraper (attempt {}/{})",
                task.getName(), task.getRestartCount() + 1, MAX_RESTART_ATTEMPTS);

        // Schedule restart after delay
        CompletableFuture.delayedExecutor(RESTART_DELAY_SEC, TimeUnit.SECONDS)
                .execute(() -> {
                    try {
                        // Remove old task
                        activeTasks.remove(task);

                        // Start new task
                        Future<?> newFuture = orchestratorExecutor.submit(() -> {
                            try {
                                log.info("🚀 Restarting {} scraper...", task.getName());
                                task.getFetcher().run();
                            } catch (Exception e) {
                                log.error("{} scraper crashed on restart: {}",
                                        task.getName(), e.getMessage(), e);
                                throw e;
                            }
                        });

                        // Create new task with incremented restart count
                        ScraperTask newTask = new ScraperTask(
                                task.getName(),
                                task.getFetcher(),
                                task.isEnabled(),
                                newFuture
                        );
                        newTask.restartCount.set(task.getRestartCount() + 1);
                        activeTasks.add(newTask);

                        log.info("✅ {} scraper restarted successfully", task.getName());
                    } catch (Exception e) {
                        log.error("❌ Failed to restart {} scraper: {}",
                                task.getName(), e.getMessage(), e);
                    }
                });
    }

    // ==================== GRACEFUL SHUTDOWN ====================
    @PreDestroy
    public void shutdown() {
        if (!isRunning.compareAndSet(true, false)) {
            log.info("Orchestrator already stopped");
            return;
        }

        log.info("═══════════════════════════════════════════════════════════");
        log.info("   PLAYER - SHUTTING DOWN");
        log.info("═══════════════════════════════════════════════════════════");

        // Stop health monitoring
        log.info("Stopping health monitor...");
        healthMonitor.shutdownNow();

        // Shutdown all scrapers gracefully
        log.info("Shutting down {} active scrapers...", activeTasks.size());
        for (ScraperTask task : activeTasks) {
            try {
                log.info("Stopping {} scraper...", task.getName());

                // Try to call shutdown method if it exists
                shutdownScraperGracefully(task);

                // Cancel the future
                if (task.getFuture() != null && !task.isDone()) {
                    task.getFuture().cancel(true);
                }

                log.info("✅ {} scraper stopped", task.getName());
            } catch (Exception e) {
                log.error("Error stopping {} scraper: {}", task.getName(), e.getMessage());
            }
        }

        // Shutdown executor
        log.info("Shutting down orchestrator executor...");
        orchestratorExecutor.shutdown();

        try {
            if (!orchestratorExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Orchestrator executor did not terminate in time, forcing shutdown");
                orchestratorExecutor.shutdownNow();

                if (!orchestratorExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.error("Orchestrator executor did not terminate after force shutdown");
                }
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for orchestrator shutdown");
            orchestratorExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        activeTasks.clear();

        log.info("═══════════════════════════════════════════════════════════");
        log.info("   PLAYER - SHUTDOWN COMPLETE");
        log.info("═══════════════════════════════════════════════════════════");
    }

    private void shutdownScraperGracefully(ScraperTask task) {
        try {
            // Use reflection to call shutdown method if it exists
            Runnable fetcher = task.getFetcher();
            if (fetcher != null) {
                var shutdownMethod = fetcher.getClass().getMethod("shutdown");
                shutdownMethod.invoke(fetcher);
                log.info("Called shutdown() on {} scraper", task.getName());
            }
        } catch (NoSuchMethodException e) {
            log.debug("{} scraper has no shutdown method", task.getName());
        } catch (Exception e) {
            log.warn("Error calling shutdown on {} scraper: {}",
                    task.getName(), e.getMessage());
        }
    }

    // ==================== UTILITY METHODS ====================
    public List<String> getActiveScraperNames() {
        return activeTasks.stream()
                .map(ScraperTask::getName)
                .toList();
    }

    public int getActiveScraperCount() {
        return (int) activeTasks.stream()
                .filter(t -> !t.isDone())
                .count();
    }

    public boolean isScraperRunning(String scraperName) {
        return activeTasks.stream()
                .anyMatch(t -> t.getName().equalsIgnoreCase(scraperName) && !t.isDone());
    }

    public String getHealthStatus() {
        long healthy = activeTasks.stream()
                .filter(t -> !t.isDone() && t.isHealthy())
                .count();

        return String.format("Healthy: %d/%d", healthy, activeTasks.size());
    }
}