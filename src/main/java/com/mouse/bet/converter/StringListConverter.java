package com.mouse.bet.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Converts a List<String> (e.g., game scores like ["0:0", "0:2"]) to a
 * comma-separated String for database storage (e.g., "0:0,0:2") and back.
 */
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final String SEPARATOR = ",";

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        // Joins the list elements into a single comma-separated string
        return attribute.stream().collect(Collectors.joining(SEPARATOR));
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.trim().isEmpty()) {
            return Collections.emptyList();
        }
        // Splits the database string back into a list of strings
        return Arrays.asList(dbData.split(SEPARATOR));
    }
}