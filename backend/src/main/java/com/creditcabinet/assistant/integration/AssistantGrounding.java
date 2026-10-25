package com.creditcabinet.assistant.integration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.creditcabinet.assistant.model.AssistantContext;
import com.creditcabinet.assistant.model.AssistantContext.CatalogProduct;
import com.creditcabinet.assistant.service.AssistantException;
import org.springframework.stereotype.Component;

@Component
public class AssistantGrounding {

    public static final String VERSION = "facts-v3-java-1";
    private static final String NUMBER = "\\d+(?:[ \\u00a0]\\d{3})*(?:[.,]\\d+)?";
    private static final Pattern MONEY = Pattern.compile("([−-]?" + NUMBER + ")\\s*(млн|миллион\\p{L}*|тыс\\.?|тысяч\\p{L}*)?");
    private static final Map<String, Integer> COUNTS = Map.ofEntries(Map.entry("один", 1), Map.entry("одну", 1),
            Map.entry("два", 2), Map.entry("две", 2), Map.entry("три", 3), Map.entry("четыре", 4),
            Map.entry("пять", 5), Map.entry("шесть", 6), Map.entry("семь", 7), Map.entry("восемь", 8),
            Map.entry("девять", 9), Map.entry("десять", 10));
    private static final Pattern TERM = Pattern.compile("\\bна\\s+(\\d+|" + String.join("|", COUNTS.keySet())
            + ")\\s+(лет|год\\p{L}*|месяц\\p{L}*)", Pattern.UNICODE_CHARACTER_CLASS);

    public GroundedReply apply(AssistantContext input, AssistantReply reply) {
        String message = input.message().toLowerCase(Locale.ROOT);
        GroundedReply calculation = calculateLimit(input, message);
        if (calculation != null) {
            return calculation;
        }
        if (message.contains("возобновляем") && !message.contains("невозобновляем") && !message.contains("не возобновляем")
                && message.contains("основно") && matches("погас|верну", message)
                && input.context().catalog().stream().anyMatch(p -> p.financingType().equals("REVOLVING_LINE"))) {
            return grounded("Да, погашение основного долга восстанавливает доступный лимит возобновляемой линии, и его можно использовать снова. "
                    + "Выплата процентов сама по себе лимит не восстанавливает.", null, "revolving_repayment");
        }
        if (reply.recommendedProductCode() != null) {
            return recommendation(input, reply.recommendedProductCode(), message);
        }
        GroundedReply field = explainField(input, message);
        return field == null ? new GroundedReply(reply, "model_text") : field;
    }

    private GroundedReply recommendation(AssistantContext input, String code, String message) {
        var product = product(input, code);
        var terms = product.terms();
        if (terms == null) {
            throw AssistantException.invalidResponse();
        }
        Integer wanted = desiredTerm(message);
        String duration = terms.minTermMonths() == terms.maxTermMonths() ? String.valueOf(terms.minTermMonths())
                : terms.minTermMonths() + "–" + terms.maxTermMonths();
        if (wanted != null && (wanted < terms.minTermMonths() || wanted > terms.maxTermMonths())) {
            boolean none = input.context().catalog().stream().allMatch(p -> p.terms() != null
                    && (wanted < p.terms().minTermMonths() || wanted > p.terms().maxTermMonths()));
            String answer = none ? "В переданном каталоге нет продукта на запрошенный срок " + wanted + " " + months(wanted) + ". " : "";
            return grounded(answer + "У продукта «" + product.name() + "» срок " + duration + " " + months(terms.maxTermMonths())
                    + ", поэтому на указанный срок я его не предлагаю.", null, "incompatible_term");
        }
        String answer = "Можно предварительно рассмотреть «" + product.name() + "» под " + display(terms.annualRatePercent())
                + "% годовых на " + (wanted == null ? duration : wanted) + " " + months(wanted == null ? terms.maxTermMonths() : wanted) + ". "
                + product.purpose();
        if (code.equals("LONG_TERM") && !(product.purpose().contains("цель") && product.purpose().contains("бюджет"))) {
            answer += " Нужны цель финансирования и положительный бюджет проекта.";
        }
        if (terms.overdraftRules() != null) {
            answer += " Срок возврата долга — " + terms.overdraftRules().maxDebtPeriodDays()
                    + " дней с первого использования; это отдельный срок от срока лимита.";
        }
        return grounded(answer, code, "catalog_recommendation");
    }

    private GroundedReply calculateLimit(AssistantContext input, String message) {
        if (!matches("лимит|предел|максим|рассчит", message)) {
            return null;
        }
        String code = message.contains("овердрафт") ? "OVERDRAFT"
                : message.contains("контракт") && message.contains("аванс") ? "GOVERNMENT_CONTRACT_LINE" : null;
        var product = input.context().catalog().stream().filter(p -> p.code().equals(code)).findFirst().orElse(null);
        if (product == null || product.terms() == null) {
            return null;
        }
        List<BigDecimal> values = moneyValues(message);
        BigDecimal share;
        String formula;
        if (code.equals("OVERDRAFT") && values.size() == 1 && product.terms().overdraftRules() != null) {
            if (values.getFirst().signum() < 0) {
                return grounded("Укажите неотрицательную сумму среднемесячных поступлений для расчёта предела.", null, "invalid_amount");
            }
            BigDecimal percent = product.terms().overdraftRules().maxInflowSharePercent();
            share = values.getFirst().multiply(percent).movePointLeft(2);
            formula = display(percent) + "% × " + display(values.getFirst()) + " ₽ = " + display(share) + " ₽";
        } else if (code.equals("GOVERNMENT_CONTRACT_LINE") && values.size() == 2 && product.terms().governmentContractRules() != null) {
            var contract = Pattern.compile("контракт[^\\d−-]{0,25}" + NUMBER + "\\s*(млн|миллион\\p{L}*|тыс\\.?|тысяч\\p{L}*)?").matcher(message);
            var advance = Pattern.compile("аванс[^\\d−-]{0,25}" + NUMBER + "\\s*(млн|миллион\\p{L}*|тыс\\.?|тысяч\\p{L}*)?").matcher(message);
            if (!contract.find() || !advance.find()) {
                return null;
            }
            BigDecimal cost = moneyValues(contract.group()).getFirst();
            BigDecimal paid = moneyValues(advance.group()).getFirst();
            if (cost.signum() <= 0 || paid.signum() < 0 || paid.compareTo(cost) > 0) {
                return null;
            }
            BigDecimal percent = product.terms().governmentContractRules().maxNetContractSharePercent();
            share = cost.subtract(paid).multiply(percent).movePointLeft(2);
            formula = display(percent) + "% × (" + display(cost) + " − " + display(paid) + ") ₽";
        } else {
            return null;
        }
        BigDecimal cap = product.terms().maxAmount();
        BigDecimal limit = share.min(cap).setScale(2, RoundingMode.DOWN);
        return grounded("Расчётный предел — " + display(limit) + " ₽: " + formula + ", при потолке " + display(cap)
                + " ₽. Остальные условия продукта сохраняются; это не одобрение.", null, "source_formula");
    }

    private GroundedReply explainField(AssistantContext input, String message) {
        if (matches("отправ|измени|скры|придум|гарантир", message)) {
            return null;
        }
        var context = input.context();
        if (message.contains("период") && matches("средн|счит|расч", message)) {
            String selected = context.selectedProductCode();
            String periods = context.catalog().stream().filter(p -> selected == null || selected.equals(p.code()))
                    .map(p -> "«" + p.name() + "» — " + p.financialPeriodMonths() + " " + months(p.financialPeriodMonths()))
                    .collect(Collectors.joining("; "));
            return grounded("Период для среднемесячных показателей зависит от продукта: " + periods + ".", null, "catalog_periods");
        }
        if (!matches("включ|учит|среднемесяч|впис|заполн|в этом поле", message)) {
            return null;
        }
        String code = matches("расход|затрат|трат", message) ? "form.averageMonthlyOutflow"
                : message.contains("поступлен") ? "form.averageMonthlyInflow" : null;
        var definition = context.fieldDefinitions().stream().filter(field -> field.code().equals(code)).findFirst().orElse(null);
        if (definition == null) {
            return null;
        }
        String text = definition.label() + ": " + definition.description().replace("reportingPeriod / months", "выбранный период, делённые на число месяцев")
                .replace("reportingPeriod", "выбранный период").replace("его months", "число месяцев").replace("months", "число месяцев");
        if (context.application() != null && context.application().reportingPeriod() != null) {
            int period = context.application().reportingPeriod().months();
            text += " В вашей заявке период — " + period + " " + months(period) + ".";
        }
        return grounded(text, null, "field_definition");
    }

    private CatalogProduct product(AssistantContext input, String code) {
        return input.context().catalog().stream().filter(product -> product.code().equals(code)).findFirst()
                .orElseThrow(AssistantException::invalidResponse);
    }

    private Integer desiredTerm(String message) {
        var match = TERM.matcher(message);
        if (!match.find()) {
            return null;
        }
        BigDecimal count = COUNTS.containsKey(match.group(1)) ? BigDecimal.valueOf(COUNTS.get(match.group(1))) : new BigDecimal(match.group(1));
        if (!match.group(2).startsWith("месяц")) {
            count = count.multiply(BigDecimal.valueOf(12));
        }
        return count.min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValue();
    }

    private List<BigDecimal> moneyValues(String text) {
        List<BigDecimal> values = new ArrayList<>();
        var match = MONEY.matcher(text);
        while (match.find()) {
            if (match.group(2) == null && matches("^(месяц|год|лет|дн|недел|%|процент)", text.substring(match.end()).stripLeading())) {
                continue;
            }
            BigDecimal value = new BigDecimal(match.group(1).replace(" ", "").replace("\u00a0", "").replace(',', '.').replace('−', '-'));
            String unit = match.group(2) == null ? "" : match.group(2);
            values.add(value.multiply(BigDecimal.valueOf(unit.startsWith("млн") || unit.startsWith("миллион") ? 1_000_000 : unit.startsWith("тыс") ? 1_000 : 1)));
        }
        return values;
    }

    private boolean matches(String pattern, String text) {
        return Pattern.compile(pattern).matcher(text).find();
    }

    private String display(BigDecimal value) {
        String[] parts = value.stripTrailingZeros().toPlainString().split("\\.");
        return parts[0].replaceAll("(?<=\\d)(?=(\\d{3})+$)", " ") + (parts.length == 2 ? "," + parts[1] : "");
    }

    private String months(int count) {
        return count % 100 >= 11 && count % 100 <= 14 ? "месяцев" : count % 10 == 1 ? "месяц" : count % 10 >= 2 && count % 10 <= 4 ? "месяца" : "месяцев";
    }

    private GroundedReply grounded(String answer, String code, String rule) {
        return new GroundedReply(new AssistantReply(answer, code), rule);
    }

    public record GroundedReply(AssistantReply reply, String rule) {
    }
}
