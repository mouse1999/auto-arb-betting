package com.mouse.bet.manager;

import com.microsoft.playwright.Page;
import com.mouse.bet.exception.PageHealthException;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Monitors page health and detects issues like crashes, unresponsiveness, or errors
 */
@Slf4j
public class PageHealthMonitor {

    private static final String EMOJI_HEALTH = "💚";
    private static final String EMOJI_WARNING = "⚠️";
    private static final String EMOJI_ERROR = "❌";
    private static final String EMOJI_CHECK = "🔍";

    private final Page page;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean isHealthy = new AtomicBoolean(true);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final long CHECK_INTERVAL_SECONDS = 10;
    private static final int HEALTH_CHECK_TIMEOUT_MS = 5000;

    public PageHealthMonitor(Page page) {
        this.page = page;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PageHealthMonitor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start periodic health checks
     */
    public void start() {
        log.info("{} {} Starting page health monitor", EMOJI_HEALTH, EMOJI_CHECK);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                performHealthCheck();
            } catch (Exception e) {
                log.error("{} {} Error in scheduled health check: {}",
                        EMOJI_ERROR, EMOJI_HEALTH, e.getMessage());
            }
        }, CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stop health monitoring
     */
    public void stop() {
        log.info("Stopping page health monitor");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Perform immediate health check
     * @throws PageHealthException if page is unhealthy
     */
    public void checkHealth() throws PageHealthException {
        if (!isHealthy.get()) {
            throw new PageHealthException("Page is marked as unhealthy");
        }

        try {
            performHealthCheck();
        } catch (Exception e) {
            throw new PageHealthException("Health check failed: " + e.getMessage());
        }
    }

    /**
     * Perform actual health check operations
     */
    private void performHealthCheck() {
        try {
            // Check if page is closed
            if (page.isClosed()) {
                log.error("{} {} Page is closed", EMOJI_ERROR, EMOJI_HEALTH);
                markUnhealthy();
                return;
            }

            // Check for browser console errors
            checkConsoleErrors();

            // Check if page is responsive
            boolean responsive = checkResponsiveness();
            if (!responsive) {
                log.warn("{} {} Page not responsive", EMOJI_WARNING, EMOJI_HEALTH);
                incrementFailureCount();
                return;
            }

            // Check for specific error elements on page
            checkForErrorElements();

            // Check for CAPTCHA
            checkForCaptcha();

            // All checks passed
            resetFailureCount();
            isHealthy.set(true);

            log.debug("{} {} Health check passed", EMOJI_HEALTH, EMOJI_CHECK);

        } catch (Exception e) {
            log.error("{} {} Health check exception: {}",
                    EMOJI_ERROR, EMOJI_HEALTH, e.getMessage());
            incrementFailureCount();
        }
    }

    /**
     * Check if page is responsive
     */
    private boolean checkResponsiveness() {

        try {
            // Set a custom default timeout for this evaluate call (affects all actions on this page until reset)
            page.setDefaultTimeout(HEALTH_CHECK_TIMEOUT_MS);

            // Run the JS expression - no options needed!
            Object result = page.evaluate("() => document.readyState");

            if (result == null) {
                log.warn("{} {} Responsiveness check failed: null result from evaluate", EMOJI_WARNING, EMOJI_HEALTH);
                return false;
            }

            String readyState;
            try {
                readyState = (String) result; // JS strings return as String in Java
            } catch (ClassCastException e) {
                log.warn("{} {} Unexpected result type from evaluate: {}", EMOJI_WARNING, EMOJI_HEALTH,
                        result.getClass().getSimpleName());
                return false;
            }

            boolean isResponsive = "complete".equals(readyState);
            if (isResponsive) {
                log.debug("{} Page responsive (readyState: {})", EMOJI_HEALTH,  readyState);
            } else {
                log.debug("{} {} Page not fully loaded yet (readyState: {})", EMOJI_WARNING, EMOJI_HEALTH, readyState);
            }

            return isResponsive;
        } catch (Exception e) {
            // Reset timeout if needed, but optional
            page.setDefaultTimeout(30_000L); // Back to default
            log.warn("{} {} Responsiveness check failed: {}", EMOJI_WARNING, EMOJI_HEALTH, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Check for console errors
     */
    private void checkConsoleErrors() {
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) {
                log.warn("{} {} Console error detected: {}",
                        EMOJI_WARNING, EMOJI_ERROR, msg.text());
            }
        });
    }

    /**
     * Check for error elements on page
     */
    private void checkForErrorElements() {
        try {
            // Common error selectors
            String errorSelector = String.join(", ",
                    "[class*='error']",
                    "[class*='alert-danger']",
                    "//div[contains(text(), 'Error')]",
                    "//div[contains(text(), 'Something went wrong')]",
                    "//div[contains(text(), 'unavailable')]"
            );

            if (page.locator(errorSelector).count() > 0) {
                String errorText = page.locator(errorSelector).first().textContent();
                log.warn("{} {} Error element detected: {}",
                        EMOJI_WARNING, EMOJI_ERROR, errorText);
                incrementFailureCount();
            }

        } catch (Exception e) {
            log.debug("Error element check failed: {}", e.getMessage());
        }
    }

    /**
     * Check for CAPTCHA
     */
    private void checkForCaptcha() {
        try {
            String captchaSelector = String.join(", ",
                    "iframe[src*='recaptcha']",
                    "iframe[src*='captcha']",
                    "[class*='captcha']",
                    "//div[contains(text(), 'CAPTCHA')]",
                    "//div[contains(text(), 'verify you are human')]"
            );

            if (page.locator(captchaSelector).count() > 0) {
                log.error("{} {} CAPTCHA detected on page", EMOJI_ERROR, EMOJI_WARNING);
                markUnhealthy();
                throw new PageHealthException("CAPTCHA detected");
            }

        } catch (PageHealthException e) {
            throw e;
        } catch (Exception e) {
            log.debug("CAPTCHA check failed: {}", e.getMessage());
        }
    }

    /**
     * Check for session expiry
     */
    public boolean isSessionExpired() {
        try {
            String loginSelector = String.join(", ",
                    "button:has-text('Login')",
                    "button:has-text('Sign in')",
                    "a[href*='login']",
                    "//div[contains(text(), 'session expired')]"
            );

            return page.locator(loginSelector).count() > 0;

        } catch (Exception e) {
            log.warn("Session expiry check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check network connectivity
     */
    public boolean hasNetworkIssue() {
        try {
            String networkErrorSelector = String.join(", ",
                    "//div[contains(text(), 'No internet')]",
                    "//div[contains(text(), 'Connection lost')]",
                    "//div[contains(text(), 'Network error')]",
                    "[class*='offline']"
            );

            return page.locator(networkErrorSelector).count() > 0;

        } catch (Exception e) {
            log.warn("Network check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Increment failure count and mark unhealthy if threshold exceeded
     */
    private void incrementFailureCount() {
        int failures = consecutiveFailures.incrementAndGet();
        log.warn("{} {} Consecutive failures: {}/{}",
                EMOJI_WARNING, EMOJI_HEALTH, failures, MAX_CONSECUTIVE_FAILURES);

        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            markUnhealthy();
        }
    }

    /**
     * Reset failure count
     */
    private void resetFailureCount() {
        int previous = consecutiveFailures.getAndSet(0);
        if (previous > 0) {
            log.info("{} {} Failure count reset (was: {})",
                    EMOJI_HEALTH, EMOJI_CHECK, previous);
        }
    }

    /**
     * Mark page as unhealthy
     */
    private void markUnhealthy() {
        boolean wasHealthy = isHealthy.getAndSet(false);
        if (wasHealthy) {
            log.error("{} {} Page marked as UNHEALTHY", EMOJI_ERROR, EMOJI_HEALTH);
        }
    }

    /**
     * Check if page is currently healthy
     */
    public boolean isHealthy() {
        return isHealthy.get();
    }

    /**
     * Get consecutive failure count
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * Force mark as healthy (use with caution)
     */
    public void forceHealthy() {
        log.warn("{} {} Forcing page to healthy state", EMOJI_WARNING, EMOJI_HEALTH);
        isHealthy.set(true);
        consecutiveFailures.set(0);
    }
}