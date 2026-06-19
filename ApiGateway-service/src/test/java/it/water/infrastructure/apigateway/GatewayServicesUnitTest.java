package it.water.infrastructure.apigateway;

import it.water.infrastructure.apigateway.api.CircuitBreakerApi;
import it.water.infrastructure.apigateway.api.GatewaySystemApi;
import it.water.infrastructure.apigateway.api.LoadBalancerApi;
import it.water.infrastructure.apigateway.api.RateLimiterApi;
import it.water.infrastructure.apigateway.api.RequestTransformerApi;
import it.water.infrastructure.apigateway.api.RouteRepository;
import it.water.infrastructure.apigateway.api.options.GatewaySystemOptions;
import it.water.infrastructure.apigateway.model.CircuitState;
import it.water.infrastructure.apigateway.model.GatewayRequest;
import it.water.infrastructure.apigateway.model.GatewayResponse;
import it.water.infrastructure.apigateway.model.HttpMethod;
import it.water.infrastructure.apigateway.model.RateLimitResult;
import it.water.infrastructure.apigateway.model.Route;
import it.water.infrastructure.apigateway.service.GatewayRouterServiceImpl;
import it.water.infrastructure.apigateway.service.GatewaySystemServiceImpl;
import it.water.service.discovery.api.ServiceRegistrationApi;
import it.water.service.discovery.model.ServiceRegistration;
import it.water.service.discovery.model.ServiceStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

class GatewayServicesUnitTest {

    @AfterEach
    void clearInterruptedFlag() {
        Thread.interrupted();
    }

    @Test
    void gatewaySystemSyncUsesLocalServiceDiscoveryAndFiltersHealthyInstances() {
        GatewaySystemServiceImpl system = new GatewaySystemServiceImpl();
        ServiceRegistrationApi serviceRegistrationApi = Mockito.mock(ServiceRegistrationApi.class);
        CircuitBreakerApi circuitBreakerApi = Mockito.mock(CircuitBreakerApi.class);

        ServiceRegistration closed = registration("svc", "closed", ServiceStatus.UP);
        ServiceRegistration halfOpen = registration("svc", "half-open", ServiceStatus.UP);
        ServiceRegistration open = registration("svc", "open", ServiceStatus.UP);
        ServiceRegistration down = registration("svc", "down", ServiceStatus.DOWN);

        Mockito.when(serviceRegistrationApi.getAvailableServices())
                .thenReturn(List.of(closed, halfOpen, open, down));
        Mockito.when(circuitBreakerApi.getState("svc", "closed")).thenReturn(CircuitState.CLOSED);
        Mockito.when(circuitBreakerApi.getState("svc", "half-open")).thenReturn(CircuitState.HALF_OPEN);
        Mockito.when(circuitBreakerApi.getState("svc", "open")).thenReturn(CircuitState.OPEN);
        system.setServiceRegistrationApi(serviceRegistrationApi);
        system.setCircuitBreakerApi(circuitBreakerApi);

        system.syncWithServiceDiscovery();

        Assertions.assertEquals(4, system.getCachedInstances("svc").size());
        List<ServiceRegistration> healthy = system.getHealthyInstances("svc");
        Assertions.assertEquals(List.of(closed, halfOpen), healthy);

        system.recordRequest("svc", true, 100);
        system.recordRequest("svc", false, 50);
        Assertions.assertEquals(2, system.getServiceStatistics().get("svc").getTotalRequests());
        Assertions.assertEquals(1, system.getServiceStatistics().get("svc").getSuccessCount());
        Assertions.assertEquals(1, system.getServiceStatistics().get("svc").getFailureCount());
        Assertions.assertEquals(75.0, system.getServiceStatistics().get("svc").getAvgLatencyMs());
    }

    @Test
    void gatewaySystemDeactivateClearsRuntimeState() {
        GatewaySystemServiceImpl system = new GatewaySystemServiceImpl();
        system.activate();
        system.recordRequest("svc", true, 10);
        system.evictServiceFromCache("unknown");

        system.deactivate();

        Assertions.assertTrue(system.getServiceStatistics().isEmpty());
        Assertions.assertTrue(system.getCachedInstances("svc").isEmpty());
    }

    @Test
    void gatewaySystemRemoteHelpersNormalizeEndpointsAndNullResponses() throws Exception {
        GatewaySystemServiceImpl system = new GatewaySystemServiceImpl();
        GatewaySystemOptions options = Mockito.mock(GatewaySystemOptions.class);
        system.setGatewaySystemOptions(options);

        Mockito.when(options.getServiceDiscoveryUrl()).thenReturn(" http://localhost:9000/water/ ");
        Assertions.assertEquals("http://localhost:9000/water/internal/serviceregistration/available",
                invokeString(system, "resolveServiceDiscoveryEndpoint"));

        Mockito.when(options.getServiceDiscoveryUrl()).thenReturn("http://localhost:9000/internal/serviceregistration/available");
        Assertions.assertEquals("http://localhost:9000/internal/serviceregistration/available",
                invokeString(system, "resolveServiceDiscoveryEndpoint"));

        Mockito.when(options.getServiceDiscoveryUrl()).thenReturn("http://localhost:9000");
        Assertions.assertEquals("http://localhost:9000/water/internal/serviceregistration/available",
                invokeString(system, "resolveServiceDiscoveryEndpoint"));

        Method parse = GatewaySystemServiceImpl.class
                .getDeclaredMethod("parseRemoteServiceRegistrations", String.class);
        parse.setAccessible(true);
        Assertions.assertEquals(List.of(), parse.invoke(system, "null"));
    }

    @Test
    void gatewaySystemSyncReInterruptsWhenRemoteFetchIsInterrupted() throws Exception {
        GatewaySystemServiceImpl system = new GatewaySystemServiceImpl();
        GatewaySystemOptions options = Mockito.mock(GatewaySystemOptions.class);
        HttpClient httpClient = Mockito.mock(HttpClient.class);

        Mockito.when(options.getServiceDiscoveryUrl()).thenReturn("http://localhost:9000/water");
        Mockito.when(httpClient.send(Mockito.any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new InterruptedException("stop"));

        system.setGatewaySystemOptions(options);
        setField(system, "serviceDiscoveryHttpClient", httpClient);

        IllegalStateException thrown = Assertions.assertThrows(IllegalStateException.class,
                system::syncWithServiceDiscovery);
        Assertions.assertTrue(thrown.getMessage().contains("interrupted"));
        Assertions.assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void routerRoutesSuccessfulAndFailingResponsesThroughMockedHttpClient() throws Exception {
        GatewayRouterServiceImpl router = newConfiguredRouter(mockResponseClient(200, "ok"));
        String serviceName = "svc-router";
        ServiceRegistration instance = registration(serviceName, "inst-1", ServiceStatus.UP);
        Route route = new Route("route-success", "/water/**", HttpMethod.ANY, serviceName, 10, true);
        GatewayRequest request = request("/water/test", "127.0.0.1");

        configureRouteDependencies(router, route, instance, true);

        GatewayResponse success = router.route(request);

        Assertions.assertEquals(200, success.getStatusCode());
        Assertions.assertEquals("inst-1", success.getUpstreamInstanceId());

        GatewayRouterServiceImpl failingRouter = newConfiguredRouter(mockResponseClient(503, "down"));
        configureRouteDependencies(failingRouter, route, instance, true);

        GatewayResponse failure = failingRouter.route(request);

        Assertions.assertEquals(503, failure.getStatusCode());
    }

    @Test
    void routerReturnsCircuitBreakerAndInterruptedProxyErrors() throws Exception {
        String serviceName = "svc-router-errors";
        ServiceRegistration instance = registration(serviceName, "inst-2", ServiceStatus.UP);
        Route route = new Route("route-errors", "/errors/**", HttpMethod.ANY, serviceName, 10, true);

        GatewayRouterServiceImpl blockedRouter = newConfiguredRouter(mockResponseClient(200, "unused"));
        configureRouteDependencies(blockedRouter, route, instance, false);
        GatewayResponse blocked = blockedRouter.route(request("/errors/test", null));
        Assertions.assertEquals(503, blocked.getStatusCode());

        HttpClient interruptedClient = Mockito.mock(HttpClient.class);
        Mockito.when(interruptedClient.send(Mockito.any(HttpRequest.class),
                        Mockito.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenThrow(new InterruptedException("stop"));
        GatewayRouterServiceImpl interruptedRouter = newConfiguredRouter(interruptedClient);
        configureRouteDependencies(interruptedRouter, route, instance, true);

        GatewayResponse interrupted = interruptedRouter.route(request("/errors/test", "10.0.0.2"));

        Assertions.assertEquals(502, interrupted.getStatusCode());
        Assertions.assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void routerLifecycleAndRefreshHandleUnavailableRepository() {
        GatewayRouterServiceImpl router = new GatewayRouterServiceImpl();
        router.activate(null, Mockito.mock(GatewaySystemApi.class));

        Assertions.assertTrue(router.getActiveRoutes().isEmpty());

        RouteRepository routeRepository = Mockito.mock(RouteRepository.class);
        Mockito.when(routeRepository.findOrderedByPriority()).thenThrow(new IllegalStateException("database down"));
        router.setRouteRepository(routeRepository);

        Assertions.assertDoesNotThrow(router::refreshRoutes);
        router.deactivate();
        Assertions.assertTrue(router.getActiveRoutes().isEmpty());
    }

    private GatewayRouterServiceImpl newConfiguredRouter(HttpClient httpClient) throws Exception {
        GatewayRouterServiceImpl router = new GatewayRouterServiceImpl();
        router.activate(null, Mockito.mock(GatewaySystemApi.class));
        setField(router, "httpClient", httpClient);
        return router;
    }

    private void configureRouteDependencies(GatewayRouterServiceImpl router, Route route,
                                            ServiceRegistration instance, boolean circuitAllowsRequest) {
        GatewaySystemApi gatewaySystemApi = Mockito.mock(GatewaySystemApi.class);
        LoadBalancerApi loadBalancerApi = Mockito.mock(LoadBalancerApi.class);
        RateLimiterApi rateLimiterApi = Mockito.mock(RateLimiterApi.class);
        CircuitBreakerApi circuitBreakerApi = Mockito.mock(CircuitBreakerApi.class);
        RequestTransformerApi requestTransformerApi = Mockito.mock(RequestTransformerApi.class);

        Mockito.when(gatewaySystemApi.getHealthyInstances(route.getTargetServiceName()))
                .thenReturn(List.of(instance));
        Mockito.when(loadBalancerApi.selectInstance(Mockito.eq(route.getTargetServiceName()),
                Mockito.any(GatewayRequest.class), Mockito.eq(List.of(instance)))).thenReturn(instance);
        Mockito.when(rateLimiterApi.checkRateLimit(Mockito.anyString(), Mockito.any(GatewayRequest.class)))
                .thenReturn(RateLimitResult.builder().allowed(true).remaining(1).build());
        Mockito.when(circuitBreakerApi.allowRequest(route.getTargetServiceName(), instance.getInstanceId()))
                .thenReturn(circuitAllowsRequest);
        Mockito.when(requestTransformerApi.transformRequest(Mockito.any(GatewayRequest.class), Mockito.eq(route)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(requestTransformerApi.transformResponse(Mockito.any(GatewayResponse.class), Mockito.eq(route)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        router.setGatewaySystemApi(gatewaySystemApi);
        router.setLoadBalancerApi(loadBalancerApi);
        router.setRateLimiterApi(rateLimiterApi);
        router.setCircuitBreakerApi(circuitBreakerApi);
        router.setRequestTransformerApi(requestTransformerApi);
        router.addDynamicRoute(route);
    }

    @SuppressWarnings("unchecked")
    private HttpClient mockResponseClient(int statusCode, String body) throws IOException, InterruptedException {
        HttpClient httpClient = Mockito.mock(HttpClient.class);
        HttpResponse<byte[]> response = Mockito.mock(HttpResponse.class);
        Mockito.when(response.statusCode()).thenReturn(statusCode);
        Mockito.when(response.body()).thenReturn(body.getBytes());
        Mockito.when(response.headers()).thenReturn(HttpHeaders.of(
                Map.of("content-type", List.of("text/plain")),
                (name, value) -> true
        ));
        Mockito.when(httpClient.send(Mockito.any(HttpRequest.class), Mockito.any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        return httpClient;
    }

    private GatewayRequest request(String path, String clientIp) {
        return GatewayRequest.builder()
                .method(HttpMethod.GET)
                .path(path)
                .clientIp(clientIp)
                .build();
    }

    private ServiceRegistration registration(String serviceName, String instanceId, ServiceStatus status) {
        return new ServiceRegistration(serviceName, "1.0", instanceId,
                "http://localhost:9081/water", "http", status);
    }

    private String invokeString(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (String) method.invoke(target);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
