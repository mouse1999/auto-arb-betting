package com.mouse.bet.model;

import com.mouse.bet.enums.ChangeReason;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents a single odds change event
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class OddsChange {
    private Instant timestamp;
    private BigDecimal oldOddsA;
    private BigDecimal newOddsA;
    private BigDecimal oldOddsB;
    private BigDecimal newOddsB;
    private BigDecimal deltaA;  // newOddsA - oldOddsA
    private BigDecimal deltaB;  // newOddsB - oldOddsB
    private ChangeReason changeReason;
}
