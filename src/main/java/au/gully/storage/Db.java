package au.gully.storage;

import lombok.experimental.UtilityClass;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The two conversions every SQL in this service needs, written once: an {@link Instant} goes to
 * Postgres as an {@code OffsetDateTime} at UTC (the driver binds that; it does not bind an Instant),
 * and a {@code timestamptz} comes back as whichever of three types the driver felt like.
 */
@UtilityClass
public class Db {

    public static OffsetDateTime ts(Instant at) {
        return at == null ? null : at.atOffset(ZoneOffset.UTC);
    }

    public static Instant instant(Object column) {
        return switch (column) {
            case null -> null;
            case OffsetDateTime o -> o.toInstant();
            case Timestamp t -> t.toInstant();
            case Instant i -> i;
            default -> null;
        };
    }

    public static LocalDate date(Object column) {
        return switch (column) {
            case null -> null;
            case LocalDate d -> d;
            case java.sql.Date d -> d.toLocalDate();
            default -> null;
        };
    }

    public static Double dbl(Object column) {
        return column instanceof Number n ? n.doubleValue() : null;
    }

    public static Integer integer(Object column) {
        return column instanceof Number n ? n.intValue() : null;
    }
}
