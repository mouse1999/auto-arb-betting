package com.mouse.bet.entity;

import com.mouse.bet.enums.ChangeReason;
import com.mouse.bet.enums.Status;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Snapshot of an Arb at a specific point in time
 */
@Entity
@Table(name = "arb_snapshots", indexes = {
        @Index(name = "idx_arb_captured", columnList = "arb_id, captured_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ArbSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "arb_id", nullable = false)
    private Arb arb;

    @Column(nullable = false)
    private Instant capturedAt;

    private BigDecimal oddsLegA;
    private BigDecimal oddsLegB;
    private BigDecimal stakeA;
    private BigDecimal stakeB;
    private BigDecimal expectedProfit;

    private Double confidenceScore;
    private Double volatilitySigma;
    private Double velocityPctPerSec;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private Status status;

    @Column(length = 256)
    @Enumerated(EnumType.STRING)
    private ChangeReason changeReason;
}
