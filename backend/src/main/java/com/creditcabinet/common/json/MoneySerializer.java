package com.creditcabinet.common.json;

import java.math.BigDecimal;
import java.math.RoundingMode;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

public class MoneySerializer extends ValueSerializer<BigDecimal> {

    @Override
    public void serialize(BigDecimal value, JsonGenerator generator, SerializationContext context) {
        String encoded;
        try {
            encoded = value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException ex) {
            context.reportMappingProblem(ex, "Денежное значение нельзя округлять при формировании ответа");
            return;
        }
        if (!MoneyDeserializer.FORMAT.matcher(encoded).matches()) {
            context.reportMappingProblem("Денежное значение ответа вне диапазона контракта");
            return;
        }
        generator.writeString(encoded);
    }
}
