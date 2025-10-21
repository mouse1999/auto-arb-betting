package com.mouse.bet.service;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.enums.BetLegStatus;
import com.mouse.bet.enums.SportEnum;
import com.mouse.bet.enums.Status;
import com.mouse.bet.repository.ArbRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArbServiceTest {

    @Mock
    ArbRepository arbRepository;

    @InjectMocks
    ArbService service;

    // --------------- saveArb ----------------

    @Test
    void saveArb_newArb_withBothLegsAndOdds_updatesOddsHistoryAndSaves() {
        Arb incoming = newArb("ARB-1");
        incoming.setProfitPercentage(bd("6.0"));
        BetLeg a = leg(true, bd("2.00"), bd("10.00"));
        BetLeg b = leg(false, bd("3.50"), bd("10.00"));
        incoming.getLegs().addAll(List.of(a, b));

        when(arbRepository.findById("ARB-1")).thenReturn(Optional.empty());
        when(arbRepository.save(any(Arb.class))).thenAnswer(inv -> inv.getArgument(0));

        Arb spyIncoming = spy(incoming);
        service.saveArb(spyIncoming);

        verify(spyIncoming, atLeastOnce()).markSeen(any());
        verify(spyIncoming).updateOdds(bd("2.00"), bd("3.50"), "CREATED");
        verify(spyIncoming, never()).captureSnapshot("CREATED_NO_ODDS");
        verify(arbRepository).save(spyIncoming);

        assertThat(spyIncoming.getLegA().get().getPotentialPayout()).isEqualByComparingTo(bd("20.00"));
        assertThat(spyIncoming.getLegB().get().getPotentialPayout()).isEqualByComparingTo(bd("35.00"));
    }

    @Test
    void saveArb_newArb_bothOddsNull_capturesSnapshotAndSaves() {
        Arb incoming = newArb("ARB-2");
        BetLeg a = leg(true, null, bd("10.00"));
        BetLeg b = leg(false, null, bd("10.00"));
        incoming.getLegs().addAll(List.of(a, b));

        when(arbRepository.findById("ARB-2")).thenReturn(Optional.empty());
        when(arbRepository.save(any(Arb.class))).thenAnswer(inv -> inv.getArgument(0));

        Arb spyIncoming = spy(incoming);
        service.saveArb(spyIncoming);

        verify(spyIncoming).captureSnapshot("CREATED_NO_ODDS");
        verify(spyIncoming, never()).updateOdds(any(), any(), anyString());
        verify(arbRepository).save(spyIncoming);
    }

    @Test
    void saveArb_existingArb_oddsChanged_updatesOddsHistoryWithUpsertReason() {
        Arb existing = newArb("ARB-3");
        existing.setLegA(leg(true, bd("2.00"), bd("10")));
        existing.setLegB(leg(false, bd("3.00"), bd("10")));
        Arb spyExisting = spy(existing);

        Arb incoming = newArb("ARB-3");
        incoming.getLegs().addAll(List.of(leg(true, bd("2.10"), bd("10")), leg(false, bd("3.00"), bd("10"))));
        incoming.setProfitPercentage(bd("7.5"));

        when(arbRepository.findById("ARB-3")).thenReturn(Optional.of(spyExisting));
        when(arbRepository.save(any(Arb.class))).thenAnswer(inv -> inv.getArgument(0));

        service.saveArb(incoming);

        verify(spyExisting, atLeastOnce()).markSeen(any());
        verify(spyExisting).updateOdds(bd("2.10"), bd("3.00"), "SERVICE_UPSERT");
        verify(spyExisting, never()).captureSnapshot("UPSERT_FALLBACK_SNAPSHOT");
        verify(arbRepository).save(spyExisting);
    }

    @Test
    void saveArb_existingArb_noOddsChange_capturesNoChangeSnapshot() {
        Arb existing = newArb("ARB-4");
        existing.setLegA(leg(true, bd("2.00"), bd("10")));
        existing.setLegB(leg(false, bd("3.00"), bd("10")));
        Arb spyExisting = spy(existing);

        Arb incoming = newArb("ARB-4");
        incoming.getLegs().addAll(List.of(leg(true, bd("2.00"), bd("10")), leg(false, bd("3.00"), bd("10"))));

        when(arbRepository.findById("ARB-4")).thenReturn(Optional.of(spyExisting));
        when(arbRepository.save(any(Arb.class))).thenAnswer(inv -> inv.getArgument(0));

        service.saveArb(incoming);

        verify(spyExisting).captureSnapshot("NO_ODDS_CHANGE");
        verify(spyExisting, never()).updateOdds(any(), any(), anyString());
        verify(arbRepository).save(spyExisting);
    }

    @Test
    void saveArb_existingArb_incomingHasOnlyOneLeg_keepsOtherRoleAndNoNpe() {
        Arb existing = newArb("ARB-5");
        existing.setLegA(leg(true, bd("2.05"), bd("5")));
        existing.setLegB(leg(false, bd("3.10"), bd("5")));
        Arb spyExisting = spy(existing);

        Arb incoming = newArb("ARB-5");
        incoming.getLegs().add(leg(true, bd("2.05"), bd("5"))); // only A arrives

        when(arbRepository.findById("ARB-5")).thenReturn(Optional.of(spyExisting));
        when(arbRepository.save(any(Arb.class))).thenAnswer(inv -> inv.getArgument(0));

        service.saveArb(incoming);

        verify(spyExisting).captureSnapshot("NO_ODDS_CHANGE");
        verify(spyExisting, never()).updateOdds(any(), any(), anyString());
        verify(arbRepository).save(spyExisting);
        assertThat(spyExisting.getLegB()).isPresent(); // B preserved
    }

    @Test
    void saveArb_existingArb_updateOddsThrows_fallbackSnapshotCaptured() {
        Arb existing = newArb("ARB-6");
        existing.setLegA(leg(true, bd("2.00"), bd("10")));
        existing.setLegB(leg(false, bd("3.00"), bd("10")));
        Arb spyExisting = spy(existing);

        Arb incoming = newArb("ARB-6");
        incoming.getLegs().addAll(List.of(leg(true, bd("2.10"), bd("10")), leg(false, bd("3.00"), bd("10"))));

        when(arbRepository.findById("ARB-6")).thenReturn(Optional.of(spyExisting));
        when(arbRepository.save(any(Arb.class))).thenAnswer(inv -> inv.getArgument(0));

        doThrow(new RuntimeException("history backend down"))
                .when(spyExisting).updateOdds(bd("2.10"), bd("3.00"), "SERVICE_UPSERT");

        service.saveArb(incoming);

        verify(spyExisting).captureSnapshot("UPSERT_FALLBACK_SNAPSHOT");
        verify(arbRepository).save(spyExisting);
    }

    @ParameterizedTest(name = "saveArb_existingArb_oddsMatrix_case{index}")
    @MethodSource("oddsChangeCases")
    void saveArb_existingArb_parameterizedOddsMatrix_correctBranch(
            BigDecimal oldA, BigDecimal oldB,
            BigDecimal newA, BigDecimal newB,
            boolean expectUpdateOdds
    ) {
        // --- existing: ATTACH via setters so legs are definitely owned by 'existing'
        Arb existing = newArb("ARB-X");
        if (oldA != null) existing.setLegA(leg(true, oldA, bd("5")));
        if (oldB != null) existing.setLegB(leg(false, oldB, bd("5")));
        Arb spyExisting = spy(existing);

        // --- incoming: DO NOT pre-attach; just supply *free* legs in the list.
        // They must have arb == null and unique instances.
        Arb incoming = newArb("ARB-X");
        if (newA != null) incoming.getLegs().add(leg(true, newA, bd("5")));
        if (newB != null) incoming.getLegs().add(leg(false, newB, bd("5")));

        when(arbRepository.findById("ARB-X")).thenReturn(Optional.of(spyExisting));
        when(arbRepository.save(any(Arb.class))).thenAnswer(inv -> inv.getArgument(0));

        service.saveArb(incoming);

        if (expectUpdateOdds) {
            verify(spyExisting).updateOdds(
                    newA != null ? newA : oldA,
                    newB != null ? newB : oldB,
                    "SERVICE_UPSERT"
            );
            verify(spyExisting, never()).captureSnapshot("NO_ODDS_CHANGE");
        } else {
            verify(spyExisting, never()).updateOdds(any(), any(), anyString());
            verify(spyExisting).captureSnapshot("NO_ODDS_CHANGE");
        }
    }


    static Stream<Arguments> oddsChangeCases() {
        return Stream.of(
                Arguments.of(null, null, null, null, false),
                Arguments.of(null, null, bd("2.0"), null, true),
                Arguments.of(bd("2.0"), null, bd("2.0"), null, false),
                Arguments.of(bd("2.0"), bd("3.0"), bd("2.1"), bd("3.0"), true),
                Arguments.of(bd("2.0"), bd("3.0"), bd("2.0"), bd("3.1"), true)
        );
    }

    // --------------- fetchTopArbsByMetrics ----------------

    @Test
    void fetchTopArbsByMetrics_mixedPool_filtersByShouldBet_sortsByScore_limitsAndPages() {
        Arb a1 = candidate("A1", true, bd("8.0"), 0.9, 0.2, 0.1, 2.0, Instant.now());
        Arb a2 = candidate("A2", true, bd("7.5"), 0.1, 0.5, 0.8, 2.0, Instant.now().minusSeconds(20));
        Arb a3 = candidate("A3", false, bd("99.0"), 1.0, 0.0, 0.0, 0.0, Instant.now()); // filtered out
        Arb a4 = candidate("A4", true, bd("5.0"), 0.5, 0.1, 0.1, 0.0, Instant.now().minusSeconds(1));
        Arb a5 = candidate("A5", true, bd("8.1"), 0.0, 1.5, 1.0, 0.0, Instant.now());

        when(arbRepository.findActiveCandidates(any(), eq(bd("4.0")), any(Pageable.class)))
                .thenReturn(List.of(a1, a2, a3, a4, a5));

        List<Arb> top = service.fetchTopArbsByMetrics(bd("4.0"), 2);

        assertThat(top).hasSize(2);
        assertThat(top.get(0).getArbId()).isEqualTo("A1");
        assertThat(List.of("A4", "A2", "A5")).contains(top.get(1).getArbId());

        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(arbRepository).findActiveCandidates(any(), eq(bd("4.0")), pageCaptor.capture());
        Pageable pr = pageCaptor.getValue();
        assertThat(pr.getPageNumber()).isEqualTo(0);
        assertThat(pr.getPageSize()).isGreaterThanOrEqualTo(100);
        Sort.Order order = pr.getSort().getOrderFor("profitPercentage");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    // --------------- getArbByNormalEventId ----------------

    @Test
    void getArbByNormalEventId_exists_returnsEntity() {
        Arb arb = newArb("EVT-1");
        when(arbRepository.findByArbId("EVT-1")).thenReturn(Optional.of(arb));

        Arb out = service.getArbByNormalEventId("EVT-1");
        assertThat(out).isSameAs(arb);
    }

    @Test
    void getArbByNormalEventId_notExists_returnsNull() {
        when(arbRepository.findByArbId("NONE")).thenReturn(Optional.empty());
        assertThat(service.getArbByNormalEventId("NONE")).isNull();
    }

    // --------------- helpers ----------------

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private Arb newArb(String id) {
        return Arb.builder()
                .arbId(id)
                .status(Status.ACTIVE)
                .active(true)
                .shouldBet(true)
                .sportEnum(SportEnum.FOOTBALL)
                .build();
    }

    private BetLeg leg(boolean primary, BigDecimal odds, BigDecimal stake) {
        BetLeg l = BetLeg.builder()
                .bookmaker(BookMaker.BET9JA)
                .league("L1")
                .sportEnum(SportEnum.FOOTBALL)
                .odds(odds)
                .stake(stake)
                .isPrimaryLeg(primary)
                .status(BetLegStatus.PENDING)
                .homeTeam("Home")
                .awayTeam("Away")
                .build();
        // IMPORTANT: make absolutely sure these are "free" legs
        l.setId(null);            // not yet persisted
        l.setVersion(null);       // not yet versioned
        l.setArb(null);           // <-- critical: no owner
        return l;
    }

    private Arb candidate(
            String id, boolean shouldBet, BigDecimal profit,
            Double conf, Double vel, Double vol, Double meanDevPct, Instant lastUpdated
    ) {
        Arb a = newArb(id);
        a.setShouldBet(shouldBet);
        a.setProfitPercentage(profit);
        a.setConfidenceScore(conf);
        a.setVelocityPctPerSec(vel);
        a.setVolatilitySigma(vol);
        a.setLastUpdatedAt(lastUpdated);

        BigDecimal currA = bd("2.00").multiply(BigDecimal.valueOf(1.0 + meanDevPct / 100.0));
        BigDecimal currB = bd("3.00").multiply(BigDecimal.valueOf(1.0 + meanDevPct / 100.0));
        a.setLegA(leg(true, currA, bd("5")));
        a.setLegB(leg(false, currB, bd("5")));
        a.setMeanOddsLegA(2.00);
        a.setMeanOddsLegB(3.00);

        return a;
    }
}
