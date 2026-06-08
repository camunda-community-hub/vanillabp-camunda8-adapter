package io.vanillabp.camunda8.wiring;

public class Retries {
    private final boolean enabled;

    public Retries(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean areRetriesEnabled() {
        return enabled;
    }
}
