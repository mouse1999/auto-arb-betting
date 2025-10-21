package com.mouse.bet.service;

import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.BetLegStatus;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.model.NormalizedEvent;
import com.mouse.bet.model.NormalizedMarket;
import com.mouse.bet.model.NormalizedOutcome;
import com.mouse.bet.repository.BetLegRepository;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * Retry registry + ready queues.
 *
 * - Registry: bookmaker -> (outcomeId -> RetrySpec)
 * - Ready queues: bookmaker -> BlockingQueue<Long> (legId signals)
 *
 * Flow:
 *   addFailedBetLegForRetry() -> register target odds for (bookmaker, outcomeId)
 *   updateFailedBetLeg(event, bookmaker) -> if fresh odds match, update BetLeg & enqueue legId
 * Workers:
 *   takeNextReadyLegId(bookmaker, timeout, unit)  // blocking wait for a signal
 *   or pollNextReadyLegId(bookmaker)              // non-blocking
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BetLegRetryService {

    private final BetLegRepository betLegRepository;

    /** Max attempts to re-check a retry spec before it’s evicted. */
    @Value("${bet.retry.maxAttempts:5}")
    private int defaultMaxAttempts;

    /** Tolerance percent around target odds (0.02 = 2%). */
    @Value("${bet.retry.tolerancePct:0.02}")
    private BigDecimal defaultTolerancePct;

    /** TTL for a retry spec (ISO-8601 duration), default 30 minutes. */
    @Value("${bet.retry.ttl:PT1M}")
    private Duration retryTtl;

    /**
     * If true: require freshOdds >= target*(1 - tol).
     * If false: accept freshOdds within [target*(1 - tol), target*(1 + tol)].
     */
    @Value("${bet.retry.requireAtLeastTarget:true}")
    private boolean requireAtLeastTarget;

    /** Capacity of per-bookmaker ready queues (legId signals). */
    @Value("${bet.retry.queueCapacity:10000}")
    private int queueCapacity;

    /** bookmaker → (outcomeId → retrySpec) */
    private final ConcurrentMap<BookMaker, ConcurrentMap<String, RetrySpec>> registry = new ConcurrentHashMap<>();

    /** bookmaker → ready queue of legIds */
    private final ConcurrentMap<BookMaker, BlockingQueue<Long>> readyQueues = new ConcurrentHashMap<>();

    /**
     * @param betLegId   BetLeg.id (if known)
     * @param arbId      for tracing/logs
     * @param outcomeId  key
     * @param targetOdds desired odds
     * @param eventId    optional
     * @param note       metadata
     * @param attempts   immutable counter
     */
        @Builder
        private record RetrySpec(Long betLegId, String arbId, String outcomeId, BigDecimal targetOdds,
                                 BigDecimal minAcceptable, BigDecimal maxAcceptable, int maxAttempts, Instant createdAt,
                                 BookMaker bookmaker, String eventId, String note, int attempts) {
    }

    /** PUBLIC API: register a failed leg for odds-based retry. */
    public void addFailedBetLegForRetry(BetLeg betLeg, BookMaker bookMaker) {
        Objects.requireNonNull(bookMaker, "bookMaker is required");
        if (betLeg == null || betLeg.getOutcomeId() == null) {
            log.warn("Skipping retry registration: betLeg/outcomeId is null");
            return;
        }

        RetrySpec spec = computeRetrySpec(betLeg, bookMaker);
        registry.computeIfAbsent(bookMaker, b -> new ConcurrentHashMap<>())
                .put(spec.outcomeId(), spec);

        // Ensure queue exists for this bookmaker
        readyQueues.computeIfAbsent(bookMaker, b -> new LinkedBlockingQueue<>(queueCapacity));

        log.info("Retry registered: bm={} arb={} legId={} outcomeId={} target={} tolPct={} range=[{},{}] ttl={} requireAtLeastTarget={}",
                bookMaker, spec.arbId(), spec.betLegId(), spec.outcomeId(),
                spec.targetOdds(), defaultTolerancePct, spec.minAcceptable(), spec.maxAcceptable(),
                retryTtl, requireAtLeastTarget);
    }

    /**
     * PUBLIC API: called when fresh odds arrive for a bookmaker.
     * If odds match target/range for a registered outcomeId:
     *  1) Updates BetLeg in DB (odds & potential payout; FAILED->PENDING)
     *  2) Enqueues legId to the bookmaker’s ready queue (signal to worker)
     */
    @Transactional
    public void updateFailedBetLeg(NormalizedEvent event, BookMaker bookMaker) {
        if (event == null || bookMaker == null) return;

        // strictly operate on the exact bookmaker bucket
        Map<String, RetrySpec> byOutcome = registry.get(bookMaker);
        if (byOutcome == null || byOutcome.isEmpty()) return;

        // TTL cleanup on touch
        byOutcome.entrySet().removeIf(e -> isExpired(e.getValue()));

        BlockingQueue<Long> queue = readyQueues.computeIfAbsent(bookMaker,
                b -> new LinkedBlockingQueue<>(queueCapacity));

        // Scan outcomes in the event
        eventOutcomes(event).forEach(out -> {
            String outcomeId = out.getOutcomeId();
            if (outcomeId == null) return;

            RetrySpec spec = byOutcome.get(outcomeId);
            if (spec == null) return;


            if (spec.bookmaker() == null || !spec.bookmaker().equals(bookMaker)) {
                // Defensive: mismatch; ignore this spec for this call
                return;
            }

            BigDecimal freshOdds = Optional.ofNullable(out.getOdds()).orElse(BigDecimal.ZERO);
            if (!oddsAcceptable(spec, freshOdds)) {
                bumpAttemptsOrEvict(byOutcome, spec);
                return;
            }

            // Persist update (odds + potential payout + status transition)
            Long legId = persistOddsUpdate(spec, freshOdds);
            if (legId == null) {
                // If we can't find/save leg, evict the spec
                byOutcome.remove(outcomeId);
                return;
            }

            // Enqueue signal for worker (only after bookmaker match and successful persist)
            boolean offered = queue.offer(legId);
            if (!offered) {
                log.warn("Ready queue full for {}. Dropping signal for legId={}", bookMaker, legId);
            } else {
                log.info("Enqueued legId={} to ready queue for {}", legId, bookMaker);
            }

            // Remove the retry spec once satisfied
            byOutcome.remove(outcomeId);
        });
    }


    /* ========================== Worker-facing API ========================== */

    /** Non-blocking: returns next legId if present, else null. */
    public Long pollNextReadyLegId(BookMaker bookMaker) {
        BlockingQueue<Long> q = readyQueues.computeIfAbsent(bookMaker,
                b -> new LinkedBlockingQueue<>(queueCapacity));
        return q.poll();
    }

    /** Blocking: waits up to timeout for a legId signal; returns null on timeout. */
    public Long takeNextReadyLegId(BookMaker bookMaker, long timeout, TimeUnit unit) throws InterruptedException {
        BlockingQueue<Long> q = readyQueues.computeIfAbsent(bookMaker,
                b -> new LinkedBlockingQueue<>(queueCapacity));
        return q.poll(timeout, unit);
    }

    /** Queue size (monitoring/metrics). */
    public int pendingSignals(BookMaker bookMaker) {
        BlockingQueue<Long> q = readyQueues.get(bookMaker);
        return q == null ? 0 : q.size();
    }

    /* ============================ Internals ============================ */

    private RetrySpec computeRetrySpec(BetLeg betLeg, BookMaker bookMaker) {
        BigDecimal target = Optional.ofNullable(betLeg.getOdds()).orElse(BigDecimal.ZERO);
        BigDecimal tol = defaultTolerancePct != null ? defaultTolerancePct : BigDecimal.ZERO;
        if (tol.signum() < 0) tol = BigDecimal.ZERO;

        BigDecimal min = target.multiply(BigDecimal.ONE.subtract(tol));
        BigDecimal max = requireAtLeastTarget ? new BigDecimal("9e9") : target.multiply(BigDecimal.ONE.add(tol));

        return RetrySpec.builder()
                .betLegId(betLeg.getId())
                .arbId(betLeg.getArb() != null ? betLeg.getArb().getArbId() : null)
                .outcomeId(betLeg.getOutcomeId())
                .targetOdds(target)
                .minAcceptable(min)
                .maxAcceptable(max)
                .maxAttempts(defaultMaxAttempts)
                .createdAt(Instant.now())
                .bookmaker(bookMaker)
                .eventId(betLeg.getEventId())
                .note("auto-from-failed")
                .attempts(0)
                .build();
    }

    private boolean isExpired(RetrySpec spec) {
        return retryTtl != null && spec.createdAt().plus(retryTtl).isBefore(Instant.now());
    }

    private void bumpAttemptsOrEvict(Map<String, RetrySpec> byOutcome, RetrySpec spec) {
        int next = spec.attempts() + 1;
        if (next >= spec.maxAttempts()) {
            byOutcome.remove(spec.outcomeId());
            log.debug("Retry evicted (max attempts): bm={} outcome={} legId={}",
                    spec.bookmaker(), spec.outcomeId(), spec.betLegId());
        } else {
            RetrySpec updated = RetrySpec.builder()
                    .betLegId(spec.betLegId())
                    .arbId(spec.arbId())
                    .outcomeId(spec.outcomeId())
                    .targetOdds(spec.targetOdds())
                    .minAcceptable(spec.minAcceptable())
                    .maxAcceptable(spec.maxAcceptable())
                    .maxAttempts(spec.maxAttempts())
                    .createdAt(spec.createdAt())
                    .bookmaker(spec.bookmaker())
                    .eventId(spec.eventId())
                    .note(spec.note())
                    .attempts(next)
                    .build();
            byOutcome.put(spec.outcomeId(), updated);
        }
    }

    private boolean oddsAcceptable(RetrySpec spec, BigDecimal fresh) {
        if (fresh == null) return false;
        return fresh.compareTo(spec.minAcceptable()) >= 0
                && fresh.compareTo(spec.maxAcceptable()) <= 0;
    }

    @Transactional
    protected Long persistOddsUpdate(RetrySpec spec, BigDecimal newOdds) {
        Optional<BetLeg> legOpt = spec.betLegId() != null
                ? betLegRepository.findById(spec.betLegId())
                : betLegRepository.findByOutcomeId(spec.outcomeId());

        if (legOpt.isEmpty()) {
            log.warn("Retry match but BetLeg not found: legId={}, outcomeId={}", spec.betLegId(), spec.outcomeId());
            return null;
        }

        BetLeg leg = legOpt.get();
        if (leg.isFinalState()) {
            log.info("Skipping retry update: leg already final. legId={} status={}", leg.getId(), leg.getStatus());
            return null;
        }

        leg.setOdds(newOdds);
        leg.updatePotentialPayout();

        // Move FAILED back to PENDING so worker logic will attempt it again
        if (leg.getStatus() == BetLegStatus.FAILED) {
            leg.setStatus(BetLegStatus.PENDING);
        }

        betLegRepository.save(leg);
        log.info("BetLeg updated for retry: legId={} newOdds={} potential={}", leg.getId(), newOdds, leg.getPotentialPayout());

        return leg.getId();
    }

    private static Stream<NormalizedOutcome> eventOutcomes(NormalizedEvent ev) {
        if (ev == null || ev.getMarkets() == null) return Stream.empty();
        return ev.getMarkets().stream()
                .filter(Objects::nonNull)
                .map(NormalizedMarket::getOutcomes)
                .filter(Objects::nonNull)
                .flatMap(java.util.Collection::stream)
                .filter(Objects::nonNull);
    }
}
