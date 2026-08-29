package com;

import org.springframework.boot.autoconfigure.graphql.GraphQlProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SseHub {
    private final List<SseEmitter> emitters =new CopyOnWriteArrayList<>();
    public SseEmitter subscribe(){
        SseEmitter emitter=new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);
        emitter.onCompletion(()->emitters.remove(emitter));
        emitter.onTimeout(()->emitters.remove(emitter));
        emitter.onError(e->emitters.remove(emitter));
        return emitter;

    }
    public void publish(String event,Object data)
    {
        for(SseEmitter emitter : emitters)
            try{
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException e) {
                emitter.completeWithError(e);
                emitters.remove(emitter);
            }
    }


}
