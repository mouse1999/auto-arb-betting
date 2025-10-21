package com.mouse.bet.service;

import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.BetLegStatus;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.exception.InvalidArbException;
import com.mouse.bet.repository.BetLegRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BetLegService {

    private final BetLegRepository betLegRepository;

    @Transactional(readOnly = true)
    public BetLeg getBetLegByArbIdAndBookMaker(String arbId, BookMaker bookmaker) {
        if (arbId == null || bookmaker == null){
            return null;
        }
        return betLegRepository.findByArb_ArbIdAndBookmaker(arbId, bookmaker).orElse(null);
    }

    @Transactional
    public void updateBetLegStatus(BetLeg betLeg, BetLegStatus status) {
        if (betLeg == null){
            return;
        }
        betLeg.setStatus(status);
        betLegRepository.save(betLeg);
    }


//    @Transactional(readOnly = true)
//    public List<BetLeg> getAllFailedBetForAnArb(String arbId) {
//        if (arbId == null) {
//            return List.of();
//        }
//        final int MAX = Integer.MAX_VALUE;
//        return betLegRepository.findFailedBetForArb(arbId, MAX, Pageable.unpaged());
//    }


    public long countArbLegsByStatus(String arbId, BetLegStatus status) {
        if(arbId == null) {
            throw new InvalidArbException("Arb ID must not be null");
        }
        if (status == null) status = BetLegStatus.PLACED;

        return betLegRepository.countLegsByStatus(arbId, status);

    }


}
