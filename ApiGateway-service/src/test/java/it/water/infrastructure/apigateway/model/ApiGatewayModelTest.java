package it.water.infrastructure.apigateway.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

class ApiGatewayModelTest {

    @Test
    void routeAppliesDefaultsAndExposesMutableFields() {
        Route route = new Route();
        route.setRouteId("route-1");
        route.setPathPattern("/assets/**");
        route.setTargetServiceName("assetcategory");
        route.setRewritePath("/assetcategories");
        route.setPriority(10);
        route.setPredicates(Map.of("Host", "example.local"));
        route.setFilters(Map.of("addHeader.X-Test", "true"));
        route.setOwnerUserId(42L);

        route.onCreate();

        Assertions.assertEquals("route-1", route.getRouteId());
        Assertions.assertEquals("/assets/**", route.getPathPattern());
        Assertions.assertEquals(HttpMethod.ANY, route.getMethod());
        Assertions.assertEquals("assetcategory", route.getTargetServiceName());
        Assertions.assertEquals("/assetcategories", route.getRewritePath());
        Assertions.assertEquals(10, route.getPriority());
        Assertions.assertTrue(route.isEnabled());
        Assertions.assertEquals("example.local", route.getPredicates().get("Host"));
        Assertions.assertEquals("true", route.getFilters().get("addHeader.X-Test"));
        Assertions.assertEquals(42L, route.getOwnerUserId());
        Assertions.assertTrue(route.toString().contains("route-1"));
        Assertions.assertEquals(route, new Route("route-1", "/other/**", HttpMethod.POST, "other", 1, false));
    }

    @Test
    void rateLimitRuleAppliesDefaultsAndKeepsExplicitValues() {
        RateLimitRule defaults = new RateLimitRule();
        defaults.setRuleId("default-rule");
        defaults.setMaxRequests(20);
        defaults.setWindowSeconds(60);
        defaults.setOwnerUserId(7L);
        defaults.onCreate();

        Assertions.assertEquals(RateLimitAlgorithm.TOKEN_BUCKET, defaults.getAlgorithm());
        Assertions.assertEquals(RateLimitKeyType.CLIENT_IP, defaults.getKeyType());
        Assertions.assertEquals(20, defaults.getBurstCapacity());
        Assertions.assertTrue(defaults.isEnabled());
        Assertions.assertEquals(7L, defaults.getOwnerUserId());

        RateLimitRule explicit = new RateLimitRule("explicit-rule", RateLimitKeyType.GLOBAL, 5, 30,
                RateLimitAlgorithm.SLIDING_WINDOW);
        explicit.setKeyPattern("/water/**");
        explicit.setBurstCapacity(9);
        explicit.setEnabled(false);
        explicit.onCreate();

        Assertions.assertEquals(RateLimitAlgorithm.SLIDING_WINDOW, explicit.getAlgorithm());
        Assertions.assertEquals(RateLimitKeyType.GLOBAL, explicit.getKeyType());
        Assertions.assertEquals(9, explicit.getBurstCapacity());
        Assertions.assertFalse(explicit.isEnabled());
        Assertions.assertEquals("/water/**", explicit.getKeyPattern());
        Assertions.assertTrue(explicit.toString().contains("explicit-rule"));
        Assertions.assertEquals(explicit, new RateLimitRule("explicit-rule", RateLimitKeyType.CLIENT_IP, 1, 1,
                RateLimitAlgorithm.TOKEN_BUCKET));
    }

    @Test
    void dtoBuildersExposeDefaultsAndAllFields() {
        Date expiresAt = new Date();
        ApiKeyConfig apiKeyConfig = ApiKeyConfig.builder()
                .apiKey("key")
                .description("test key")
                .allowedPaths(List.of("/water/**"))
                .rateLimit(100)
                .expiresAt(expiresAt)
                .build();

        Assertions.assertTrue(apiKeyConfig.isEnabled());
        apiKeyConfig.setEnabled(false);
        Assertions.assertEquals("key", apiKeyConfig.getApiKey());
        Assertions.assertEquals("test key", apiKeyConfig.getDescription());
        Assertions.assertEquals(List.of("/water/**"), apiKeyConfig.getAllowedPaths());
        Assertions.assertEquals(100, apiKeyConfig.getRateLimit());
        Assertions.assertSame(expiresAt, apiKeyConfig.getExpiresAt());
        Assertions.assertFalse(apiKeyConfig.isEnabled());

        ServiceStats stats = ServiceStats.builder()
                .serviceName("assetcategory")
                .totalRequests(4)
                .successCount(3)
                .failureCount(1)
                .avgLatencyMs(12.5)
                .circuitState(CircuitState.HALF_OPEN)
                .build();
        Assertions.assertEquals("assetcategory", stats.getServiceName());
        Assertions.assertEquals(4, stats.getTotalRequests());
        Assertions.assertEquals(3, stats.getSuccessCount());
        Assertions.assertEquals(1, stats.getFailureCount());
        Assertions.assertEquals(12.5, stats.getAvgLatencyMs());
        Assertions.assertEquals(CircuitState.HALF_OPEN, stats.getCircuitState());

        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.builder()
                .serviceName("svc")
                .build();
        Assertions.assertEquals(5, circuitBreakerConfig.getFailureThreshold());
        Assertions.assertEquals(3, circuitBreakerConfig.getSuccessThreshold());
        Assertions.assertEquals(30, circuitBreakerConfig.getTimeoutSeconds());
        Assertions.assertEquals(60, circuitBreakerConfig.getWindowSizeSeconds());
        Assertions.assertTrue(circuitBreakerConfig.isEnabled());

        RateLimitResult rateLimitResult = RateLimitResult.builder()
                .allowed(false)
                .remaining(0)
                .resetAfterMs(250)
                .rule(new RateLimitRule("rule", RateLimitKeyType.CLIENT_IP, 1, 60, RateLimitAlgorithm.FIXED_WINDOW))
                .build();
        Assertions.assertFalse(rateLimitResult.isAllowed());
        Assertions.assertEquals(0, rateLimitResult.getRemaining());
        Assertions.assertEquals(250, rateLimitResult.getResetAfterMs());
        Assertions.assertEquals("rule", rateLimitResult.getRule().getRuleId());

        AuthResult authResult = AuthResult.builder()
                .authenticated(true)
                .userId("user")
                .roles(List.of("gatewayViewer"))
                .apiKey("api-key")
                .method(AuthMethod.API_KEY)
                .build();
        Assertions.assertTrue(authResult.isAuthenticated());
        Assertions.assertEquals("user", authResult.getUserId());
        Assertions.assertEquals(List.of("gatewayViewer"), authResult.getRoles());
        Assertions.assertEquals("api-key", authResult.getApiKey());
        Assertions.assertEquals(AuthMethod.API_KEY, authResult.getMethod());
    }

    @Test
    void gatewayRequestAndResponseInitializeMutableDefaults() {
        GatewayRequest request = GatewayRequest.builder()
                .method(HttpMethod.GET)
                .path("/water/test")
                .build();

        request.getHeaders().put("Accept", "application/json");
        request.getAttributes().put("route", "test");

        Assertions.assertNotNull(request.getRequestId());
        Assertions.assertTrue(request.getTimestamp() > 0);
        Assertions.assertEquals("application/json", request.getHeaders().get("Accept"));
        Assertions.assertEquals("test", request.getAttributes().get("route"));

        GatewayResponse response = GatewayResponse.builder()
                .statusCode(200)
                .body("ok".getBytes())
                .build();
        response.getHeaders().put("Content-Type", "text/plain");
        response.getMetadata().put("cached", Boolean.FALSE);

        Assertions.assertEquals(200, response.getStatusCode());
        Assertions.assertArrayEquals("ok".getBytes(), response.getBody());
        Assertions.assertEquals("text/plain", response.getHeaders().get("Content-Type"));
        Assertions.assertEquals(Boolean.FALSE, response.getMetadata().get("cached"));
    }
}
