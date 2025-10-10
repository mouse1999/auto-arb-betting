package com.mouse.bet.enums;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum MSportMarketType {

    private final String marketId;
    private final String specifier;
    private final String normalizedName;
    private final MarketCategory category;
    private final OutcomeType outcomeType;

    // The provider key is a combination of marketId and specifier to uniquely identify a market line.
    private final String providerKey;

    private static final Map<String, MSportMarketType> BY_PROVIDER_KEY;

    static {
        BY_PROVIDER_KEY = Arrays.stream(values())
                .collect(Collectors.toMap(
                        MSportMarketType::getProviderKey,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));
    }

    MSportMarketType(String marketId, String specifier, String normalizedName, MarketCategory category, OutcomeType outcomeType) {
        this.marketId = marketId;
        this.specifier = specifier;
        this.normalizedName = normalizedName;
        this.category = category;
        this.outcomeType = outcomeType;
        // Create a unique key. Use marketId alone if specifier is null.
        this.providerKey = specifier == null ? marketId + ":" + normalizedName : marketId + ":" + specifier + ":" + normalizedName;
    }

    public String getMarketId() {
        return marketId;
    }

    public String getSpecifier() {
        return specifier;
    }

    public String getProviderKey() {
        return providerKey;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public MarketCategory getCategory() {
        return category;
    }

    public OutcomeType getOutcomeType() {
        return outcomeType;
    }

    // Static lookup methods
    public static MSportMarketType fromProviderKey(String providerKey) {
        MSportMarketType market = BY_PROVIDER_KEY.get(providerKey);
        if (market == null) {
            throw new IllegalArgumentException("Unknown Sporty market key: " + providerKey);
        }
        return market;
    }

    public static Optional<MSportMarketType> safeFromProviderKey(String providerKey) {
        return Optional.ofNullable(BY_PROVIDER_KEY.get(providerKey));
    }

    public static boolean isKnownMarket(String providerKey) {
        return BY_PROVIDER_KEY.containsKey(providerKey);
    }

    public static Optional<MarketCategory> getCategoryForProviderKey(String providerKey) {
        return safeFromProviderKey(providerKey).map(MSportMarketType::getCategory);
    }

    public static String getNormalizedNameSafe(String providerKey) {
        return safeFromProviderKey(providerKey)
                .map(MSportMarketType::getNormalizedName)
                .orElse(providerKey);
    }

    public static boolean isOverUnderMarket(String providerKey) {
        return safeFromProviderKey(providerKey)
                .map(m -> m.getCategory() == MarketCategory.OVER_UNDER_TOTAL)
                .orElse(false);
    }

    // Helper method to generate the provider key from raw JSON data
    public static String generateProviderKey(String marketId, String specifier, String outcomeDesc) {
//        return specifier == null || specifier.isEmpty() ? marketId : marketId + ":" + specifier;
        if (specifier != null && !specifier.isEmpty()) {
            return marketId + ":" + specifier + ":" + outcomeDesc;
        }
        return marketId + ":" + outcomeDesc;
    }
}
