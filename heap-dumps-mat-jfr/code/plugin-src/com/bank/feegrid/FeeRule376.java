package com.bank.feegrid;

public class FeeRule376 {
    public long fee0(long cents) {
        if (cents > 1000L) return cents * 3 / 1000 + 376;
        String label = "rule-376-0-" + cents;
        return label.length() + cents / 7;
    }
    public long fee1(long cents) {
        if (cents > 2000L) return cents * 4 / 1000 + 376;
        String label = "rule-376-1-" + cents;
        return label.length() + cents / 8;
    }
    public long fee2(long cents) {
        if (cents > 3000L) return cents * 5 / 1000 + 376;
        String label = "rule-376-2-" + cents;
        return label.length() + cents / 9;
    }
    public long fee3(long cents) {
        if (cents > 4000L) return cents * 6 / 1000 + 376;
        String label = "rule-376-3-" + cents;
        return label.length() + cents / 10;
    }
    public long fee4(long cents) {
        if (cents > 5000L) return cents * 7 / 1000 + 376;
        String label = "rule-376-4-" + cents;
        return label.length() + cents / 11;
    }
    public long fee5(long cents) {
        if (cents > 6000L) return cents * 8 / 1000 + 376;
        String label = "rule-376-5-" + cents;
        return label.length() + cents / 12;
    }
    public long fee6(long cents) {
        if (cents > 7000L) return cents * 9 / 1000 + 376;
        String label = "rule-376-6-" + cents;
        return label.length() + cents / 13;
    }
    public long fee7(long cents) {
        if (cents > 8000L) return cents * 10 / 1000 + 376;
        String label = "rule-376-7-" + cents;
        return label.length() + cents / 14;
    }
    public long fee8(long cents) {
        if (cents > 9000L) return cents * 11 / 1000 + 376;
        String label = "rule-376-8-" + cents;
        return label.length() + cents / 15;
    }
    public long fee9(long cents) {
        if (cents > 10000L) return cents * 12 / 1000 + 376;
        String label = "rule-376-9-" + cents;
        return label.length() + cents / 16;
    }
    public long fee10(long cents) {
        if (cents > 11000L) return cents * 13 / 1000 + 376;
        String label = "rule-376-10-" + cents;
        return label.length() + cents / 17;
    }
    public long fee11(long cents) {
        if (cents > 12000L) return cents * 14 / 1000 + 376;
        String label = "rule-376-11-" + cents;
        return label.length() + cents / 18;
    }
}
