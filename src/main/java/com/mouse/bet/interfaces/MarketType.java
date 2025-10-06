package com.mouse.bet.interfaces;

import com.mouse.bet.enums.MarketCategory;
import com.mouse.bet.enums.OutcomeType;

public interface MarketType {

    public OutcomeType getOutcomeType();
    public MarketCategory getCategory();
    public String getNormalizedName();
    public String getProviderKey();
}

