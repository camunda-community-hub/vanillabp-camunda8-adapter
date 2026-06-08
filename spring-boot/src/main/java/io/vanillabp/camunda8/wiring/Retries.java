package io.vanillabp.camunda8.wiring;

import java.time.Duration;

public class Retries {
    private final boolean enabled;
    private final Duration backoff;

    public Retries(boolean enabled, Duration backoff) {
        this.enabled = enabled;
        this.backoff = backoff;
    }

    public boolean areRetriesEnabled() {
        return enabled;
    }

    public Duration getBackoff() {
        return backoff;
    }
}
