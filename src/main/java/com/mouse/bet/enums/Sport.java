package com.mouse.bet.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum Sport {
    FOOTBALL("Football"),

    BASKETBALL("Basketball"),

    TABLE_TENNIS("TableTennis");

    private final String name;
    /**
     * Get Sport enum from name (case-insensitive)
     * @param name the sport name
     * @return Optional containing the Sport if found
     */
    public static Optional<Sport> fromName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        return Arrays.stream(Sport.values())
                .filter(sport -> sport.getName().equalsIgnoreCase(name.trim()))
                .findFirst();
    }

}


