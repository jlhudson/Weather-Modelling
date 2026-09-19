package au.gully.platform;

/**
 * What an on-request read of a source came to (W-14): nothing, because it was checked inside its
 * cadence; a round trip that found it unchanged; a download; or a failure. The ledger records the
 * last three against what asked for them, so the console can show that nothing is read but on
 * request.
 */
public enum ReadOutcome {
    SKIPPED, UNCHANGED, READ, FAILED;

    public boolean touched() {
        return this != SKIPPED;
    }
}
