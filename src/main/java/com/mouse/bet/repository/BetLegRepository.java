package com.mouse.bet.repository;

import com.mouse.bet.entity.BetLeg;
import com.mouse.bet.enums.BetLegStatus;
import com.mouse.bet.enums.BookMaker;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BetLegRepository extends JpaRepository<BetLeg, Long>, JpaSpecificationExecutor<BetLeg> {

    List<BetLeg> findByStatus(BetLegStatus status);

    List<BetLeg> findByBookmakerAndStatus(BookMaker bookmaker, BetLegStatus status);

    Optional<BetLeg> findByOutcomeId(String outcomeId);

    @Query("select bl from BetLeg bl where bl.arb.arbId = :arbId and bl.isPrimaryLeg = true")
    Optional<BetLeg> findPrimaryByArbId(String arbId);

    @Query("select bl from BetLeg bl where bl.arb.arbId = :arbId and bl.isPrimaryLeg = false")
    Optional<BetLeg> findSecondaryByArbId(String normalEveId);


    Optional<BetLeg> findByArb_ArbIdAndBookmaker(String arbId, BookMaker bookmaker);

    @Query("""
        SELECT l FROM BetLeg l
        WHERE l.arb.arbId = :arbId
          AND l.status IN (BetLegStatus.FAILED)
          AND l.attemptCount < :maxAttempts
        ORDER BY l.lastAttemptAt NULLS FIRST
    """)
    List<BetLeg> findFailedBetForArb(@Param("arbId") String arbId,
                                     @Param("maxAttempts") int maxAttempts,
                                     Pageable page);





    @Query("""
    SELECT COUNT(bl) FROM BetLeg bl
    WHERE bl.arb.arbId = :arbId
      AND bl.status = :status
""")
    long countLegsByStatus(
            @Param("arbId") String arbId,
            @Param("status") BetLegStatus status
    );



}
