package com.mouse.bet.service;

import com.mouse.bet.interfaces.Event;
import com.mouse.bet.interfaces.NormalizedEvent;
import com.mouse.bet.interfaces.OddService;

public class Bet9jaService implements OddService<Event> {
    @Override
    public NormalizedEvent convertToNormalEvent(Event event) {
        return null;
    }

    @Override
    public void addNormalizedEventToPool(NormalizedEvent normalizedEvent) {

    }
}
