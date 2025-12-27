package com.mouse.bet.repository;

import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.BetLegStatus;
import com.mouse.bet.enums.BookMaker;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BetLegRepository extends JpaRepository<BetLeg, String> {

    // Find a specific leg by its own PK + bookmaker (useful for lookup)
    Optional<BetLeg> findByBetLegId(String betLegId);

    // All legs belonging to a given Arb
    List<BetLeg> findByArb_ArbId(String arbId);
    Optional<BetLeg> findByArb_ArbIdAndBookmaker(String arbId, BookMaker bookMaker);

    // Primary leg for an Arb
    Optional<BetLeg> findByArb_ArbIdAndIsPrimaryLegTrue(String arbId);

    // Status-based queries per Arb
    List<BetLeg> findByArb_ArbIdAndStatusIn(String arbId, List<BetLegStatus> statuses);

    List<BetLeg> findByArb_ArbIdAndStatus(String arbId, BetLegStatus status);

    Page<BetLeg> findByArb_ArbIdAndStatus(String arbId, BetLegStatus status, Pageable pageable);

    // Counts
    long countByArb_ArbIdAndStatus(String arbId, BetLegStatus status);

    long countByArb_ArbIdAndStatusIn(String arbId, List<BetLegStatus> statuses);

    long countByArb_ArbId(String arbId);
}