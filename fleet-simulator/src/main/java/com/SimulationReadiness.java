package com;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class SimulationReadiness {
    private final AtomicBoolean ready = new AtomicBoolean(false);
    public boolean isReady(){return ready.get();}
    public void markReady(){ready.set(true);}
}
