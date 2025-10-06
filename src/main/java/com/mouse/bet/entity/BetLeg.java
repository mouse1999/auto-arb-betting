package com.mouse.bet.entity;

import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.enums.BetLegStatus;
import com.mouse.bet.enums.Sport;
import com.mouse.bet.interfaces.MarketType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents one side/leg of an arbitrage bet
 * This is typically embedded in the Arb entity or used as a standalone entity
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class BetLeg {

    /**
     * The bookmaker for this bet leg
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private BookMaker bookmaker;

    private String eventId;

    /**
     * Home team name
     */
    @Column(length = 128)
    private String homeTeam;

    /**
     * Away team name
     */
    @Column(length = 128)
    private String awayTeam;

    /**
     * League/Competition name
     */
    @Column(length = 128)
    private String league;

    /**
     * Sport type
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 64)
    private Sport sport;

    /**
     * Market type interface implementation
     * This will be stored as the market type identifier string
     * e.g., "MATCH_RESULT", "OVER_UNDER_2.5", "BTTS"
     */
    @Column(length = 64)
    private MarketType marketType;

//    /**
//     * The specific selection/outcome being bet on within the market
//     * e.g., "Home Win", "Over 2.5", "Yes"
//     */
//    @Column(length = 128)
//    private String selection;

    /**
     * Current odds for this selection
     */
    @Column(precision = 10, scale = 4)
    private BigDecimal odds;

    /**
     * Raw calculated stake (before rounding for anti-detection)
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal rawStake;

    /**
     * Actual stake to place (rounded for anti-detection)
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal stake;

    /**
     * Potential payout if this bet wins (stake × odds)
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal potentialPayout;

    /**
     * Status of this bet leg
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    @Builder.Default
    private BetLegStatus status = BetLegStatus.PENDING;

    /**
     * Bookmaker's bet ID (once placed)
     */
    @Column(length = 128)
    private String betId;

    /**
     * When this bet was placed
     */
    private Instant placedAt;

    /**
     * When this bet was settled/resulted
     */
    private Instant settledAt;

    /**
     * Actual payout received (if won)
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal actualPayout;

    /**
     * Error message if bet placement failed
     */
    @Column(length = 512)
    private String errorMessage;

    /**
     * Number of placement attempts
     */
    @Builder.Default
    @Column(nullable = false)
    private Integer attemptCount = 0;

    /**
     * Last attempt timestamp
     */
    private Instant lastAttemptAt;

    /**
     * Odds at the time of bet placement (may differ from current odds)
     */
    @Column(precision = 10, scale = 4)
    private BigDecimal placedOdds;

    /**
     * Whether this leg is the primary leg (typically the one with lower odds)
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean isPrimaryLeg = false;
    private String matchStatus;
    private String outcomeDescription;
    private String outcomeId;
    private String period;
    private Integer cashOutAvailable;
    private BigDecimal profitPercent;

    /**
     * Account balance before placing this bet
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal balanceBeforeBet;

    /**
     * Account balance after placing this bet
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal balanceAfterBet;

    // -------- Business Logic Methods --------

    /**
     * Calculate potential payout based on current stake and odds
     */
    public BigDecimal calculatePotentialPayout() {
        if (stake == null || odds == null) {
            return BigDecimal.ZERO;
        }
        return stake.multiply(odds).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Update potential payout (should be called after stake or odds change)
     */
    public void updatePotentialPayout() {
        this.potentialPayout = calculatePotentialPayout();
    }

    /**
     * Mark bet as placed successfully
     */
    public void markAsPlaced(String betId, BigDecimal oddsAtPlacement) {
        this.status = BetLegStatus.PLACED;
        this.betId = betId;
        this.placedAt = Instant.now();
        this.placedOdds = oddsAtPlacement;
    }

    /**
     * Mark bet as failed
     */
    public void markAsFailed(String errorMessage) {
        this.status = BetLegStatus.FAILED;
        this.errorMessage = errorMessage;
        this.lastAttemptAt = Instant.now();
    }

    /**
     * Mark bet as won
     */
    public void markAsWon(BigDecimal actualPayout) {
        this.status = BetLegStatus.WON;
        this.actualPayout = actualPayout;
        this.settledAt = Instant.now();
    }

    /**
     * Mark bet as lost
     */
    public void markAsLost() {
        this.status = BetLegStatus.LOST;
        this.actualPayout = BigDecimal.ZERO;
        this.settledAt = Instant.now();
    }

    /**
     * Mark bet as void/cancelled
     */
    public void markAsVoid(String reason) {
        this.status = BetLegStatus.VOID;
        this.errorMessage = reason;
        this.settledAt = Instant.now();
    }

    /**
     * Increment attempt counter
     */
    public void incrementAttempt() {
        this.attemptCount++;
        this.lastAttemptAt = Instant.now();
    }

    /**
     * Check if bet can be retried
     */
    public boolean canRetry(int maxAttempts) {
        return attemptCount < maxAttempts &&
                (status == BetLegStatus.PENDING || status == BetLegStatus.FAILED);
    }

    /**
     * Check if bet is in a final state
     */
    public boolean isFinalState() {
        return status == BetLegStatus.WON ||
                status == BetLegStatus.LOST ||
                status == BetLegStatus.VOID ||
                status == BetLegStatus.CANCELLED;
    }

    /**
     * Check if bet is pending execution
     */
    public boolean isPending() {
        return status == BetLegStatus.PENDING;
    }

    /**
     * Check if bet was successfully placed
     */
    public boolean isPlaced() {
        return status == BetLegStatus.PLACED;
    }

    /**
     * Calculate profit/loss for this leg
     */
    public BigDecimal calculateProfitLoss() {
        if (actualPayout == null || stake == null) {
            return BigDecimal.ZERO;
        }
        return actualPayout.subtract(stake).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate ROI for this leg
     */
    public BigDecimal calculateROI() {
        if (stake == null || stake.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal profitLoss = calculateProfitLoss();
        return profitLoss.divide(stake, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * Check if odds have changed significantly since placement
     */
    public boolean hasOddsChangedSignificantly(BigDecimal threshold) {
        if (placedOdds == null || odds == null) {
            return false;
        }
        BigDecimal change = odds.subtract(placedOdds).abs();
        return change.compareTo(threshold) > 0;
    }

    /**
     * Get odds change percentage since placement
     */
    public BigDecimal getOddsChangePercentage() {
        if (placedOdds == null || odds == null ||
                placedOdds.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return odds.subtract(placedOdds)
                .divide(placedOdds, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * Validate if this leg has all required data for placement
     */
    public boolean isValidForPlacement() {
        return bookmaker != null &&
                homeTeam != null && !homeTeam.isBlank() &&
                awayTeam != null && !awayTeam.isBlank() &&
                odds != null && odds.compareTo(BigDecimal.ONE) > 0 &&
                stake != null && stake.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Get the full event name
     */
    public String getEventName() {
        if (homeTeam == null || awayTeam == null) {
            return "";
        }
        return homeTeam + " vs " + awayTeam;
    }

    /**
     * Get a human-readable description of this bet
     */
    public String getBetDescription() {
        return String.format("%s - %s: @ %s (%s)",
                getEventName(),
                marketType != null ? marketType : "Unknown Market",

                odds != null ? odds : "N/A",
                bookmaker != null ? bookmaker : "Unknown Bookmaker");
    }

    /**
     * Create a copy of this bet leg with updated odds
     */
    public BetLeg withUpdatedOdds(BigDecimal newOdds) {
        BetLeg copy = this.toBuilder().build();
        copy.setOdds(newOdds);
        copy.updatePotentialPayout();
        return copy;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (object == null || getClass() != object.getClass()) return false;
        BetLeg betLeg = (BetLeg) object;
        return isPrimaryLeg() == betLeg.isPrimaryLeg() && getBookmaker() == betLeg.getBookmaker() && Objects.equals(getEventId(), betLeg.getEventId()) && Objects.equals(getHomeTeam(), betLeg.getHomeTeam()) && Objects.equals(getAwayTeam(), betLeg.getAwayTeam()) && Objects.equals(getLeague(), betLeg.getLeague()) && getSport() == betLeg.getSport() && Objects.equals(getMarketType(), betLeg.getMarketType()) && Objects.equals(getOdds(), betLeg.getOdds()) && Objects.equals(getRawStake(), betLeg.getRawStake()) && Objects.equals(getStake(), betLeg.getStake()) && Objects.equals(getPotentialPayout(), betLeg.getPotentialPayout()) && getStatus() == betLeg.getStatus() && Objects.equals(getBetId(), betLeg.getBetId()) && Objects.equals(getPlacedAt(), betLeg.getPlacedAt()) && Objects.equals(getSettledAt(), betLeg.getSettledAt()) && Objects.equals(getActualPayout(), betLeg.getActualPayout()) && Objects.equals(getErrorMessage(), betLeg.getErrorMessage()) && Objects.equals(getAttemptCount(), betLeg.getAttemptCount()) && Objects.equals(getLastAttemptAt(), betLeg.getLastAttemptAt()) && Objects.equals(getPlacedOdds(), betLeg.getPlacedOdds()) && Objects.equals(getBalanceBeforeBet(), betLeg.getBalanceBeforeBet()) && Objects.equals(getBalanceAfterBet(), betLeg.getBalanceAfterBet());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getBookmaker(), getEventId(), getHomeTeam(), getAwayTeam(), getLeague(), getSport(), getMarketType(), getOdds(), getRawStake(), getStake(), getPotentialPayout(), getStatus(), getBetId(), getPlacedAt(), getSettledAt(), getActualPayout(), getErrorMessage(), getAttemptCount(), getLastAttemptAt(), getPlacedOdds(), isPrimaryLeg(), getBalanceBeforeBet(), getBalanceAfterBet());
    }

    @Override
    public String toString() {
        return "BetLeg{" +
                "bookmaker=" + bookmaker +
                ", eventId='" + eventId + '\'' +
                ", homeTeam='" + homeTeam + '\'' +
                ", awayTeam='" + awayTeam + '\'' +
                ", league='" + league + '\'' +
                ", sport=" + sport +
                ", marketType=" + marketType +
                ", odds=" + odds +
                ", rawStake=" + rawStake +
                ", stake=" + stake +
                ", potentialPayout=" + potentialPayout +
                ", status=" + status +
                ", betId='" + betId + '\'' +
                ", placedAt=" + placedAt +
                ", settledAt=" + settledAt +
                ", actualPayout=" + actualPayout +
                ", errorMessage='" + errorMessage + '\'' +
                ", attemptCount=" + attemptCount +
                ", lastAttemptAt=" + lastAttemptAt +
                ", placedOdds=" + placedOdds +
                ", isPrimaryLeg=" + isPrimaryLeg +
                ", balanceBeforeBet=" + balanceBeforeBet +
                ", balanceAfterBet=" + balanceAfterBet +
                '}';
    }
}
