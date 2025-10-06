package com.mouse.bet.repository;

import com.mouse.bet.entity.Wallet;
import com.mouse.bet.enums.BookMaker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    /**
     * Find wallet by bookmaker
     */
    Optional<Wallet> findByBookmaker(BookMaker bookmaker);

    /**
     * Check if wallet exists for bookmaker
     */
    boolean existsByBookmaker(BookMaker bookmaker);

    /**
     * Get all wallets with balance greater than specified amount
     */
    List<Wallet> findByAvailableBalanceGreaterThan(BigDecimal amount);

}
