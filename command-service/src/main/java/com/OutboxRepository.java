package com;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class OutboxRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper=new ObjectMapper();
    public void insert(String aggregate, String aggregateId, String eventType, Map<String,Object> payload)
    {
        try{
            jdbc.update("""
    INSERT INTO outbox (id,aggregate,aggregate_id,event_type,payload,published,created_at)
    VALUES (?,?,?,?,?::jsonb,false,now())
""", UUID.randomUUID(),aggregate,aggregateId,eventType,mapper.writeValueAsString(payload)
                    );
  }catch (JsonProcessingException e)
        {
            throw new IllegalStateException("Payload serialization failed",e);
        }
    }
}
