package com.mouse.bet.interfaces;

import com.mouse.bet.model.NormalizedEvent;

public interface OddService {
    NormalizedEvent convertToNormalEvent(Object object);

    /**
     * This adds an event to event-pool that will be used to search for an Arb
     * @param normalizedEvent-This is the event tobe added to event-pool
     */
    void addNormalizedEventToPool(NormalizedEvent normalizedEvent);

}
