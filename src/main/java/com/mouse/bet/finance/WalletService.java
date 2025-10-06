package com.mouse.bet.finance;

import com.mouse.bet.entity.Wallet;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.logservice.ExecutionLogService;
import com.mouse.bet.repository.WalletRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final ExecutionLogService executionLogService;

    /**
     * Save or update balance for a bookmaker
     */
    @Transactional
    public Wallet saveBalance(BookMaker bookMaker, BigDecimal balance) {
        if (bookMaker == null || balance == null) {
            log.warn("Cannot save balance with null bookMaker or balance");
            return null;
        }

        Optional<Wallet> existing = walletRepository.findByBookmaker(bookMaker);

        Wallet wallet;
        if (existing.isPresent()) {
            wallet = existing.get();
            BigDecimal oldBalance = wallet.getAvailableBalance();
            wallet.setAvailableBalance(balance);
            wallet.setLastUpdated(Instant.now());

            log.info("Updated balance for bookmaker={}: {} -> {}", bookMaker, oldBalance, balance);
        } else {
            wallet = Wallet.builder()
                    .bookmaker(bookMaker)
                    .availableBalance(balance)
                    .lastUpdated(Instant.now())
                    .build();

            log.info("Created new wallet for bookmaker={} with balance={}", bookMaker, balance);

        }

        return walletRepository.save(wallet);
    }

    /**
     * Get available balance by bookmaker
     */
    @Transactional
    public BigDecimal getBalance(BookMaker bookMaker) {
        if (bookMaker == null) {
            log.warn("Cannot get balance with null bookMaker");
            return BigDecimal.ZERO;
        }

        return walletRepository.findByBookmaker(bookMaker)
                .map(Wallet::getAvailableBalance)
                .orElseGet(() -> {
                    log.warn("No wallet found for bookmaker={}, returning zero", bookMaker);
                    return BigDecimal.ZERO;
                });
    }

    /**
     * Update balance by adding or subtracting amount
     */
    @Transactional
    public Wallet updateBalance(BookMaker bookMaker, BigDecimal amount) {
        if (bookMaker == null || amount == null) {
            log.warn("Cannot update balance with null bookMaker or amount");
            return null;
        }

        Wallet wallet = walletRepository.findByBookmaker(bookMaker)
                .orElseThrow(() -> new IllegalStateException("No wallet found for bookmaker: " + bookMaker));

        BigDecimal oldBalance = wallet.getAvailableBalance();
        BigDecimal newBalance = oldBalance.add(amount);

        wallet.setAvailableBalance(newBalance);
        wallet.setLastUpdated(Instant.now());

        log.info("Updated balance for bookmaker={}: {} -> {} (change: {})",
                bookMaker, oldBalance, newBalance, amount);


        return walletRepository.save(wallet);
    }

    /**
     * Quick check if bookmaker can afford amount
     */
    @Transactional
    public boolean canAfford(BookMaker bookMaker, BigDecimal amount) {
        if (bookMaker == null || amount == null) {
            return false;
        }
        return getBalance(bookMaker).compareTo(amount) >= 0;
    }


}
