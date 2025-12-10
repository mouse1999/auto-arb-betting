package com.mouse.bet.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@Data
@Configuration
public class ScraperConfig {
    private final List<String> BROWSER_FlAGS= Arrays.asList(
            // === Core Stealth Flags ===
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--disable-blink-features=AutomationControlled",
            "--disable-infobars",
            "--disable-extensions",
            "--disable-default-apps",
            "--disable-popup-blocking",
            "--disable-notifications",
            "--mute-audio",
            "--no-first-run",
            "--lang=en-US",
            "--disable-background-timer-throttling",
            "--disable-backgrounding-occluded-windows",
            "--disable-renderer-backgrounding"



//            "--disable-blink-features=AutomationControlled",
//            "--disable-features=UserAgentClientHint",
//            "--no-sandbox",
//            "--disable-dev-shm-usage",
//            "--use-gl=swiftshader",
//            "--enable-webgl",
//            "--disable-web-security",
//            "--disable-extensions",
//            "--disable-default-apps",
//            "--mute-audio",
//            "--no-first-run",
//            "--no-default-browser-check"
    );

    @Value("${scraper.sportybet.enabled:true}")
    private boolean sportyBetEnabled;

    @Value("${scraper.bet9ja.enabled:true}")
    private boolean bet9jaEnabled;

    @Value("${scraper.msport.enabled:false}")
    private boolean betKingEnabled;




    // ==================== PERFORMANCE TUNING ====================

    @Value("${scraper.thread.pool.size:100}")
    private int threadPoolSize;

    @Value("${scraper.connection.pool.size:200}")
    private int connectionPoolSize;

    @Value("${scraper.request.timeout.ms:5000}")
    private int requestTimeoutMs;

    @Value("${scraper.health.check.interval.sec:30}")
    private int healthCheckIntervalSec;

    @Value("${scraper.profile.rotation.interval.sec:300}")
    private int profileRotationIntervalSec;

    // ==================== RATE LIMITING ====================

    @Value("${scraper.rate.limit.requests.per.minute:60}")
    private int rateLimitRequestsPerMinute;

    @Value("${scraper.rate.limit.threshold:5}")
    private int rateLimitThreshold;

    // ==================== RETRY CONFIGURATION ====================

    @Value("${scraper.retry.max.attempts:3}")
    private int maxRetryAttempts;

    @Value("${scraper.retry.delay.ms:1000}")
    private int retryDelayMs;
}
