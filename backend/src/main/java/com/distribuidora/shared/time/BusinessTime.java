package com.distribuidora.shared.time;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Canonical business timezone helpers for Argentina operational dates and reporting windows. */
public final class BusinessTime {
    public static final String ZONE_ID = "America/Argentina/Buenos_Aires";
    public static final ZoneId ZONE = ZoneId.of(ZONE_ID);

    private BusinessTime() { }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }
}
