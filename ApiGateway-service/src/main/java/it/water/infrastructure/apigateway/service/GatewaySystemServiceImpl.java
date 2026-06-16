package it.water.infrastructure.apigateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.water.core.api.interceptors.OnActivate;
import it.water.core.api.interceptors.OnDeactivate;
import it.water.core.interceptors.annotations.FrameworkComponent;
import it.water.core.interceptors.annotations.Inject;
import it.water.infrastructure.apigateway.api.CircuitBreakerApi;
import it.water.infrastructure.apigateway.api.GatewaySystemApi;
import it.water.infrastructure.apigateway.api.RouteRepository;
import it.water.infrastructure.apigateway.api.options.GatewaySystemOptions;
import it.water.infrastructure.apigateway.model.CircuitState;
import it.water.infrastructure.apigateway.model.Route;
import it.water.infrastructure.apigateway.model.ServiceStats;
import it.water.repository.service.BaseEntitySystemServiceImpl;
import it.water.service.discovery.api.ServiceRegistrationApi;
import it.water.service.discovery.model.ServiceRegistration;
import it.water.service.discovery.model.ServiceStatus;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gateway system service - handles service discovery integration and gateway management.
 *
 * <p><b>SECURITY (#17) — SystemApi is a TRUSTED layer that bypasses ALL permission interceptors.</b>
 * Operational methods here ({@code getServiceStatistics}, {@code syncWithServiceDiscovery}, ...) run with
 * NO authorization checks. This class MUST NEVER be the dependency that an externally-reachable REST
 * controller relies on for authorization — the management controller delegates instead to the
 * permission-checked {@code GatewayApi} ({@code GatewayServiceImpl}), which carries
 * {@code @AllowGenericPermissions} (VIEW_METRICS / REFRESH_ROUTES on the Route resource). The earlier
 * {@code @LoggedIn}-only wiring (#32) let any authenticated user reach this trusted layer; that gap is
 * now closed at the Api layer.
 */
@FrameworkComponent
public class GatewaySystemServiceImpl extends BaseEntitySystemServiceImpl<Route> implements GatewaySystemApi {

    private static final Logger log = LoggerFactory.getLogger(GatewaySystemServiceImpl.class);
    private static final Duration SERVICE_DISCOVERY_TIMEOUT = Duration.ofSeconds(10);

    @Inject
    @Getter
    @Setter
    private RouteRepository repository;

    @Inject
    @Getter
    @Setter
    private CircuitBreakerApi circuitBreakerApi;

    @Inject
    @Getter
    @Setter
    private ServiceRegistrationApi serviceRegistrationApi;

    @Inject
    @Setter
    private GatewaySystemOptions gatewaySystemOptions;

    private volatile Map<String, List<ServiceRegistration>> serviceCache = new ConcurrentHashMap<>();
    private final Map<String, ServiceStats> statsMap = new ConcurrentHashMap<>();
    private HttpClient serviceDiscoveryHttpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GatewaySystemServiceImpl() {
        super(Route.class);
    }

    @OnActivate
    public void activate() {
        log.info("GatewaySystemServiceImpl activating: initializing HTTP client");
        this.serviceDiscoveryHttpClient = HttpClient.newBuilder()
                .connectTimeout(SERVICE_DISCOVERY_TIMEOUT)
                .build();
    }

    @OnDeactivate
    public void deactivate() {
        log.info("GatewaySystemServiceImpl deactivating: releasing HTTP client and caches");
        // HttpClient is not AutoCloseable on Java 17; dereferencing lets the GC
        // close its internal selector/executor threads once the component is unloaded.
        this.serviceDiscoveryHttpClient = null;
        this.serviceCache = new ConcurrentHashMap<>();
        this.statsMap.clear();
    }

    @Override
    public void syncWithServiceDiscovery() {
        log.info("Syncing with ServiceDiscovery");
        try {
            List<ServiceRegistration> services = fetchAvailableServices();
            refreshServiceCache(services);
            log.info("Synced {} service instances from ServiceDiscovery", services.size());
        } catch (Exception e) {
            log.error("Failed to sync with ServiceDiscovery: {}", e.getMessage(), e);
            throw new IllegalStateException("ServiceDiscovery sync failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void evictServiceFromCache(String serviceName) {
        log.info("Evicting service from cache: {}", serviceName);
        serviceCache.remove(serviceName);
    }

    @Override
    public Map<String, ServiceStats> getServiceStatistics() {
        return Collections.unmodifiableMap(statsMap);
    }

    @Override
    public List<ServiceRegistration> getHealthyInstances(String serviceName) {
        List<ServiceRegistration> instances = serviceCache.getOrDefault(serviceName, Collections.emptyList());
        List<ServiceRegistration> healthy = new ArrayList<>();
        for (ServiceRegistration reg : instances) {
            if (reg.getStatus() == ServiceStatus.UP) {
                CircuitState state = circuitBreakerApi.getState(serviceName, reg.getInstanceId());
                if (state == CircuitState.CLOSED || state == CircuitState.HALF_OPEN) {
                    healthy.add(reg);
                }
            }
        }
        return healthy;
    }

    public void recordRequest(String serviceName, boolean success, long latencyMs) {
        ServiceStats stats = statsMap.computeIfAbsent(serviceName, k ->
                ServiceStats.builder().serviceName(k).circuitState(CircuitState.CLOSED).build());
        stats.setTotalRequests(stats.getTotalRequests() + 1);
        if (success) {
            stats.setSuccessCount(stats.getSuccessCount() + 1);
        } else {
            stats.setFailureCount(stats.getFailureCount() + 1);
        }
        // Rolling average latency
        double currentAvg = stats.getAvgLatencyMs();
        long total = stats.getTotalRequests();
        stats.setAvgLatencyMs((currentAvg * (total - 1) + latencyMs) / total);
    }

    public List<ServiceRegistration> getCachedInstances(String serviceName) {
        return serviceCache.getOrDefault(serviceName, Collections.emptyList());
    }

    private List<ServiceRegistration> fetchAvailableServices() throws IOException, InterruptedException {
        if (serviceRegistrationApi != null) {
            try {
                return serviceRegistrationApi.getAvailableServices();
            } catch (Exception e) {
                if (!hasRemoteServiceDiscoveryConfigured()) {
                    throw e;
                }
                log.warn("Local ServiceDiscovery API unavailable, falling back to remote HTTP sync: {}", e.getMessage());
            }
        }
        return fetchAvailableServicesRemotely();
    }

    private List<ServiceRegistration> fetchAvailableServicesRemotely() throws IOException, InterruptedException {
        HttpClient client = serviceDiscoveryHttpClient;
        if (client == null) {
            throw new IllegalStateException("GatewaySystemServiceImpl is not active: HTTP client not initialized");
        }
        String endpoint = resolveServiceDiscoveryEndpoint();
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(endpoint))
                .GET()
                .timeout(SERVICE_DISCOVERY_TIMEOUT)
                .header("Accept", "application/json");
        HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Remote ServiceDiscovery returned HTTP " + response.statusCode());
        }
        return parseRemoteServiceRegistrations(response.body());
    }

    private List<ServiceRegistration> parseRemoteServiceRegistrations(String responseBody) throws IOException {
        List<ServiceRegistration> services = objectMapper.readValue(responseBody, new TypeReference<List<ServiceRegistration>>() {});
        return services == null ? Collections.emptyList() : services;
    }

    private void refreshServiceCache(List<ServiceRegistration> services) {
        Map<String, List<ServiceRegistration>> refreshedCache = new ConcurrentHashMap<>();
        for (ServiceRegistration reg : services) {
            refreshedCache.computeIfAbsent(reg.getServiceName(), k -> new ArrayList<>()).add(reg);
        }
        serviceCache = refreshedCache;
    }

    private boolean hasRemoteServiceDiscoveryConfigured() {
        return !getConfiguredServiceDiscoveryUrl().isBlank();
    }

    private String resolveServiceDiscoveryEndpoint() {
        String configuredUrl = getConfiguredServiceDiscoveryUrl();
        if (configuredUrl.isBlank()) {
            throw new IllegalStateException(
                    "No local ServiceDiscovery API available and GatewaySystemOptions.getServiceDiscoveryUrl() is not configured");
        }
        String normalizedUrl = configuredUrl.endsWith("/") ? configuredUrl.substring(0, configuredUrl.length() - 1) : configuredUrl;
        if (normalizedUrl.endsWith("/internal/serviceregistration/available")) {
            return normalizedUrl;
        }
        if (normalizedUrl.endsWith("/water")) {
            return normalizedUrl + "/internal/serviceregistration/available";
        }
        return normalizedUrl + "/water/internal/serviceregistration/available";
    }

    private String getConfiguredServiceDiscoveryUrl() {
        if (gatewaySystemOptions == null) {
            return "";
        }
        String url = gatewaySystemOptions.getServiceDiscoveryUrl();
        return url == null ? "" : url.trim();
    }
}
