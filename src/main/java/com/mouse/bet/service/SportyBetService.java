package com.mouse.bet.service;

import com.mouse.bet.detector.ArbDetector;
import com.mouse.bet.interfaces.OddService;
import com.mouse.bet.model.NormalizedEvent;
import org.springframework.stereotype.Service;

@Service
public class SportyBetService implements OddService {
    private final ArbDetector arbDetector;

    public SportyBetService(ArbDetector arbDetector) {
        this.arbDetector = arbDetector;
    }

    @Override
    public NormalizedEvent convertToNormalEvent(Object object) {
        return null;
    }

    @Override
    public void addNormalizedEventToPool(NormalizedEvent normalizedEvent) {

    }
}
