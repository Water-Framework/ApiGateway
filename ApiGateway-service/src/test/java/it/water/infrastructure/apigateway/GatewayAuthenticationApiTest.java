package it.water.infrastructure.apigateway;

import it.water.core.api.registry.ComponentRegistry;
import it.water.core.api.service.Service;
import it.water.core.interceptors.annotations.Inject;
import it.water.core.testing.utils.junit.WaterTestExtension;
import it.water.core.testing.utils.runtime.TestRuntimeUtils;
import it.water.infrastructure.apigateway.api.GatewayAuthenticationApi;
import it.water.infrastructure.apigateway.model.ApiKeyConfig;
import it.water.infrastructure.apigateway.model.AuthMethod;
import it.water.infrastructure.apigateway.model.AuthResult;
import it.water.infrastructure.apigateway.model.GatewayRequest;
import it.water.infrastructure.apigateway.model.HttpMethod;
import lombok.Setter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Tests the reserved gateway-authentication component.
 *
 * <p>JWT assertions intentionally verify fail-closed behavior: accepting JWTs
 * here would bypass Water signature validation. JWT support can only become
 * positive after delegating to the real Water JwtTokenService.
 */
@ExtendWith(WaterTestExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayAuthenticationApiTest implements Service {

    @Inject
    @Setter
    private ComponentRegistry componentRegistry;

    @Inject
    @Setter
    private GatewayAuthenticationApi gatewayAuthenticationApi;

    @BeforeAll
    void beforeAll() {
        TestRuntimeUtils.impersonateAdmin(componentRegistry);
    }

    @Test
    @Order(1)
    void componentInstantiatedCorrectly() {
        Assertions.assertNotNull(gatewayAuthenticationApi);
    }

    @Test
    @Order(2)
    void noAuthHeaderReturnsPassthrough() {
        AuthResult result = gatewayAuthenticationApi.authenticate(buildRequest(null, null));
        Assertions.assertFalse(result.isAuthenticated());
        Assertions.assertEquals(AuthMethod.PASSTHROUGH, result.getMethod());
    }

    @Test
    @Order(3)
    void validApiKeyReturnsAuthenticated() {
        ApiKeyConfig config = ApiKeyConfig.builder()
                .apiKey("test-api-key-123")
                .description("Test API Key")
                .enabled(true)
                .allowedPaths(List.of("/api/**"))
                .build();
        gatewayAuthenticationApi.registerApiKey("test-api-key-123", config);

        AuthResult result = gatewayAuthenticationApi.authenticate(buildRequest(null, "test-api-key-123"));
        Assertions.assertTrue(result.isAuthenticated());
        Assertions.assertEquals(AuthMethod.API_KEY, result.getMethod());
        Assertions.assertEquals("test-api-key-123", result.getApiKey());
    }

    @Test
    @Order(4)
    void revokedApiKeyNotAuthenticated() {
        ApiKeyConfig config = ApiKeyConfig.builder()
                .apiKey("revoked-key")
                .description("Revoked Key")
                .enabled(true)
                .build();
        gatewayAuthenticationApi.registerApiKey("revoked-key", config);
        gatewayAuthenticationApi.revokeApiKey("revoked-key");

        AuthResult result = gatewayAuthenticationApi.authenticate(buildRequest(null, "revoked-key"));
        Assertions.assertFalse(result.isAuthenticated());
    }

    @Test
    @Order(5)
    void expiredApiKeyNotAuthenticated() {
        ApiKeyConfig config = ApiKeyConfig.builder()
                .apiKey("expired-key")
                .description("Expired Key")
                .enabled(true)
                .expiresAt(new Date(System.currentTimeMillis() - 1000))
                .build();
        gatewayAuthenticationApi.registerApiKey("expired-key", config);

        Assertions.assertFalse(gatewayAuthenticationApi.validateApiKey("expired-key"));
    }

    @Test
    @Order(6)
    void wellFormedBasicAuthIsFailClosed() {
        //#28: the Basic-auth stub is a reserved/unwired extension point and must stay fail-closed —
        //even a well-formed user:pass must NOT be treated as authenticated.
        String credentials = Base64.getEncoder().encodeToString("user:password".getBytes(StandardCharsets.UTF_8));
        AuthResult result = gatewayAuthenticationApi.authenticate(buildRequest("Basic " + credentials, null));
        Assertions.assertFalse(result.isAuthenticated());
        Assertions.assertEquals(AuthMethod.BASIC_AUTH, result.getMethod());
    }

    @Test
    @Order(7)
    void invalidBasicAuthNotAuthenticated() {
        AuthResult result = gatewayAuthenticationApi.authenticate(buildRequest("Basic invalid-base64!!!", null));
        Assertions.assertFalse(result.isAuthenticated());
        Assertions.assertEquals(AuthMethod.BASIC_AUTH, result.getMethod());
    }

    @Test
    @Order(8)
    void bearerJwtAlwaysFailsClosedUntilWaterJwtTokenServiceIsUsed() {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"admin\",\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));
        AuthResult result = gatewayAuthenticationApi.authenticate(buildRequest("Bearer " + header + "." + payload + ".x", null));
        Assertions.assertFalse(result.isAuthenticated());
        Assertions.assertEquals(AuthMethod.JWT_VALIDATION, result.getMethod());
    }

    @Test
    @Order(9)
    void unknownAuthSchemeReturnsPassthrough() {
        AuthResult result = gatewayAuthenticationApi.authenticate(buildRequest("Digest realm=\"example\"", null));
        Assertions.assertFalse(result.isAuthenticated());
        Assertions.assertEquals(AuthMethod.PASSTHROUGH, result.getMethod());
    }

    private GatewayRequest buildRequest(String authHeader, String apiKeyHeader) {
        GatewayRequest request = GatewayRequest.builder()
                .method(HttpMethod.GET)
                .path("/api/test")
                .clientIp("10.0.0.1")
                .build();
        if (authHeader != null) {
            request.getHeaders().put("Authorization", authHeader);
        }
        if (apiKeyHeader != null) {
            request.getHeaders().put("X-API-KEY", apiKeyHeader);
        }
        return request;
    }
}
