package com.creditcabinet.credit.scoring;

import java.util.UUID;

import com.creditcabinet.credit.decision.RiskEstimate;

public interface RiskScoringClient {

    RiskEstimate assess(ScoringRequest request, UUID requestId);
}
