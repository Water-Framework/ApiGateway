package it.water.infrastructure.apigateway.service.rest.spring;

import it.water.infrastructure.apigateway.api.GatewayApi;
import it.water.infrastructure.apigateway.model.ServiceStats;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring REST controller for Gateway Management.
 * <p>
 * SECURITY (#32 / #17): delegates to the permission-checked {@link GatewayApi}, never to the
 * trusted {@code GatewaySystemApi}. Authorization (VIEW_METRICS / REFRESH_ROUTES on the Route
 * resource) is enforced in the service layer, consistent with the JAX-RS runtime controller.
 */
@RestController
public class GatewayManagementSpringRestControllerImpl implements GatewayManagementSpringRestApi {

    private final GatewayApi gatewayApi;

    public GatewayManagementSpringRestControllerImpl(GatewayApi gatewayApi) {
        this.gatewayApi = gatewayApi;
    }

    @Override
    public Map<String, Object> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());
        health.put("service", "ApiGateway");
        return health;
    }

    @Override
    public Map<String, ServiceStats> metrics() {
        return gatewayApi.getServiceStatistics();
    }

    @Override
    public Map<String, String> circuitBreakers() {
        Map<String, ServiceStats> stats = gatewayApi.getServiceStatistics();
        Map<String, String> result = new HashMap<>();
        stats.forEach((service, stat) -> result.put(service,
                stat.getCircuitState() != null ? stat.getCircuitState().name() : "CLOSED"));
        return result;
    }

    @Override
    public ResponseEntity<Map<String, String>> syncServiceDiscovery() {
        try {
            gatewayApi.syncWithServiceDiscovery();
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", e.getMessage() == null ? "" : e.getMessage()));
        }
    }
}
