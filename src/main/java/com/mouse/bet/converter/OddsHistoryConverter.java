package com.mouse.bet.converter;

import com.mouse.bet.model.OddsChange;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Map;
import java.util.TreeMap;

/**
 * JPA Converter for storing odds history as JSON
 */
@Converter
public class OddsHistoryConverter implements AttributeConverter<Map<Long, OddsChange>, String> {

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Override
    public String convertToDatabaseColumn(Map<Long, OddsChange> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert odds history to JSON", e);
        }
    }

    @Override
    public Map<Long, OddsChange> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank() || dbData.equals("{}")) {
            return new TreeMap<>();
        }
        try {
            return objectMapper.readValue(dbData,
                    objectMapper.getTypeFactory().constructMapType(
                            TreeMap.class, Long.class, OddsChange.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert JSON to odds history", e);
        }
    }

}