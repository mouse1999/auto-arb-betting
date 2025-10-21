package com.mouse.bet.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SportyMarketType Tests")
public class SportyMarketTypeTest {


    @Test
    @DisplayName("fromProviderKey_validMatchOddsHome_returnsMatchOddsHome")
    void fromProviderKey_validMatchOddsHome_returnsMatchOddsHome() {
        String providerKey = "1:HOME";

        SportyMarketType result = SportyMarketType.fromProviderKey(providerKey);

        assertEquals(SportyMarketType.MATCH_ODDS_HOME, result);
        assertEquals("HOME", result.getNormalizedName());
        assertEquals(MarketCategory.MATCH_RESULT, result.getCategory());
    }

    @Test
    @DisplayName("fromProviderKey_validOverUnder25Over_returnsOverUnder25Over")
    void fromProviderKey_validOverUnder25Over_returnsOverUnder25Over() {
        String providerKey = "18:total=2.5:OVER_2.5";

        SportyMarketType result = SportyMarketType.fromProviderKey(providerKey);

        assertEquals(SportyMarketType.OVER_UNDER_2_5_OVER, result);
        assertEquals("18", result.getMarketId());
        assertEquals("total=2.5", result.getSpecifier());
    }

    @Test
    @DisplayName("fromProviderKey_validAsianHandicapWithSpecifier_returnsCorrectHandicap")
    void fromProviderKey_validAsianHandicapWithSpecifier_returnsCorrectHandicap() {
        String providerKey = "16:hcp=-1.5:HOME_(-1.5)";

        SportyMarketType result = SportyMarketType.fromProviderKey(providerKey);

        assertEquals(SportyMarketType.ASIAN_HANDICAP_HOME_MINUS_1_5, result);
        assertEquals("hcp=-1.5", result.getSpecifier());
        assertEquals(MarketCategory.ASIAN_HANDICAP_FULLTIME, result.getCategory());
    }

    @Test
    @DisplayName("fromProviderKey_invalidKey_throwsIllegalArgumentException")
    void fromProviderKey_invalidKey_throwsIllegalArgumentException() {
        String invalidKey = "999:INVALID_MARKET";

        assertThrows(IllegalArgumentException.class, () -> {
            SportyMarketType.fromProviderKey(invalidKey);
        });
    }

    @Test
    @DisplayName("fromProviderKey_nullKey_throwsNullPointerException")
    void fromProviderKey_nullKey_throwsNullPointerException() {
        assertThrows(IllegalArgumentException.class, () -> {
            SportyMarketType.fromProviderKey(null);
        });
    }

    @Test
    @DisplayName("fromProviderKey_emptyString_throwsIllegalArgumentException")
    void fromProviderKey_emptyString_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> {
            SportyMarketType.fromProviderKey("");
        });
    }


    @Test
    @DisplayName("safeFromProviderKey_validKey_returnsOptionalWithValue")
    void safeFromProviderKey_validKey_returnsOptionalWithValue() {
        String providerKey = "29:BOTH_TEAMS_TO_SCORE_YES";

        Optional<SportyMarketType> result = SportyMarketType.safeFromProviderKey(providerKey);

        assertTrue(result.isPresent());
        assertEquals(SportyMarketType.BTTS_YES, result.get());
    }

    @Test
    @DisplayName("safeFromProviderKey_invalidKey_returnsEmptyOptional")
    void safeFromProviderKey_invalidKey_returnsEmptyOptional() {
        String invalidKey = "999:INVALID";

        Optional<SportyMarketType> result = SportyMarketType.safeFromProviderKey(invalidKey);

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("safeFromProviderKey_nullKey_returnsEmptyOptional")
    void safeFromProviderKey_nullKey_returnsEmptyOptional() {
        Optional<SportyMarketType> result = SportyMarketType.safeFromProviderKey(null);

        assertFalse(result.isPresent());
    }


    @Test
    @DisplayName("isKnownMarket_validMatchResult_returnsTrue")
    void isKnownMarket_validMatchResult_returnsTrue() {
        String providerKey = "1:DRAW";

        boolean result = SportyMarketType.isKnownMarket(providerKey);

        assertTrue(result);
    }

    @Test
    @DisplayName("isKnownMarket_validDoubleChance_returnsTrue")
    void isKnownMarket_validDoubleChance_returnsTrue() {
        String providerKey = "10:HOME_OR_DRAW";

        boolean result = SportyMarketType.isKnownMarket(providerKey);

        assertTrue(result);
    }

    @Test
    @DisplayName("isKnownMarket_invalidKey_returnsFalse")
    void isKnownMarket_invalidKey_returnsFalse() {
        String invalidKey = "999:UNKNOWN";

        boolean result = SportyMarketType.isKnownMarket(invalidKey);

        assertFalse(result);
    }

    @Test
    @DisplayName("isKnownMarket_nullKey_returnsFalse")
    void isKnownMarket_nullKey_returnsFalse() {
        boolean result = SportyMarketType.isKnownMarket(null);

        assertFalse(result);
    }


    @Test
    @DisplayName("getCategoryForProviderKey_validOverUnder_returnsOverUnderTotalCategory")
    void getCategoryForProviderKey_validOverUnder_returnsOverUnderTotalCategory() {
        String providerKey = "18:total=1.5:OVER_1.5";

        Optional<MarketCategory> result = SportyMarketType.getCategoryForProviderKey(providerKey);

        assertTrue(result.isPresent());
        assertEquals(MarketCategory.OVER_UNDER_TOTAL, result.get());
    }

    @Test
    @DisplayName("getCategoryForProviderKey_validBTTS_returnsBTTSCategory")
    void getCategoryForProviderKey_validBTTS_returnsBTTSCategory() {
        String providerKey = "29:BOTH_TEAMS_TO_SCORE_NO";

        Optional<MarketCategory> result = SportyMarketType.getCategoryForProviderKey(providerKey);

        assertTrue(result.isPresent());
        assertEquals(MarketCategory.BTTS, result.get());
    }

    @Test
    @DisplayName("getCategoryForProviderKey_invalidKey_returnsEmptyOptional")
    void getCategoryForProviderKey_invalidKey_returnsEmptyOptional() {
        String invalidKey = "999:INVALID";

        Optional<MarketCategory> result = SportyMarketType.getCategoryForProviderKey(invalidKey);

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("getNormalizedNameSafe_validKey_returnsNormalizedName")
    void getNormalizedNameSafe_validKey_returnsNormalizedName() {
        String providerKey = "26:ODD";

        String result = SportyMarketType.getNormalizedNameSafe(providerKey);

        assertEquals("ODD", result);
    }

    @Test
    @DisplayName("getNormalizedNameSafe_invalidKey_returnsProviderKey")
    void getNormalizedNameSafe_invalidKey_returnsProviderKey() {
        String invalidKey = "999:UNKNOWN";

        String result = SportyMarketType.getNormalizedNameSafe(invalidKey);

        assertEquals(invalidKey, result);
    }

    @Test
    @DisplayName("getNormalizedNameSafe_nullKey_returnsNull")
    void getNormalizedNameSafe_nullKey_returnsNull() {
        String result = SportyMarketType.getNormalizedNameSafe(null);

        assertNull(result);
    }


    @ParameterizedTest
    @ValueSource(strings = {
            "18:total=0.5:OVER_0.5",
            "18:total=1.5:UNDER_1.5",
            "18:total=2.5:OVER_2.5",
            "18:total=3.5:UNDER_3.5",
            "18:total=4.5:OVER_4.5"
    })
    @DisplayName("isOverUnderMarket_validOverUnderKeys_returnsTrue")
    void isOverUnderMarket_validOverUnderKeys_returnsTrue(String providerKey) {
        boolean result = SportyMarketType.isOverUnderMarket(providerKey);

        assertTrue(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1:HOME",
            "10:HOME_OR_DRAW",
            "29:BOTH_TEAMS_TO_SCORE_YES",
            "26:ODD",
            "16:hcp=-1.5:HOME_(-1.5)"
    })
    @DisplayName("isOverUnderMarket_nonOverUnderKeys_returnsFalse")
    void isOverUnderMarket_nonOverUnderKeys_returnsFalse(String providerKey) {
        boolean result = SportyMarketType.isOverUnderMarket(providerKey);

        assertFalse(result);
    }

    @Test
    @DisplayName("isOverUnderMarket_invalidKey_returnsFalse")
    void isOverUnderMarket_invalidKey_returnsFalse() {
        String invalidKey = "999:INVALID";

        boolean result = SportyMarketType.isOverUnderMarket(invalidKey);

        assertFalse(result);
    }

    // ========== generateProviderKey Tests ==========

    @Test
    @DisplayName("generateProviderKey_withSpecifierAndOutcome_returnsFullKey")
    void generateProviderKey_withSpecifierAndOutcome_returnsFullKey() {
        String marketId = "18";
        String specifier = "total=2.5";
        String outcomeDesc = "OVER_2.5";

        String result = SportyMarketType.generateProviderKey(marketId, specifier, outcomeDesc);

        assertEquals("18:total=2.5:OVER_2.5", result);
    }

    @Test
    @DisplayName("generateProviderKey_withNullSpecifier_returnsMarketIdAndOutcome")
    void generateProviderKey_withNullSpecifier_returnsMarketIdAndOutcome() {
        String marketId = "1";
        String specifier = null;
        String outcomeDesc = "HOME";

        String result = SportyMarketType.generateProviderKey(marketId, specifier, outcomeDesc);

        assertEquals("1:HOME", result);
    }

    @Test
    @DisplayName("generateProviderKey_withEmptySpecifier_returnsMarketIdAndOutcome")
    void generateProviderKey_withEmptySpecifier_returnsMarketIdAndOutcome() {
        String marketId = "29";
        String specifier = "";
        String outcomeDesc = "BOTH_TEAMS_TO_SCORE_YES";

        String result = SportyMarketType.generateProviderKey(marketId, specifier, outcomeDesc);

        assertEquals("29:BOTH_TEAMS_TO_SCORE_YES", result);
    }

    @Test
    @DisplayName("generateProviderKey_withComplexSpecifier_returnsCorrectFormat")
    void generateProviderKey_withComplexSpecifier_returnsCorrectFormat() {
        String marketId = "236";
        String specifier = "total=41.5|quarternr=1";
        String outcomeDesc = "OVER_41.5";

        String result = SportyMarketType.generateProviderKey(marketId, specifier, outcomeDesc);

        assertEquals("236:total=41.5|quarternr=1:OVER_41.5", result);
    }


    @Test
    @DisplayName("fromProviderKey_basketballMatchWinner_returnsBasketballWinnerHome")
    void fromProviderKey_basketballMatchWinner_returnsBasketballWinnerHome() {
        String providerKey = "219:HOME_WINNER_OT";

        SportyMarketType result = SportyMarketType.fromProviderKey(providerKey);

        assertEquals(SportyMarketType.BASKETBALL_WINNER_HOME, result);
        assertEquals(MarketCategory.BASKETBALL_MATCH_WINNER, result.getCategory());
    }

    @Test
    @DisplayName("fromProviderKey_basketballHandicap_returnsCorrectHandicap")
    void fromProviderKey_basketballHandicap_returnsCorrectHandicap() {
        String providerKey = "223:hcp=-10.5:HOME_(-10.5)";

        SportyMarketType result = SportyMarketType.fromProviderKey(providerKey);

        assertEquals(SportyMarketType.BASKETBALL_HCP_MINUS_10_5_HOME, result);
        assertEquals(MarketCategory.BASKETBALL_HANDICAP, result.getCategory());
    }

    @Test
    @DisplayName("fromProviderKey_basketball1stHalfOverUnder_returnsCorrect1HMarket")
    void fromProviderKey_basketball1stHalfOverUnder_returnsCorrect1HMarket() {
        String providerKey = "68:total=85.5:OVER_85.5";

        SportyMarketType result = SportyMarketType.fromProviderKey(providerKey);

        assertEquals(SportyMarketType.BASKETBALL_1H_OU_85_5_OVER, result);
        assertEquals(MarketCategory.OVER_UNDER_1STHALF, result.getCategory());
    }


    @Test
    @DisplayName("getMarketId_matchOddsHome_returns1")
    void getMarketId_matchOddsHome_returns1() {
        SportyMarketType market = SportyMarketType.MATCH_ODDS_HOME;

        assertEquals("1", market.getMarketId());
    }

    @Test
    @DisplayName("getSpecifier_overUnder25_returnsTotalEquals25")
    void getSpecifier_overUnder25_returnsTotalEquals25() {
        SportyMarketType market = SportyMarketType.OVER_UNDER_2_5_OVER;

        assertEquals("total=2.5", market.getSpecifier());
    }

    @Test
    @DisplayName("getSpecifier_matchOddsHome_returnsNull")
    void getSpecifier_matchOddsHome_returnsNull() {
        SportyMarketType market = SportyMarketType.MATCH_ODDS_HOME;

        assertNull(market.getSpecifier());
    }

    @Test
    @DisplayName("getCategory_bttsYes_returnsBTTSCategory")
    void getCategory_bttsYes_returnsBTTSCategory() {
        SportyMarketType market = SportyMarketType.BTTS_YES;

        assertEquals(MarketCategory.BTTS, market.getCategory());
    }

    @Test
    @DisplayName("getOutcomeType_matchOddsHome_returnsHomeOutcomeType")
    void getOutcomeType_matchOddsHome_returnsHomeOutcomeType() {
        SportyMarketType market = SportyMarketType.MATCH_ODDS_HOME;

        assertEquals(OutcomeType.HOME, market.getOutcomeType());
    }

    @Test
    @DisplayName("getProviderKey_overUnder25Over_returnsCorrectFormat")
    void getProviderKey_overUnder25Over_returnsCorrectFormat() {
        SportyMarketType market = SportyMarketType.OVER_UNDER_2_5_OVER;

        assertEquals("18:total=2.5:OVER_2.5", market.getProviderKey());
    }



    @Test
    @DisplayName("fromProviderKey_multipleMarketsWithSameId_distinguishesBySpecifier")
    void fromProviderKey_multipleMarketsWithSameId_distinguishesBySpecifier() {
        SportyMarketType market15 = SportyMarketType.fromProviderKey("18:total=1.5:OVER_1.5");
        SportyMarketType market25 = SportyMarketType.fromProviderKey("18:total=2.5:OVER_2.5");

        assertNotEquals(market15, market25);
        assertEquals("18", market15.getMarketId());
        assertEquals("18", market25.getMarketId());
        assertEquals("total=1.5", market15.getSpecifier());
        assertEquals("total=2.5", market25.getSpecifier());
    }

    @ParameterizedTest
    @CsvSource({
            "1:HOME, MATCH_RESULT",
            "10:HOME_OR_DRAW, DOUBLE_CHANCE",
            "11:HOME, DRAW_NO_BET",
            "16:hcp=-1.5:HOME_(-1.5), ASIAN_HANDICAP_FULLTIME",
            "18:total=2.5:OVER_2.5, OVER_UNDER_TOTAL",
            "29:BOTH_TEAMS_TO_SCORE_YES, BTTS",
            "26:ODD, ODD_EVEN",
            "68:total=1.5:OVER_1.5, OVER_UNDER_1STHALF",
            "90:total=1.5:OVER_1.5, OVER_UNDER_2NDHALF",
            "219:HOME_WINNER_OT, BASKETBALL_MATCH_WINNER",
            "223:hcp=-10.5:HOME_(-10.5), BASKETBALL_HANDICAP"
    })
    @DisplayName("getCategoryForProviderKey_variousMarkets_returnsCorrectCategory")
    void getCategoryForProviderKey_variousMarkets_returnsCorrectCategory(
            String providerKey, MarketCategory expectedCategory) {

        Optional<MarketCategory> result = SportyMarketType.getCategoryForProviderKey(providerKey);

        assertTrue(result.isPresent());
        assertEquals(expectedCategory, result.get());
    }

    @Test
    @DisplayName("allEnumValues_haveUniqueProviderKeys_noDuplicates")
    void allEnumValues_haveUniqueProviderKeys_noDuplicates() {
        SportyMarketType[] values = SportyMarketType.values();
        long uniqueKeys = Arrays.stream(values)
                .map(SportyMarketType::getProviderKey)
                .distinct()
                .count();

        assertEquals(values.length, uniqueKeys,
                "All enum values should have unique provider keys");
    }

    @Test
    @DisplayName("allEnumValues_haveNonNullProperties_validated")
    void allEnumValues_haveNonNullProperties_validated() {
        for (SportyMarketType market : SportyMarketType.values()) {
            assertNotNull(market.getMarketId(),
                    "Market ID should not be null for " + market.name());
            assertNotNull(market.getNormalizedName(),
                    "Normalized name should not be null for " + market.name());
            assertNotNull(market.getCategory(),
                    "Category should not be null for " + market.name());
            assertNotNull(market.getOutcomeType(),
                    "Outcome type should not be null for " + market.name());
            assertNotNull(market.getProviderKey(),
                    "Provider key should not be null for " + market.name());
        }
    }
}