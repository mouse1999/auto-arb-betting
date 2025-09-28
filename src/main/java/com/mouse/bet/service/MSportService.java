package com.mouse.bet.service;

import com.mouse.bet.interfaces.OddService;
import com.mouse.bet.model.NormalizedEvent;

public class MSportService implements OddService {
    @Override
    public NormalizedEvent convertToNormalEvent(Object object) {
        return null;
    }

    @Override
    public void addNormalizedEventToPool(NormalizedEvent normalizedEvent) {

    }
}
