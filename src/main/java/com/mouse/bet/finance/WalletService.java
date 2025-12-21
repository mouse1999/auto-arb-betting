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
     * Save or update the available balance for a bookmaker.
     * This is typically called after scraping or syncing balance from the bookmaker site.
     *
     * @param bookMaker The bookmaker
     * @param balance   The new available balance (must not be null)
     * @return The updated/persisted Wallet entity
     * @throws IllegalArgumentException if inputs are invalid
     */
    /**
     * Sync the actual available balance from the bookmaker (e.g., after scraping).
     * This REPLACES the stored balance with the real one.
     */
    @Transactional
    public Wallet saveBalance(BookMaker bookMaker, BigDecimal actualBalance) {
        if (bookMaker == null) {
            throw new IllegalArgumentException("BookMaker cannot be null");
        }
        if (actualBalance == null) {
            throw new IllegalArgumentException("Balance cannot be null");
        }
        if (actualBalance.compareTo(BigDecimal.ZERO) < 0) {
            log.warn("Received negative balance for {}: {}. Clamping to zero.", bookMaker, actualBalance);
            actualBalance = BigDecimal.ZERO;
        }

        BigDecimal finalActualBalance = actualBalance;
        return walletRepository.findByBookmaker(bookMaker)
                .map(existing -> {
                    BigDecimal oldBalance = existing.getAvailableBalance();
                    if (oldBalance.compareTo(finalActualBalance) != 0) {
                        existing.setAvailableBalance(finalActualBalance);
                        existing.setLastUpdated(Instant.now());
                        log.info("🔄 Synced balance for {}: {} → {}", bookMaker, oldBalance, finalActualBalance);
                    } else {
                        log.debug("Balance unchanged for {}: {}", bookMaker, finalActualBalance);
                        existing.setLastUpdated(Instant.now());
                    }
                    return walletRepository.save(existing);
                })
                .orElseGet(() -> {
                    log.info("🆕 Created new wallet for {} with initial synced balance {}", bookMaker, finalActualBalance);
                    Wallet newWallet = Wallet.builder()
                            .bookmaker(bookMaker)
                            .availableBalance(finalActualBalance)
                            .totalDeposited(BigDecimal.ZERO)
                            .totalWithdrawn(BigDecimal.ZERO)
                            .lastUpdated(Instant.now())
                            .build();
                    return walletRepository.save(newWallet);
                });
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



    /**
     * Deduct amount from wallet balance after placing a bet
     * This method ensures sufficient balance before deduction
     *
     * @param bookMaker The bookmaker whose wallet to deduct from
     * @param amount The amount to deduct (bet stake)
     * @return Updated Wallet entity, or null if insufficient balance
     * @throws IllegalStateException if wallet doesn't exist
     * @throws IllegalArgumentException if amount is negative or zero
     */
    @Transactional
    public Wallet spend(BookMaker bookMaker, BigDecimal amount) {
        if (bookMaker == null || amount == null) {
            log.warn("Cannot spend with null bookMaker or amount");
            return null;
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Cannot spend non-positive amount: {}", amount);
            throw new IllegalArgumentException("Spend amount must be positive");
        }

        // Get wallet or throw exception
        Wallet wallet = walletRepository.findByBookmaker(bookMaker)
                .orElseThrow(() -> new IllegalStateException("No wallet found for bookmaker: " + bookMaker));

        BigDecimal currentBalance = wallet.getAvailableBalance();

        // Check if wallet has sufficient balance
        if (!wallet.hasSufficientBalance(amount)) {
            log.error("Insufficient balance for bookmaker={}: required={}, available={}",
                    bookMaker, amount, currentBalance);
            return null;
        }

        // Deduct amount from balance
        BigDecimal newBalance = currentBalance.subtract(amount);
        wallet.setAvailableBalance(newBalance);
        wallet.setLastUpdated(Instant.now());

        log.info("Spent {} from bookmaker={}: balance {} -> {}",
                amount, bookMaker, currentBalance, newBalance);

        // Log to execution log if needed
//        executionLogService.logBalanceChange(bookMaker, currentBalance, newBalance, amount.negate(), "BET_PLACED");

        return walletRepository.save(wallet);
    }

    /**
     * Deduct amount from wallet with description/reason
     *
     * @param bookMaker The bookmaker whose wallet to deduct from
     * @param amount The amount to deduct
     * @param reason Description of the spend (e.g., "Bet on Arsenal vs Chelsea")
     * @return Updated Wallet entity, or null if insufficient balance
     */
    @Transactional
    public Wallet spend(BookMaker bookMaker, BigDecimal amount, String reason) {
        log.info("Spending {} from bookmaker={} - Reason: {}", amount, bookMaker, reason);

        Wallet result = spend(bookMaker, amount);

//        if (result != null) {
//            // Log with reason
//            executionLogService.logBalanceChange(
//                    bookMaker,
//                    result.getAvailableBalance().add(amount),
//                    result.getAvailableBalance(),
//                    amount.negate(),
//                    reason
//            );
//        }

        return result;
    }

    /**
     * Add winnings back to wallet balance
     *
     * @param bookMaker The bookmaker whose wallet to credit
     * @param amount The winning amount to add
     * @return Updated Wallet entity
     */
    @Transactional
    public Wallet addWinnings(BookMaker bookMaker, BigDecimal amount) {
        if (bookMaker == null || amount == null) {
            log.warn("Cannot add winnings with null bookMaker or amount");
            return null;
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Cannot add non-positive winnings: {}", amount);
            return null;
        }

        Wallet wallet = walletRepository.findByBookmaker(bookMaker)
                .orElseThrow(() -> new IllegalStateException("No wallet found for bookmaker: " + bookMaker));

        BigDecimal oldBalance = wallet.getAvailableBalance();
        BigDecimal newBalance = oldBalance.add(amount);

        wallet.setAvailableBalance(newBalance);
        wallet.setLastUpdated(Instant.now());

        log.info("Added winnings {} to bookmaker={}: balance {} -> {}",
                amount, bookMaker, oldBalance, newBalance);

//        executionLogService.logBalanceChange(bookMaker, oldBalance, newBalance, amount, "BET_WON");

        return walletRepository.save(wallet);
    }

    /**
     * Refund a bet amount back to wallet
     * Used when bet is cancelled, void, or rejected
     *
     * @param bookMaker The bookmaker whose wallet to refund
     * @param amount The amount to refund
     * @param reason Reason for refund
     * @return Updated Wallet entity
     */
    @Transactional
    public Wallet refund(BookMaker bookMaker, BigDecimal amount, String reason) {
        if (bookMaker == null || amount == null) {
            log.warn("Cannot refund with null bookMaker or amount");
            return null;
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Cannot refund non-positive amount: {}", amount);
            return null;
        }

        Wallet wallet = walletRepository.findByBookmaker(bookMaker)
                .orElseThrow(() -> new IllegalStateException("No wallet found for bookmaker: " + bookMaker));

        BigDecimal oldBalance = wallet.getAvailableBalance();
        BigDecimal newBalance = oldBalance.add(amount);

        wallet.setAvailableBalance(newBalance);
        wallet.setLastUpdated(Instant.now());

        log.info("Refunded {} to bookmaker={}: balance {} -> {} - Reason: {}",
                amount, bookMaker, oldBalance, newBalance, reason);

//        executionLogService.logBalanceChange(bookMaker, oldBalance, newBalance, amount, "REFUND: " + reason);

        return walletRepository.save(wallet);
    }

    /**
     * Record a deposit transaction
     *
     * @param bookMaker The bookmaker whose wallet to credit
     * @param amount The deposit amount
     * @return Updated Wallet entity
     */
    @Transactional
    public Wallet recordDeposit(BookMaker bookMaker, BigDecimal amount) {
        if (bookMaker == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid deposit parameters");
            return null;
        }

        Wallet wallet = walletRepository.findByBookmaker(bookMaker)
                .orElseThrow(() -> new IllegalStateException("No wallet found for bookmaker: " + bookMaker));

        BigDecimal oldBalance = wallet.getAvailableBalance();
        BigDecimal newBalance = oldBalance.add(amount);

        wallet.setAvailableBalance(newBalance);
        wallet.setTotalDeposited(wallet.getTotalDeposited().add(amount));
        wallet.setLastUpdated(Instant.now());

        log.info("Deposited {} to bookmaker={}: balance {} -> {}",
                amount, bookMaker, oldBalance, newBalance);

//        executionLogService.logBalanceChange(bookMaker, oldBalance, newBalance, amount, "DEPOSIT");

        return walletRepository.save(wallet);
    }

    /**
     * Record a withdrawal transaction
     *
     * @param bookMaker The bookmaker whose wallet to debit
     * @param amount The withdrawal amount
     * @return Updated Wallet entity, or null if insufficient balance
     */
    @Transactional
    public Wallet recordWithdrawal(BookMaker bookMaker, BigDecimal amount) {
        if (bookMaker == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid withdrawal parameters");
            return null;
        }

        Wallet wallet = walletRepository.findByBookmaker(bookMaker)
                .orElseThrow(() -> new IllegalStateException("No wallet found for bookmaker: " + bookMaker));

        if (!wallet.hasSufficientBalance(amount)) {
            log.error("Insufficient balance for withdrawal: required={}, available={}",
                    amount, wallet.getAvailableBalance());
            return null;
        }

        BigDecimal oldBalance = wallet.getAvailableBalance();
        BigDecimal newBalance = oldBalance.subtract(amount);

        wallet.setAvailableBalance(newBalance);
        wallet.setTotalWithdrawn(wallet.getTotalWithdrawn().add(amount));
        wallet.setLastUpdated(Instant.now());

        log.info("Withdrew {} from bookmaker={}: balance {} -> {}",
                amount, bookMaker, oldBalance, newBalance);

//        executionLogService.logBalanceChange(bookMaker, oldBalance, newBalance, amount.negate(), "WITHDRAWAL");

        return walletRepository.save(wallet);
    }


}
