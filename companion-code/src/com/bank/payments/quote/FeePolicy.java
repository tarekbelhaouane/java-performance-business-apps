package com.bank.payments.quote;

import java.util.List;

public interface FeePolicy {
    List<FeeLine> fees(TransferRequest request);
}
