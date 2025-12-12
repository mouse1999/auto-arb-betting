package com.mouse.bet.model;

/**
 *
 * @param outcomeName- this is the description or name of the outcome to be chosen (e.g, home)
 * @param desc- this is the title above the outcome to be chosen (e.g, 1x2)
 * @param group
 * @param specifiers
 * @param marketId
 */

public record MarketMeta(String desc, String outcomeName, String group, String specifiers, String marketId) {
}
