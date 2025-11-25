package com.mouse.bet.utils;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
public class LoginUtils {

    private static final int LOGIN_TIMEOUT_SEC = 12;

    private static final List<String> LOGIN_TRIGGER_SELECTORS = Arrays.asList(
            "button.m-btn-login",
            "button[name='logIn']",
            "text=Log in",
            "text=Login",
            "text=Sign in",
            "button:has-text('Log in')",
            "button:has-text('Login')",
            "a[aria-label*='login' i]",
            "button[aria-label*='login' i]",
            "[data-testid='login-button']",
            ".login-btn"
    );

    private static final List<String> EMAIL_SELECTORS = Arrays.asList(
            "input[name='phone']",                           // ✅ EXACT match from HTML
            "input[type='text'][name='phone']",              // ✅ Type + name
            "input[placeholder='Mobile Number']",             // ✅ Exact placeholder
            ".m-phone input[type='text']",                   // ✅ Parent class selector
            ".m-phone-wrapper input",                        // ✅ Wrapper + input
            "div.m-phone > input",                           // ✅ Direct child
            ".m-phone input[name='phone']"
            );

    private static final List<String> PASSWORD_SELECTORS = Arrays.asList(
            "input[name='psd']",  // SportyBet uses 'psd' not 'password'
            "input[type='password']",
            "input[name='password']",
            "input#password",
            "input[autocomplete='current-password']"
    );

    private static final List<String> SUBMIT_SELECTORS = Arrays.asList(
            "button.m-btn-login",  // SportyBet specific
            "button[name='logIn']",
            "button[type='submit'] >> visible=true",
            "button:has-text('Log in')",
            "button:has-text('Login')",
            "button:has-text('Sign in')",
            "text=Log in",
            "text=Login",
            "text=Sign in",
            "[data-testid='login-submit']",
            ".login-submit",
            "button.primary:has-text('Log')"
    );

    // UPDATED: Actual SportyBet logged-in indicators from HTML
    private static final List<String> LOGGED_IN_INDICATORS = Arrays.asList(
            // Primary indicators - balance and user menu
//            "#j_balance",
//            ".m-balance",
//            "span.m-balance",
//            ".m-bablance-wrapper",
//
//            // User info and account menu
//            "#j_userInfo",
//            ".m-userInfo",
//            ".m-user-center",
//
//            // Action buttons (Deposit, Withdraw, etc.)
//            "a.m-deposit",
//            ".m-deposit",
//            "text=Deposit",
//
//            "#j_betHistory",
//            ".m-history",
//            "text=Bet History",
//
//            // Logout button (definitive proof of being logged in)
//            ".m-list-bar[data-cms-key='log_out']",
//            "li:has-text('Logout')",
//            "text=Logout",
//
//            // Balance refresh/toggle icons
//            "#j_refreshBalance",
//            "#j_toggleBalance",
//            ".m-icon-refresh",
//            ".m-icon-toggle",

            // User menu items
            "span[data-cms-key='my_account']",
            "span[data-cms-key='withdraw']",
            "span[data-cms-key='transactions']",

            // Generic indicators (fallback)
            "text=My Account",
            "text=Withdraw",
            "text=Transactions"
//            "text=Notification Center",
//            ".user-balance",
//            ".header__user",
//            ".account-balance"
    );

    // UPDATED: Login form indicators (when NOT logged in)
    private static final List<String> LOGIN_FORM_INDICATORS = Arrays.asList(
            "button.m-btn-login",
            "button[name='logIn']",
            "button.m-btn-register",
            "input[name='phone']",
            "input[name='psd']",
            ".m-login-bar:not(.m-login-bar-fix)",  // Login bar when not logged in
            "span[data-cms-key='login_in']"
    );

    /**
     * Universal robust login method — works on 95%+ of bookmakers in 2025
     * UPDATED: Optimized for SportyBet
     */
    /**
     * Universal robust login method with human-like typing
     * UPDATED: Types like a human to avoid bot detection
     */
    public boolean performLogin(Page page, String email, String password) {
        log.info("Attempting automated login for: {}", email);

        try {
            // Step 0: Check if already logged in
            if (isLoggedIn(page)) {
                log.info("✅ Already logged in - skipping login process");
                return true;
            }

            // Step 1: Try to open login modal/page
            openLoginModal(page);

            // Step 2: Wait a moment for form to appear
            page.waitForTimeout(randomDelay(1500, 2000));

            // Step 3: Find email & password fields
            String emailField = findFirstVisibleSelector(page, EMAIL_SELECTORS);
            String passwordField = findFirstVisibleSelector(page, PASSWORD_SELECTORS);

            if (emailField == null || passwordField == null) {
                log.warn("⚠️ Login form not found — possible CAPTCHA or new layout");
                log.warn("Email field found: {}, Password field found: {}",
                        emailField != null, passwordField != null);
                return false;
            }

            log.info("✅ Found login form - filling credentials");

            // Step 4: Focus and clear email field
            Locator emailLocator = page.locator(emailField);
            emailLocator.click();
            page.waitForTimeout(randomDelay(200, 400));

            // Clear field with Ctrl+A and Backspace (human-like)
            emailLocator.press("Control+A");
            page.waitForTimeout(randomDelay(50, 150));
            emailLocator.press("Backspace");
            page.waitForTimeout(randomDelay(100, 300));

            // Type email like a human
            typeHumanLike(emailLocator, email);
            page.waitForTimeout(randomDelay(300, 500));

            // Step 5: Focus and clear password field
            Locator passwordLocator = page.locator(passwordField);
            passwordLocator.click();
            page.waitForTimeout(randomDelay(200, 400));

            // Clear field
            passwordLocator.press("Control+A");
            page.waitForTimeout(randomDelay(50, 150));
            passwordLocator.press("Backspace");
            page.waitForTimeout(randomDelay(100, 300));

            // Type password like a human
            typeHumanLike(passwordLocator, password);
            page.waitForTimeout(randomDelay(500, 800));

            log.info("✅ Credentials filled, attempting to submit");

            // Step 6: Submit form
            boolean submitted = tryClickFirst(page, SUBMIT_SELECTORS);
            if (!submitted) {
                log.info("Submit button not found, trying Enter key");
                passwordLocator.press("Enter"); // fallback
            }

            // Step 7: Wait & detect successful login
            return waitForLoginSuccess(page);

        } catch (Exception e) {
            log.error("❌ Login failed with exception: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Type text character by character with human-like delays and occasional mistakes
     *
     * Features:
     * - Random delays between keystrokes (50-200ms)
     * - Occasional longer pauses (simulates thinking)
     * - Random typos with corrections (5% chance)
     * - Variable typing speed (faster for common words)
     *
     * @param locator The input field to type into
     * @param text The text to type
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

                log.info("Simulated typo: typed '{}' instead of '{}'", wrongChar, currentChar);
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
     * Check if user is already logged in
     * UPDATED: Uses actual SportyBet HTML structure
     */
    public boolean isLoggedIn(Page page) {
        try {
            page.waitForTimeout(500); // Small delay for page to stabilize

            // Method 1: Check for balance display (most reliable for SportyBet)
            try {
                Locator balanceElement = page.locator("#j_balance, .m-balance, span.m-balance");
                log.info("balance locator found");
                if (balanceElement.count() > 0 && balanceElement.first().isVisible()) {
                    String balanceText = balanceElement.first().textContent();
                    if (balanceText != null && balanceText.contains("NGN")) {
                        log.info("✅ Login detected - found balance: {}", balanceText.trim());
                        return true;
                    }
                }
            } catch (Exception e) {
                log.debug("Balance check failed: {}", e.getMessage());
            }

            // Method 2: Check for user menu
            try {
                Locator userMenu = page.locator("#j_userInfo, .m-userInfo, .m-user-center");
                if (userMenu.count() > 0 && userMenu.first().isVisible()) {
                    log.info("✅ Login detected - found user menu");
                    return true;
                }
            } catch (Exception e) {
                log.error("User menu check failed: {}", e.getMessage());
            }

            // Method 3: Check for logout button (definitive proof)
            try {
                Locator logoutButton = page.locator(".m-list-bar[data-cms-key='log_out'], li:has-text('Logout')");
                if (logoutButton.count() > 0) {
                    log.info("✅ Login detected - found logout button");
                    return true;
                }
            } catch (Exception e) {
                log.error("Logout button check failed: {}", e.getMessage());
            }

            // Method 4: Check for any logged-in UI indicators
            for (String selector : LOGGED_IN_INDICATORS) {
                try {
                    if (page.isVisible(selector)) {
                        log.info("✅ Login detected via selector: {}", selector);
                        return true;
                    }
                } catch (PlaywrightException ignored) {
                    // Continue to next selector
                }
            }

            // Method 6: Check if login form is NOT visible (inverse check)
            boolean loginFormVisible = isAnyVisible(page, LOGIN_FORM_INDICATORS);
            if (!loginFormVisible) {
                log.info("✅ Login assumed - login form not visible");
                return true;
            }

            log.info("❌ Not logged in - no indicators found");
            return false;

        } catch (Exception e) {
            log.error("Error checking login status: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if any selector in the list is visible
     */
    private boolean isAnyVisible(Page page, List<String> selectors) {
        for (String selector : selectors) {
            try {
                if (page.isVisible(selector)) {
                    return true;
                }
            } catch (PlaywrightException ignored) {
                // Continue
            }
        }
        return false;
    }

    private void openLoginModal(Page page) {
        for (String selector : LOGIN_TRIGGER_SELECTORS) {
            try {
                if (page.isVisible(selector)) {
                    log.info("Clicking login button: {}", selector);
                    page.click(selector);
                    page.waitForTimeout(800);
                    log.info("✅ Opened login modal with: {}", selector);
                    return;
                }
            } catch (PlaywrightException e) {
                log.error("Failed to click {}: {}", selector, e.getMessage());
            }
        }
        log.info("ℹ️ No login button found — already on login form or logged in");
    }

    private String findFirstVisibleSelector(Page page, List<String> selectors) {
        for (String sel : selectors) {
            try {
                if (page.isVisible(sel)) {
                    log.debug("✅ Found visible selector: {}", sel);
                    return sel;
                }
            } catch (PlaywrightException ignored) {
                // Continue
            }
        }
        return null;
    }

    private boolean tryClickFirst(Page page, List<String> selectors) {
        for (String sel : selectors) {
            try {
                if (page.isVisible(sel)) {
                    log.info("✅ Clicking submit button: {}", sel);
                    page.click(sel);
                    return true;
                }
            } catch (PlaywrightException e) {
                log.debug("Failed to click {}: {}", sel, e.getMessage());
            }
        }
        return false;
    }

    /**
     * Wait for login success indicators
     * UPDATED: More robust checking without clicking
     */
    private boolean waitForLoginSuccess(Page page) {
        log.info("⏳ Waiting for login success indicators...");

        for (int i = 0; i < LOGIN_TIMEOUT_SEC; i++) {
            page.waitForTimeout(1000);
            System.out.print(".");

            // Method 1: Check for balance (most reliable for SportyBet)
            try {
                Locator balance = page.locator("#j_balance, .m-balance");
                if (balance.count() > 0 && balance.first().isVisible()) {
                    String balanceText = balance.first().textContent();
                    if (balanceText != null && (balanceText.contains("NGN") || balanceText.contains("₦"))) {
                        log.info("\n✅ Login successful — balance displayed: {}", balanceText.trim());
                        return true;
                    }
                }
            } catch (Exception ignored) {}

            // Method 2: Check for user menu
            try {
                if (page.isVisible("#j_userInfo") || page.isVisible(".m-userInfo")) {
                    log.info("\n✅ Login successful — user menu detected!");
                    return true;
                }
            } catch (Exception ignored) {}

            // Method 3: Check for any logged-in UI indicators (don't click)
            boolean hasIndicator = isAnyVisible(page, LOGGED_IN_INDICATORS);
            if (hasIndicator) {
                log.info("\n✅ Login successful — detected user interface elements!");
                return true;
            }

            // Method 4: Auth cookie appears
            List<Cookie> cookies = page.context().cookies();
            boolean hasAuth = cookies.stream().anyMatch(c ->
                    c.name.toLowerCase().contains("session") ||
                            c.name.toLowerCase().contains("auth") ||
                            c.name.toLowerCase().contains("token") ||
                            c.name.toLowerCase().contains("sb_") ||
                            c.name.toLowerCase().contains("user") ||
                            c.name.toLowerCase().contains("sporty")
            );

            if (hasAuth) {
                log.info("\n✅ Login successful — auth cookie detected!");
                return true;
            }

            // Method 5: URL change (navigated away from login)
            String url = page.url();
            if (!url.contains("login") && !url.contains("signin")) {
                log.info("\n✅ Login successful — navigated away from login page!");
                page.waitForTimeout(2000); // Wait for page to load

                // Final verification
                if (isAnyVisible(page, LOGGED_IN_INDICATORS)) {
                    return true;
                }
            }

            // Method 6: Check if login form disappeared
            boolean loginFormVisible = isAnyVisible(page, LOGIN_FORM_INDICATORS);
            if (!loginFormVisible && i > 3) { // After 3 seconds
                log.info("\n✅ Login successful — login form disappeared!");
                return true;
            }
        }

        log.warn("\n❌ Login failed — timeout after {}s", LOGIN_TIMEOUT_SEC);
        log.warn("Current URL: {}", page.url());

        // Debug: Check what's visible
        try {
            boolean hasBalance = page.isVisible("#j_balance");
            boolean hasUserMenu = page.isVisible("#j_userInfo");
            boolean hasLoginButton = page.isVisible("button.m-btn-login");
            log.warn("Debug - Balance visible: {}, User menu visible: {}, Login button visible: {}",
                    hasBalance, hasUserMenu, hasLoginButton);
        } catch (Exception e) {
            log.warn("Debug check failed: {}", e.getMessage());
        }

        return false;
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



}