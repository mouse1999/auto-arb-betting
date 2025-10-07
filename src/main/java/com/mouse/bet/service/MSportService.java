package com.mouse.bet.service;

import com.mouse.bet.interfaces.Event;
import com.mouse.bet.model.NormalizedEvent;
import com.mouse.bet.interfaces.OddService;

public class MSportService implements OddService<Event> {
    @Override
    public NormalizedEvent convertToNormalEvent(Event object) {
        return null;
    }

    @Override
    public void addNormalizedEventToPool(NormalizedEvent normalizedEvent) {

    }

}
