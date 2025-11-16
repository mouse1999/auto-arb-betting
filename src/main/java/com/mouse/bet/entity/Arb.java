package com.mouse.bet.entity;

import com.mouse.bet.converter.OddsHistoryConverter;
import com.mouse.bet.converter.StringListConverter;
import com.mouse.bet.enums.ChangeReason;
import com.mouse.bet.enums.SportEnum;
import com.mouse.bet.enums.Status;
import com.mouse.bet.model.OddsChange;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Entity
@Table(
        name = "arb",
        indexes = {
                @Index(name = "idx_arb_sport", columnList = "sportEnum"),
                @Index(name = "idx_arb_status_active", columnList = "status,active"),
                @Index(name = "idx_arb_expires_at", columnList = "expiresAt")
        }
)
@EntityListeners(AuditingEntityListener.class)
@ToString(exclude = {"legs", "history"})
public class Arb {

    @Version
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(length = 64)
    private SportEnum sportEnum;

    @Column(length = 128)
    private String league;

    /** Business key that already existed in your model */
    @Id
    @Column(length = 128)
    private String arbId;

    /** Period/phase (kept on Arb; legs also store period for their own context) */
    @Column(length = 64)
    private String period;

    @Column(length = 128)
    private String selectionKey;

    private Instant eventStartTime;

    /** Current set/match score (e.g., "0:2") */
    @Column(length = 24)
    private String setScore;

    /** Period scores stored as TEXT via converter */
    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> gameScore;

    /** Match status (e.g., "H2") */
    @Column(length = 24)
    private String matchStatus;

    /** Time elapsed in current period */
    @Column(length = 24)
    private String playedSeconds;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant firstSeenAt;
    private Instant lastSeenAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant lastUpdatedAt;

    private Instant expiresAt;
    private Integer predictedHoldUpMs;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(length = 24, nullable = false)
    private Status status = Status.ACTIVE;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    private boolean shouldBet;
    private Double confidenceScore;
    private Double volatilitySigma;
    private Double velocityPctPerSec;
    private Double meanOddsLegA;
    private Double meanOddsLegB;

    private BigDecimal stakeA;
    private BigDecimal stakeB;

    /** Profit percentage of the arb */
    private BigDecimal profitPercentage;

    /** Legs (A=primary, B=secondary) */
    @Builder.Default
    @OneToMany(
            mappedBy = "arb",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @OrderBy("isPrimaryLeg DESC, id ASC")
    private List<BetLeg> legs = new ArrayList<>();

    /** Historical snapshots */
    @OneToMany(mappedBy = "arb", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("capturedAt DESC")
    @Builder.Default
    private List<ArbSnapshot> history = new ArrayList<>();

    /** Odds changes timeline (JSON) */
    @Column(columnDefinition = "TEXT")
    @Convert(converter = OddsHistoryConverter.class)
    @Builder.Default
    private Map<Long, OddsChange> oddsHistory = new TreeMap<>();

    /** Count of how many times odds have changed */
    @Builder.Default
    @Column(nullable = false)
    private Integer oddsChangeCount = 0;

    /** Min/Max tracking */
    private BigDecimal maxOddsLegA;
    private BigDecimal minOddsLegA;
    private BigDecimal maxOddsLegB;
    private BigDecimal minOddsLegB;

    /** Peak profit tracking */
    private BigDecimal peakProfitPercentage;
    private Instant peakProfitAt;

    /* -------------------- Helpers for legs -------------------- */

    @Transient
    public Optional<BetLeg> getLegA() {
        return legs.stream().filter(BetLeg::isPrimaryLeg).findFirst();
    }

    @Transient
    public Optional<BetLeg> getLegB() {
        return legs.stream().filter(l -> !l.isPrimaryLeg()).findFirst();
    }

    public void setLegA(BetLeg legA) {
        legA.setPrimaryLeg(true);
        attachLeg(legA);
    }

    public void setLegB(BetLeg legB) {
        legB.setPrimaryLeg(false);
        attachLeg(legB);
    }

    public void clearLegs() {
        this.legs.clear();
    }

    public void attachLeg(BetLeg leg) {
        if (leg.getArb() != null && leg.getArb() != this) {
            throw new IllegalStateException("Leg is already attached to another Arb");
        }
        leg.setArb(this);
        // replace existing A/B by role
        if (leg.isPrimaryLeg()) {
            getLegA().ifPresent(existing -> legs.remove(existing));
        } else {
            getLegB().ifPresent(existing -> legs.remove(existing));
        }
        legs.add(leg);
    }

    /* -------------------- Business logic (adapted) -------------------- */

    /** Capture current state before updating odds */
    public void captureSnapshot(String changeReason) {
        Optional<BetLeg> legA = getLegA();
        Optional<BetLeg> legB = getLegB();
        if (legA.isEmpty() || legB.isEmpty()) {
            return;
        }

        ArbSnapshot snapshot = ArbSnapshot.builder()
                .arb(this)
                .capturedAt(Instant.now())
                .oddsLegA(legA.get().getOdds())
                .oddsLegB(legB.get().getOdds())
                .stakeA(stakeA)
                .stakeB(stakeB)
                .expectedProfit(profitPercentage)
                .confidenceScore(confidenceScore)
                .volatilitySigma(volatilitySigma)
                .velocityPctPerSec(velocityPctPerSec)
                .status(status)
                .changeReason(ChangeReason.MARKET_UPDATE)
                .build();

        history.add(snapshot);
    }

    /** Update odds for both legs and track the change */
    public void updateOdds(BigDecimal newOddsA, BigDecimal newOddsB, String changeReason) {
        captureSnapshot(changeReason);

        BigDecimal oldOddsA = getLegA().map(BetLeg::getOdds).orElse(null);
        BigDecimal oldOddsB = getLegB().map(BetLeg::getOdds).orElse(null);

        OddsChange change = OddsChange.builder()
                .timestamp(Instant.now())
                .oldOddsA(oldOddsA)
                .newOddsA(newOddsA)
                .oldOddsB(oldOddsB)
                .newOddsB(newOddsB)
                .deltaA(calculateDelta(oldOddsA, newOddsA))
                .deltaB(calculateDelta(oldOddsB, newOddsB))
                .changeReason(changeReason)
                .build();

        oddsHistory.put(System.currentTimeMillis(), change);
        oddsChangeCount++;

        getLegA().ifPresent(leg -> { leg.setOdds(newOddsA); leg.updatePotentialPayout(); });
        getLegB().ifPresent(leg -> { leg.setOdds(newOddsB); leg.updatePotentialPayout(); });

        updateMinMaxOdds(newOddsA, newOddsB);
    }

    /** Update one leg at a time (A) */
    public void updateLegAOdds(BigDecimal newOdds, String changeReason) {
        BigDecimal other = getLegB().map(BetLeg::getOdds).orElse(BigDecimal.ZERO);
        updateOdds(newOdds, other, changeReason);
    }

    /** Update one leg at a time (B) */
    public void updateLegBOdds(BigDecimal newOdds, String changeReason) {
        BigDecimal other = getLegA().map(BetLeg::getOdds).orElse(BigDecimal.ZERO);
        updateOdds(other, newOdds, changeReason);
    }

    /** Track peak profit if current profit is higher */
    public void updatePeakProfit(BigDecimal currentProfitPercentage) {
        if (currentProfitPercentage == null) return;

        if (peakProfitPercentage == null ||
                currentProfitPercentage.compareTo(peakProfitPercentage) > 0) {
            peakProfitPercentage = currentProfitPercentage;
            peakProfitAt = Instant.now();
        }
    }

    /** Most recent snapshot */
    public Optional<ArbSnapshot> getLatestSnapshot() {
        if (history == null || history.isEmpty()) return Optional.empty();
        return Optional.of(history.get(0)); // ordered DESC
    }

    /** Snapshots within time range */
    public List<ArbSnapshot> getSnapshotsBetween(Instant start, Instant end) {
        if (history == null || history.isEmpty()) return Collections.emptyList();
        return history.stream()
                .filter(s -> !s.getCapturedAt().isBefore(start) && !s.getCapturedAt().isAfter(end))
                .collect(Collectors.toList());
    }

    /** Recent odds changes (last N) */
    public List<OddsChange> getRecentOddsChanges(int limit) {
        if (oddsHistory == null || oddsHistory.isEmpty()) return Collections.emptyList();
        return oddsHistory.values().stream()
                .sorted(Comparator.comparing(com.mouse.bet.model.OddsChange::getTimestamp).reversed())
                .limit(limit)
                .toList();
    }

    /** Odds changes within time range */
    public List<OddsChange> getOddsChangesBetween(Instant start, Instant end) {
        if (oddsHistory == null || oddsHistory.isEmpty()) return Collections.emptyList();
        long startMillis = start.toEpochMilli();
        long endMillis = end.toEpochMilli();

        return oddsHistory.entrySet().stream()
                .filter(e -> e.getKey() >= startMillis && e.getKey() <= endMillis)
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparing(com.mouse.bet.model.OddsChange::getTimestamp).reversed())
                .toList();
    }

    /** Average odds velocity over recent window */
    public Double calculateAverageVelocity(int windowMinutes) {
        Instant cutoff = Instant.now().minusSeconds(windowMinutes * 60L);
        List<com.mouse.bet.model.OddsChange> recent = getOddsChangesBetween(cutoff, Instant.now());
        if (recent.isEmpty()) return 0.0;

        double totalDelta = recent.stream()
                .mapToDouble(ch -> {
                    double dA = ch.getDeltaA() != null ? ch.getDeltaA().abs().doubleValue() : 0.0;
                    double dB = ch.getDeltaB() != null ? ch.getDeltaB().abs().doubleValue() : 0.0;
                    return (dA + dB) / 2.0;
                })
                .sum();

        long span = recent.get(0).getTimestamp().getEpochSecond()
                - recent.get(recent.size() - 1).getTimestamp().getEpochSecond();
        return span > 0 ? totalDelta / span : 0.0;
    }

    /** Simple “uptrend” check */
    public boolean isOddsTrendingUp() {
        List<OddsChange> recent = getRecentOddsChanges(3);
        if (recent.size() < 2) return false;

        return recent.stream()
                .filter(ch -> ch.getDeltaA() != null && ch.getDeltaB() != null)
                .allMatch(ch -> ch.getDeltaA().compareTo(BigDecimal.ZERO) >= 0
                        || ch.getDeltaB().compareTo(BigDecimal.ZERO) >= 0);
    }

    public void markSeen(Instant when) {
        if (firstSeenAt == null) firstSeenAt = when;
        lastSeenAt = when;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    /* -------------------- Private helpers -------------------- */

    private BigDecimal calculateDelta(BigDecimal oldValue, BigDecimal newValue) {
        if (oldValue == null || newValue == null) return BigDecimal.ZERO;
        return newValue.subtract(oldValue);
    }

    private void updateMinMaxOdds(BigDecimal oddsA, BigDecimal oddsB) {
        if (oddsA != null) {
            if (maxOddsLegA == null || oddsA.compareTo(maxOddsLegA) > 0) maxOddsLegA = oddsA;
            if (minOddsLegA == null || oddsA.compareTo(minOddsLegA) < 0) minOddsLegA = oddsA;
        }
        if (oddsB != null) {
            if (maxOddsLegB == null || oddsB.compareTo(maxOddsLegB) > 0) maxOddsLegB = oddsB;
            if (minOddsLegB == null || oddsB.compareTo(minOddsLegB) < 0) minOddsLegB = oddsB;
        }
    }

}
