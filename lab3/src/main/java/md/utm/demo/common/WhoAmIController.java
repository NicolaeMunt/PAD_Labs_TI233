package md.utm.demo.common;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WhoAmIController {

    private final MongoTemplate mongoTemplate;
    private final String instanceId;
    private final String warehouse;

    public WhoAmIController(MongoTemplate mongoTemplate,
                            @Value("${app.instance-id:unknown}") String instanceId,
                            @Value("${app.warehouse:unknown}") String warehouse) {
        this.mongoTemplate = mongoTemplate;
        this.instanceId = instanceId;
        this.warehouse = warehouse;
    }

    @GetMapping("/whoami")
    public Map<String, String> whoAmI() {
        return Map.of(
                "instanceId", instanceId,
                "warehouse", warehouse,
                "database", mongoTemplate.getDb().getName());
    }
}