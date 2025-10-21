package com.mouse.bet.repository;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.enums.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ArbRepository extends JpaRepository<Arb, String>, JpaSpecificationExecutor<Arb> {

    // Primary key is normalEventId (String)
    Optional<Arb> findByArbId(String arbId);

    List<Arb> findByStatusAndActive(Status status, boolean active);

    @Query("select a from Arb a where a.expiresAt is not null and a.expiresAt < :cutoff")
    List<Arb> findExpired(Instant cutoff);

    @Query("""
     SELECT a FROM Arb a
     WHERE a.status = Status.ACTIVE
     AND a.active = true
     AND (a.expiresAt IS NULL OR a.expiresAt > :now)
     AND SIZE(a.legs) = 2
     AND a.profitPercentage >= :minProfit
     """)
    List<Arb> findActiveCandidates(@Param("now") Instant now,
                                   @Param("minProfit") BigDecimal minProfit,
                                   Pageable page);

}
