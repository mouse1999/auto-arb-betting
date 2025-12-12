package com.mouse.bet.utils;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.mouse.bet.entity.Wallet;
import com.mouse.bet.enums.BookMaker;
import com.mouse.bet.finance.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@RequiredArgsConstructor
public class MSportLoginUtils {

    private final WalletService walletService;

    /**
     * Performs login on MSport website
     * @param page Playwright page object
     * @param phoneNumber Phone number (without country code, e.g., "8012345678")
     * @param password User password
     * @return true if login successful, false otherwise
     */
    public boolean performLogin(Page page, String phoneNumber, String password) {
        try {
            log.info("Starting MSport login process");

            // Check if already logged in
            if (isLoggedIn(page)) {
                log.info("User is already logged in");
                return true;
            }

            // Wait for login form to be visible
            log.info("Waiting for login form");
            Locator phoneInput = page.locator("input[type='tel'][placeholder='Mobile Phone']");
            Locator passwordInput = page.locator("input[type='password'][placeholder='Password']");
            Locator loginButton = page.locator("button.login:has-text('Login')");

            // Wait for elements to be visible
            phoneInput.waitFor(new Locator.WaitForOptions().setTimeout(10000));

            // Random delay before starting (simulate human behavior)
            sleepRandom(500, 1500);

            // Click on phone input field
            log.info("Clicking phone input field");
            phoneInput.click();
            sleepRandom(200, 500);

            // Clear any existing value
            phoneInput.clear();
            sleepRandom(100, 300);

            // Type phone number with human-like behavior
            log.info("Entering phone number");
            typeHumanLike(phoneInput, phoneNumber);
            sleepRandom(300, 700);

            // Click on password input field
            log.info("Clicking password input field");
            passwordInput.click();
            sleepRandom(200, 500);

            // Clear any existing value
            passwordInput.clear();
            sleepRandom(100, 300);

            // Type password with human-like behavior
            log.info("Entering password");
            typeHumanLike(passwordInput, password);
            sleepRandom(500, 1000);

            // Click login button
            log.info("Clicking login button");
            loginButton.click();

            // Wait for navigation or login to complete
            sleepRandom(2000, 3000);

            // Verify login was successful
            boolean loginSuccess = isLoggedIn(page);

            if (loginSuccess) {
                log.info("Login successful");
            } else {
                log.error("Login failed - user not logged in after attempt");
            }

            return loginSuccess;

        } catch (TimeoutError e) {
            log.error("Timeout waiting for login elements", e);
            return false;
        } catch (Exception e) {
            log.error("Error during login process", e);
            return false;
        }
    }

    /**
     * Checks if user is currently logged in by looking for account-specific elements
     * @param page Playwright page object
     * @return true if logged in, false otherwise
     */
    public boolean isLoggedIn(Page page) {
        try {
            log.info("Checking if user is logged in");

            // First check: If login form is visible, user is definitely NOT logged in
            try {
                Locator loginButton = page.locator("button.login:has-text('Login')");
                if (loginButton.isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                    log.info("Login form detected - user is NOT logged in");
                    return false;
                }
            } catch (TimeoutError e) {
                log.debug("No login form found, checking for logged-in elements");
            }

            // Check for specific logged-in elements from the account info section
            // Multiple indicators to ensure accuracy

            // Check 1: Account balance element
            Locator accountBalance = page.locator(".account--balance.account-item");

            // Check 2: Deposit button
            Locator depositButton = page.locator("a.account-btn:has-text('Deposit')");

            // Check 3: My Bets button
            Locator myBetsButton = page.locator("a.account-btn:has-text('My Bets')");

            // Check 4: My Account button
            Locator myAccountButton = page.locator("a.account-btn.account.popper-my-accounts:has-text('My Account')");

            // Check 5: Account info container
            Locator accountInfo = page.locator(".account-info");

            // Verify multiple elements to be certain (at least 2 should be present)
            int visibleCount = 0;

            try {
                if (accountBalance.isVisible(new Locator.IsVisibleOptions().setTimeout(2000))) {
                    log.debug("Account balance element found");
                    visibleCount++;
                }
            } catch (TimeoutError e) {
                log.debug("Account balance element not found");
            }

            try {
                if (depositButton.isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                    log.debug("Deposit button found");
                    visibleCount++;
                }
            } catch (TimeoutError e) {
                log.debug("Deposit button not found");
            }

            try {
                if (myBetsButton.isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                    log.debug("My Bets button found");
                    visibleCount++;
                }
            } catch (TimeoutError e) {
                log.debug("My Bets button not found");
            }

            try {
                if (myAccountButton.isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                    log.debug("My Account button found");
                    visibleCount++;
                }
            } catch (TimeoutError e) {
                log.debug("My Account button not found");
            }

            try {
                if (accountInfo.isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                    log.debug("Account info container found");
                    visibleCount++;
                }
            } catch (TimeoutError e) {
                log.debug("Account info container not found");
            }

            // If at least 2 logged-in indicators are present, consider user logged in
            boolean isLoggedIn = visibleCount >= 2;

            if (isLoggedIn) {
                log.info("User IS logged in ({} account elements detected)", visibleCount);
            } else {
                log.info("User is NOT logged in (only {} account elements detected)", visibleCount);
            }

            return isLoggedIn;

        } catch (Exception e) {
            log.error("Error checking login status", e);
            return false;
        }
    }

    /**
     * Types text with human-like behavior including random delays and occasional typos
     */
    private static void typeHumanLike(Locator locator, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        log.info("Typing text with human-like behavior (length: {})", text.length());

        for (int i = 0; i < text.length(); i++) {
            char currentChar = text.charAt(i);

            // 5% chance of making a typo (only for letters, not numbers)
            if (ThreadLocalRandom.current().nextInt(100) < 5 && Character.isLetter(currentChar)) {
                // Type wrong character
                char wrongChar = getRandomWrongChar(currentChar);
                locator.pressSequentially(String.valueOf(wrongChar),
                        new Locator.PressSequentiallyOptions().setDelay(randomDelay(50, 150)));

                // Pause (realize mistake)
                sleepRandom(100, 300);

                // Backspace to correct
                locator.press("Backspace");
                sleepRandom(50, 150);

                log.debug("Simulated typo: typed '{}' instead of '{}'", wrongChar, currentChar);
            }

            // Type the correct character
            locator.pressSequentially(String.valueOf(currentChar),
                    new Locator.PressSequentiallyOptions().setDelay(randomDelay(50, 200)));

            // Occasional longer pause (simulates thinking or looking at keyboard)
            // 10% chance every 3-5 characters
            if (i > 0 && i % randomInt(3, 6) == 0 && ThreadLocalRandom.current().nextInt(100) < 10) {
                sleepRandom(200, 500);
            }
        }

        log.debug("Finished typing");
    }

    /**
     * Get a random wrong character near the correct one on QWERTY keyboard
     */
    private static char getRandomWrongChar(char correctChar) {
        // QWERTY keyboard adjacency map (simplified)
        String[][] keyboard = {
                {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
                {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
                {"z", "x", "c", "v", "b", "n", "m"}
        };

        char lower = Character.toLowerCase(correctChar);

        // Find the character on keyboard
        for (int row = 0; row < keyboard.length; row++) {
            for (int col = 0; col < keyboard[row].length; col++) {
                if (keyboard[row][col].charAt(0) == lower) {
                    // Pick an adjacent key
                    int direction = ThreadLocalRandom.current().nextInt(4);
                    int newRow = row;
                    int newCol = col;

                    switch (direction) {
                        case 0: newCol++; break; // Right
                        case 1: newCol--; break; // Left
                        case 2: newRow--; break; // Up
                        case 3: newRow++; break; // Down
                    }

                    // Check bounds
                    if (newRow >= 0 && newRow < keyboard.length &&
                            newCol >= 0 && newCol < keyboard[newRow].length) {
                        char wrongChar = keyboard[newRow][newCol].charAt(0);
                        return Character.isUpperCase(correctChar)
                                ? Character.toUpperCase(wrongChar)
                                : wrongChar;
                    }
                }
            }
        }

        // Fallback: return a random nearby letter
        int offset = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        char wrongChar = (char) (lower + offset);
        return Character.isUpperCase(correctChar)
                ? Character.toUpperCase(wrongChar)
                : wrongChar;
    }


    /**
     * Types text into an input field like a real human — fast but natural.
     * Includes tiny random delays, realistic key speed, and human-like variation.
     */
    public static void typeFastHumanLike(Locator locator, String text) {
        // Optional: small random delay before starting (mimics human reaction)
        randomDelay(80, 250);

        // Focus the field first (critical for some betting sites)
        locator.evaluate("el => el.focus()");

        // Convert text to char array for per-character typing
        char[] chars = text.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            String charStr = String.valueOf(c);

            // 1. Type the character
            locator.press(charStr);

            // 2. Human-like typing speed: 80–220 ms per character (avg ~140ms = ~7 chars/sec)
            int baseDelay = 80 + (i % 3 == 0 ? 60 : 0); // slight rhythm variation
            randomDelay(baseDelay, baseDelay + 140);

            // 3. 3% chance of a tiny "thinking" pause (200–600ms) — makes it ultra-realistic
            if (Math.random() < 0.03) {
                randomDelay(200, 600);
            }

            // 4. 1% chance of a small backspace + retype (classic human typo fix)
            if (Math.random() < 0.01 && i > 0) {
                locator.press("Backspace");
                randomDelay(100, 300);
                locator.press(charStr); // retype the same char
                randomDelay(120, 280);
            }
        }

        // Final small pause after typing (human habit)
        randomDelay(100, 350);
    }

    /**
     * Generate random delay in milliseconds
     */
    private static int randomDelay(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /**
     * Generate random integer between min and max (inclusive)
     */
    private static int randomInt(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /**
     * Sleep for a random duration
     */
    private static void sleepRandom(int minMs, int maxMs) {
        try {
            Thread.sleep(randomDelay(minMs, maxMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }



    /**
     * Updates wallet balance for a specific bookmaker
     * @param page The Playwright Page object
     * @param bookMaker The bookmaker enum (e.g., BookMaker.MSPORT)
     * @return true if balance was successfully updated, false otherwise
     */
    public  boolean updateWalletBalance(Page page, BookMaker bookMaker) {
        try {
            log.info("Updating wallet balance for bookmaker: {}", bookMaker);

            // Wait for balance element to be visible with timeout
            Locator balanceContainer = page.locator(".account--balance.account-item");
            balanceContainer.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(10000));

            // Get the balance text
            String balanceText = balanceContainer.textContent().trim();

            // Extract numeric value
            BigDecimal balance = extractBalanceAmount(balanceText);

            if (balance == null) {
                log.error("Failed to extract balance amount from text: {}", balanceText);
                return false;
            }

            log.info("Current {} balance: NGN {}", bookMaker, balance);

            // Save balance using WalletService
            Wallet updatedWallet = walletService.saveBalance(bookMaker, balance);

            if (updatedWallet != null) {
                log.info("Successfully updated {} wallet balance to NGN {}", bookMaker, balance);
                return true;
            } else {
                log.warn("Failed to save {} balance to database", bookMaker);
                return false;
            }

        } catch (Exception e) {
            log.error("Error updating wallet balance for {}: {}", bookMaker, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Extracts the numeric balance amount from balance text
     * @param balanceText The balance text (e.g., "NGN 47.90")
     * @return BigDecimal balance or null if extraction fails
     */
    private BigDecimal extractBalanceAmount(String balanceText) {
        try {
            // Remove currency code and whitespace, extract number
            // Pattern: "NGN 47.90" or "NGN47.90" or "47.90"
            String cleaned = balanceText
                    .replaceAll("NGN", "")
                    .replaceAll("[^0-9.]", "")
                    .trim();

            if (cleaned.isEmpty()) {
                return null;
            }

            return new BigDecimal(cleaned);

        } catch (NumberFormatException e) {
            log.error("Failed to parse balance amount: {}", balanceText, e);
            return null;
        }
    }



    public void spendAmount(BookMaker bookMaker, BigDecimal betAmount, String arbId) {
        Wallet updatedWallet = walletService.spend(bookMaker, betAmount);

        if (updatedWallet != null) {
            log.info("SUCCESS: Bet stake deducted for arbId={}, bookmaker={}: {} - New balance: {}",
                    arbId, bookMaker, betAmount, updatedWallet.getAvailableBalance());
        }else {
            log.error("FAILED: Could not deduct bet stake for arbId={}, bookmaker={}: {} - Spend operation returned null",
                    arbId, bookMaker, betAmount);
        }
    }

    /**
     * Credit amount back to balance (for rollback scenarios)
     */
    public void creditAmount(BookMaker bookmaker, double amount, String arbId) {
        log.info("🔄 Crediting {} back to {} balance (rollback) | ArbId: {}",
                amount, bookmaker, arbId);

        // Update your balance tracking system
        // Example implementation:
        // balanceService.credit(bookmaker, amount);
    }
}