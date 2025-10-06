package com.mouse.bet.entity;
// ... (imports remain the same) ...

import com.mouse.bet.converter.OddsHistoryConverter;
import com.mouse.bet.converter.StringListConverter;
import com.mouse.bet.enums.ChangeReason;
import com.mouse.bet.enums.MarketCategory;
import com.mouse.bet.enums.Sport;
import com.mouse.bet.enums.Status;
import com.mouse.bet.interfaces.MarketType;
import com.mouse.bet.model.OddsChange;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Entity
@EntityListeners(AuditingEntityListener.class)
public class Arb {

    @Id
    @Column(nullable = false, updatable = false)
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Version
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(length = 64)
    private Sport sport;

    @Column(length = 128)
    private String league;

    @Column(length = 128)
    private String eventId;

    @Column(length = 128)
    private String homeTeam;


    @Column(length = 128)
    private String awayTeam;

    @Column(length = 64)
    private MarketType marketType;

    @Column(length = 64)
    private String period;

    @Column(length = 128)
    private String selectionKey;

    private Instant eventStartTime;

    /**
     * Current set/match score (e.g., "0:2" for football/tennis)
     */
    @Column(length = 24)
    private String setScore;

    /**
     * List of scores for each period/half (e.g., ["0:0", "0:2"])
     * Uses a custom converter to store the list in a single TEXT column.
     */
    @Convert(converter =  StringListConverter.class)
    @Column(columnDefinition = "TEXT") // Use TEXT for potentially long strings of scores
    private List<String> gameScore; // Field remains List<String> in Java

    /**
     * Current match status code (e.g., "H2" for second half)
     */
    @Column(length = 24)
    private String matchStatus;

    /**
     * Time elapsed in the current period (e.g., "82:45")
     */
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

    /**
     * Profit percentage of the arb
     */
    private BigDecimal profitPercentage;


    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "bookmaker", column = @Column(name = "leg_a_bookmaker")),
            @AttributeOverride(name = "odds", column = @Column(name = "leg_a_odds")),
            @AttributeOverride(name = "rawStake", column = @Column(name = "leg_a_raw_stake")),
            @AttributeOverride(name = "stake", column = @Column(name = "leg_a_stake")),
            @AttributeOverride(name = "potentialPayout", column = @Column(name = "leg_a_payout"))
    })
    private BetLeg legA;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "bookmaker", column = @Column(name = "leg_b_bookmaker")),
            @AttributeOverride(name = "odds", column = @Column(name = "leg_b_odds")),
            @AttributeOverride(name = "rawStake", column = @Column(name = "leg_b_raw_stake")),
            @AttributeOverride(name = "stake", column = @Column(name = "leg_b_stake")),
            @AttributeOverride(name = "potentialPayout", column = @Column(name = "leg_b_payout"))
    })
    private BetLeg legB;

    private Integer executionAttempts;
    private Instant lastExecutionAt;

    @Column(length = 24)
    private String lastExecutionStatus;

    @Column(length = 256)
    private String lastExecutionMessage;

    /**
     * Stores historical snapshots of this arbitrage opportunity
     * Each snapshot captures the complete state when odds changed
     */
    @OneToMany(mappedBy = "arb", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("capturedAt DESC")
    @Builder.Default
    private List<ArbSnapshot> history = new ArrayList<>();

    /**
     * Quick access to odds changes over time
     * Key: timestamp (epoch millis), Value: OddsChange object
     * Stored as JSON in a single column for performance
     */
    @Column(columnDefinition = "TEXT")
    @Convert(converter = OddsHistoryConverter.class)
    @Builder.Default
    private Map<Long, OddsChange> oddsHistory = new TreeMap<>();

    /**
     * Count of how many times odds have changed
     */
    @Builder.Default
    @Column(nullable = false)
    private Integer oddsChangeCount = 0;

    /**
     * Maximum odds observed for leg A
     */
    private BigDecimal maxOddsLegA;

    /**
     * Minimum odds observed for leg A
     */
    private BigDecimal minOddsLegA;

    /**
     * Maximum odds observed for leg B
     */
    private BigDecimal maxOddsLegB;

    /**
     * Minimum odds observed for leg B
     */
    private BigDecimal minOddsLegB;

    /**
     * Best profit percentage seen during this arb's lifetime
     */
    private BigDecimal peakProfitPercentage;

    /**
     * Timestamp when peak profit was observed
     */
    private Instant peakProfitAt;



    /**
     * Capture current state before updating odds
     * Creates a snapshot and records odds change
     */
    public void captureSnapshot(String changeReason) {
        if (legA == null || legB == null) {
            return;
        }

        // Create snapshot
        ArbSnapshot snapshot = ArbSnapshot.builder()
                .arb(this)
                .capturedAt(Instant.now())
                .oddsLegA(legA.getOdds())
                .oddsLegB(legB.getOdds())
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



    /**
     * Update odds and track the change
     */
    public void updateOdds(BigDecimal newOddsA, BigDecimal newOddsB, String changeReason) {
        // Capture current state before updating
        captureSnapshot(changeReason);

        // Track odds change
        BigDecimal oldOddsA = legA != null ? legA.getOdds() : null;
        BigDecimal oldOddsB = legB != null ? legB.getOdds() : null;

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

        // Update current odds
        if (legA != null) {
            legA.setOdds(newOddsA);
        }
        if (legB != null) {
            legB.setOdds(newOddsB);
        }

        // Update min/max tracking
        updateMinMaxOdds(newOddsA, newOddsB);
    }

    /**
     * Update odds for a single leg
     */
    public void updateLegAOdds(BigDecimal newOdds, String changeReason) {
        BigDecimal oddsB = legB != null ? legB.getOdds() : BigDecimal.ZERO;
        updateOdds(newOdds, oddsB, changeReason);
    }

    /**
     * Update odds for a single leg
     */
    public void updateLegBOdds(BigDecimal newOdds, String changeReason) {
        BigDecimal oddsA = legA != null ? legA.getOdds() : BigDecimal.ZERO;
        updateOdds(oddsA, newOdds, changeReason);
    }

    /**
     * Track peak profit if current profit is higher
     */
    public void updatePeakProfit(BigDecimal currentProfitPercentage) {
        if (currentProfitPercentage == null) {
            return;
        }

        if (peakProfitPercentage == null ||
                currentProfitPercentage.compareTo(peakProfitPercentage) > 0) {
            peakProfitPercentage = currentProfitPercentage;
            peakProfitAt = Instant.now();
        }
    }

    /**
     * Get the most recent snapshot
     */
    public Optional<ArbSnapshot> getLatestSnapshot() {
        if (history == null || history.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(history.get(0)); // Already ordered by capturedAt DESC
    }

    /**
     * Get snapshots within a time range
     */
    public List<ArbSnapshot> getSnapshotsBetween(Instant start, Instant end) {

        if (history == null) {
            return Collections.emptyList();
        }

        return history.stream()
                .filter(snapshot -> !snapshot.getCapturedAt().isBefore(start) &&
                        !snapshot.getCapturedAt().isAfter(end))
                .toList();
    }

    /**
     * Get recent odds changes (last N)
     */
    public List<OddsChange> getRecentOddsChanges(int limit) {
        if (oddsHistory == null || oddsHistory.isEmpty()) {
            return Collections.emptyList();
        }

        return oddsHistory.values().stream()
                .sorted(Comparator.comparing(OddsChange::getTimestamp).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * Get odds changes within a time range
     */
    public List<OddsChange> getOddsChangesBetween(Instant start, Instant end) {
        if (oddsHistory == null || oddsHistory.isEmpty()) {
            return Collections.emptyList();
        }

        long startMillis = start.toEpochMilli();
        long endMillis = end.toEpochMilli();

        return oddsHistory.entrySet().stream()
                .filter(entry -> entry.getKey() >= startMillis && entry.getKey() <= endMillis)
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparing(OddsChange::getTimestamp).reversed())
                .toList();
    }

    /**
     * Calculate average odds velocity over recent window
     */
    public Double calculateAverageVelocity(int windowMinutes) {
        Instant cutoff = Instant.now().minusSeconds(windowMinutes * 60L);
        List<OddsChange> recentChanges = getOddsChangesBetween(cutoff, Instant.now());

        if (recentChanges.isEmpty()) {
            return 0.0;
        }

        double totalDelta = recentChanges.stream()
                .mapToDouble(change -> {
                    double deltaA = change.getDeltaA() != null ?
                            Math.abs(change.getDeltaA().doubleValue()) : 0.0;
                    double deltaB = change.getDeltaB() != null ?
                            Math.abs(change.getDeltaB().doubleValue()) : 0.0;
                    return (deltaA + deltaB) / 2;
                })
                .sum();

        long timeSpanSeconds = recentChanges.get(0).getTimestamp().getEpochSecond() -
                recentChanges.get(recentChanges.size() - 1).getTimestamp().getEpochSecond();

        return timeSpanSeconds > 0 ? totalDelta / timeSpanSeconds : 0.0;
    }

    /**
     * Check if odds are trending favorably (increasing)
     */
    public boolean isOddsTrendingUp() {
        List<OddsChange> recent = getRecentOddsChanges(3);
        if (recent.size() < 2) {
            return false;
        }

        return recent.stream()
                .filter(change -> change.getDeltaA() != null && change.getDeltaB() != null)
                .allMatch(change ->
                        change.getDeltaA().compareTo(BigDecimal.ZERO) >= 0 ||
                                change.getDeltaB().compareTo(BigDecimal.ZERO) >= 0
                );
    }

    public void markSeen(Instant when) {
        if (firstSeenAt == null) firstSeenAt = when;
        lastSeenAt = when;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    private BigDecimal calculateDelta(BigDecimal oldValue, BigDecimal newValue) {
        if (oldValue == null || newValue == null) {
            return BigDecimal.ZERO;
        }
        return newValue.subtract(oldValue);
    }

    private void updateMinMaxOdds(BigDecimal oddsA, BigDecimal oddsB) {
        if (oddsA != null) {
            if (maxOddsLegA == null || oddsA.compareTo(maxOddsLegA) > 0) {
                maxOddsLegA = oddsA;
            }
            if (minOddsLegA == null || oddsA.compareTo(minOddsLegA) < 0) {
                minOddsLegA = oddsA;
            }
        }

        if (oddsB != null) {
            if (maxOddsLegB == null || oddsB.compareTo(maxOddsLegB) > 0) {
                maxOddsLegB = oddsB;
            }
            if (minOddsLegB == null || oddsB.compareTo(minOddsLegB) < 0) {
                minOddsLegB = oddsB;
            }
        }
    }


}