package com;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReassignService {
    private static final Set<String>  TERMINAL=Set.of("DELIVERED","CANCELLED");
    private final JdbcTemplate jdbc;
    private final OutboxRepository outbox;
    @Transactional
    public String reassign(String orderId,String newDriverId,String reason){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT status ,assigned_driver FROM orders WHERE id=?",orderId);
        if(rows.isEmpty())
        {
            throw new ToolRejection("ORDER NOT FOUND WITH ID ="+orderId);
        }
        String status=(String)rows.getFirst().get("status");
        String oldDriver=(String)rows.getFirst().get("assigned_driver");
        if(TERMINAL.contains(status))
        {
            throw new ToolRejection("order"+orderId+" is already "+status);
        }
        if(newDriverId.equals(oldDriver))
        {
            throw new ToolRejection("driver "+newDriverId + " is already assigned to "+orderId);

        }
        //atomic claim-check and act in one statement
        int claimed=jdbc.update(
                "UPDATE drivers SET status = 'TO_PICKUP',updated_at= now() WHERE id=? AND status='IDLE'",
                newDriverId
        );
        if(claimed==0){
            throw new ToolRejection("driver "+newDriverId+" is not IDLE(busy,offline or unknown)");

        }
        jdbc.update("" +
                "UPDATE orders SET assigned_driver=?,status='ASSIGNED',updated_at=now() WHERE id=?",
                newDriverId,orderId);
        //mirror DispatchAction.asvc
        Map<String,Object> payload=new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("action", "REASSIGN");
        payload.put("targetId", newDriverId);
        payload.put("requestedTs", System.currentTimeMillis());
        payload.put("reason", reason);  // extra context; publisher ignores unknown keys

        outbox.insert("order", orderId, "DispatchAction", payload);
        return "order %s reassigned from %s to %s".formatted(orderId, oldDriver, newDriverId);
    }

}
