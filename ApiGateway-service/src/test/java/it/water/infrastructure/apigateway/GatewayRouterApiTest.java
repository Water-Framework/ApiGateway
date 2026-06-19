package it.water.infrastructure.apigateway;

import it.water.core.api.bundle.ApplicationProperties;
import it.water.infrastructure.apigateway.api.CircuitBreakerApi;
import it.water.infrastructure.apigateway.api.GatewayRouterApi;
import it.water.infrastructure.apigateway.api.GatewaySystemApi;
import it.water.infrastructure.apigateway.api.options.GatewaySystemOptions;
import it.water.infrastructure.apigateway.api.RateLimiterApi;
import it.water.infrastructure.apigateway.api.RouteSystemApi;
import it.water.infrastructure.apigateway.model.*;
import it.water.infrastructure.apigateway.service.GatewayRouterServiceImpl;
import it.water.infrastructure.apigateway.service.GatewaySystemServiceImpl;
import it.water.core.api.registry.ComponentRegistry;
import it.water.core.api.service.Service;
import it.water.core.interceptors.annotations.Inject;
import it.water.core.testing.utils.junit.WaterTestExtension;
import it.water.core.testing.utils.runtime.TestRuntimeUtils;
import it.water.service.discovery.model.ServiceRegistration;
import it.water.service.discovery.model.ServiceStatus;
import lombok.Setter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Unit tests for GatewayRouterServiceImpl covering routing, dynamic routes,
 * path pattern matching, and error responses.
 */
@ExtendWith(WaterTestExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayRouterApiTest implements Service {

    @Inject
    @Setter
    private ComponentRegistry componentRegistry;

    @Inject
    @Setter
    private GatewayRouterApi gatewayRouterApi;

    @Inject
    @Setter
    private RouteSystemApi routeSystemApi;

    @Inject
    @Setter
    private CircuitBreakerApi circuitBreakerApi;

    @Inject
    @Setter
    private RateLimiterApi rateLimiterApi;

    @Inject
    @Setter
    private GatewaySystemApi gatewaySystemApi;

    @Inject
    @Setter
    private GatewaySystemOptions gatewaySystemOptions;

    @Inject
    @Setter
    private ApplicationProperties applicationProperties;

    @BeforeAll
    void beforeAll() {
        TestRuntimeUtils.impersonateAdmin(componentRegistry);
    }

    @Test
    @Order(1)
    void componentInstantiatedCorrectly() {
        Assertions.assertNotNull(gatewayRouterApi);
    }

    @Test
    @Order(2)
    void getActiveRoutesReturnsNonNull() {
        List<Route> routes = gatewayRouterApi.getActiveRoutes();
        Assertions.assertNotNull(routes);
    }

    @Test
    @Order(3)
    void addDynamicRouteAddsToActiveRoutes() {
        Route route = new Route("dyn-route-1", "/dynamic/**", HttpMethod.ANY, "svc-a", 10, true);
        gatewayRouterApi.addDynamicRoute(route);
        List<Route> routes = gatewayRouterApi.getActiveRoutes();
        Assertions.assertTrue(routes.stream().anyMatch(r -> "dyn-route-1".equals(r.getRouteId())));
    }

    @Test
    @Order(4)
    void removeDynamicRouteRemovesFromActiveRoutes() {
        gatewayRouterApi.removeDynamicRoute("dyn-route-1");
        List<Route> routes = gatewayRouterApi.getActiveRoutes();
        Assertions.assertTrue(routes.stream().noneMatch(r -> "dyn-route-1".equals(r.getRouteId())));
    }

    @Test
    @Order(5)
    void addMultipleRoutesAreSortedByPriority() {
        Route low = new Route("prio-low", "/multi/**", HttpMethod.ANY, "svc-b", 1, true);
        Route high = new Route("prio-high", "/multi/**", HttpMethod.ANY, "svc-b", 999, true);
        gatewayRouterApi.addDynamicRoute(low);
        gatewayRouterApi.addDynamicRoute(high);
        List<Route> routes = gatewayRouterApi.getActiveRoutes();
        int idxHigh = -1, idxLow = -1;
        for (int i = 0; i < routes.size(); i++) {
            if ("prio-high".equals(routes.get(i).getRouteId())) idxHigh = i;
            if ("prio-low".equals(routes.get(i).getRouteId())) idxLow = i;
        }
        Assertions.assertTrue(idxHigh >= 0 && idxLow >= 0, "Both routes should be present");
        Assertions.assertTrue(idxHigh < idxLow, "Higher priority route should appear before lower priority");
    }

    @Test
    @Order(6)
    void refreshRoutesLoadsFromDatabase() {
        Route dbRoute = new Route("db-route-grt", "/db-grt/**", HttpMethod.GET, "db-svc", 5, true);
        routeSystemApi.save(dbRoute);
        Assertions.assertDoesNotThrow(() -> gatewayRouterApi.refreshRoutes());
        List<Route> routes = gatewayRouterApi.getActiveRoutes();
        Assertions.assertNotNull(routes);
        // db-route-grt should be loaded from DB
        Assertions.assertTrue(routes.stream().anyMatch(r -> "db-route-grt".equals(r.getRouteId())));
    }

    @Test
    @Order(7)
    void resolveRouteReturnsNullWhenNoMatch() {
        GatewayRequest req = buildRequest(HttpMethod.GET, "/zzz-absolutely-no-match/xyz/here");
        RouteResult result = gatewayRouterApi.resolveRoute(req);
        Assertions.assertNull(result);
    }

    @Test
    @Order(8)
    void resolveRouteFindsMatchingRoute() {
        Route route = new Route("find-me-grt", "/findme-grt/**", HttpMethod.ANY, "svc-c", 100, true);
        gatewayRouterApi.addDynamicRoute(route);
        GatewayRequest req = buildRequest(HttpMethod.GET, "/findme-grt/resource/123");
        RouteResult result = gatewayRouterApi.resolveRoute(req);
        Assertions.assertNotNull(result);
        Assertions.assertNotNull(result.getRoute());
        Assertions.assertEquals("find-me-grt", result.getRoute().getRouteId());
    }

    @Test
    @Order(9)
    void resolveRouteSkipsDisabledRoutes() {
        Route disabled = new Route("disabled-grt", "/disabled-grt/**", HttpMethod.ANY, "svc-d", 500, false);
        gatewayRouterApi.addDynamicRoute(disabled);
        GatewayRequest req = buildRequest(HttpMethod.GET, "/disabled-grt/test");
        RouteResult result = gatewayRouterApi.resolveRoute(req);
        boolean matchesDisabled = result != null && "disabled-grt".equals(result.getRoute().getRouteId());
        Assertions.assertFalse(matchesDisabled, "Disabled route should be skipped");
    }

    @Test
    @Order(10)
    void resolveRouteWithMethodFilterDoesNotMatchWrongMethod() {
        Route postOnly = new Route("post-only-grt", "/postroute-grt/**", HttpMethod.POST, "svc-e", 50, true);
        gatewayRouterApi.addDynamicRoute(postOnly);
        GatewayRequest getReq = buildRequest(HttpMethod.GET, "/postroute-grt/data");
        RouteResult result = gatewayRouterApi.resolveRoute(getReq);
        boolean matchesPostOnly = result != null && "post-only-grt".equals(result.getRoute().getRouteId());
        Assertions.assertFalse(matchesPostOnly, "GET request should not match a POST-only route");
    }

    @Test
    @Order(11)
    void resolveRouteWithMethodFilterMatchesCorrectMethod() {
        GatewayRequest postReq = buildRequest(HttpMethod.POST, "/postroute-grt/data");
        RouteResult result = gatewayRouterApi.resolveRoute(postReq);
        Assertions.assertNotNull(result, "POST request should match the POST-only route");
        Assertions.assertEquals("post-only-grt", result.getRoute().getRouteId());
    }

    @Test
    @Order(12)
    void resolveRouteWithPathVariablePattern() {
        Route route = new Route("var-route-grt", "/api/{version}/users", HttpMethod.ANY, "user-svc", 30, true);
        gatewayRouterApi.addDynamicRoute(route);
        GatewayRequest req = buildRequest(HttpMethod.GET, "/api/v1/users");
        RouteResult result = gatewayRouterApi.resolveRoute(req);
        Assertions.assertNotNull(result);
        Assertions.assertEquals("var-route-grt", result.getRoute().getRouteId());
    }

    @Test
    @Order(13)
    void routeReturns404WhenNoRouteMatches() {
        GatewayResponse response = gatewayRouterApi.route(buildRequest(HttpMethod.GET, "/zzz-no-route-404/here"));
        Assertions.assertEquals(404, response.getStatusCode());
    }

    @Test
    @Order(14)
    void routeReturns503WhenNoInstanceAvailable() {
        Route route = new Route("no-inst-grt", "/noinst-grt/**", HttpMethod.ANY, "no-instance-svc-grt", 200, true);
        gatewayRouterApi.addDynamicRoute(route);
        GatewayResponse response = gatewayRouterApi.route(buildRequest(HttpMethod.GET, "/noinst-grt/test"));
        Assertions.assertEquals(503, response.getStatusCode());
    }

    @Test
    @Order(15)
    void routeResultContainsRouteAndTransformedRequest() {
        GatewayRequest req = buildRequest(HttpMethod.GET, "/findme-grt/item/99");
        RouteResult result = gatewayRouterApi.resolveRoute(req);
        Assertions.assertNotNull(result);
        Assertions.assertNotNull(result.getRoute());
        Assertions.assertNotNull(result.getTransformedRequest());
    }

    @Test
    @Order(16)
    void removeDynamicRouteForNonExistingRouteIsIdempotent() {
        Assertions.assertDoesNotThrow(() -> gatewayRouterApi.removeDynamicRoute("non-existing-route-xyz"));
    }

    @Test
    @Order(17)
    void routeReturns429WhenRateLimitExceeded() {
        String svcName = "rl-429-svc";
        String routeId = "rl-429-route";
        String testIp = "10.77.77.77";

        ServiceRegistration instance = new ServiceRegistration(svcName, "1.0", "rl-429-inst",
                "http://localhost:19998", "http", ServiceStatus.UP);
        if (!injectTestInstance(svcName, instance)) {
            return; // skip if reflection not available
        }

        Route route = new Route(routeId, "/rl429grt/**", HttpMethod.ANY, svcName, 9999, true);
        gatewayRouterApi.addDynamicRoute(route);

        // Remove all existing rate limit rules so only our blocking rule applies
        rateLimiterApi.getAllRules().forEach(r -> rateLimiterApi.configureLimit(r.getRuleId(), null));
        RateLimitRule blockingRule = new RateLimitRule("block-rl-429", RateLimitKeyType.CLIENT_IP, 1, 60,
                RateLimitAlgorithm.FIXED_WINDOW);
        rateLimiterApi.configureLimit("block-rl-429", blockingRule);

        // Pre-consume the only allowed token for testIp
        GatewayRequest warmUp = GatewayRequest.builder()
                .method(HttpMethod.GET).path("/rl429grt/x").clientIp(testIp).build();
        rateLimiterApi.checkRateLimit(testIp, warmUp);

        // Now route() must return 429
        GatewayRequest req = GatewayRequest.builder()
                .method(HttpMethod.GET).path("/rl429grt/test").clientIp(testIp).build();
        GatewayResponse response = gatewayRouterApi.route(req);
        Assertions.assertEquals(429, response.getStatusCode());

        // Clean up
        rateLimiterApi.configureLimit("block-rl-429", null);
        gatewayRouterApi.removeDynamicRoute(routeId);
    }

    @Test
    @Order(18)
    void routeReturns502WhenProxyFails() {
        String svcName = "proxy-fail-svc";
        String routeId = "proxy-fail-route";

        // Port 19997 is not expected to be listening → ConnectException → 502
        ServiceRegistration instance = new ServiceRegistration(svcName, "1.0", "pf-inst-1",
                "http://localhost:19997", "http", ServiceStatus.UP);
        if (!injectTestInstance(svcName, instance)) {
            return;
        }

        Route route = new Route(routeId, "/proxyfailgrt/**", HttpMethod.ANY, svcName, 9998, true);
        gatewayRouterApi.addDynamicRoute(route);
        // Ensure no rate limit rules interfere
        rateLimiterApi.getAllRules().forEach(r -> rateLimiterApi.configureLimit(r.getRuleId(), null));

        // Request with hop-by-hop header (covers the HOP_BY_HOP filter branch in proxyRequest)
        GatewayRequest req = GatewayRequest.builder()
                .method(HttpMethod.GET)
                .path("/proxyfailgrt/test")
                .clientIp("10.78.78.78")
                .build();
        req.getHeaders().put("connection", "keep-alive");   // hop-by-hop → must be filtered
        req.getHeaders().put("X-Custom-Fwd", "value");     // non-hop-by-hop → must pass

        GatewayResponse response = gatewayRouterApi.route(req);
        Assertions.assertEquals(502, response.getStatusCode());

        gatewayRouterApi.removeDynamicRoute(routeId);
    }

    @Test
    @Order(19)
    void routeReturns502WithPostBodyAndTrailingSlashEndpoint() {
        String svcName = "trailing-svc";
        String routeId = "trailing-route";

        // Endpoint WITH trailing slash – covers the trim branch in buildTargetUrl
        ServiceRegistration instance = new ServiceRegistration(svcName, "1.0", "ts-inst-1",
                "http://localhost:19996/", "http", ServiceStatus.UP);
        if (!injectTestInstance(svcName, instance)) {
            return;
        }

        Route route = new Route(routeId, "/trailinggrt/**", HttpMethod.ANY, svcName, 9997, true);
        gatewayRouterApi.addDynamicRoute(route);
        rateLimiterApi.getAllRules().forEach(r -> rateLimiterApi.configureLimit(r.getRuleId(), null));

        // POST with body (covers the body != null && length > 0 branch) + queryString
        GatewayRequest req = GatewayRequest.builder()
                .method(HttpMethod.POST)
                .path("/trailinggrt/resource")
                .queryString("v=1")
                .clientIp("10.79.79.79")
                .body("payload".getBytes())
                .build();

        GatewayResponse response = gatewayRouterApi.route(req);
        // Connection refused → 502
        Assertions.assertEquals(502, response.getStatusCode());

        gatewayRouterApi.removeDynamicRoute(routeId);
    }

    @Test
    @Order(20)
    void buildTargetUrlDoesNotAppendSlashWhenRequestPathIsRoot() throws Exception {
        GatewayRouterServiceImpl router = new GatewayRouterServiceImpl();
        Method buildTargetUrl = GatewayRouterServiceImpl.class
                .getDeclaredMethod("buildTargetUrl", ServiceRegistration.class, GatewayRequest.class);
        buildTargetUrl.setAccessible(true);

        ServiceRegistration instance = new ServiceRegistration("svc", "1.0", "inst-1",
                "http://localhost:9081/water/assetcategories", "http", ServiceStatus.UP);
        GatewayRequest request = GatewayRequest.builder()
                .method(HttpMethod.GET)
                .path("/")
                .build();

        String targetUrl = (String) buildTargetUrl.invoke(router, instance, request);
        Assertions.assertEquals("http://localhost:9081/water/assetcategories", targetUrl);
    }

    @Test
    @Order(21)
    void buildTargetUrlDoesNotDuplicateRegisteredEndpointSuffix() throws Exception {
        GatewayRouterServiceImpl router = new GatewayRouterServiceImpl();
        Method buildTargetUrl = GatewayRouterServiceImpl.class
                .getDeclaredMethod("buildTargetUrl", ServiceRegistration.class, GatewayRequest.class);
        buildTargetUrl.setAccessible(true);

        ServiceRegistration instance = new ServiceRegistration("svc", "1.0", "inst-1",
                "http://localhost:9081/water/assetcategories", "http", ServiceStatus.UP);
        GatewayRequest request = GatewayRequest.builder()
                .method(HttpMethod.GET)
                .path("/assetcategories")
                .build();

        String targetUrl = (String) buildTargetUrl.invoke(router, instance, request);
        Assertions.assertEquals("http://localhost:9081/water/assetcategories", targetUrl);
    }

    @Test
    @Order(22)
    @SuppressWarnings("unchecked")
    void proxyRequestStripsHopByHopResponseHeaders() throws Exception {
        GatewayRouterServiceImpl router = new GatewayRouterServiceImpl();
        HttpClient httpClient = Mockito.mock(HttpClient.class);
        HttpResponse<byte[]> httpResponse = Mockito.mock(HttpResponse.class);

        Field httpClientField = GatewayRouterServiceImpl.class.getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        httpClientField.set(router, httpClient);

        Mockito.when(httpResponse.statusCode()).thenReturn(200);
        Mockito.when(httpResponse.body()).thenReturn("{\"ok\":true}".getBytes());
        Mockito.when(httpResponse.headers()).thenReturn(HttpHeaders.of(
                Map.of(
                        "content-type", List.of("application/json"),
                        "transfer-encoding", List.of("chunked"),
                        "connection", List.of("keep-alive"),
                        "content-length", List.of("11")
                ),
                (name, value) -> true
        ));
        Mockito.when(httpClient.send(Mockito.any(), Mockito.any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        Method proxyRequest = GatewayRouterServiceImpl.class
                .getDeclaredMethod("proxyRequest", GatewayRequest.class, ServiceRegistration.class);
        proxyRequest.setAccessible(true);

        GatewayRequest request = GatewayRequest.builder()
                .method(HttpMethod.GET)
                .path("/assetcategories")
                .headers(new java.util.HashMap<>())
                .clientIp("127.0.0.1")
                .build();
        ServiceRegistration instance = new ServiceRegistration("svc", "1.0", "inst-1",
                "http://localhost:9081/water/assetcategories", "http", ServiceStatus.UP);

        GatewayResponse response = (GatewayResponse) proxyRequest.invoke(router, request, instance);
        Assertions.assertEquals("application/json", response.getHeaders().get("content-type"));
        Assertions.assertFalse(response.getHeaders().containsKey("transfer-encoding"));
        Assertions.assertFalse(response.getHeaders().containsKey("connection"));
        Assertions.assertFalse(response.getHeaders().containsKey("content-length"));
    }

    @Test
    @Order(23)
    @SuppressWarnings("unchecked")
    void proxyRequestUsesConfiguredTimeout() throws Exception {
        GatewayRouterServiceImpl router = new GatewayRouterServiceImpl();
        HttpClient httpClient = Mockito.mock(HttpClient.class);
        HttpResponse<byte[]> httpResponse = Mockito.mock(HttpResponse.class);

        Field httpClientField = GatewayRouterServiceImpl.class.getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        httpClientField.set(router, httpClient);

        Field optionsField = GatewayRouterServiceImpl.class.getDeclaredField("gatewaySystemOptions");
        optionsField.setAccessible(true);
        optionsField.set(router, gatewaySystemOptions);

        Mockito.when(httpResponse.statusCode()).thenReturn(200);
        Mockito.when(httpResponse.body()).thenReturn(new byte[0]);
        Mockito.when(httpResponse.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));

        Properties props = new Properties();
        props.setProperty("water.apigateway.proxy.timeout", "1234");
        try {
            applicationProperties.loadProperties(props);

            Method proxyRequest = GatewayRouterServiceImpl.class
                    .getDeclaredMethod("proxyRequest", GatewayRequest.class, ServiceRegistration.class);
            proxyRequest.setAccessible(true);

            GatewayRequest request = GatewayRequest.builder()
                    .method(HttpMethod.GET)
                    .path("/assetcategories")
                    .headers(new java.util.HashMap<>())
                    .clientIp("127.0.0.1")
                    .build();
            ServiceRegistration instance = new ServiceRegistration("svc", "1.0", "inst-1",
                    "http://localhost:9081/water/assetcategories", "http", ServiceStatus.UP);

            Mockito.when(httpClient.send(Mockito.argThat((HttpRequest sent) ->
                            sent.timeout().isPresent() && sent.timeout().get().toMillis() == 1234L),
                    Mockito.any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

            proxyRequest.invoke(router, request, instance);

            Mockito.verify(httpClient).send(Mockito.argThat((HttpRequest sent) ->
                            sent.timeout().isPresent() && sent.timeout().get().toMillis() == 1234L),
                    Mockito.any(HttpResponse.BodyHandler.class));
        } finally {
            applicationProperties.unloadProperties(props);
        }
    }

    private GatewayRequest buildRequest(HttpMethod method, String path) {
        return GatewayRequest.builder()
                .method(method)
                .path(path)
                .clientIp("10.0.0.1")
                .build();
    }

    @SuppressWarnings("unchecked")
    private boolean injectTestInstance(String serviceName, ServiceRegistration instance) {
        try {
            Object component = componentRegistry.findComponent(GatewaySystemApi.class, null);
            if (!(component instanceof GatewaySystemServiceImpl impl)) {
                return false;
            }
            Field field = GatewaySystemServiceImpl.class.getDeclaredField("serviceCache");
            field.setAccessible(true);
            Map<String, List<ServiceRegistration>> cache =
                    (Map<String, List<ServiceRegistration>>) field.get(impl);
            cache.put(serviceName, new ArrayList<>(List.of(instance)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
