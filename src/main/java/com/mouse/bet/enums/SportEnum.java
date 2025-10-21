package com.mouse.bet.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum SportEnum {
    FOOTBALL("Football"),

    BASKETBALL("Basketball"),

    TABLE_TENNIS("Table Tennis");

    private final String name;
    /**
     * Get Sport enum from name (case-insensitive)
     * @param name the sport name
     * @return Optional containing the Sport if found
     */
    public static Optional<SportEnum> fromName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        return Arrays.stream(SportEnum.values())
                .filter(sport -> sport.getName().equalsIgnoreCase(name.trim()))
                .findFirst();
    }

}


