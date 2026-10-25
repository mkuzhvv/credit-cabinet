package com.creditcabinet.credit.credithistory;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.creditcabinet.credit.processing.ProcessingProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NEVER)
public class CreditHistoryService {

    private final CreditHistoryProvider provider;
    private final CreditHistoryReportValidator validator;
    private final ExecutorService executor;
    private final ProcessingProperties properties;

    public CreditHistoryService(CreditHistoryProvider provider, CreditHistoryReportValidator validator,
            @Qualifier("creditHistoryExecutor") ExecutorService executor, ProcessingProperties properties) {
        this.provider = provider;
        this.validator = validator;
        this.executor = executor;
        this.properties = properties;
    }

    public CreditHistoryReport getReport(UUID companyId, LocalDate asOfDate) {
        long deadline = System.nanoTime() + properties.creditHistoryTimeout().toNanos();
        var call = executor.submit(() -> {
            CreditHistoryReport report = provider.getCreditHistory(companyId);
            validate(companyId, asOfDate, report);
            return report;
        });
        try {
            return call.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            call.cancel(true);
            throw new CreditHistoryException(CreditHistoryException.Code.CREDIT_HISTORY_TIMEOUT, "Истёк бюджет получения кредитного отчёта", ex);
        } catch (InterruptedException ex) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new CreditHistoryException(CreditHistoryException.Code.CREDIT_HISTORY_UNAVAILABLE, "Получение кредитного отчёта прервано", ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof CreditHistoryException failure) {
                throw failure;
            }
            throw new CreditHistoryException(CreditHistoryException.Code.CREDIT_HISTORY_UNAVAILABLE, "Не удалось получить кредитный отчёт", ex.getCause());
        }
    }

    public void validate(UUID companyId, LocalDate asOfDate, CreditHistoryReport report) {
        validator.validate(companyId, report);
        if (report.profile().asOfDate().isAfter(asOfDate)) {
            throw new CreditHistoryException(CreditHistoryException.Code.CREDIT_HISTORY_INVALID_RESPONSE,
                    "Дата кредитного профиля позже даты принятой попытки");
        }
    }
}
