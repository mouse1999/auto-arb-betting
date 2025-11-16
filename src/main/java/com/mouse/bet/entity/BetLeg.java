package com.mouse.bet.entity;

import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.enums.BetLegStatus;
import com.mouse.bet.enums.SportEnum;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Entity
@Table(
        name = "bet_leg",
        indexes = {
                @Index(name = "idx_leg_outcome_id", columnList = "outcomeId"),
                @Index(name = "idx_leg_bet_id", columnList = "betId"),
                @Index(name = "idx_leg_status", columnList = "status"),
                @Index(name = "idx_leg_bookmaker_status", columnList = "bookmaker,status"),
                @Index(name = "idx_leg_event", columnList = "eventId")
        },
        uniqueConstraints = {
                // guarantees only one primary leg and one secondary leg per Arb
                @UniqueConstraint(name = "uq_arb_leg_role", columnNames = {"arb_id", "isPrimaryLeg"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@ToString(exclude = "arb")
public class BetLeg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Integer version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "arb_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_betleg_arb")
    )
    private Arb arb;



    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private BookMaker bookmaker;

    @Column(length = 128)
    private String eventId;

    @Column(length = 128)
    private String homeTeam;

    @Column(length = 128)
    private String awayTeam;

    @Column(length = 128)
    private String league;

    @Enumerated(EnumType.STRING)
    @Column(length = 64)
    private SportEnum sportEnum;

    @Column(precision = 10, scale = 4)
    private BigDecimal odds;

    @Column(precision = 12, scale = 2)
    private BigDecimal rawStake;

    @Column(precision = 12, scale = 2)
    private BigDecimal stake;

    @Column(precision = 12, scale = 2)
    private BigDecimal potentialPayout;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    @Builder.Default
    private BetLegStatus status = BetLegStatus.PENDING;

    @Column(length = 128)
    private String betId;

    private Instant placedAt;
    private Instant settledAt;

    @Column(precision = 12, scale = 2)
    private BigDecimal actualPayout;

    @Column(length = 512)
    private String errorMessage;

    @Builder.Default
    @Column(nullable = false)
    private Integer attemptCount = 0;

    private Instant lastAttemptAt;

    @Column(precision = 10, scale = 4)
    private BigDecimal placedOdds;

    @Builder.Default
    @Column(nullable = false)
    private boolean isPrimaryLeg = false;

    private String matchStatus;
    private String outcomeDescription;
    private String outcomeId;
    private String period;
    private Integer cashOutAvailable;
    private BigDecimal profitPercent;

    @Column(precision = 12, scale = 2)
    private BigDecimal balanceBeforeBet;

    @Column(precision = 12, scale = 2)
    private BigDecimal balanceAfterBet;

    /* -------------------- Business logic -------------------- */

    public BigDecimal calculatePotentialPayout() {
        if (stake == null || odds == null) return BigDecimal.ZERO;
        return stake.multiply(odds).setScale(2, RoundingMode.HALF_UP);
    }

    public void updatePotentialPayout() {
        this.potentialPayout = calculatePotentialPayout();
    }

    public void markAsPlaced(String betId, BigDecimal oddsAtPlacement) {
        this.status = BetLegStatus.PLACED;
        this.betId = betId;
        this.placedAt = Instant.now();
        this.placedOdds = oddsAtPlacement;
    }

    public void markAsFailed(String errorMessage) {
        this.status = BetLegStatus.FAILED;
        this.errorMessage = errorMessage;
        this.lastAttemptAt = Instant.now();
    }

    public void markAsWon(BigDecimal actualPayout) {
        this.status = BetLegStatus.WON;
        this.actualPayout = actualPayout;
        this.settledAt = Instant.now();
    }

    public void markAsLost() {
        this.status = BetLegStatus.LOST;
        this.actualPayout = BigDecimal.ZERO;
        this.settledAt = Instant.now();
    }

    public void markAsVoid(String reason) {
        this.status = BetLegStatus.VOID;
        this.errorMessage = reason;
        this.settledAt = Instant.now();
    }

    public void incrementAttempt() {
        this.attemptCount++;
        this.lastAttemptAt = Instant.now();
    }

    public boolean canRetry(int maxAttempts) {
        return attemptCount < maxAttempts &&
                (status == BetLegStatus.PENDING || status == BetLegStatus.FAILED);
    }

    public boolean isFinalState() {
        return status == BetLegStatus.WON ||
                status == BetLegStatus.LOST ||
                status == BetLegStatus.VOID ||
                status == BetLegStatus.CANCELLED;
    }

    public boolean isPending() {
        return status == BetLegStatus.PENDING;
    }

    public boolean isPlaced() {
        return status == BetLegStatus.PLACED;
    }

    public BigDecimal calculateProfitLoss() {
        if (actualPayout == null || stake == null) return BigDecimal.ZERO;
        return actualPayout.subtract(stake).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal calculateROI() {
        if (stake == null || stake.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        BigDecimal profitLoss = calculateProfitLoss();
        return profitLoss
                .divide(stake, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    public boolean hasOddsChangedSignificantly(BigDecimal threshold) {
        if (placedOdds == null || odds == null) return false;
        BigDecimal change = odds.subtract(placedOdds).abs();
        return change.compareTo(threshold) > 0;
    }

    public BigDecimal getOddsChangePercentage() {
        if (placedOdds == null || odds == null || placedOdds.compareTo(BigDecimal.ZERO) == 0)
            return BigDecimal.ZERO;
        return odds.subtract(placedOdds)
                .divide(placedOdds, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    public boolean isValidForPlacement() {
        return bookmaker != null &&
                homeTeam != null && !homeTeam.isBlank() &&
                awayTeam != null && !awayTeam.isBlank() &&
                odds != null && odds.compareTo(BigDecimal.ONE) > 0 &&
                stake != null && stake.compareTo(BigDecimal.ZERO) > 0;
    }

    public String getEventName() {
        if (homeTeam == null || awayTeam == null) return "";
        return homeTeam + " vs " + awayTeam;
    }

    public String getBetDescription() {
        return String.format("%s - @ %s (%s)",
                getEventName(),
                odds != null ? odds : "N/A",
                bookmaker != null ? bookmaker : "Unknown Bookmaker");
    }

    public BetLeg withUpdatedOdds(BigDecimal newOdds) {
        BetLeg copy = this.toBuilder().id(null).version(null).build();
        copy.setOdds(newOdds);
        copy.updatePotentialPayout();
        return copy;
    }

}
