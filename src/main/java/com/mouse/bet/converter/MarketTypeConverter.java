package com.mouse.bet.converter;

import com.mouse.bet.interfaces.MarketType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

@Converter
@Slf4j
public class MarketTypeConverter implements AttributeConverter<MarketType, String> {


    @Override
    public String convertToDatabaseColumn(MarketType attribute) {
        if (attribute == null) {
            return null;
        }

        return attribute.getClass().getName() + ":" + ((Enum<?>) attribute).name();
    }

    @Override
    public MarketType convertToEntityAttribute(String dbData) {
        if (dbData == null || !dbData.contains(":")) {
            return null;
        }

        String[] parts = dbData.split(":", 2);
        String className = parts[0];
        String enumName = parts[1];

        try {
            // Find the specific enum class using reflection
            Class<?> enumClass = Class.forName(className);

            // Look up the enum constant by name
            @SuppressWarnings("unchecked")
            MarketType marketType = (MarketType) Enum.valueOf((Class<Enum>) enumClass, enumName);

            return marketType;
        } catch (ClassNotFoundException | IllegalArgumentException e) {
            log.error("Failed to deserialize MarketType from DB value: "+ dbData);

            throw new IllegalStateException("Failed to deserialize MarketType from DB value: " + dbData, e);
        }
    }
}
