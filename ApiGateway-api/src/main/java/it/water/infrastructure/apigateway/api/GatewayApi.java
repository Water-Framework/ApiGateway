package it.water.infrastructure.apigateway.api;

import it.water.core.api.service.BaseApi;
import it.water.infrastructure.apigateway.model.ServiceStats;

import java.util.Map;

/**
 * Public, permission-checked service API for gateway management operations.
 * <p>
 * This is the authorization boundary in front of {@link GatewaySystemApi}: the gateway management
 * REST controller MUST delegate to this interface (never to the SystemApi directly), because the
 * implementation ({@code GatewayServiceImpl}, a {@code @FrameworkComponent}) carries the
 * {@code @AllowGenericPermissions} checks against the Route resource. The SystemApi remains the
 * trusted/internal layer with no permission interceptors.
 * <p>
 * No permission annotations are declared here on purpose: {@code ApiGateway-api} must stay free of
 * any dependency on {@code Core-permission}. The annotations live on the implementation in
 * {@code ApiGateway-service}.
 * <p>
 * Extends {@link BaseApi} (not the bare {@code Service}) because the permission interceptor only
 * accepts {@code @AllowGenericPermissions} on Api-layer components ({@code instanceof BaseApi}); a
 * plain {@code Service} is rejected with a {@code WaterRuntimeException}.
 */
public interface GatewayApi extends BaseApi {

    /**
     * @return per-service runtime statistics (request counts, latencies, circuit-breaker state, ...).
     * Requires the {@code view-metrics} action on the Route resource.
     */
    Map<String, ServiceStats> getServiceStatistics();

    /**
     * Forces a synchronization of the gateway routing state with the ServiceDiscovery registry.
     * Requires the {@code refresh-routes} action on the Route resource.
     */
    void syncWithServiceDiscovery();
}
