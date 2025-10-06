package com.mouse.bet.model;

import com.mouse.bet.enums.MarketCategory;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class NormalizedMarket {
    private MarketCategory marketCategory;
    private List<NormalizedOutcome> outcomes;
}
