package it.water.infrastructure.apigateway.service.rest;

import it.water.core.api.service.rest.FrameworkRestController;
import it.water.core.interceptors.annotations.Inject;
import it.water.infrastructure.apigateway.api.GatewayApi;
import it.water.infrastructure.apigateway.api.rest.GatewayManagementRestApi;
import it.water.infrastructure.apigateway.model.CircuitState;
import it.water.infrastructure.apigateway.model.ServiceStats;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * REST controller for gateway management operations.
 */
@FrameworkRestController(referredRestApi = GatewayManagementRestApi.class)
public class GatewayManagementRestControllerImpl implements GatewayManagementRestApi {

    private static final Logger log = LoggerFactory.getLogger(GatewayManagementRestControllerImpl.class);

    @Inject
    @Setter
    private GatewayApi gatewayApi;

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
        stats.forEach((service, stat) -> {
            CircuitState state = stat.getCircuitState();
            result.put(service, state != null ? state.name() : CircuitState.CLOSED.name());
        });
        return result;
    }

    @Override
    public Response syncServiceDiscovery() {
        log.info("REST: triggering ServiceDiscovery sync");
        try {
            gatewayApi.syncWithServiceDiscovery();
            return Response.noContent().build();
        } catch (Exception e) {
            log.error("REST: ServiceDiscovery sync failed: {}", e.getMessage(), e);
            return Response.status(502)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(Map.of("error", e.getMessage() == null ? "" : e.getMessage()))
                    .build();
        }
    }
}
