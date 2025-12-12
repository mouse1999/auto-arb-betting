package com.mouse.bet.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Setter
@Getter
public class ArbConfig {
    private BigDecimal TOTAL_STAKE = BigDecimal.valueOf(100);
}
