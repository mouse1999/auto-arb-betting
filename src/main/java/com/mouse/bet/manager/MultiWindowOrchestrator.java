//package com.mouse.bet.manager;
//
//import com.mouse.bet.enums.BookMaker;
//import com.mouse.bet.interfaces.BettingWindow;
//import com.mouse.bet.manager.ProfileManager;
//import com.mouse.bet.config.ScraperConfig;
//import com.mouse.bet.service.ArbPollingService;
//import com.mouse.bet.service.BetLegRetryService;
//import com.mouse.bet.window.*;
//import jakarta.annotation.PostConstruct;
//import jakarta.annotation.PreDestroy;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.context.annotation.Lazy;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Component;
//
//import java.time.Duration;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.*;
//import java.util.stream.Collectors;
//
//@Slf4j
//@Component
//@RequiredArgsConstructor
//public class MultiWindowOrchestrator {
//
//    private static final String EMOJI_START = "Starting";
//
//    private static final String EMOJI_SUCCESS = "Success";
//    private static final String EMOJI_WARNING = "Warning";
//    private static final String EMOJI_ERROR = "Error";
//    private static final String EMOJI_SHUTDOWN = "Shutting Down";
//    private static final String EMOJI_MONITOR = "Monitoring";
//
//    private final ProfileManager profileManager;
//    private final ScraperConfig scraperConfig;
//    private final ArbPollingService arbPollingService;
//    private final BetLegRetryService betRetryService;
//    private final WindowSyncManager syncManager;
//    private final ArbOrchestrator arbOrchestrator;
//
//    // Lazy inject windows to avoid circular dependencies
//    private final @Lazy SportyWindow sportyWindow;
////    private final @Lazy MSportWindow msportWindow;
////    private final @Lazy Bet9jaWindow bet9jaWindow;
//
//    private final ExecutorService windowExecutor = Executors.newCachedThreadPool(r -> {
//        Thread t = new Thread(r);
//        t.setName("BetWindow-" + t.getId());
//        t.setDaemon(false);
//        return t;
//    });
//
//    // Track all windows with their bookmaker
//    private final ConcurrentMap<BookMaker, BettingWindow> activeWindows = new ConcurrentHashMap<>();
//    private final ScheduledExecutorService monitorScheduler = Executors.newSingleThreadScheduledExecutor();
//
//    @PostConstruct
//    public void initialize() {
//        log.info("{} {} Initializing Multi-Window Orchestrator (3 windows)", EMOJI_START, EMOJI_SUCCESS);
//
//        // Start all windows on startup
//        startWindow(BookMaker.SPORTY_BET, sportyWindow);
////        startWindow(BookMaker.M_SPORT, msportWindow);
////        startWindow(BookMaker.BET9JA, bet9jaWindow);
//
//        startMonitoringTasks();
//
//        log.info("{} {} All 3 windows started and waiting for arbs", EMOJI_SUCCESS, EMOJI_START);
//    }
//
//    private void startWindow(BookMaker bookmaker, BettingWindow window) {
//        log.info("{} {} Starting {} window...", EMOJI_START, EMOJI_SUCCESS, bookmaker);
//
//        activeWindows.put(bookmaker, window);
//
//        windowExecutor.submit(() -> {
//            try {
//                log.info("{} {} {} thread started | Thread: {}",
//                        EMOJI_SUCCESS, bookmaker, bookmaker, Thread.currentThread().getName());
//                window.run();
//            } catch (Exception e) {
//                log.error("{} {} {} crashed: {}", EMOJI_ERROR, EMOJI_ERROR, bookmaker, e.getMessage(), e);
//                handleWindowCrash(bookmaker, window);
//            } finally {
//                log.warn("{} {} thread terminated", EMOJI_WARNING, bookmaker);
//            }
//        });
//
//        log.info("{} {} {} window started and ready", EMOJI_SUCCESS, EMOJI_SUCCESS, bookmaker);
//    }
//
//    private void handleWindowCrash(BookMaker bookmaker, BettingWindow window) {
//        activeWindows.remove(bookmaker);
//        try { window.shutdown(); } catch (Exception ignored) {}
//
//        // Auto-restart after delay
//        CompletableFuture.delayedExecutor(15, TimeUnit.SECONDS)
//                .execute(() -> {
//                    log.info("{} {} Restarting crashed window: {}", EMOJI_START, EMOJI_WARNING, bookmaker);
//                    startWindow(bookmaker, createNewWindowInstance(bookmaker));
//                });
//    }
//
//    @SuppressWarnings("deprecation")
//    private BettingWindow createNewWindowInstance(BookMaker bookmaker) {
//        return switch (bookmaker) {
//            case SPORTY_BET -> new SportyWindow(profileManager, scraperConfig, arbPollingService,arbOrchestrator, betRetryService, syncManager);
////            case M_SPORT    -> new MSportWindow(profileManager, scraperConfig, arbPollingService, betRetryService, syncManager);
////            case BET9JA     -> new Bet9jaWindow(profileManager, scraperConfig, arbPollingService, betRetryService, syncManager);
//            default -> throw new IllegalArgumentException("Unsupported bookmaker: " + bookmaker);
//        };
//    }
//
//    private void startMonitoringTasks() {
//        monitorScheduler.scheduleAtFixedRate(this::logHealthStatus, 15, 15, TimeUnit.SECONDS);
//        monitorScheduler.scheduleAtFixedRate(this::logStats, 60, 60, TimeUnit.SECONDS);
//    }
//
//    private void logHealthStatus() {
//        long running = activeWindows.values().stream().filter(BettingWindow::isRunning).count();
//        long paused = activeWindows.values().stream().filter(BettingWindow::isPaused).count();
//
//        log.info("{} {} Window Status → Total: {} | Running: {} | Paused: {} | Down: {}",
//                EMOJI_MONITOR, EMOJI_SUCCESS,
//                activeWindows.size(), running, paused, activeWindows.size() - running);
//    }
//
//    @Scheduled(fixedRate = 60000)
//    public void logStats() {
////        var stats = arbPollingService.getStats();
//        int activeSync = syncManager.getActiveCoordinationCount();
//        int registered = syncManager.getRegisteredWindowCount();
//
////        log.info("{} {} Stats → Windows: {} | Registered: {} | Queue: {} | Processing: {} | Syncs: {}",
////                EMOJI_MONITOR, EMOJI_SUCCESS,
////                activeWindows.size(), registered,
////                stats.queuedCount(), stats.processingCount(), activeSync);
//    }
//
//    // === Control Methods ===
//
//    public void pauseAll() {
//        log.warn("{} {} Pausing all windows", EMOJI_WARNING, EMOJI_SHUTDOWN);
//        activeWindows.values().forEach(BettingWindow::pause);
//    }
//
//    public void resumeAll() {
//        log.info("{} {} Resuming all windows", EMOJI_SUCCESS, EMOJI_START);
//        activeWindows.values().forEach(BettingWindow::resume);
//    }
//
//    public void restartAll() {
//        log.warn("{} {} Restarting all windows", EMOJI_WARNING, EMOJI_SHUTDOWN);
//        activeWindows.forEach((bm, win) -> {
//            win.stop();
//            CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS).execute(() ->
//                    startWindow(bm, createNewWindowInstance(bm)));
//        });
//    }
//
//    @PreDestroy
//    public void shutdown() {
//        log.warn("{} {} Shutting down orchestrator...", EMOJI_SHUTDOWN, EMOJI_SHUTDOWN);
//
//        activeWindows.values().forEach(w -> {
//            try {
//                w.stop();
//            } catch (Exception ignored) {}
//        });
//
//        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
//
//        activeWindows.values().forEach(w -> {
//            try { w.shutdown(); } catch (Exception ignored) {}
//        });
//
////        arbPollingService.releaseAllArbs();
//        syncManager.clearAll();
//
//        windowExecutor.shutdownNow();
//        monitorScheduler.shutdownNow();
//
//        log.info("{} {} Orchestrator shutdown complete", EMOJI_SUCCESS, EMOJI_SHUTDOWN);
//    }
//
//    public Map<BookMaker, Boolean> getWindowStatus() {
//        return Map.copyOf(
//                activeWindows.entrySet().stream()
//                        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().isRunning()))
//        );
//    }
//
//    public int getActiveCount() {
//        return (int) activeWindows.values().stream().filter(BettingWindow::isRunning).count();
//    }
//}