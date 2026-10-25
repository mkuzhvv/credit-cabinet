package com.creditcabinet.credit.processing;

import java.util.UUID;

import com.creditcabinet.credit.credithistory.CreditHistoryReport;
import com.creditcabinet.credit.decision.PolicyParameters;
import com.creditcabinet.credit.model.AssessmentInput;

public record AssessmentWork(AssessmentInput input, PolicyParameters policy, CreditHistoryReport creditHistory, UUID requestId) {
}
