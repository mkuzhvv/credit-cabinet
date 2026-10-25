package com.creditcabinet.credit.processing;

import com.creditcabinet.credit.service.ProcessApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "credit.processing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AssessmentWorker {

    private static final Logger log = LoggerFactory.getLogger(AssessmentWorker.class);
    private final AssessmentRecoveryService recovery;
    private final AssessmentClaimService claims;
    private final ProcessApplicationService processing;

    public AssessmentWorker(AssessmentRecoveryService recovery, AssessmentClaimService claims, ProcessApplicationService processing) {
        this.recovery = recovery;
        this.claims = claims;
        this.processing = processing;
    }

    @Scheduled(initialDelayString = "${credit.processing.poll-delay}", fixedDelayString = "${credit.processing.poll-delay}")
    public void runOnce() {
        try {
            recovery.recoverNext();
            claims.claimNext().ifPresent(processing::process);
        } catch (RuntimeException ex) {
            log.error("Не удалось выполнить цикл обработки заявок", ex);
        }
    }
}
