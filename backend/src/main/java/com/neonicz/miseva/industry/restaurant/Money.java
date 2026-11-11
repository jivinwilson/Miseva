package com.neonicz.miseva.industry.restaurant;

final class Money {

    private Money() {
    }

    static long tax(long subtotalMinor, int rateBp) {
        return (subtotalMinor * rateBp + 5_000) / 10_000;
    }
}
