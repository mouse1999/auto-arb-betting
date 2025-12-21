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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ArbRepository extends JpaRepository<Arb, String>, JpaSpecificationExecutor<Arb> {

    // Basic lookup with eager legs
    @EntityGraph(attributePaths = {"legs"})
    Optional<Arb> findById(String arbId);

    // === SIMPLIFIED: Get fresh arbs for betting ===
    @Query(value = """
    SELECT a FROM Arb a
    WHERE a.active = true
      AND a.status = 'ACTIVE'
      AND a.shouldBet = true
      AND a.lastUpdatedAt >= :cutoffTime
      AND a.profitPercentage >= :minProfit
    ORDER BY a.profitPercentage DESC
    """)
    @QueryHints(@QueryHint(name = "org.hibernate.cacheable", value = "false"))
    Page<Arb> findFreshArbsForBetting(
            @Param("minProfit") BigDecimal minProfit,
            @Param("cutoffTime") Instant cutoffTime,
            Pageable pageable
    );

    @Query(value = """
    SELECT DISTINCT a
    FROM Arb a
    LEFT JOIN FETCH a.legs 
    WHERE a.active = true 
      AND a.lastUpdatedAt >= :freshCutoff 
      AND a.profitPercentage >= :minProfit 
      AND a.currentSessionStartedAt IS NOT NULL
      AND a.lastUpdatedAt >= :minSessionStartTime
      AND (a.continuityBreakCount IS NULL OR a.continuityBreakCount <= :maxBreaks) 
    ORDER BY a.profitPercentage DESC, a.lastUpdatedAt DESC
    """)
    Page<Arb> findStableArbsForBetting(
            @Param("minProfit") BigDecimal minProfit,
            @Param("freshCutoff") Instant freshCutoff,
            @Param("minSessionStartTime") Instant minSessionStartTime,  // = now - minStableSeconds
            @Param("maxBreaks") Integer maxBreaks,
            Pageable pageable);


    // === Simple count for monitoring ===
    @Query("""
        SELECT COUNT(a) FROM Arb a
        WHERE a.active = true
          AND a.status = 'ACTIVE'
          AND a.profitPercentage >= :minProfit
        """)
    long countActiveProfitableArbs(@Param("minProfit") BigDecimal minProfit);

    // === Get total arbs by session age (for monitoring) ===
    @Query("""
        SELECT COUNT(a) FROM Arb a
        WHERE a.active = true
          AND a.status = 'ACTIVE'
          AND a.currentSessionStartedAt IS NOT NULL
          AND a.currentSessionStartedAt <= :cutoff
        """)
    long countArbsWithMinimumSession(@Param("cutoff") Instant cutoff);

    // === Bulk expiration ===
    @Modifying
    @Query("""
        UPDATE Arb a
        SET a.status = 'EXPIRED',
            a.active = false
        WHERE a.expiresAt IS NOT NULL 
          AND a.expiresAt < :cutoff
          AND a.active = true
        """)
    int expireOldArbs(@Param("cutoff") Instant cutoff);

    // === Clean up old inactive arbs ===
    @Modifying
    @Query("""
        DELETE FROM Arb a
        WHERE a.active = false
          AND a.lastUpdatedAt < :cutoff
        """)
    int deleteInactiveArbs(@Param("cutoff") Instant cutoff);

    // === Mark stale arbs (simplified) ===
    @Modifying
    @Query("""
        UPDATE Arb a
        SET a.continuityBreakCount = COALESCE(a.continuityBreakCount, 0) + 1
        WHERE a.active = true
          AND a.lastUpdatedAt < :staleCutoff
        """)
    int markStaleArbs(@Param("staleCutoff") Instant staleCutoff);

    // === Useful monitoring queries ===
    @Query("""
        SELECT a FROM Arb a
        WHERE a.active = true
          AND a.profitPercentage >= :minProfit
        ORDER BY a.profitPercentage DESC
        """)
    Page<Arb> findTopProfitableArbs(
            @Param("minProfit") BigDecimal minProfit,
            Pageable pageable
    );

    @Query("""
        SELECT a FROM Arb a
        WHERE a.sportEnum = :sportEnum
          AND a.active = true
          AND a.profitPercentage >= :minProfit
        ORDER BY a.profitPercentage DESC
        """)
    Page<Arb> findProfitableArbsBySport(
            @Param("sportEnum") SportEnum sportEnum,
            @Param("minProfit") BigDecimal minProfit,
            Pageable pageable
    );

    @Query("""
        SELECT a FROM Arb a
        WHERE a.lastSeenAt > :since
          AND a.active = true
        ORDER BY a.lastSeenAt DESC
        """)
    Page<Arb> findRecentActiveArbs(
            @Param("since") Instant since,
            Pageable pageable
    );
    @Query(value = """
    SELECT DISTINCT a.* 
    FROM arb a
    LEFT JOIN bet_leg bl ON a.arb_id = bl.arb_id  -- Optional: if you need legs in results
    WHERE a.active = true 
      AND a.status = 'ACTIVE' 
      AND a.should_bet = true 
      AND a.last_updated_at >= :freshCutoff 
      AND a.profit_percentage >= :minProfit 
      AND a.current_session_started_at IS NOT NULL 
      AND a.last_updated_at >= a.current_session_started_at + (:minSessionSeconds || ' seconds')::interval 
      AND (a.continuity_break_count IS NULL OR a.continuity_break_count <= :maxBreaks)
    ORDER BY a.profit_percentage DESC, a.last_updated_at DESC
    """,
            countQuery = """
    SELECT COUNT(DISTINCT a.arb_id) 
    FROM arb a
    WHERE a.active = true 
      AND a.status = 'ACTIVE' 
      AND a.should_bet = true 
      AND a.last_updated_at >= :freshCutoff 
      AND a.profit_percentage >= :minProfit 
      AND a.current_session_started_at IS NOT NULL 
      AND a.last_updated_at >= a.current_session_started_at + (:minSessionSeconds || ' seconds')::interval 
      AND (a.continuity_break_count IS NULL OR a.continuity_break_count <= :maxBreaks)
    """,
            nativeQuery = true)
    Page<Arb> findStableArbsNative(
            @Param("minProfit") BigDecimal minProfit,
            @Param("freshCutoff") Instant freshCutoff,
            @Param("minSessionSeconds") Long minSessionSeconds,
            @Param("maxBreaks") Integer maxBreaks,
            Pageable pageable
    );

    // === Statistics query (simplified) ===
    @Query("""
        SELECT 
            COUNT(a) as totalArbs,
            AVG(a.profitPercentage) as avgProfit,
            MIN(a.profitPercentage) as minProfit,
            MAX(a.profitPercentage) as maxProfit
        FROM Arb a
        WHERE a.active = true
          AND a.lastUpdatedAt >= :since
        """)
    Object[] getArbStatistics(@Param("since") Instant since);

    // === Get session duration stats (simplified) ===
    @Query("""
        SELECT 
            COUNT(a) as total,
            AVG(CASE 
                WHEN a.totalCumulativeDurationSeconds IS NOT NULL 
                THEN a.totalCumulativeDurationSeconds 
                ELSE 0 
            END) as avgTotalDuration
        FROM Arb a
        WHERE a.active = true
          AND a.currentSessionStartedAt IS NOT NULL
        """)
    Object[] getSessionStats();
}