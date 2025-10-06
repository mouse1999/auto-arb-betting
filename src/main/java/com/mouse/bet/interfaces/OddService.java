package com.mouse.bet.interfaces;



public interface OddService<T> {
    NormalizedEvent convertToNormalEvent(T object);

    /**
     * This adds an event to event-pool that will be used to search for an Arb
     * @param normalizedEvent-This is the event tobe added to event-pool
     */
    void addNormalizedEventToPool(NormalizedEvent normalizedEvent);

}
