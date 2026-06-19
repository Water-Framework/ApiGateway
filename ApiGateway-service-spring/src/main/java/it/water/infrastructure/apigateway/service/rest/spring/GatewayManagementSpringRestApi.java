package it.water.infrastructure.apigateway.service.rest.spring;

import it.water.core.api.service.rest.FrameworkRestApi;
import it.water.infrastructure.apigateway.model.ServiceStats;
import it.water.service.rest.api.security.LoggedIn;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

/**
 * Spring REST API interface for Gateway Management.
 */
@RequestMapping("/gateway/management")
@FrameworkRestApi
public interface GatewayManagementSpringRestApi {

    @LoggedIn
    @GetMapping("/health")
    Map<String, Object> health();

    @LoggedIn
    @GetMapping("/metrics")
    Map<String, ServiceStats> metrics();

    @LoggedIn
    @GetMapping("/circuit-breakers")
    Map<String, String> circuitBreakers();

    @LoggedIn
    @PostMapping("/sync")
    ResponseEntity<Map<String, String>> syncServiceDiscovery();

}
