package it.water.infrastructure.apigateway.service;

import it.water.core.interceptors.annotations.FrameworkComponent;
import it.water.core.api.service.BaseSystemApi;
import it.water.core.interceptors.annotations.Inject;
import it.water.core.permission.annotations.AllowGenericPermissions;
import it.water.core.service.BaseServiceImpl;
import it.water.infrastructure.apigateway.actions.ApiGatewayActions;
import it.water.infrastructure.apigateway.api.GatewayApi;
import it.water.infrastructure.apigateway.api.GatewaySystemApi;
import it.water.infrastructure.apigateway.model.ServiceStats;
import lombok.Setter;

import java.util.Map;

/**
 * Permission-checked service implementation for gateway management operations.
 * <p>
 * SECURITY (#32 / #17): this {@code @FrameworkComponent} is the authorization boundary in front of
 * {@link GatewaySystemApi} (a trusted SystemApi with no permission interceptors). It extends
 * {@link BaseServiceImpl} (an Api-layer base, {@code instanceof BaseApi}) so the permission
 * interceptor accepts the {@code @AllowGenericPermissions} checks declared below — a plain
 * {@code AbstractService} is rejected by the interceptor with a {@code WaterRuntimeException}.
 * The checks are enforced against the {@code Route} resource whose {@code @AccessControl} declares
 * the {@code view-metrics} / {@code refresh-routes} actions. The gateway management REST controller
 * delegates here (never to {@link GatewaySystemApi}), so a plain authenticated user without those
 * actions is rejected with {@code UnauthorizedException}.
 */
@FrameworkComponent
public class GatewayServiceImpl extends BaseServiceImpl implements GatewayApi {

    /**
     * Resource on which the gateway-management generic permissions are enforced. The Route entity's
     * {@code @AccessControl} declares the VIEW_METRICS / REFRESH_ROUTES custom actions, so management
     * operations are authorized against the Route resource.
     */
    private static final String GATEWAY_RESOURCE_NAME = "it.water.infrastructure.apigateway.model.Route";

    @Inject
    @Setter
    private GatewaySystemApi gatewaySystemApi;

    /**
     * {@link BaseServiceImpl} requires the backing system service; the gateway management operations
     * are served by {@link GatewaySystemApi} (a {@code BaseSystemApi}).
     */
    @Override
    protected BaseSystemApi getSystemService() {
        return gatewaySystemApi;
    }

    @Override
    @AllowGenericPermissions(actions = ApiGatewayActions.VIEW_METRICS, resourceName = GATEWAY_RESOURCE_NAME)
    public Map<String, ServiceStats> getServiceStatistics() {
        return gatewaySystemApi.getServiceStatistics();
    }

    @Override
    @AllowGenericPermissions(actions = ApiGatewayActions.REFRESH_ROUTES, resourceName = GATEWAY_RESOURCE_NAME)
    public void syncWithServiceDiscovery() {
        gatewaySystemApi.syncWithServiceDiscovery();
    }
}
