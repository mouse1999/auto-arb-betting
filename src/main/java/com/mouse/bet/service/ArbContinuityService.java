package com.mouse.bet.service;

import com.mouse.bet.entity.Arb;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Service to manage arb continuity and detect gaps
 */
@Slf4j
@Component
public class ArbContinuityService {

    private static final String EMOJI_BREAK = "💔";
    private static final String EMOJI_CONTINUE = "🔗";
    private static final String EMOJI_NEW_SESSION = "🆕";
    private static final String EMOJI_WARNING = "⚠️";
    private static final String EMOJI_CLOCK = "⏰";

    /**
     * Check and handle continuity for an existing arb
     * Returns true if continuity is maintained, false if broken
     */
    public boolean checkAndHandleContinuity(Arb existing, Instant now) {
        // If this is the first update (no lastContinuousUpdateAt), initialize continuity
        if (existing.getLastContinuousUpdateAt() == null) {
            log.debug("{} {} First update detected, initializing continuity | ArbId: {}",
                    EMOJI_NEW_SESSION, EMOJI_CLOCK, existing.getArbId());
            initializeContinuity(existing, now);
            return true;
        }

        if (existing.hasContinuityBreak(now)) {
            handleContinuityBreak(existing, now);
            return false;
        }

        existing.updateContinuity(now);
        log.debug("{} {} Continuity maintained | ArbId: {} | Session: {}s",
                EMOJI_CONTINUE, EMOJI_CLOCK,
                existing.getArbId(),
                existing.getCurrentSessionDurationSeconds());
        return true;
    }

    /**
     * Handle continuity break - reset metrics and start new session
     */
    private void handleContinuityBreak(Arb arb, Instant now) {
        Instant lastUpdate = arb.getLastContinuousUpdateAt();
        long gapSeconds = now.getEpochSecond() - lastUpdate.getEpochSecond();

        log.warn("{} {} CONTINUITY BREAK DETECTED | ArbId: {} | Gap: {}s | LastUpdate: {} | Now: {} | BreakCount: {} -> {}",
                EMOJI_BREAK, EMOJI_WARNING,
                arb.getArbId(),
                gapSeconds,
                lastUpdate,
                now,
                arb.getContinuityBreakCount(),
                arb.getContinuityBreakCount() + 1);

        arb.breakContinuity(now);

        log.info("{} {} New session started | ArbId: {} | StartedAt: {}",
                EMOJI_NEW_SESSION, EMOJI_CLOCK,
                arb.getArbId(),
                arb.getCurrentSessionStartedAt());
    }

    /**
     * Initialize continuity tracking for a new arb
     */
    public void initializeContinuity(Arb arb, Instant now) {
        arb.setCurrentSessionStartedAt(now);
        arb.setLastContinuousUpdateAt(now);
        arb.setContinuityBreakCount(0);

        log.info("{} {} Continuity initialized | ArbId: {} | StartedAt: {}",
                EMOJI_NEW_SESSION, EMOJI_CONTINUE,
                arb.getArbId(),
                now);
    }

    /**
     * Get continuity status report
     */
    public String getContinuityReport(Arb arb) {
        return String.format(
                "Continuity Report | Session: %ds | Breaks: %d | LastUpdate: %s",
                arb.getCurrentSessionDurationSeconds(),
                arb.getContinuityBreakCount(),
                arb.getLastContinuousUpdateAt()
        );
    }
}