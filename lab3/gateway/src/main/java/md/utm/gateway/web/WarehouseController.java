package md.utm.gateway.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import md.utm.gateway.backend.Backend;
import md.utm.gateway.config.GatewayProperties;
import md.utm.gateway.scaling.ScalingException;
import md.utm.gateway.scaling.WarehouseManager;
import md.utm.gateway.scaling.WarehouseManager.ScaleAccepted;
import md.utm.gateway.scaling.WarehouseManager.WarehouseView;

/**
 * Warehouses, their API instances and their database copies, and scaling on the client's request.
 *
 * <pre>
 * GET    /gateway/warehouses                       all warehouses with their instances and Mongo nodes
 * PUT    /gateway/warehouses/{name} {"replicas":5}  5 API instances + 5 database copies (at least 3)
 * DELETE /gateway/warehouses/{name}                remove the warehouse, its containers and its data
 * </pre>
 *
 * Scaling runs in the background (starting Mongo nodes and electing a primary takes a while):
 * PUT and DELETE answer 202 at once, and GET shows the progress ({@code phase}).
 */
@RestController
@RequestMapping("/gateway/warehouses")
public class WarehouseController {

    public record ScaleRequest(Integer replicas) { }

    private final WarehouseManager manager;
    private final GatewayProperties props;

    public WarehouseController(WarehouseManager manager, GatewayProperties props) {
        this.manager = manager;
        this.props = props;
    }

    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("defaultWarehouse", props.defaultWarehouse());
        body.put("scalingEnabled", manager.isEnabled());
        body.put("maxReplicas", manager.maxReplicas());
        body.put("minDbCopies", manager.minDbCopies());
        body.put("warehouses", manager.view().stream().map(WarehouseController::warehouse).toList());
        return body;
    }

    @PutMapping("/{name}")
    public ResponseEntity<ScaleAccepted> scale(@PathVariable String name, @RequestBody ScaleRequest request) {
        if (request == null || request.replicas() == null) {
            throw new ScalingException(400, "Body must be {\"replicas\": <number>}");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(manager.scale(name, request.replicas()));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<ScaleAccepted> remove(@PathVariable String name) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(manager.remove(name));
    }

    @ExceptionHandler(ScalingException.class)
    public ResponseEntity<ProblemDetail> failed(ScalingException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
        return ResponseEntity.status(e.status()).body(problem);
    }

    private static Map<String, Object> warehouse(WarehouseView w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", w.name());
        m.put("database", w.database());
        m.put("replicaSet", w.replicaSet());
        m.put("desiredReplicas", w.desiredReplicas());
        m.put("replicas", w.instances().size());
        m.put("healthy", w.instances().stream().filter(Backend::isHealthy).count());
        m.put("phase", w.phase());
        m.put("lastError", w.lastError());
        m.put("instances", w.instances().stream().map(WarehouseController::instance).toList());
        m.put("dbNodes", w.dbNodes().stream().map(n -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", n.name());
            d.put("index", n.index());
            d.put("host", n.host());
            d.put("state", n.state());
            return d;
        }).toList());
        return m;
    }

    static Map<String, Object> instance(Backend b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", b.name());
        m.put("url", b.baseUri().toString());
        m.put("hostPort", b.hostPort());
        m.put("directUrl", b.hostPort() == null ? null : "http://localhost:" + b.hostPort());
        m.put("state", b.state().name());
        m.put("managed", b.isManaged());
        m.put("activeRequests", b.active());
        m.put("lastError", b.lastError());
        return m;
    }
}
