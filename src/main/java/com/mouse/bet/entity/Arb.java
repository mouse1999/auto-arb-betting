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

/**
 * Arb entity - Pure data model with minimal business logic
 * Business logic moved to ArbService and ArbMetricsCalculator
 */
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

    @Id
    @Column(length = 128)
    private String arbId;

    @Version
    private Integer version;

    // Event metadata
    @Enumerated(EnumType.STRING)
    @Column(length = 64)
    private SportEnum sportEnum;

    @Column(length = 128)
    private String league;

    @Column(length = 64)
    private String period;

    @Column(length = 128)
    private String selectionKey;

    private Instant eventStartTime;

    // Live match data
    @Column(length = 24)
    private String setScore;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> gameScore;

    @Column(length = 24)
    private String matchStatus;

    @Column(length = 24)
    private String playedSeconds;

    // Timestamps
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

    // Status flags
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(length = 24, nullable = false)
    private Status status = Status.ACTIVE;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    private boolean shouldBet;

    // Computed metrics (calculated by ArbMetricsCalculator)
    private Double confidenceScore;
    private Double volatilitySigma;
    private Double velocityPctPerSec;
    private Double meanOddsLegA;
    private Double meanOddsLegB;

    // Financial data
    private BigDecimal stakeA;
    private BigDecimal stakeB;
    private BigDecimal profitPercentage;

    // Relationships
    @Builder.Default
    @OneToMany(
            mappedBy = "arb",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @OrderBy("isPrimaryLeg DESC, id ASC")
    private List<BetLeg> legs = new ArrayList<>();

    @OneToMany(mappedBy = "arb", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("capturedAt DESC")
    @Builder.Default
    private List<ArbSnapshot> history = new ArrayList<>();

    // Odds tracking
    @Column(columnDefinition = "TEXT")
    @Convert(converter = OddsHistoryConverter.class)
    @Builder.Default
    private Map<Long, OddsChange> oddsHistory = new TreeMap<>();

    @Builder.Default
    @Column(nullable = false)
    private Integer oddsChangeCount = 0;

    // Odds range tracking
    private BigDecimal maxOddsLegA;
    private BigDecimal minOddsLegA;
    private BigDecimal maxOddsLegB;
    private BigDecimal minOddsLegB;

    // Peak profit tracking
    private BigDecimal peakProfitPercentage;
    private Instant peakProfitAt;

    /**
     * Maximum gap allowed between updates before treating as new arb
     * Default: 5 seconds
     */
    @Transient
    private static final long MAX_CONTINUITY_GAP_SECONDS = 5;

    /**
     * Timestamp of the last continuous update
     * Used to detect gaps in arb existence
     */
    private Instant lastContinuousUpdateAt;

    /**
     * Counter for how many times this arb has been "reborn" after gaps
     * Incremented each time continuity is broken
     */
    @Builder.Default
    @Column(nullable = false)
    private Integer continuityBreakCount = 0;

    /**
     * Timestamp when current continuous session started
     * Reset each time continuity is broken
     */
    private Instant currentSessionStartedAt;

    /**
     * Total seconds of continuous existence in current session
     * Calculated from currentSessionStartedAt
     */
    @Transient
    public Long getCurrentSessionDurationSeconds() {
        if (currentSessionStartedAt == null) return 0L;
        return Instant.now().getEpochSecond() - currentSessionStartedAt.getEpochSecond();
    }

    /**
     * Check if there's been a continuity break
     * Returns true if last update was too long ago
     */
    @Transient
    public boolean hasContinuityBreak(Instant now) {
        if (lastContinuousUpdateAt == null) return false;

        long gapSeconds = now.getEpochSecond() - lastContinuousUpdateAt.getEpochSecond();
        return gapSeconds > MAX_CONTINUITY_GAP_SECONDS;
    }

    /**
     * Mark continuity break and reset session tracking
     */
    public void breakContinuity(Instant now) {
        continuityBreakCount++;
        currentSessionStartedAt = now;
        lastContinuousUpdateAt = now;

        // Clear metrics that rely on continuity
        oddsHistory = new TreeMap<>();
        oddsChangeCount = 0;
        confidenceScore = null;
        volatilitySigma = null;
        velocityPctPerSec = null;
        meanOddsLegA = null;
        meanOddsLegB = null;
    }

    /**
     * Update continuity timestamp for ongoing session
     */
    public void updateContinuity(Instant now) {
        lastContinuousUpdateAt = now;
        if (currentSessionStartedAt == null) {
            currentSessionStartedAt = now;
        }
    }

    /**
     * Check if arb is in a reliable state for metrics
     * Returns true only if session has been running long enough
     */
    @Transient
    public boolean hasReliableMetrics(int minSessionSeconds) {
        Long duration = getCurrentSessionDurationSeconds();
        return duration != null && duration >= minSessionSeconds;
    }

    // ==================== LEG ACCESSORS ====================

    @Transient
    public Optional<BetLeg> getLegA() {
        return legs.stream().filter(BetLeg::isPrimaryLeg).findFirst();
    }

    @Transient
    public Optional<BetLeg> getLegB() {
        return legs.stream().filter(l -> !l.isPrimaryLeg()).findFirst();
    }

    // ==================== SIMPLE HELPERS ====================

    /**
     * Mark this arb as seen at a specific time
     */
    public void markSeen(Instant when) {
        if (firstSeenAt == null) firstSeenAt = when;
        lastSeenAt = when;
    }

    /**
     * Check if arb has expired
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    /**
     * Add a bet leg to this arb
     */
    public void attachLeg(BetLeg leg) {
        if (leg.getArb() != null && leg.getArb() != this) {
            throw new IllegalStateException("Leg is already attached to another Arb");
        }
        leg.setArb(this);

        // Replace existing leg with same role
        if (leg.isPrimaryLeg()) {
            getLegA().ifPresent(existing -> legs.remove(existing));
        } else {
            getLegB().ifPresent(existing -> legs.remove(existing));
        }
        legs.add(leg);
    }

    /**
     * Add snapshot to history
     */
    public void addSnapshot(ArbSnapshot snapshot) {
        snapshot.setArb(this);
        history.add(snapshot);
    }

    /**
     * Add odds change to history
     */
    public void recordOddsChange(Long timestamp, OddsChange change) {
        oddsHistory.put(timestamp, change);
        oddsChangeCount++;
    }

    /**
     * Update min/max odds tracking
     */
    public void updateOddsRange(BigDecimal oddsA, BigDecimal oddsB) {
        if (oddsA != null) {
            if (maxOddsLegA == null || oddsA.compareTo(maxOddsLegA) > 0) maxOddsLegA = oddsA;
            if (minOddsLegA == null || oddsA.compareTo(minOddsLegA) < 0) minOddsLegA = oddsA;
        }
        if (oddsB != null) {
            if (maxOddsLegB == null || oddsB.compareTo(maxOddsLegB) > 0) maxOddsLegB = oddsB;
            if (minOddsLegB == null || oddsB.compareTo(minOddsLegB) < 0) minOddsLegB = oddsB;
        }
    }

    /**
     * Update peak profit if current is higher
     */
    public void updatePeakProfit(BigDecimal currentProfitPercentage) {
        if (currentProfitPercentage == null) return;

        if (peakProfitPercentage == null ||
                currentProfitPercentage.compareTo(peakProfitPercentage) > 0) {
            peakProfitPercentage = currentProfitPercentage;
            peakProfitAt = Instant.now();
        }
    }

    // ==================== QUERY HELPERS ====================

    /**
     * Get most recent snapshot
     */
    public Optional<ArbSnapshot> getLatestSnapshot() {
        if (history == null || history.isEmpty()) return Optional.empty();
        return Optional.of(history.get(0));
    }

    /**
     * Get snapshots within time range
     */
    public List<ArbSnapshot> getSnapshotsBetween(Instant start, Instant end) {
        if (history == null || history.isEmpty()) return Collections.emptyList();
        return history.stream()
                .filter(s -> !s.getCapturedAt().isBefore(start) && !s.getCapturedAt().isAfter(end))
                .toList();
    }

    /**
     * Get recent odds changes (last N)
     */
    public List<OddsChange> getRecentOddsChanges(int limit) {
        if (oddsHistory == null || oddsHistory.isEmpty()) return Collections.emptyList();
        return oddsHistory.values().stream()
                .sorted(Comparator.comparing(OddsChange::getTimestamp).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * Get odds changes within time range
     */
    public List<OddsChange> getOddsChangesBetween(Instant start, Instant end) {
        if (oddsHistory == null || oddsHistory.isEmpty()) return Collections.emptyList();
        long startMillis = start.toEpochMilli();
        long endMillis = end.toEpochMilli();

        return oddsHistory.entrySet().stream()
                .filter(e -> e.getKey() >= startMillis && e.getKey() <= endMillis)
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparing(OddsChange::getTimestamp).reversed())
                .toList();
    }
}