package com.mouse.bet.repository;

import com.mouse.bet.entity.Arb;
import com.mouse.bet.enums.SportEnum;
import com.mouse.bet.enums.Status;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.security.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ArbRepository extends JpaRepository<Arb, String>, JpaSpecificationExecutor<Arb> {

    // Basic lookup with eager legs
    @EntityGraph(attributePaths = {"legs"})
    Optional<Arb> findById(String arbId);

    // === Main method: Get all live arbs (works with your real data) ===
//    @EntityGraph(attributePaths = {"legs"})
    @Query(value = """
    SELECT a FROM Arb a
    LEFT JOIN FETCH a.legs
    WHERE a.active = true
      AND a.lastUpdatedAt >= :cutoffTime
      AND a.profitPercentage >= :minProfit
    ORDER BY a.lastUpdatedAt DESC
    """)
    @QueryHints(@QueryHint(name = "org.hibernate.cacheable", value = "false"))
    List<Arb> findFreshArbs(
            @Param("minProfit") double minProfit,
            @Param("cutoffTime") Instant cutoffTime,
            @Param("limit") int limit
    );

    // Count for the main query
    @Query("""
        SELECT COUNT(a) FROM Arb a
        WHERE a.active = true
          AND (a.expiresAt IS NULL OR a.expiresAt > :now)
          AND a.profitPercentage >= :minProfit
        """)
    long countLiveArbsForBetting(
            @Param("now") Instant now,
            @Param("minProfit") BigDecimal minProfit
    );

    // Bulk expiration
    @Modifying
    @Query("""
        UPDATE Arb a
        SET a.status = Status.EXPIRED,
            a.active = false
        WHERE a.expiresAt IS NOT NULL AND a.expiresAt < :cutoff
        """)
    int expireOldArbs(@Param("cutoff") Instant cutoff);

    // === Useful monitoring queries ===
    List<Arb> findByProfitPercentageGreaterThanEqualOrderByProfitPercentageDesc(
            BigDecimal minProfit,
            Pageable pageable
    );

    List<Arb> findBySportEnumAndActiveAndProfitPercentageGreaterThanEqualOrderByProfitPercentageDesc(
            SportEnum sportEnum,
            boolean active,
            BigDecimal minProfit,
            Pageable pageable
    );

    List<Arb> findByLastSeenAtAfterAndActiveOrderByLastSeenAtDesc(
            Instant since,
            boolean active,
            Pageable pageable
    );
}