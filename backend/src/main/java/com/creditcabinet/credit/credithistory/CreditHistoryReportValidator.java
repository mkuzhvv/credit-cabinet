package com.creditcabinet.credit.credithistory;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import com.creditcabinet.credit.model.CreditHistoryStatus;
import com.creditcabinet.credit.model.ReportingPeriod;
import org.springframework.stereotype.Component;

@Component
public class CreditHistoryReportValidator {

    private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999.99");

    public void validate(UUID companyId, CreditHistoryReport report) {
        Objects.requireNonNull(companyId);
        if (report == null) {
            throw invalid("Кредитный отчёт отсутствует");
        }
        if (!"1".equals(report.schemaVersion())) {
            throw invalid("Неподдерживаемая версия кредитного отчёта");
        }
        if (!companyId.equals(report.companyId())) {
            throw invalid("Кредитный отчёт относится к другой компании");
        }
        if (report.source() == null || report.source().isBlank()
                || report.sourceVersion() == null || report.sourceVersion().isBlank()
                || report.reportId() == null || report.receivedAt() == null) {
            throw invalid("Не заполнены обязательные метаданные кредитного отчёта");
        }
        CreditHistoryProfile profile = report.profile();
        if (profile == null || profile.status() == null || profile.asOfDate() == null || profile.delinquencyPeriod() == null) {
            throw invalid("Не заполнены статус, дата или период кредитного профиля");
        }
        if (profile.asOfDate().isAfter(report.receivedAt().atZone(ZoneOffset.UTC).toLocalDate())) {
            throw invalid("Дата состояния профиля позже даты получения отчёта");
        }
        YearMonth month = YearMonth.from(profile.asOfDate());
        ReportingPeriod expected = new ReportingPeriod(month.minusMonths(12).atDay(1), month.minusMonths(1).atEndOfMonth(), 12);
        if (!expected.equals(profile.delinquencyPeriod())) {
            throw invalid("Период просрочек должен содержать 12 полных месяцев перед месяцем даты профиля");
        }
        if (profile.status() == CreditHistoryStatus.NO_HISTORY) {
            if (profile.totalOutstandingDebt() != null || profile.monthlyDebtPayment() != null
                    || profile.overdueDebtAmount() != null || profile.maxDaysPastDueLast12Months() != null) {
                throw invalid("При отсутствии кредитной истории все четыре показателя должны быть null");
            }
            return;
        }
        validateMoney(profile.totalOutstandingDebt());
        validateMoney(profile.monthlyDebtPayment());
        validateMoney(profile.overdueDebtAmount());
        if (profile.maxDaysPastDueLast12Months() == null || profile.maxDaysPastDueLast12Months() < 0) {
            throw invalid("Число дней просрочки должно быть заполнено и не может быть отрицательным");
        }
        if (profile.overdueDebtAmount().compareTo(profile.totalOutstandingDebt()) > 0) {
            throw invalid("Просроченная задолженность превышает общую задолженность");
        }
    }

    private void validateMoney(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_MONEY) > 0 || value.stripTrailingZeros().scale() > 2) {
            throw invalid("Денежный показатель отсутствует или не соответствует допустимому диапазону и точности");
        }
    }

    private CreditHistoryException invalid(String message) {
        return new CreditHistoryException(CreditHistoryException.Code.CREDIT_HISTORY_INVALID_RESPONSE, message);
    }
}
