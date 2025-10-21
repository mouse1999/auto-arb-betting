package com.mouse.bet.service;

import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.BetLegStatus;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.model.NormalizedEvent;
import com.mouse.bet.model.NormalizedMarket;
import com.mouse.bet.model.NormalizedOutcome;
import com.mouse.bet.repository.BetLegRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for BetLegRetryService public API.
 */
@ExtendWith(MockitoExtension.class)
public class BetLegRetryServiceTest {

    @Mock
    private BetLegRepository betLegRepository;

    private BetLegRetryService service;

    // Common test values
    private static final BookMaker BM_SPORTY = BookMaker.SPORTY_BET;
    private static final BookMaker BM_BET9JA = BookMaker.BET9JA;
    private static final String OUTCOME_ID = "O-123";
    private static final String ARB_ID = "ARB-1";
    private static final String EVENT_ID = "EVT-1";

    @BeforeEach
    void setUp() {
        service = new BetLegRetryService(betLegRepository);

        // Make config deterministic for tests
        ReflectionTestUtils.setField(service, "defaultMaxAttempts", 3);        // keep small for eviction tests
        ReflectionTestUtils.setField(service, "defaultTolerancePct", new BigDecimal("0.02")); // 2%
        ReflectionTestUtils.setField(service, "retryTtl", Duration.ofSeconds(25));
        ReflectionTestUtils.setField(service, "requireAtLeastTarget", true);   // accept >= target*(1 - tol)
        ReflectionTestUtils.setField(service, "queueCapacity", 100);
    }

    /* -------------------------- Helpers -------------------------- */

    private BetLeg failedLeg(long id, BigDecimal targetOdds) {
        BetLeg bl = new BetLeg();
        bl.setId(id);
        bl.setOutcomeId(OUTCOME_ID);
        bl.setOdds(targetOdds);
        bl.setStatus(BetLegStatus.FAILED);
        bl.setEventId(EVENT_ID);

        // minimal Arb stub for logging (arbId used in service logs)
        var arb = new com.mouse.bet.entity.Arb();
        arb.setArbId(ARB_ID);
        bl.setArb(arb);

        return bl;
    }

    private NormalizedEvent eventWithOutcomes(String outcomeId, BigDecimal odds) {
        NormalizedOutcome o = new NormalizedOutcome();
        o.setOutcomeId(outcomeId);
        o.setOdds(odds);

        NormalizedMarket m = new NormalizedMarket();
        m.setOutcomes(List.of(o));

        NormalizedEvent e = new NormalizedEvent();
        e.setMarkets(List.of(m));
        return e;
    }

    /* ========================= TESTS ========================= */

    @Nested
    @DisplayName("addFailedBetLegForRetry")
    class AddFailedLeg {

        @Test
        @DisplayName("addFailedBetLegForRetry_nullLeg_noop")
        void addFailedBetLegForRetry_nullLeg_noop() {
            service.addFailedBetLegForRetry(null, BM_SPORTY);
            // No queue created, signals 0
            assertThat(service.pendingSignals(BM_SPORTY)).isEqualTo(0);
            verifyNoInteractions(betLegRepository);
        }

        @Test
        @DisplayName("addFailedBetLegForRetry_noOutcomeId_noop")
        void addFailedBetLegForRetry_noOutcomeId_noop() {
            BetLeg bl = failedLeg(10L, new BigDecimal("2.00"));
            bl.setOutcomeId(null);

            service.addFailedBetLegForRetry(bl, BM_SPORTY);
            assertThat(service.pendingSignals(BM_SPORTY)).isEqualTo(0);
            verifyNoInteractions(betLegRepository);
        }

        @Test
        @DisplayName("addFailedBetLegForRetry_valid_registersSpec_and_ensuresQueue")
        void addFailedBetLegForRetry_valid_registersSpec_and_ensuresQueue() {
            BetLeg bl = failedLeg(11L, new BigDecimal("2.00"));

            service.addFailedBetLegForRetry(bl, BM_SPORTY);

            // Queue exists but empty
            assertThat(service.pendingSignals(BM_SPORTY)).isEqualTo(0);
            verifyNoInteractions(betLegRepository);
        }
    }

    @Nested
    @DisplayName("updateFailedBetLeg")
    class UpdateFailedLeg {

        @Test
        @DisplayName("updateFailedBetLeg_nullEvent_noop")
        void updateFailedBetLeg_nullEvent_noop() {
            service.updateFailedBetLeg(null, BM_SPORTY);
            verifyNoInteractions(betLegRepository);
        }

        @Test
        @DisplayName("updateFailedBetLeg_nullBookmaker_noop")
        void updateFailedBetLeg_nullBookmaker_noop() {
            service.updateFailedBetLeg(eventWithOutcomes(OUTCOME_ID, new BigDecimal("2.10")), null);
            verifyNoInteractions(betLegRepository);
        }

        @Test
        @DisplayName("updateFailedBetLeg_mismatchedBookmaker_bucket_onlyProcessesExactMatch")
        void updateFailedBetLeg_mismatchedBookmaker_bucket_onlyProcessesExactMatch() {
            // Register under SPORTY
            BetLeg bl = failedLeg(20L, new BigDecimal("2.00"));
            service.addFailedBetLegForRetry(bl, BM_SPORTY);

            // Incoming event processed under BET9JA → should do nothing
            NormalizedEvent ev = eventWithOutcomes(OUTCOME_ID, new BigDecimal("2.10"));
            service.updateFailedBetLeg(ev, BM_BET9JA);

            assertThat(service.pendingSignals(BM_SPORTY)).isEqualTo(0);
            assertThat(service.pendingSignals(BM_BET9JA)).isEqualTo(0);
            verifyNoInteractions(betLegRepository);
        }

        @Test
        @DisplayName("updateFailedBetLeg_unregisteredOutcome_noop")
        void updateFailedBetLeg_unregisteredOutcome_noop() {
            // Registered for OUTCOME_ID, but event uses OTHER_ID → noop
            BetLeg bl = failedLeg(21L, new BigDecimal("2.00"));
            service.addFailedBetLegForRetry(bl, BM_SPORTY);

            NormalizedEvent ev = eventWithOutcomes("OTHER_ID", new BigDecimal("2.10"));
            service.updateFailedBetLeg(ev, BM_SPORTY);

            assertThat(service.pendingSignals(BM_SPORTY)).isEqualTo(0);
            verifyNoInteractions(betLegRepository);
        }

        @Test
        @DisplayName("updateFailedBetLeg_oddsNotAcceptable_doesNotEnqueue_or_Save")
        void updateFailedBetLeg_oddsNotAcceptable_doesNotEnqueue_or_Save() {
            // target=2.00, requireAtLeastTarget=true, tol=2% -> min acceptable ≈1.96
            // Give fresh=1.90 -> not acceptable
            BetLeg bl = failedLeg(22L, new BigDecimal("2.00"));
            service.addFailedBetLegForRetry(bl, BM_SPORTY);

            NormalizedEvent ev = eventWithOutcomes(OUTCOME_ID, new BigDecimal("1.90"));
            service.updateFailedBetLeg(ev, BM_SPORTY);

            assertThat(service.pendingSignals(BM_SPORTY)).isEqualTo(0);
            verifyNoInteractions(betLegRepository);
        }

        @Test
        @DisplayName("updateFailedBetLeg_repoFindEmpty_evictSpec_noEnqueue")
        void updateFailedBetLeg_repoFindEmpty_evictSpec_noEnqueue() {
            BetLeg bl = failedLeg(23L, new BigDecimal("2.00"));
            service.addFailedBetLegForRetry(bl, BM_SPORTY);

            when(betLegRepository.findById(23L)).thenReturn(Optional.empty());

            NormalizedEvent ev = eventWithOutcomes(OUTCOME_ID, new BigDecimal("2.10"));
            service.updateFailedBetLeg(ev, BM_SPORTY);

            assertThat(service.pendingSignals(BM_SPORTY)).isEqualTo(0);
            verify(betLegRepository, times(1)).findById(23L);
            verify(betLegRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateFailedBetLeg_legFinalState_noSave_noEnqueue")
        void updateFailedBetLeg_legFinalState_noSave_noEnqueue() {
            BetLeg bl = failedLeg(24L, new BigDecimal("2.00"));
            service.addFailedBetLegForRetry(bl, BM_SPORTY);

            BetLeg finalLeg = failedLeg(24L, new BigDecimal("2.00"));
            finalLeg.setStatus(BetLegStatus.WON);

            when(betLegRepository.findById(24L)).thenReturn(Optional.of(finalLeg));

            NormalizedEvent ev = eventWithOutcomes(OUTCOME_ID, new BigDecimal("2.10"));
            service.updateFailedBetLeg(ev, BM_SPORTY);

            assertThat(service.pendingSignals(BM_SPORTY)).isEqualTo(0);
            verify(betLegRepository, times(1)).findById(24L);
            verify(betLegRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateFailedBetLeg_successfulSave_enqueueSignal_and_flipFailedToPending")
        void updateFailedBetLeg_successfulSave_enqueueSignal_and_flipFailedToPending() {
            BetLeg bl = failedLeg(25L, new BigDecimal("2.00"));
            service.addFailedBetLegForRetry(bl, BM_SPORTY);

            // repo returns the leg in FAILED state; save should update to PENDING and odds updated
            when(betLegRepository.findById(25L)).thenReturn(Optional.of(failedLeg(25L, new BigDecimal("2.00"))));
            when(betLegRepository.save(any(BetLeg.class))).thenAnswer(inv -> inv.getArgument(0));

            NormalizedEvent ev = eventWithOutcomes(OUTCOME_ID, new BigDecimal("2.05")); // acceptable
            service.updateFailedBetLeg(ev, BM_SPORTY);

            // Queue should have a signal
            Long signaled = service.pollNextReadyLegId(BM_SPORTY);
            assertThat(signaled).isEqualTo(25L);

            // Verify saved leg fields
            ArgumentCaptor<BetLeg> saved = ArgumentCaptor.forClass(BetLeg.class);
            verify(betLegRepository, times(1)).save(saved.capture());
            BetLeg savedLeg = saved.getValue();

            assertThat(savedLeg.getId()).isEqualTo(25L);
            assertThat(savedLeg.getOdds()).isEqualByComparingTo("2.05");
            assertThat(savedLeg.getStatus()).isEqualTo(BetLegStatus.PENDING);
            assertThat(savedLeg.getPotentialPayout()).isNotNull(); // updated via updatePotentialPayout()
        }

        @Test
        @DisplayName("updateFailedBetLeg_TTLExpired_specIsPruned_thenNoAction")
        void updateFailedBetLeg_TTLExpired_specIsPruned_thenNoAction() throws InterruptedException {
            // Set TTL effectively to zero so created spec expires before processing
            ReflectionTestUtils.setField(service, "retryTtl", Duration.ZERO);

            BetLeg bl = failedLeg(26L, new BigDecimal("2.00"));
            service.addFailedBetLegForRetry(bl, BM_SPORTY);

            // Small sleep to ensure createdAt < now for isExpired() comparison edge
            Thread.sleep(5);

            NormalizedEvent ev = eventWithOutcomes(OUTCOME_ID, new BigDecimal("2.50"));
            service.updateFailedBetLeg(ev, BM_SPORTY);

            assertThat(service.pendingSignals(BM_SPORTY)).isEqualTo(0);
            verifyNoInteractions(betLegRepository);
        }
    }

    @Nested
    @DisplayName("Queues API")
    class QueuesApi {

        @Test
        @DisplayName("pollNextReadyLegId_emptyQueue_returnsNull")
        void poll_empty_returnsNull() {
            assertThat(service.pollNextReadyLegId(BM_SPORTY)).isNull();
        }

        @Test
        @DisplayName("takeNextReadyLegId_timesOut_returnsNull")
        @Timeout(2) // seconds
        void take_timesOut_returnsNull() throws Exception {
            Long id = service.takeNextReadyLegId(BM_SPORTY, 200, TimeUnit.MILLISECONDS);
            assertThat(id).isNull();
        }

        @Test
        @DisplayName("pendingSignals_reflectsEnqueuedSignals")
        void pendingSignals_reflectsEnqueuedSignals() {
            // Arrange: make a successful update that enqueues
            BetLeg bl = failedLeg(30L, new BigDecimal("2.00"));
            service.addFailedBetLegForRetry(bl, BM_SPORTY);

            when(betLegRepository.findById(30L)).thenReturn(Optional.of(failedLeg(30L, new BigDecimal("2.00"))));
            when(betLegRepository.save(any(BetLeg.class))).thenAnswer(inv -> inv.getArgument(0));

            service.updateFailedBetLeg(eventWithOutcomes(OUTCOME_ID, new BigDecimal("2.10")), BM_SPORTY);

            assertThat(service.pendingSignals(BM_SPORTY)).isEqualTo(1);

            // consuming the signal reduces size
            assertThat(service.pollNextReadyLegId(BM_SPORTY)).isEqualTo(30L);
            assertThat(service.pendingSignals(BM_SPORTY)).isEqualTo(0);
        }
    }
}
