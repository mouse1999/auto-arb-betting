package com.mouse.bet.deserializer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class OddsMapDeserializer extends JsonDeserializer<Map<String, String>> {

    @Override
    public Map<String, String> deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {

        // Handle null case
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }

        // Expecting START_OBJECT for the O field
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            return null;
        }

        Map<String, String> oddsMap = new HashMap<>();

        // Iterate through each market key in O
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String marketKey = parser.getCurrentName(); // e.g., "LIVETT_12_1HH"
            parser.nextToken(); // Move to the value (START_OBJECT for {"v": 1.04})

            // Handle case where value is not an object
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }

            // Look for the "v" field inside the nested object
            String oddValue = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.getCurrentName();
                parser.nextToken(); // Move to field value

                if ("v".equals(fieldName)) {
                    // Extract the value as String regardless of type
                    if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                        oddValue = String.valueOf(parser.getLongValue());
                    } else if (parser.currentToken() == JsonToken.VALUE_NUMBER_FLOAT) {
                        oddValue = parser.getDecimalValue().toPlainString();
                    } else if (parser.currentToken() == JsonToken.VALUE_STRING) {
                        oddValue = parser.getText();
                    }
                } else {
                    parser.skipChildren();
                }
            }

            // Add to map if value was found
            if (oddValue != null) {
                oddsMap.put(marketKey, oddValue);
            }
        }

        return oddsMap.isEmpty() ? null : oddsMap;
    }
}