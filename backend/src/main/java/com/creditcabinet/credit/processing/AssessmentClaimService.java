package com.creditcabinet.credit.processing;

import java.util.Optional;

import com.creditcabinet.credit.persistence.AssessmentClaimRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssessmentClaimService {

    private final AssessmentClaimRepository attempts;
    private final ProcessingProperties properties;

    public AssessmentClaimService(AssessmentClaimRepository attempts, ProcessingProperties properties) {
        this.attempts = attempts;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<AssessmentClaim> claimNext() {
        return attempts.claimNext(properties.leaseDuration());
    }
}
