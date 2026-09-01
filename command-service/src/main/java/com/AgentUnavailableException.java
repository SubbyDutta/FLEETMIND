package com;

public class AgentUnavailableException extends RuntimeException {

    private final boolean circuitOpen;

    public AgentUnavailableException(String message, Throwable cause, boolean circuitOpen) {
        super(message, cause);
        this.circuitOpen = circuitOpen;
    }

    public boolean isCircuitOpen() {
        return circuitOpen;
    }
}
