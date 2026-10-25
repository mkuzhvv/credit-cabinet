package com.creditcabinet.credit.processing;

import com.creditcabinet.credit.persistence.AssessmentClaimRepository;
import com.creditcabinet.credit.service.AssessmentStateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssessmentRecoveryService {

    private final AssessmentClaimRepository claims;
    private final AssessmentStateService state;
    private final ProcessingProperties properties;

    public AssessmentRecoveryService(AssessmentClaimRepository claims, AssessmentStateService state, ProcessingProperties properties) {
        this.claims = claims;
        this.state = state;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recoverNext() {
        var candidate = claims.findExpiredCandidate();
        if (candidate.isEmpty()) {
            return false;
        }
        var expired = claims.lockExpired(candidate.get());
        if (expired.isEmpty()) {
            return false;
        }
        var claim = expired.get();
        if (claim.executionCount() < properties.maxExecutions()) {
            return claims.requeueExpired(claim, properties.retryDelay(), properties.maxExecutions());
        }
        return state.interruptExpired(claim);
    }
}
