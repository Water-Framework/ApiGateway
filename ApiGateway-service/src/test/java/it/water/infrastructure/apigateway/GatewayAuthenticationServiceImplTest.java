package it.water.infrastructure.apigateway;

import it.water.core.api.service.Service;
import it.water.core.interceptors.annotations.Inject;
import it.water.core.testing.utils.junit.WaterTestExtension;
import it.water.infrastructure.apigateway.api.GatewayAuthenticationApi;
import it.water.infrastructure.apigateway.model.*;
import lombok.Setter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for {@link it.water.infrastructure.apigateway.service.GatewayAuthenticationServiceImpl}
 * (fix #28 — Basic-auth fail-closed).
 *
 * <p>Fix #28 makes {@code authenticateBasic} always return an unauthenticated result,
 * mirroring {@code authenticateJwt}. These tests assert that:
 * <ul>
 *   <li>A well-formed {@code Basic <base64>} Authorization header returns NOT authenticated.</li>
 *   <li>A {@code Basic} header with no credential returns NOT authenticated.</li>
 *   <li>A {@code Bearer} Authorization header (JWT path) returns NOT authenticated.</li>
 *   <li>A request with no Authorization header returns NOT authenticated.</li>
 *   <li>An X-API-KEY request with an unknown/unregistered key returns NOT authenticated.</li>
 *   <li>An X-API-KEY request with a registered, enabled, non-expired key IS authenticated.</li>
 * </ul>
 */
@ExtendWith(WaterTestExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayAuthenticationServiceImplTest implements Service {

    @Inject
    @Setter
    private GatewayAuthenticationApi gatewayAuthenticationApi;

    // -------------------------------------------------------------------
    // Sanity
    // -------------------------------------------------------------------

    @Test
    @Order(1)
    void gatewayAuthenticationApi_componentRegistered() {
        Assertions.assertNotNull(gatewayAuthenticationApi,
                "GatewayAuthenticationApi must be registered in the test component registry");
    }

    // -------------------------------------------------------------------
    // #28 — Basic-auth must always be fail-closed
    // -------------------------------------------------------------------

    @Test
    @Order(2)
    void authenticate_basicAuthWellFormedCredential_isNotAuthenticated() {
        // Well-formed "Basic <base64(user:pass)>" — fix #28: must return NOT authenticated
        // java.util.Base64 is in the std lib; the encoded form of "user:pass" is "dXNlcjpwYXNz"
        String encoded = java.util.Base64.getEncoder().encodeToString("user:pass".getBytes());
        GatewayRequest request = buildRequestWithAuthHeader("Basic " + encoded);

        AuthResult result = gatewayAuthenticationApi.authenticate(request);

        Assertions.assertNotNull(result, "authenticate must not return null");
        Assertions.assertFalse(result.isAuthenticated(),
                "#28: Basic-auth with a well-formed user:pass must NOT be authenticated (fail-closed)");
        Assertions.assertEquals(AuthMethod.BASIC_AUTH, result.getMethod(),
                "#28: result method must be BASIC_AUTH so callers know which path was taken");
    }

    @Test
    @Order(3)
    void authenticate_basicAuthEmptyCredential_isNotAuthenticated() {
        // "Basic " with no credential after the space
        GatewayRequest request = buildRequestWithAuthHeader("Basic ");

        AuthResult result = gatewayAuthenticationApi.authenticate(request);

        Assertions.assertNotNull(result);
        Assertions.assertFalse(result.isAuthenticated(),
                "#28: Basic-auth with empty credential must NOT be authenticated");
    }

    @Test
    @Order(4)
    void authenticate_basicAuthBareWordBasic_isNotAuthenticated() {
        // "Basic" with no space/token — startsWith("Basic ") is false → falls to PASSTHROUGH path
        GatewayRequest request = buildRequestWithAuthHeader("Basic");

        AuthResult result = gatewayAuthenticationApi.authenticate(request);

        Assertions.assertNotNull(result);
        Assertions.assertFalse(result.isAuthenticated(),
                "A bare 'Basic' header without a space should reach the PASSTHROUGH path (also not authenticated)");
    }

    // -------------------------------------------------------------------
    // JWT path — must also be fail-closed (existing behavior regression guard)
    // -------------------------------------------------------------------

    @Test
    @Order(5)
    void authenticate_bearerToken_isNotAuthenticated() {
        // JWT path was already fail-closed; regression guard to confirm it remains so
        GatewayRequest request = buildRequestWithAuthHeader("Bearer eyJhbGciOiJSUzI1NiJ9.test.test");

        AuthResult result = gatewayAuthenticationApi.authenticate(request);

        Assertions.assertNotNull(result);
        Assertions.assertFalse(result.isAuthenticated(),
                "JWT Bearer token must NOT be authenticated (JWT validation not yet wired)");
        Assertions.assertEquals(AuthMethod.JWT_VALIDATION, result.getMethod(),
                "JWT path must use JWT_VALIDATION method");
    }

    // -------------------------------------------------------------------
    // No Authorization header — PASSTHROUGH, not authenticated
    // -------------------------------------------------------------------

    @Test
    @Order(6)
    void authenticate_noAuthorizationHeader_isNotAuthenticated() {
        GatewayRequest request = GatewayRequest.builder()
                .method(HttpMethod.GET)
                .path("/some/path")
                .headers(new HashMap<>())
                .build();

        AuthResult result = gatewayAuthenticationApi.authenticate(request);

        Assertions.assertNotNull(result);
        Assertions.assertFalse(result.isAuthenticated(),
                "A request with no Authorization header must not be authenticated");
        Assertions.assertEquals(AuthMethod.PASSTHROUGH, result.getMethod(),
                "No auth header → PASSTHROUGH method");
    }

    // -------------------------------------------------------------------
    // API Key path — unregistered key is rejected
    // -------------------------------------------------------------------

    @Test
    @Order(7)
    void authenticate_unknownApiKey_isNotAuthenticated() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-API-KEY", "unknown-key-xyz-99");
        GatewayRequest request = GatewayRequest.builder()
                .method(HttpMethod.GET)
                .path("/api/test")
                .headers(headers)
                .build();

        AuthResult result = gatewayAuthenticationApi.authenticate(request);

        Assertions.assertNotNull(result);
        Assertions.assertFalse(result.isAuthenticated(),
                "An unknown API key must not be authenticated");
        Assertions.assertEquals(AuthMethod.API_KEY, result.getMethod());
    }

    // -------------------------------------------------------------------
    // API Key path — registered enabled non-expired key IS authenticated
    // -------------------------------------------------------------------

    @Test
    @Order(8)
    void authenticate_registeredEnabledApiKey_isAuthenticated() {
        String apiKey = "test-api-key-valid-12345";
        ApiKeyConfig config = ApiKeyConfig.builder()
                .enabled(true)
                .expiresAt(null)  // no expiry
                .build();
        gatewayAuthenticationApi.registerApiKey(apiKey, config);

        Map<String, String> headers = new HashMap<>();
        headers.put("X-API-KEY", apiKey);
        GatewayRequest request = GatewayRequest.builder()
                .method(HttpMethod.GET)
                .path("/api/test")
                .headers(headers)
                .build();

        AuthResult result = gatewayAuthenticationApi.authenticate(request);

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isAuthenticated(),
                "A registered and enabled API key with no expiry must be authenticated");
        Assertions.assertEquals(AuthMethod.API_KEY, result.getMethod());
        Assertions.assertEquals("apikey:" + apiKey, result.getUserId());
    }

    // -------------------------------------------------------------------
    // API Key path — registered but disabled key is rejected
    // -------------------------------------------------------------------

    @Test
    @Order(9)
    void authenticate_registeredDisabledApiKey_isNotAuthenticated() {
        String apiKey = "test-api-key-disabled-99";
        ApiKeyConfig config = ApiKeyConfig.builder()
                .enabled(false)
                .expiresAt(null)
                .build();
        gatewayAuthenticationApi.registerApiKey(apiKey, config);

        Map<String, String> headers = new HashMap<>();
        headers.put("X-API-KEY", apiKey);
        GatewayRequest request = GatewayRequest.builder()
                .method(HttpMethod.GET)
                .path("/api/test")
                .headers(headers)
                .build();

        AuthResult result = gatewayAuthenticationApi.authenticate(request);

        Assertions.assertFalse(result.isAuthenticated(),
                "A disabled API key must not be authenticated");
    }

    // -------------------------------------------------------------------
    // API Key path — expired key is rejected
    // -------------------------------------------------------------------

    @Test
    @Order(10)
    void authenticate_expiredApiKey_isNotAuthenticated() {
        String apiKey = "test-api-key-expired-77";
        // expiresAt is in the past
        java.util.Date pastDate = new java.util.Date(System.currentTimeMillis() - 60_000L);
        ApiKeyConfig config = ApiKeyConfig.builder()
                .enabled(true)
                .expiresAt(pastDate)
                .build();
        gatewayAuthenticationApi.registerApiKey(apiKey, config);

        Map<String, String> headers = new HashMap<>();
        headers.put("X-API-KEY", apiKey);
        GatewayRequest request = GatewayRequest.builder()
                .method(HttpMethod.GET)
                .path("/api/test")
                .headers(headers)
                .build();

        AuthResult result = gatewayAuthenticationApi.authenticate(request);

        Assertions.assertFalse(result.isAuthenticated(),
                "An expired API key must not be authenticated");
    }

    // -------------------------------------------------------------------
    // revokeApiKey — revoked key is no longer authenticated
    // -------------------------------------------------------------------

    @Test
    @Order(11)
    void revokeApiKey_revokedKeyIsNoLongerAuthenticated() {
        String apiKey = "test-api-key-revoke-55";
        ApiKeyConfig config = ApiKeyConfig.builder()
                .enabled(true)
                .expiresAt(null)
                .build();
        gatewayAuthenticationApi.registerApiKey(apiKey, config);

        // Confirm it was registered and would pass validation
        Assertions.assertTrue(gatewayAuthenticationApi.validateApiKey(apiKey),
                "Sanity: key must pass validation before revocation");

        gatewayAuthenticationApi.revokeApiKey(apiKey);

        Assertions.assertFalse(gatewayAuthenticationApi.validateApiKey(apiKey),
                "After revocation the key must no longer be valid");

        Map<String, String> headers = new HashMap<>();
        headers.put("X-API-KEY", apiKey);
        GatewayRequest request = GatewayRequest.builder()
                .method(HttpMethod.GET)
                .path("/api/test")
                .headers(headers)
                .build();

        AuthResult result = gatewayAuthenticationApi.authenticate(request);
        Assertions.assertFalse(result.isAuthenticated(),
                "A revoked API key must not authenticate");
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private GatewayRequest buildRequestWithAuthHeader(String authorizationValue) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", authorizationValue);
        return GatewayRequest.builder()
                .method(HttpMethod.GET)
                .path("/api/test")
                .headers(headers)
                .build();
    }
}
