package com.mouse.bet.utils;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.mouse.bet.enums.BookMaker;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
public class MSportMarketSearchUtils {
    private static final BookMaker BOOK_MAKER = BookMaker.M_SPORT;

    public static Locator findAndExpandMarket(Page page, String targetMarket) {
        String method = "findAndExpandMarket(\"" + targetMarket + "\")";
        log.info("Entering {} – searching for market...", method);

        try {
            // Wait for market list
            page.locator(".m-market-list").waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(12_000));

            // Take screenshot of market content
            takeMarketScreenshot(page, "market-content-before-search");

            // Get all market titles + visibility + index
            var markets = page.evaluate("""
        () => {
            const items = document.querySelectorAll('.m-market-item');
            const result = [];
            items.forEach((item, index) => {
                const span = item.querySelector('.m-market-item--name span');
                if (span) {
                    const title = span.textContent.trim();
                    if (title) {
                        result.push({
                            title: title,
                            index: index,
                            visible: item.offsetParent !== null && getComputedStyle(item).display !== 'none'
                        });
                    }
                }
            });
            return result;
        }
        """);

            // Log all markets (as before)
            String allMarkets = markets.toString()
                    .replaceAll("[{}]", "")
                    .replaceAll(", ", " | ");
            log.info("Available markets ({} found): [ {} ]",
                    ((List<?>) markets).size(),
                    allMarkets.isEmpty() ? "NONE" : allMarkets);

            String targetLower = targetMarket.toLowerCase();

            // List of common "main" phrases that often get prefixed
            List<String> keyPhrases = List.of(
                    "point handicap", "game handicap", "total games", "total points",
                    "game winner", "set winner", "correct score", "total sets",
                    "total maps", "handicap", "over/under", "match winner", "1x2"
            );

            String matchedKeyPhrase = null;
            for (String phrase : keyPhrases) {
                if (targetLower.contains(phrase)) {
                    matchedKeyPhrase = phrase;
                    break;
                }
            }

            // Find matching market
            String matchedTitle = null;
            for (Object marketObj : (List<?>) markets) {
                @SuppressWarnings("unchecked")
                Map<String, Object> market = (Map<String, Object>) marketObj;
                String title = (String) market.get("title");
                Boolean visible = (Boolean) market.get("visible");

                if (!visible) continue;

                String titleLower = title.toLowerCase();
                boolean matches = matchedKeyPhrase != null
                        ? titleLower.contains(matchedKeyPhrase)
                        : titleLower.contains(targetLower);

                if (matches) {
                    matchedTitle = title;
                    log.info("MATCH FOUND → Market: '{}'", title);
                    break;
                }
            }

            if (matchedTitle == null) {
                log.error("Market containing '{}' NOT FOUND in available markets", targetMarket);

                // Take screenshot when market not found
                takeMarketScreenshot(page, "market-not-found-" + targetMarket.replaceAll("[^a-zA-Z0-9-]", "_"));

                // === NEW: Log all available betting options inside visible markets ===
                log.warn("Dumping all visible betting options for debugging...");

                var allOptions = page.evaluate("""
            () => {
                const options = [];
                document.querySelectorAll('.m-market-item').forEach(item => {
                    const marketTitleSpan = item.querySelector('.m-market-item--name span');
                    const marketTitle = marketTitleSpan ? marketTitleSpan.textContent.trim() : 'UNKNOWN MARKET';
                    
                    const isVisible = item.offsetParent !== null && getComputedStyle(item).display !== 'none';
                    if (!isVisible) return;

                    const optionElements = item.querySelectorAll('.m-outcome-item .m-outcome-item--name');
                    if (optionElements.length > 0) {
                        const optionTexts = Array.from(optionElements)
                            .map(el => el.textContent.trim())
                            .filter(text => text);
                        if (optionTexts.length > 0) {
                            options.push({
                                market: marketTitle,
                                selections: optionTexts
                            });
                        }
                    }
                });
                return options;
            }
            """);

                if (((List<?>) allOptions).isEmpty()) {
                    log.warn("No betting options found in any visible market (page may still be loading or empty)");
                } else {
                    log.warn("Available betting options by market:");
                    for (Object obj : (List<?>) allOptions) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> entry = (Map<String, Object>) obj;
                        String marketName = (String) entry.get("market");
                        List<String> selections = (List<String>) entry.get("selections");
                        log.warn("   → {}: [ {} ]", marketName, String.join(" | ", selections));
                    }
                }
                // === End of new logging ===

                return null;
            }

            // Build XPath for the matched market (unchanged)
            String marketXPath = String.format(
                    "//div[@class='m-market-item']" +
                            "[.//h1[@class='m-market-item--name']//span[normalize-space(text())=%s]]",
                    escapeXPath(matchedTitle)
            );

            Locator marketBlock = page.locator("xpath=" + marketXPath).first();

            if (marketBlock.count() == 0) {
                log.error("Market block NOT FOUND after match: {}", matchedTitle);
                return null;
            }

            // Expand logic (unchanged)
            Locator expandIcon = marketBlock.locator(".ms-icon-trangle.expanded");
            if (expandIcon.count() == 0) {
                log.info("Market '{}' is collapsed, expanding...", matchedTitle);
                marketBlock.locator(".m-market-item--header").click();
                randomHumanDelay(200, 400);
                page.waitForTimeout(300);
                if (marketBlock.locator(".ms-icon-trangle.expanded").count() > 0) {
                    log.info("✅ Market '{}' expanded successfully", matchedTitle);
                    // Take screenshot after successful expansion
                    takeMarketScreenshot(page, "market-expanded-" + matchedTitle.replaceAll("[^a-zA-Z0-9-]", "_"));
                } else {
                    log.warn("⚠️ Market '{}' may not have expanded", matchedTitle);
                }
            } else {
                log.info("Market '{}' is already expanded", matchedTitle);
            }

            return marketBlock;

        } catch (Exception e) {
            log.error("Exception in {}: {}", method, e.getMessage(), e);
            // Take screenshot on exception
            takeMarketScreenshot(page, "market-error-exception");
            return null;
        }
    }

    /**
     * Takes a screenshot of the market list area
     */
    private static void takeMarketScreenshot(Page page, String filenameSuffix) {
        try {
            Path screenshotDir = Paths.get("screenshots", "markets");
            Files.createDirectories(screenshotDir);

            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());

            String filename = String.format("%s-%s-%s.png", timestamp, BOOK_MAKER, filenameSuffix);
            Path screenshotPath = screenshotDir.resolve(filename);

            Locator marketList = page.locator(".m-market-list");
            if (marketList.count() > 0) {
                marketList.screenshot(new Locator.ScreenshotOptions()
                        .setPath(screenshotPath)
                        .setType(ScreenshotType.PNG));
                log.info("📸 Market screenshot saved: {}", screenshotPath);
            } else {
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(screenshotPath)
                        .setFullPage(true)
                        .setType(ScreenshotType.PNG));
                log.info("📸 Full page screenshot saved: {}", screenshotPath);
            }
        } catch (Exception e) {
            log.warn("Failed to take screenshot: {}", e.getMessage());
        }
    }

    // Detect market type based on market name and outcome pattern
    public static MarketType detectMarketType(String market, String outcome) {
        String marketLower = market.toLowerCase();
        String outcomeLower = outcome.toLowerCase();

        // Over/Under markets
        if (marketLower.contains("over/under") ||
                marketLower.contains("o/u") ||
                marketLower.contains("points o/u") ||
                marketLower.contains("total") ||
                marketLower.contains("total points") ||
                outcomeLower.startsWith("over ") ||
                outcomeLower.startsWith("under ")) {
            return MarketType.OVER_UNDER;
        }

        // Point Handicap markets
        if (marketLower.contains("handicap") ||
                marketLower.contains("spread") ||
                outcomeLower.matches("[+-]\\d+(\\.\\d+)?")) {
            return MarketType.POINT_HANDICAP;
        }


        // Both Teams to Score
        if (marketLower.contains("both teams") ||
                marketLower.contains("btts")) {
            return MarketType.BOTH_TEAMS_SCORE;
        }

        // Winner/Match Result (default for Home/Away/Draw)
        if (marketLower.contains("winner") ||
                marketLower.contains("match result") ||
                marketLower.contains("1x2")) {
            return MarketType.WINNER;
        }

        return MarketType.UNKNOWN;
    }

    // Select outcome based on market type
    public static Locator selectOutcomeByType(Locator marketBlock, MarketType marketType, String outcome) {
        switch (marketType) {
            case OVER_UNDER:
                return selectOverUnderOutcome(marketBlock, outcome);

            case POINT_HANDICAP:
                return selectHandicapOutcome(marketBlock, outcome);

            case WINNER:
            case BOTH_TEAMS_SCORE:
            default:
                return selectStandardOutcome(marketBlock, outcome);
        }
    }

    // Standard selection for Winner/Home/Away/Draw markets
    private static Locator selectStandardOutcome(Locator marketBlock, String outcome) {
        try {
            String cellXPath = String.format(
                    ".//div[contains(@class,'m-outcome') and contains(@class,'multiple') and not(contains(@class,'disabled'))]" +
                            "[.//div[@class='desc' and normalize-space(text())=%s]]",
                    escapeXPath(outcome)
            );

            Locator outcomeCell = marketBlock.locator("xpath=" + cellXPath).first();
            return outcomeCell.count() > 0 ? outcomeCell : null;
        } catch (Exception e) {
            log.error("Error selecting standard outcome: {}", e.getMessage());
            return null;
        }
    }

    // Selection for Over/Under markets (e.g., "Over 76.5", "Under 77.5")
    private static Locator selectOverUnderOutcome(Locator marketBlock, String outcome) {
        try {
            // Parse outcome: "Over 76.5" or "Under 77.5"
            String[] parts = outcome.split("\\s+", 2);
            if (parts.length != 2) {
                log.error("Invalid O/U outcome format: {}. Expected 'Over X' or 'Under X'", outcome);
                return null;
            }

            String type = parts[0].trim();        // "Over" or "Under"
            String lineValue = parts[1].trim();   // "76.5"

            log.debug("Looking for {} {} in O/U market", type, lineValue);

            // Find the row containing the line value
            Locator marketRow = marketBlock
                    .locator(".m-market-row.m-market-row")
                    .filter(new Locator.FilterOptions().setHasText(lineValue));

            if (marketRow.count() == 0) {
                log.error("Line value '{}' not found in O/U market", lineValue);
                return null;
            }

            // Determine which outcome to select (1 for Over, 2 for Under)
            // Structure: nth(0)=line descriptor, nth(1)=Over, nth(2)=Under
            int outcomeIndex = type.equalsIgnoreCase("Over") ? 1 : 2;

            Locator outcomeCell = marketRow.locator(".m-outcome").nth(outcomeIndex);

            if (outcomeCell.count() == 0) {
                log.error("Could not find {} outcome for line {}", type, lineValue);
                return null;
            }

            return outcomeCell;

        } catch (Exception e) {
            log.error("Error selecting O/U outcome '{}': {}", outcome, e.getMessage());
            return null;
        }
    }

    // Selection for Point Handicap markets (e.g., "+2.5", "-3.5", "Home +2.5", "Away -1.5")
    private static Locator selectHandicapOutcome(Locator marketBlock, String outcome) {
        try {
            log.debug("Selecting handicap outcome: {}", outcome);

            // MSport has TWO types of handicap markets:
            // Type 1: Point Handicap - .desc contains just value (e.g., "-1.5", "+2.5")
            //         Has column headers "Home" and "Away"
            // Type 2: Other Handicaps - .desc contains "Home +2.5" or "Away -1.5"
            //         Full team name in the desc

            // Determine which type by checking for column headers
            boolean hasColumnHeaders = marketBlock.locator(".m-market-row .m-title").count() > 0;

            if (hasColumnHeaders) {
                log.debug("Detected handicap market with column headers (Point Handicap type)");
                return selectPointHandicapWithColumns(marketBlock, outcome);
            } else {
                log.debug("Detected handicap market without column headers (standard type)");
                return selectStandardHandicap(marketBlock, outcome);
            }

        } catch (Exception e) {
            log.error("Error selecting handicap outcome '{}': {}", outcome, e.getMessage());
            return null;
        }
    }

    // Type 1: Point Handicap with column headers (Home | Away)
// Outcome format: "+2.5" or "Home +2.5"
// .desc contains only: "-1.5", "+2.5", etc.
    private static Locator selectPointHandicapWithColumns(Locator marketBlock, String outcomeInput) {
        try {
            // Extract handicap value
            String handicapValue = extractHandicapValue(outcomeInput);
            if (handicapValue == null) {
                log.error("Could not extract handicap value from: {}", outcomeInput);
                return null;
            }

            log.debug("Looking for handicap value: {}", handicapValue);

            // Get column headers to determine positions
            List<String> columnHeaders = marketBlock
                    .locator(".m-market-row .m-title")
                    .allTextContents()
                    .stream()
                    .map(String::trim)
                    .collect(Collectors.toList());

            log.debug("Column headers: {}", columnHeaders);

            // Extract team side from outcome if specified
            String teamSide = extractTeamSide(outcomeInput);

            // Find all rows and search for the handicap value
            Locator allRows = marketBlock.locator(".m-market-row.m-market-row");
            int rowCount = allRows.count();

            for (int i = 0; i < rowCount; i++) {
                Locator row = allRows.nth(i);
                Locator outcomes = row.locator(".m-outcome");
                int outcomeCount = outcomes.count();

                // Check each outcome in the row
                for (int j = 0; j < outcomeCount; j++) {
                    Locator outcome = outcomes.nth(j);

                    // Skip disabled outcomes
                    if (outcome.getAttribute("class").contains("disabled")) {
                        continue;
                    }

                    String desc = outcome.locator(".desc").textContent().trim();

                    // Check if this is our handicap value
                    if (desc.equals(handicapValue)) {
                        // If team side is specified, verify we're in the right column
                        if (teamSide != null && j < columnHeaders.size()) {
                            String columnHeader = columnHeaders.get(j);
                            if (!columnHeader.equalsIgnoreCase(teamSide)) {
                                continue; // Wrong column, keep searching
                            }
                        }

                        log.debug("Found handicap {} in row {} column {}", handicapValue, i, j);
                        return outcome;
                    }
                }
            }

            log.error("Could not find handicap outcome: {} with value: {}", outcomeInput, handicapValue);
            return null;

        } catch (Exception e) {
            log.error("Error in selectPointHandicapWithColumns: {}", e.getMessage());
            return null;
        }
    }

    // Type 2: Standard Handicap without column headers
// Outcome format: "Home +2.5" or "Away -1.5"
// .desc contains full text: "Home +2.5", "Away -1.5", etc.
    private static Locator selectStandardHandicap(Locator marketBlock, String outcome) {
        try {
            // For this type, the .desc should contain the exact outcome text
            // or a close match

            log.debug("Looking for standard handicap: {}", outcome);

            // Try exact match first
            String outcomeXPath = String.format(
                    ".//div[contains(@class,'m-outcome') and " +
                            "not(contains(@class,'disabled'))]" +
                            "[.//div[@class='desc' and normalize-space(text())=%s]]",
                    escapeXPath(outcome)
            );

            Locator exactMatch = marketBlock.locator("xpath=" + outcomeXPath).first();

            if (exactMatch.count() > 0) {
                log.debug("Found exact match for: {}", outcome);
                return exactMatch;
            }

            // If no exact match, try to find by handicap value and infer team
            String handicapValue = extractHandicapValue(outcome);
            if (handicapValue == null) {
                log.error("Could not extract handicap value from: {}", outcome);
                return null;
            }

            String teamSide = extractTeamSide(outcome);
            if (teamSide == null) {
                log.error("Could not determine team side from: {}", outcome);
                return null;
            }

            // Build a pattern to match "Home +2.5" or "Away -1.5" etc.
            String pattern = teamSide + "\\s+" + handicapValue.replace("+", "\\+").replace("-", "\\-");

            // Find outcomes matching the pattern
            Locator allOutcomes = marketBlock.locator(".m-outcome:not(.disabled)");
            int count = allOutcomes.count();

            for (int i = 0; i < count; i++) {
                Locator outcomeCell = allOutcomes.nth(i);
                String desc = outcomeCell.locator(".desc").textContent().trim();

                if (desc.matches("(?i).*" + pattern + ".*")) {
                    log.debug("Found handicap match: {}", desc);
                    return outcomeCell;
                }
            }

            log.error("Could not find standard handicap outcome: {}", outcome);
            return null;

        } catch (Exception e) {
            log.error("Error in selectStandardHandicap: {}", e.getMessage());
            return null;
        }
    }

    // Extract handicap value from outcome string (e.g., "Home +2.5" → "+2.5", "-1.5" → "-1.5")
    private static String extractHandicapValue(String outcome) {
        // Match pattern: +/- followed by number with optional decimal
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("([+-]\\d+(?:\\.\\d+)?)");
        java.util.regex.Matcher matcher = pattern.matcher(outcome);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    // Extract team side from outcome string (e.g., "Home +2.5" → "Home", "Away -1.5" → "Away")
    private static String extractTeamSide(String outcome) {
        String lowerOutcome = outcome.toLowerCase();

        // Check for explicit team names
        if (lowerOutcome.contains("home") || lowerOutcome.matches("^h\\s.*")) {
            return "Home";
        } else if (lowerOutcome.contains("away") || lowerOutcome.matches("^a\\s.*")) {
            return "Away";
        }

        // No explicit team side found
        return null;
    }

    // Check if outcome is disabled
    private boolean isOutcomeDisabled(Locator outcomeCell) {
        try {
            // Check for disabled class or lock icon
            return outcomeCell.getAttribute("class").contains("disabled") ||
                    outcomeCell.locator("i[aria-label='disabled']").count() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // Extract odds from outcome cell
    private String extractOdds(Locator outcomeCell, MarketType marketType) {
        try {
            Locator oddsElement = outcomeCell.locator("div.odds, .odds");
            if (oddsElement.count() == 0) {
                return null;
            }
            return oddsElement.textContent().trim();
        } catch (Exception e) {
            log.error("Error extracting odds: {}", e.getMessage());
            return null;
        }
    }

    // Click outcome with human-like behavior
    private boolean clickOutcome(Locator outcomeCell, String market, String outcome, String odds) {
        try {
            outcomeCell.scrollIntoViewIfNeeded();
            randomHumanDelay(100, 200);

            // Try standard click first
            try {
                outcomeCell.click(new Locator.ClickOptions().setTimeout(10000));
            } catch (Exception e) {
                log.warn("Direct click failed, trying JS click");
                outcomeCell.evaluate("el => el.click()");
            }

            log.info("CLICKED: {} → {} @ {}", market, outcome, odds);
            randomHumanDelay(300, 500);
            return true;

        } catch (Exception e) {
            log.error("Failed to click outcome: {}", e.getMessage());
            return false;
        }
    }

    // Log available outcomes for debugging
    private void logAvailableOutcomes(Locator marketBlock, MarketType marketType) {
        try {
            List<String> available;

            if (marketType == MarketType.OVER_UNDER || marketType == MarketType.POINT_HANDICAP) {
                // For O/U and Handicap, log line values and both outcomes
                available = marketBlock
                        .locator(".m-market-row.m-market-row")
                        .allTextContents()
                        .stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
            } else {
                // For standard markets, log outcome descriptions
                available = marketBlock
                        .locator(".m-outcome .desc")
                        .allTextContents()
                        .stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
            }

            log.warn("Available outcomes: {}", available);
        } catch (Exception e) {
            log.debug("Could not log available outcomes: {}", e.getMessage());
        }
    }

    private static String escapeXPath(String value) {
        if (!value.contains("'")) {
            return "'" + value + "'";
        } else if (!value.contains("\"")) {
            return "\"" + value + "\"";
        } else {
            return "concat('" + value.replace("'", "',\"'\",'") + "')";
        }
    }

    /**
     * HUMAN-LIKE DELAY (anti-detection)
     */
    private static void randomHumanDelay(long minMs, long maxMs) { //Todo
        try {
            long delay = minMs + ThreadLocalRandom.current().nextLong(maxMs - minMs + 1);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public enum MarketType {
        WINNER,           // Home/Away/Draw - button with outcome name as text
        OVER_UNDER,       // Over/Under with line values - has descriptor column
        POINT_HANDICAP,   // Handicap markets - similar structure to O/U
        BOTH_TEAMS_SCORE, // Yes/No markets
        UNKNOWN           // Fallback
    }




}
