package com.bank.payments.quote;

import java.util.List;

public record TransferQuote(Money amount, List<FeeLine> fees, Money totalDebited) {
}
