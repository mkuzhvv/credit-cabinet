package com.creditcabinet.common.json;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class PercentageDeserializer extends ValueDeserializer<BigDecimal> {

    private static final Pattern FORMAT = Pattern.compile("(0|[1-9][0-9]{0,2})\\.[0-9]{2}");

    @Override
    public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING) || !FORMAT.matcher(parser.getString()).matches()) {
            return context.reportInputMismatch(BigDecimal.class, "Ожидается процент строкой с двумя знаками после точки");
        }
        return new BigDecimal(parser.getString());
    }
}
