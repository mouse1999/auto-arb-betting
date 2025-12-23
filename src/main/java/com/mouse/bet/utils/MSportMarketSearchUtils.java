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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
public class MSportMarketSearchUtils {
    private static final BookMaker BOOK_MAKER = BookMaker.M_SPORT;


    /**
     * Returns ALL matching market blocks (sorted by match score descending)
     * Useful when the same market title appears multiple times with different outcomes
     */
    public static List<MarketBlockResult> findAndExpandMarkets(Page page, String targetMarket) {
        String method = "findAndExpandMarkets(\"" + targetMarket + "\")";
        log.info("Entering {} – searching for all markets with exact title match...", method);

        List<MarketBlockResult> matchedBlocks = new ArrayList<>();

        try {
            String targetLower = targetMarket.toLowerCase().trim();

            // 1. Ensure Game tab is active if target contains "game"
            boolean needsGameTab = targetLower.contains("game");
            if (needsGameTab) {
                log.info("Target contains 'game' – ensuring Game tab is active...");
                Locator gameTab = page.locator("ul.snap-nav li.m-sub-nav-item")
                        .locator("span.m-group:has-text(\"Game\")")
                        .locator("..");

                if (gameTab.count() > 0) {
                    String classes = gameTab.getAttribute("class");
                    boolean isActive = classes != null && classes.contains("active");
                    if (!isActive) {
                        log.info("Clicking 'Game' tab...");
                        gameTab.click();
                        page.waitForFunction("""
                        () => {
                            const list = document.querySelector('.m-market-list');
                            return list && list.querySelectorAll('.m-market-item').length > 3;
                        }
                        """, null, new Page.WaitForFunctionOptions().setTimeout(15000));
                        randomHumanDelay(300, 600);
                        log.info("Game tab switched + markets reloaded");
                    }
                } else {
                    log.warn("'Game' tab locator not found – proceeding anyway");
                }
            }

            // 2. Wait for market list to be visible
            page.locator(".m-market-list")
                    .waitFor(new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(10000));

            // 3. Find ALL markets with exact title match (case-insensitive)
            String markerPrefix = "pw-market-" + System.nanoTime();

            String jsFindAllExact = """
            (args) => {
                const { target, prefix } = args;
                const targetLower = target.toLowerCase().trim();
                const items = document.querySelectorAll('.m-market-item');
                const matches = [];

                items.forEach((item, index) => {
                    const span = item.querySelector('.m-market-item--name span');
                    if (!span) return;

                    const title = span.textContent.trim();
                    const titleLower = title.toLowerCase().trim();

                    const visible = item.offsetParent !== null &&
                                    getComputedStyle(item).display !== 'none' &&
                                    getComputedStyle(item).visibility !== 'hidden';

                    if (visible && titleLower === targetLower) {
                        const markerId = prefix + '-' + index;
                        item.setAttribute('data-pw-market-marker', markerId);
                        matches.push({
                            index,
                            title,
                            markerId
                        });
                    }
                });

                return { matches };
            }
            """;

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) page.evaluate(jsFindAllExact,
                    Map.of("target", targetMarket, "prefix", markerPrefix));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");

            if (matches.isEmpty()) {
                log.info("No markets found with exact title: '{}'", targetMarket);
                return matchedBlocks; // empty list
            }

            log.info("Found {} market(s) with exact title '{}'", matches.size(), targetMarket);

            // 4. Process each exact match
            for (Map<String, Object> match : matches) {
                String matchedTitle = (String) match.get("title");
                String markerId = (String) match.get("markerId");

                Locator marketBlock = page.locator("[data-pw-market-marker='" + markerId + "']");

                if (marketBlock.count() == 0) {
                    log.warn("Marked market block disappeared for title '{}'", matchedTitle);
                    continue;
                }

                // Expand if not already expanded
                Locator expandIcon = marketBlock.locator(".ms-icon-trangle.expanded");
                if (expandIcon.count() == 0) {
                    log.info("Expanding market '{}'...", matchedTitle);
                    marketBlock.locator(".m-market-item--header").click();
                    randomHumanDelay(200, 400);
                    try {
                        marketBlock.locator(".ms-icon-trangle.expanded")
                                .waitFor(new Locator.WaitForOptions().setTimeout(3000));
                        log.info("Market '{}' expanded successfully", matchedTitle);
                    } catch (Exception e) {
                        log.warn("Expansion not confirmed for market '{}'", matchedTitle);
                    }
                } else {
                    log.info("Market '{}' already expanded", matchedTitle);
                }

                // Add to results (no marker cleanup needed yet – kept for refresh later if needed)
                matchedBlocks.add(new MarketBlockResult(marketBlock, markerId, matchedTitle));
            }

            log.info("Returning {} expanded market block(s) with exact title '{}'", matchedBlocks.size(), targetMarket);
            return matchedBlocks;

        } catch (Exception e) {
            log.error("Exception in {}: {}", method, e.getMessage(), e);
            takeMarketScreenshot(page, "market-error-exception-" + sanitizeFilename(targetMarket));
            return matchedBlocks; // return whatever was found before error
        }
    }
    // Utility methods (kept mostly unchanged, just cleaned up)

    private static void takeMarketScreenshot(Page page, String suffix) {
        try {
            Path dir = Paths.get("screenshots", "markets");
            Files.createDirectories(dir);
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
            String filename = String.format("%s-%s-%s.png", timestamp, BOOK_MAKER, suffix);
            Path path = dir.resolve(filename);

            Locator marketList = page.locator(".m-market-list");
            if (marketList.count() > 0) {
                marketList.screenshot(new Locator.ScreenshotOptions().setPath(path).setType(ScreenshotType.PNG));
            } else {
                page.screenshot(new Page.ScreenshotOptions().setPath(path).setFullPage(true).setType(ScreenshotType.PNG));
            }
            log.info("📸 Screenshot saved: {}", path);
        } catch (Exception e) {
            log.warn("Failed to take screenshot: {}", e.getMessage());
        }
    }

    private static void dumpAllVisibleMarketsForDebug(Page page) {
        try {
            var markets = page.evaluate("""
                    () => {
                        const items = document.querySelectorAll('.m-market-item');
                        const result = [];
                        items.forEach(item => {
                            const span = item.querySelector('.m-market-item--name span');
                            if (span) {
                                const title = span.textContent.trim();
                                const visible = item.offsetParent !== null && getComputedStyle(item).display !== 'none';
                                if (visible && title) result.push(title);
                            }
                        });
                        return result;
                    }
                    """);
            log.warn("All visible markets for debug: {}", markets);
        } catch (Exception e) {
            log.warn("Failed to dump markets for debug: {}", e.getMessage());
        }
    }

    private static String sanitizeFilename(String s) {
        return s.replaceAll("[^a-zA-Z0-9-]", "_");
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
    public static Locator selectOutcomeByType(List<MarketBlockResult> marketBlock,
                                              MarketType marketType, String outcome, Page page) {
        switch (marketType) {
            case OVER_UNDER:
                return selectOverUnderOutcome(marketBlock, outcome, page);

            case POINT_HANDICAP:
                return selectHandicapOutcome(marketBlock, outcome, page);

            case WINNER:
            case BOTH_TEAMS_SCORE:
            default:
                return null; //selectStandardOutcome(marketBlock, outcome, page);
        }
    }

    // Standard selection for Winner/Home/Away/Draw markets
    /**
     * FAST: Select standard outcome (e.g., Home, Away, Draw, Yes, No)
     * Uses efficient text filtering instead of slow XPath.
     */
    private static Locator selectStandardOutcome(Locator marketBlock, String outcome) {
        try {
            // Fastest approach: use Playwright's built-in text filtering
            Locator outcomeCell = marketBlock
                    .locator(".m-outcome.multiple:not(.disabled)")
                    .filter(new Locator.FilterOptions().setHasText(outcome))
                    .first();

            if (outcomeCell.count() > 0) {
                return outcomeCell;
            }

            // Fallback: case-insensitive partial match (in case of extra spaces or formatting)
            Locator allOutcomes = marketBlock.locator(".m-outcome.multiple:not(.disabled)");
            int count = allOutcomes.count();
            for (int i = 0; i < count; i++) {
                Locator cell = allOutcomes.nth(i);
                String descText = cell.locator(".desc").textContent().trim();
                if (descText.equalsIgnoreCase(outcome.trim())) {
                    return cell;
                }
            }

            log.warn("Standard outcome '{}' not found in market (tried exact and case-insensitive)", outcome);
            return null;

        } catch (Exception e) {
            log.error("Error selecting standard outcome '{}': {}", outcome, e.getMessage());
            return null;
        }
    }

    private static Locator selectOverUnderOutcome(List<MarketBlockResult> marketResults,
                                                  String outcome,
                                                  Page page) {
        if (marketResults == null || marketResults.isEmpty()) {
            log.warn("No market blocks provided for O/U outcome '{}'", outcome);
            return null;
        }

        String[] parts = outcome.split("\\s+", 2);
        if (parts.length != 2) {
            log.error("Invalid O/U format: '{}'. Expected 'Over X' or 'Under X'", outcome);
            return null;
        }

        String type = parts[0].trim();          // "Over" or "Under"
        String lineValue = parts[1].trim();     // "14.5"

        String expectedFullText = type + " " + lineValue;
        String expectedNorm = expectedFullText
//                .replace('½', '.5')
                .replace("(", "")
                .replace(")", "")
                .trim()
                .toLowerCase();

        String typeLower = type.toLowerCase();

        log.debug("Searching for O/U: '{}' (normalized: {})", outcome, expectedNorm);

        // Use only the first market block — all others are duplicates
        MarketBlockResult result = marketResults.get(0);
        log.debug("Using market block: '{}'", result.title);

        try {
            Locator freshBlock = result.refresh(page);
            if (freshBlock.count() == 0) {
                log.warn("Market block disappeared");
                return null;
            }

            String baseMarker = "pw-ou-" + System.nanoTime();

            String jsFindAndMark = """
        (el, args) => {
            const expectedNorm = args.expectedNorm || '';
            const typeLower = args.typeLower || '';
            const baseMarker = args.baseMarker || '';

            const available = [];
            let matchedMarkerId = null;

            const rows = el.querySelectorAll('.m-market-row');

            for (const row of rows) {
                // === LAYOUT 1: 3-column (Point O/U) — Line in middle, Over left, Under right ===
                const lineSpan = row.querySelector('.m-outcome-desc span');
                if (lineSpan && lineSpan.textContent) {
                    const lineText = lineSpan.textContent.trim();
                    const lineNorm = lineText.replace('½', '.5')
                                             .replace(/[()]/g, '')
                                             .trim()
                                             .toLowerCase();

                    if (lineNorm === expectedNorm || lineNorm.includes(expectedNorm.replace(/[^0-9.]/g, ''))) {
                        const outcomeCells = row.querySelectorAll('.m-outcome:not(.m-outcome-desc)');
                        if (outcomeCells.length >= 2) {
                            const overCell = outcomeCells[0];
                            const underCell = outcomeCells[1];

                            const checkCell = (cell, label) => {
                                if (!cell || cell.classList.contains('disabled')) return;
                                available.push({ layout: '3-column', line: lineText, side: label });
                                if (label.toLowerCase() === typeLower) {
                                    const markerId = baseMarker + '-match';
                                    cell.setAttribute('data-pw-marker', markerId);
                                    matchedMarkerId = markerId;
                                }
                            };

                            checkCell(overCell, 'Over');
                            checkCell(underCell, 'Under');

                            if (matchedMarkerId) return { found: true, markerId: matchedMarkerId, available };
                        }
                    }
                    continue; // skip 2-column check if this is 3-column row
                }

                // === LAYOUT 2: 2-column (Game total points) — full text in .desc ===
                const descEls = row.querySelectorAll('.m-outcome .desc');
                for (const descEl of descEls) {
                    const text = descEl.textContent?.trim();
                    if (!text) continue;

                    const textNorm = text.replace('½', '.5')
                                        .replace(/[()]/g, '')
                                        .trim()
                                        .toLowerCase();

                    const cell = descEl.closest('.m-outcome');
                    if (!cell) continue;

                    const disabled = cell.classList.contains('disabled');
                    const odds = cell.querySelector('.odds')?.textContent.trim() || 'N/A';

                    available.push({ layout: '2-column', text, odds, disabled, textNorm });

                    if (disabled) continue;

                    // Exact match on full normalized text
                    if (textNorm === expectedNorm) {
                        const markerId = baseMarker + '-match';
                        cell.setAttribute('data-pw-marker', markerId);
                        matchedMarkerId = markerId;
                        return { found: true, markerId: matchedMarkerId, available };
                    }
                }
            }

            return { found: false, markerId: null, available };
        }
        """;

            @SuppressWarnings("unchecked")
            Map<String, Object> jsResult = (Map<String, Object>) freshBlock.evaluate(jsFindAndMark,
                    Map.of("expectedNorm", expectedNorm,
                            "typeLower", typeLower,
                            "baseMarker", baseMarker));

            Boolean found = (Boolean) jsResult.get("found");
            String matchedMarkerId = (String) jsResult.get("markerId");

            log.debug("JS Result - found: {}, markerId: {}", found, matchedMarkerId);

            if (found != null && found && matchedMarkerId != null) {
                Locator outcomeCell = page.locator("[data-pw-marker='" + matchedMarkerId + "']").first();

                try {
                    // Ensure Playwright sees the marked element
                    outcomeCell.waitFor(new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.ATTACHED)
                            .setTimeout(5000));
                } catch (Exception e) {
                    log.warn("Timeout waiting for O/U marked element: {}", e.getMessage());
                    return null;
                }

                if (outcomeCell.count() > 0) {
                    log.info("SUCCESS: Found and located O/U '{}' using marker '{}' in block '{}'",
                            outcome, matchedMarkerId, result.title);

                    // Get stable locator using position instead of marker
                    String jsGetPosition = """
                (el) => {
                    const row = el.closest('.m-market-row');
                    const allRows = Array.from(document.querySelectorAll('.m-market-row'));
                    const rowIndex = allRows.indexOf(row);
                    const outcomes = row.querySelectorAll('.m-outcome');
                    const colIndex = Array.from(outcomes).indexOf(el);
                    return { rowIndex, colIndex };
                }
                """;

                    @SuppressWarnings("unchecked")
                    Map<String, Object> position = (Map<String, Object>) outcomeCell.evaluate(jsGetPosition);
                    Integer rowIdx = (Integer) position.get("rowIndex");
                    Integer colIdx = (Integer) position.get("colIndex");

                    log.debug("O/U outcome position: row {}, col {}", rowIdx, colIdx);

                    // Cleanup marker
                    outcomeCell.evaluate("el => el.removeAttribute('data-pw-marker')");

                    // Return stable locator using position
                    Locator stableOutcome = page.locator(".m-market-row").nth(rowIdx)
                            .locator(".m-outcome").nth(colIdx);

                    return stableOutcome;
                } else {
                    log.warn("JS marked cell with '{}' but Playwright could not find it", matchedMarkerId);
                }
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> available = (List<Map<String, Object>>) jsResult.get("available");
                log.debug("O/U '{}' not found. Seen {} outcomes in block '{}'", outcome, available.size(), result.title);

                if (!available.isEmpty()) {
                    log.debug("Sample available outcomes: {}", available.stream().limit(5).collect(Collectors.toList()));
                }
            }

        } catch (Exception e) {
            log.warn("Error searching O/U '{}' in block '{}': {}", outcome, result.title, e.toString());
        }

        log.warn("O/U outcome '{}' NOT FOUND", outcome);
        return null;
    }



    // Selection for Point Handicap markets (e.g., "+2.5", "-3.5", "Home +2.5", "Away -1.5")
    private static Locator selectHandicapOutcome(List<MarketBlockResult> marketBlock,
                                                 String outcome, Page page) {
        try {
            log.debug("Selecting handicap outcome: {}", outcome);


            // MSport has TWO types of handicap markets:
            // Type 1: Point Handicap - .desc contains just value (e.g., "-1.5", "+2.5")
            //         Has column headers "Home" and "Away"
            // Type 2: Other Handicaps - .desc contains "Home +2.5" or "Away -1.5"
            //         Full team name in the desc

            // Determine which type by checking for column headers
//            boolean hasColumnHeaders = marketBlock.locator(".m-market-row .m-title").count() > 0;
//
//            if (hasColumnHeaders) {
                log.debug("Detected handicap market with column headers (Point Handicap type)");
                return selectPointHandicapWithColumns(marketBlock, outcome, page);
//            } else {
//                log.debug("Detected handicap market without column headers (standard type)");
//                return selectStandardHandicap(marketBlock, outcome);
//            }

        } catch (Exception e) {
            log.error("Error selecting handicap outcome '{}': {}", outcome, e.getMessage());
            return null;
        }
    }

    /**
     * ULTRA-FAST Point Handicap with columns + FULL DEBUG DUMP on failure
     */
    /**
     * Point Handicap selector - finds handicap by value in .desc and team column
     */
    /**
     * SUPER FAST Point Handicap selector - uses ONLY first market block
     */
    /**
     * SUPER FAST Point Handicap selector - uses ONLY first market block
     */
    /**
     * SUPER FAST Point Handicap selector - uses ONLY first market block
     */
    /**
     * SUPER FAST Point Handicap selector - uses ONLY first market block
     */
    /**
     * SUPER FAST Point Handicap selector - uses ONLY first market block
     */
    private static Locator selectPointHandicapWithColumns(List<MarketBlockResult> marketResults,
                                                          String outcomeInput,
                                                          Page page) {
        if (marketResults == null || marketResults.isEmpty()) {
            log.warn("No market blocks provided for handicap '{}'", outcomeInput);
            return null;
        }

        String handicapValue = extractHandicapValue(outcomeInput);
        if (handicapValue == null) {
            log.error("Could not extract handicap value from: {}", outcomeInput);
            return null;
        }

        String teamSide = extractTeamSide(outcomeInput);

        // Normalize handicap (handles +2.5, 2.5, +2½, (+2.5), etc.)
        String normalizedHandicap = handicapValue
//                .replace('½', '.5')
                .replace("(", "")
                .replace(")", "")
                .trim();

        log.debug("Searching for handicap: '{}' (normalized: '{}'), team: {}",
                outcomeInput, normalizedHandicap, teamSide != null ? teamSide : "any");

        // Use ONLY the first market block — all duplicates have same content
        MarketBlockResult result = marketResults.get(0);
        log.debug("Using market block: '{}'", result.title);

        try {
            Locator freshBlock = result.refresh(page);
            if (freshBlock.count() == 0) {
                log.warn("Market block disappeared");
                return null;
            }

            String baseMarker = "pw-hcap-" + System.nanoTime();

            String jsFindAndMark = """
        (el, args) => {
            const { handicap, team, baseMarker } = args;
            const allRows = el.querySelectorAll('.m-market-row');

            let headers = [];
            let headerRow = null;
            let targetColIndex = null;

            // Find header row (has multiple .m-title elements with "Home", "Away")
            for (let r of allRows) {
                const titles = r.querySelectorAll('.m-title');
                if (titles.length > 1) {
                    headerRow = r;
                    headers = Array.from(titles).map(t => t.textContent.trim().toLowerCase());
                    
                    // Determine target column if team specified
                    if (team) {
                        const teamLower = team.toLowerCase();
                        headers.forEach((h, idx) => {
                            if (h === teamLower || 
                                (teamLower === 'home' && (h.includes('1') || h === 'home')) ||
                                (teamLower === 'away' && (h.includes('2') || h === 'away'))) {
                                targetColIndex = idx;
                            }
                        });
                    }
                    break;
                }
            }

            const dataRows = headerRow ? Array.from(allRows).filter(r => r !== headerRow) : Array.from(allRows);
            
            let matchedMarkerId = null;
            const available = [];

            // Search all data rows for specific outcome
            dataRows.forEach((row, rowIdx) => {
                const outcomes = row.querySelectorAll('.m-outcome');
                
                outcomes.forEach((cell, colIdx) => {
                    const descEl = cell.querySelector('.desc');
                    if (!descEl) return;

                    const descText = descEl.textContent.trim();
                    const normDesc = descText.replace('½', '.5')
                                             .replace(/[()]/g, '')
                                             .trim();

                    const disabled = cell.classList.contains('disabled');
                    const oddsEl = cell.querySelector('.odds');
                    const odds = oddsEl ? oddsEl.textContent.trim() : 'N/A';
                    const header = colIdx < headers.length ? headers[colIdx] : '';

                    available.push({ 
                        row: rowIdx, 
                        col: colIdx, 
                        desc: descText, 
                        normalized: normDesc,
                        header, 
                        odds, 
                        disabled 
                    });

                    // Skip if already found or disabled
                    if (matchedMarkerId || disabled) return;

                    // Match handicap value
                    if (normDesc !== handicap) return;

                    // If team specified, check column
                    if (targetColIndex !== null && colIdx !== targetColIndex) return;

                    // FOUND: mark this cell
                    const markerId = baseMarker + '-match';
                    cell.setAttribute('data-pw-marker', markerId);
                    matchedMarkerId = markerId;
                });
            });

            return {
                found: matchedMarkerId !== null,
                markerId: matchedMarkerId,
                headers: headers,
                targetColumn: targetColIndex,
                available: available
            };
        }
        """;

            @SuppressWarnings("unchecked")
            Map<String, Object> jsResult = (Map<String, Object>) freshBlock.evaluate(jsFindAndMark,
                    Map.of("handicap", normalizedHandicap,
                            "team", teamSide != null ? teamSide.toLowerCase() : "",
                            "baseMarker", baseMarker));

            Boolean found = (Boolean) jsResult.get("found");
            String matchedMarkerId = (String) jsResult.get("markerId");

            log.debug("JS Result - found: {}, markerId: {}", found, matchedMarkerId);

            if (found != null && found && matchedMarkerId != null) {
                // Search from page root since marker might be deep in DOM
                Locator outcomeCell = page.locator("[data-pw-marker='" + matchedMarkerId + "']").first();

                log.debug("Searching for marker: {}", matchedMarkerId);
                log.debug("Initial count: {}", outcomeCell.count());

                try {
                    // Ensure Playwright sees the marked element
                    outcomeCell.waitFor(new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.ATTACHED)
                            .setTimeout(5000));

                    log.debug("After wait, count: {}", outcomeCell.count());
                } catch (Exception e) {
                    log.warn("Timeout waiting for marked element '{}': {}", matchedMarkerId, e.getMessage());
                    // Try to see if marker exists in DOM
                    String markerCheck = (String) page.evaluate("() => { const el = document.querySelector('[data-pw-marker=\"" + matchedMarkerId + "\"]'); return el ? 'EXISTS' : 'NOT FOUND'; }");
                    log.warn("Marker check in DOM: {}", markerCheck);
                    return null;
                }

                if (outcomeCell.count() > 0) {
                    @SuppressWarnings("unchecked")
                    List<String> headers = (List<String>) jsResult.get("headers");
                    Integer targetCol = (Integer) jsResult.get("targetColumn");

                    log.info("SUCCESS: Found handicap '{}' in block '{}', headers: {}, target col: {}",
                            outcomeInput, result.title, headers, targetCol);

                    // Get stable locator using position instead of marker
                    String jsGetPosition = """
                (el) => {
                    const row = el.closest('.m-market-row');
                    const allRows = Array.from(document.querySelectorAll('.m-market-row'));
                    const rowIndex = allRows.indexOf(row);
                    const outcomes = row.querySelectorAll('.m-outcome');
                    const colIndex = Array.from(outcomes).indexOf(el);
                    return { rowIndex, colIndex };
                }
                """;

                    @SuppressWarnings("unchecked")
                    Map<String, Object> position = (Map<String, Object>) outcomeCell.evaluate(jsGetPosition);
                    Integer rowIdx = (Integer) position.get("rowIndex");
                    Integer colIdx = (Integer) position.get("colIndex");

                    log.debug("Outcome position: row {}, col {}", rowIdx, colIdx);

                    // Cleanup marker
                    outcomeCell.evaluate("el => el.removeAttribute('data-pw-marker')");

                    // Return stable locator using position
                    Locator stableOutcome = page.locator(".m-market-row").nth(rowIdx)
                            .locator(".m-outcome").nth(colIdx);

                    return stableOutcome;
                } else {
                    log.warn("JS marked cell but Playwright could not find it");
                }
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> available = (List<Map<String, Object>>) jsResult.get("available");
                @SuppressWarnings("unchecked")
                List<String> headers = (List<String>) jsResult.get("headers");

                log.debug("Handicap '{}' not found in block '{}', headers: {}, found {} outcomes",
                        outcomeInput, result.title, headers, available.size());

                if (!available.isEmpty()) {
                    log.debug("Sample outcomes: {}", available.stream().limit(3).collect(Collectors.toList()));
                }
            }

        } catch (Exception e) {
            log.warn("Error searching handicap '{}' in block '{}': {}", outcomeInput, result.title, e.getMessage());
        }

        log.warn("Handicap '{}' NOT FOUND", outcomeInput);
        return null;
    }
    /**
     * ULTRA-FAST: Select handicap outcome in markets WITHOUT column headers
     * (e.g., .desc contains "Home +2.5", "Away -1.5")
     * Uses single JS evaluation for maximum speed.
     */
    private static Locator selectStandardHandicap(List<MarketBlockResult> marketBlock, String outcome, Page page) {
//        try {
//            log.debug("Looking for standard handicap outcome: {}", outcome);
//
//            String handicapValue = extractHandicapValue(outcome);
//            if (handicapValue == null) {
//                log.error("Could not extract handicap value from: {}", outcome);
//                return null;
//            }
//
//            String teamSide = extractTeamSide(outcome);
//            if (teamSide == null) {
//                log.error("Could not determine team side (Home/Away) from: {}", outcome);
//                return null;
//            }
//
//            String normalizedTeam = teamSide.toLowerCase(); // "home" or "away"
//            String cleanValue = handicapValue.trim();
//
//            log.debug("Searching for '{}' with value '{}'", teamSide, cleanValue);
//
//            String jsScript = """
//                (args) => {
//                    const { teamLower, value } = args;
//                    const outcomes = document.querySelectorAll('.m-outcome:not(.disabled)');
//
//                    for (let i = 0; i < outcomes.length; i++) {
//                        const el = outcomes[i];
//                        const descEl = el.querySelector('.desc');
//                        if (!descEl) continue;
//
//                        let descText = descEl.textContent.trim();
//                        let descLower = descText.toLowerCase();
//
//                        // 1. Try exact match first (case-insensitive)
//                        if (descLower === args.fullOutcomeLower) {
//                            return i;
//                        }
//
//                        // 2. Flexible match: contains team name + handicap value
//                        // Handles: "Home +2.5", "home+2.5", "Home+ 2.5", etc.
//                        if (descLower.includes(teamLower) && descLower.includes(value.toLowerCase())) {
//                            return i;
//                        }
//                    }
//                    return null;
//                }
//                """;
//
//            String fullOutcomeLower = outcome.trim().toLowerCase();
//
//            Object result = marketBlock.evaluate(jsScript, Map.of(
//                    "teamLower", normalizedTeam,
//                    "value", cleanValue,
//                    "fullOutcomeLower", fullOutcomeLower
//            ));
//
//            if (result == null) {
//                log.warn("Standard handicap outcome '{}' not found (team: {}, value: {})",
//                        outcome, teamSide, handicapValue);
//                return null;
//            }
//
//            int index = (Integer) result;
//
//            Locator outcomeCell = marketBlock
//                    .locator(".m-outcome:not(.disabled)")
//                    .nth(index);
//
//            String foundText = outcomeCell.locator(".desc").textContent().trim();
//            log.debug("Found standard handicap match: '{}' → '{}'", outcome, foundText);
//
//            return outcomeCell;
//
//        } catch (Exception e) {
//            log.error("Error in selectStandardHandicap for '{}': {}", outcome, e.getMessage());
//            return null;
//        }
        return null;
    }

    // Extract handicap value from outcome string (e.g., "Home +2.5" → "+2.5", "-1.5" → "-1.5")
    private static String extractHandicapValue(String outcome) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("([+-]?\\d+(?:\\.\\d+)?)");
        java.util.regex.Matcher matcher = pattern.matcher(outcome);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String extractTeamSide(String outcome) {
        String lower = outcome.toLowerCase().trim();
        if (lower.contains("home") || lower.startsWith("h ") || lower.matches("^h[\\+\\-].*")) {
            return "Home";
        }
        if (lower.contains("away") || lower.startsWith("a ") || lower.matches("^a[\\+\\-].*")) {
            return "Away";
        }
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


    public static class MarketBlockResult {
        public final Locator block;
        public final String markerId;
        public final String title;

        public MarketBlockResult(Locator block, String markerId, String title) {
            this.block = block;
            this.markerId = markerId;
            this.title = title;
        }

        // Refresh if needed
        public Locator refresh(Page page) {
            return page.locator("[data-pw-market-marker='" + markerId + "']").first();
        }
    }




}
