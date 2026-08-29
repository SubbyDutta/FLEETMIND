package com;

import fleetmind.events.GpsPing;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

public class GpsPingTimestampExtractor implements TimestampExtractor {
    @Override
    public long extract(ConsumerRecord<Object,Object> record,long partitionTime){
        if(!(record.value() instanceof GpsPing ping)){
            return partitionTime;
        }
        if(ping.getTs()==null){
            return partitionTime;
        }
        return ping.getTs().toEpochMilli();
    }

}
