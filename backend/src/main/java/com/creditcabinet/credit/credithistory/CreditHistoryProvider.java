package com.creditcabinet.credit.credithistory;

import java.util.UUID;

public interface CreditHistoryProvider {

    CreditHistoryReport getCreditHistory(UUID companyId);
}
