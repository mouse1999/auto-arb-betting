

package com.mouse.bet.service;

import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.BetLegStatus;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.exception.InvalidArbException;
import com.mouse.bet.repository.BetLegRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BetLegService {

    private final BetLegRepository betLegRepository;

    @Transactional(readOnly = true)
    public BetLeg getBetLegById(String betLegId) {
        if (betLegId == null ){
            return null;
        }
        return betLegRepository.findByBetLegId(betLegId).orElse(null);
    }

    public BetLeg findBetLegByArbIdAndBookmaker(String arbId, BookMaker bookMaker) {
        return betLegRepository.findByArb_ArbIdAndBookmaker(arbId, bookMaker).orElse(null);

    }

    @Transactional
    public void updateBetLegStatus(BetLeg betLeg, BetLegStatus status) {
        if (betLeg == null){
            return;
        }
        betLeg.setStatus(status);
        betLegRepository.save(betLeg);
    }


    public long countArbLegsByStatus(String arbId, BetLegStatus status) {
        if(arbId == null) {
            throw new InvalidArbException("Arb ID must not be null");
        }
        if (status == null) status = BetLegStatus.PLACED;

        return betLegRepository.countByArb_ArbIdAndStatus(arbId, status);

    }


}
