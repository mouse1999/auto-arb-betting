package com.mouse.bet.utils;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
public class SportyLoginUtils {

    /**
     * Performs login on Sporty website
     * @param page Playwright page object
     * @param phoneNumber Phone number (without country code, e.g., "8012345678")
     * @param password User password
     * @return true if login successful, false otherwise
     */
    public boolean performLogin(Page page, String phoneNumber, String password) {
        try {
            log.info("🔐 Starting MSport login process");

            // Check if already logged in
            if (isLoggedIn(page)) {
                log.info("✅ User is already logged in");
                return true;
            }

            // Wait for login form to be visible
            log.info("⏳ Waiting for login form");

            // ✅ CORRECT SELECTORS BASED ON YOUR HTML
            Locator phoneInput = page.locator("input[type='text'][name='phone'][placeholder='Mobile Number']");
            Locator passwordInput = page.locator("input[type='password'][name='psd'][placeholder='Password']");
            Locator loginButton = page.locator("button[name='logIn'].m-btn-login");

            // Wait for phone input to be visible with timeout
            try {
                phoneInput.waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(10000));
            } catch (TimeoutError e) {
                log.error("❌ Login form not found - timeout waiting for phone input");
                return false;
            }

            log.info("✅ Login form detected");

            // Random delay before starting (simulate human behavior)
            sleepRandom(500, 1500);

            // Click on phone input field
            log.info("📱 Clicking phone input field");
            phoneInput.click();
            sleepRandom(200, 500);

            // Clear any existing value
            phoneInput.fill(""); // More reliable than clear()
            sleepRandom(100, 300);

            // Type phone number with human-like behavior
            log.info("⌨️ Entering phone number: {}", maskPhoneNumber(phoneNumber));
            typeHumanLike(phoneInput, phoneNumber);
            sleepRandom(300, 700);

            // Click on password input field
            log.info("🔒 Clicking password input field");
            passwordInput.click();
            sleepRandom(200, 500);

            // Clear any existing value
            passwordInput.fill("");
            sleepRandom(100, 300);

            // Type password with human-like behavior
            log.info("⌨️ Entering password");
            typeHumanLike(passwordInput, password);
            sleepRandom(500, 1000);

            // Check if login button is enabled
            // Note: Your HTML shows the button can have "disabled" class
            if (loginButton.isDisabled() || loginButton.getAttribute("class").contains("disabled")) {
                log.warn("⚠️ Login button is disabled - waiting for it to enable");

                // Wait up to 5 seconds for button to become enabled
                try {
                    page.waitForFunction(
                            "() => { const btn = document.querySelector('button[name=\"logIn\"]'); " +
                                    "return btn && !btn.disabled && !btn.classList.contains('disabled'); }",
                            null,
                            new Page.WaitForFunctionOptions().setTimeout(5000)
                    );
                    log.info("✅ Login button enabled");
                } catch (TimeoutError e) {
                    log.error("❌ Login button remained disabled");
                    return false;
                }
            }

            // Click login button
            if (!clickLoginButton(page, loginButton)) {
                log.error("❌ Failed to click login button");
                return false;
            }

            // Wait for navigation or login to complete
            log.info("⏳ Waiting for login to complete...");
            sleepRandom(2000, 4000);

            // Check for error messages
            Locator errorToast = page.locator("div.m-error-toast");
            if (errorToast.isVisible(new Locator.IsVisibleOptions().setTimeout(2000))) {
                String errorText = errorToast.textContent();
                if (errorText != null && !errorText.trim().isEmpty()) {
                    log.error("❌ Login error displayed: {}", errorText);
                    return false;
                }
            }

            // Verify login was successful
            boolean loginSuccess = isLoggedIn(page);

            if (loginSuccess) {
                log.info("✅ Login successful");
            } else {
                log.error("❌ Login failed - user not logged in after attempt");
            }

            return loginSuccess;

        } catch (TimeoutError e) {
            log.error("⏱️ Timeout waiting for login elements: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("❌ Error during login process: {}", e.getMessage(), e);
            return false;
        }
    }


    private boolean clickLoginButton(Page page, Locator loginButton) {
        log.info("🖱️ Attempting to click login button");

        try {
            // Method 1: Standard click
            loginButton.scrollIntoViewIfNeeded();
            sleepRandom(300, 500);
            loginButton.click(new Locator.ClickOptions().setTimeout(3000));
            log.info("✅ Login button clicked (standard)");
            return true;

        } catch (Exception e1) {
            log.warn("⚠️ Standard click failed, trying force click");

            try {
                // Method 2: Force click
                loginButton.click(new Locator.ClickOptions()
                        .setForce(true)
                        .setTimeout(3000));
                log.info("✅ Login button clicked (forced)");
                return true;

            } catch (Exception e2) {
                log.warn("⚠️ Force click failed, trying JavaScript click");

                try {
                    // Method 3: JavaScript click
                    page.evaluate("document.querySelector('button[name=\"logIn\"]').click()");
                    sleepRandom(500, 1000);
                    log.info("✅ Login button clicked (JavaScript)");
                    return true;

                } catch (Exception e3) {
                    log.error("❌ All click methods failed");
                    return false;
                }
            }
        }
    }


    /**
     * Mask phone number for logging (show only last 4 digits)
     */
    private String maskPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() <= 4) {
            return "****";
        }
        int visibleDigits = 4;
        String masked = "*".repeat(phoneNumber.length() - visibleDigits);
        return masked + phoneNumber.substring(phoneNumber.length() - visibleDigits);
    }

    /**
     * Checks if user is currently logged in by looking for account-specific elements
     * @param page Playwright page object
     * @return true if logged in, false otherwise
     */
    public boolean isLoggedIn(Page page) {
        try {
            log.info("Checking if user is logged in");

            // Quick check: If login form is visible, user is NOT logged in
            if (isLoginFormVisible(page)) {
                log.info("Login form detected - user is NOT logged in");
                return false;
            }

            // Check for logged-in indicators
            int visibleIndicators = countVisibleLoginIndicators(page);
            boolean isLoggedIn = visibleIndicators >= 2;

            log.info("User {} logged in ({} account elements detected)",
                    isLoggedIn ? "IS" : "is NOT", visibleIndicators);

            return isLoggedIn;

        } catch (Exception e) {
            log.error("Error checking login status", e);
            return false;
        }
    }

    private boolean isLoginFormVisible(Page page) {
        try {
            Locator loginButton = page.locator("button[name='logIn']");
            return loginButton.isVisible(new Locator.IsVisibleOptions().setTimeout(1000));
        } catch (TimeoutError e) {
            log.debug("No login form found");
            return false;
        }
    }

    private int countVisibleLoginIndicators(Page page) {
        // Define login indicators based on the HTML structure
        String[] indicators = {
                ".m-balance",                    // Balance display
                "a[href*='deposit']",            // Deposit link
                "a[href*='bet_history']",        // Bet History link
                "#j_userInfo",                   // User info section
                ".m-user-center"                 // User center menu
        };

        int count = 0;
        for (String selector : indicators) {
            if (isElementVisible(page, selector, 1000)) {
                log.debug("Login indicator found: {}", selector);
                count++;
            }
        }

        return count;
    }

    private boolean isElementVisible(Page page, String selector, int timeoutMs) {
        try {
            return page.locator(selector)
                    .isVisible(new Locator.IsVisibleOptions().setTimeout(timeoutMs));
        } catch (TimeoutError e) {
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


}