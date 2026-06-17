package it.water.infrastructure.apigateway;

import it.water.core.api.bundle.ApplicationProperties;
import it.water.core.api.registry.ComponentRegistry;
import it.water.core.api.service.Service;
import it.water.core.interceptors.annotations.Inject;
import it.water.core.testing.utils.junit.WaterTestExtension;
import it.water.core.testing.utils.runtime.TestRuntimeUtils;
import it.water.infrastructure.apigateway.api.options.GatewaySystemOptions;
import lombok.Setter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Properties;
import java.util.Set;

/**
 * Unit tests for GatewaySystemOptionsImpl, including the #37 trusted-proxy gate.
 *
 * <p>The {@code extractClientIp} gate in {@code GatewayProxyRestControllerImpl} delegates the
 * trusted-proxy lookup entirely to {@link GatewaySystemOptions#getTrustedProxies()}, so exercising
 * that method covers the configurable half of the fix. The controller-level decision (checking the
 * TCP peer against the set) is tested end-to-end by the existing Karate
 * {@code gateway-proxy.feature} — no REST controller is instantiated in JUnit because the
 * ApiGateway CLAUDE.md mandates Karate-only for REST boundary tests.
 */
@ExtendWith(WaterTestExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewaySystemOptionsTest implements Service {

    @Inject
    @Setter
    private ComponentRegistry componentRegistry;

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

    // ------------------------------------------------------------------
    // Smoke
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void componentInstantiatedCorrectly() {
        Assertions.assertNotNull(gatewaySystemOptions,
                "GatewaySystemOptions must be registered in the component registry");
    }

    // ------------------------------------------------------------------
    // #37 — getTrustedProxies(): default empty set
    // ------------------------------------------------------------------

    /**
     * When {@code water.apigateway.trusted.proxies} is not set at all, the result must be an
     * empty set — meaning the proxy controller will NEVER trust forwarded headers.
     * This is the default fail-closed posture of fix #37.
     */
    @Test
    @Order(2)
    void getTrustedProxies_propertyUnset_returnsEmptySet() {
        // No property loaded — rely on the default from it.water.application.properties (not set there).
        Set<String> proxies = gatewaySystemOptions.getTrustedProxies();
        Assertions.assertNotNull(proxies, "getTrustedProxies() must never return null");
        Assertions.assertTrue(proxies.isEmpty(),
                "#37: when water.apigateway.trusted.proxies is not configured, the set must be empty " +
                "(forwarded headers must never be trusted by default)");
    }

    /**
     * When the property is explicitly set to an empty string, the result must still be an empty set.
     */
    @Test
    @Order(3)
    void getTrustedProxies_propertySetToEmptyString_returnsEmptySet() {
        Properties props = new Properties();
        props.setProperty("water.apigateway.trusted.proxies", "");
        try {
            applicationProperties.loadProperties(props);
            Set<String> proxies = gatewaySystemOptions.getTrustedProxies();
            Assertions.assertNotNull(proxies);
            Assertions.assertTrue(proxies.isEmpty(),
                    "#37: an explicitly empty trusted-proxies property must yield an empty set");
        } finally {
            applicationProperties.unloadProperties(props);
        }
    }

    // ------------------------------------------------------------------
    // #37 — getTrustedProxies(): single IP
    // ------------------------------------------------------------------

    /**
     * A single IP address must be parsed and returned as a one-element set.
     * This enables the controller to trust forwarded headers only from that specific peer.
     */
    @Test
    @Order(4)
    void getTrustedProxies_singleIp_returnsSingleElementSet() {
        Properties props = new Properties();
        props.setProperty("water.apigateway.trusted.proxies", "10.0.0.1");
        try {
            applicationProperties.loadProperties(props);
            Set<String> proxies = gatewaySystemOptions.getTrustedProxies();
            Assertions.assertNotNull(proxies);
            Assertions.assertEquals(1, proxies.size(),
                    "#37: a single IP value must yield a one-element set");
            Assertions.assertTrue(proxies.contains("10.0.0.1"),
                    "#37: the set must contain the configured IP address");
        } finally {
            applicationProperties.unloadProperties(props);
        }
    }

    // ------------------------------------------------------------------
    // #37 — getTrustedProxies(): comma-separated list
    // ------------------------------------------------------------------

    /**
     * A comma-separated list of IP addresses must all appear in the returned set.
     * This is the typical load-balancer / reverse-proxy deployment scenario.
     */
    @Test
    @Order(5)
    void getTrustedProxies_commaSeparatedList_returnsAllIps() {
        Properties props = new Properties();
        props.setProperty("water.apigateway.trusted.proxies", "10.0.0.1,10.0.0.2,192.168.1.100");
        try {
            applicationProperties.loadProperties(props);
            Set<String> proxies = gatewaySystemOptions.getTrustedProxies();
            Assertions.assertNotNull(proxies);
            Assertions.assertEquals(3, proxies.size(),
                    "#37: all comma-separated IPs must appear in the trusted-proxies set");
            Assertions.assertTrue(proxies.contains("10.0.0.1"),
                    "#37: first IP must be present");
            Assertions.assertTrue(proxies.contains("10.0.0.2"),
                    "#37: second IP must be present");
            Assertions.assertTrue(proxies.contains("192.168.1.100"),
                    "#37: third IP must be present");
        } finally {
            applicationProperties.unloadProperties(props);
        }
    }

    // ------------------------------------------------------------------
    // #37 — getTrustedProxies(): whitespace around tokens must be trimmed
    // ------------------------------------------------------------------

    /**
     * Entries with surrounding whitespace (e.g. "10.0.0.1 , 10.0.0.2") must be trimmed so
     * the set contains clean IP strings that can be compared with getRemoteAddr().
     */
    @Test
    @Order(6)
    void getTrustedProxies_whitespaceAroundTokens_trimsToCleanIps() {
        Properties props = new Properties();
        props.setProperty("water.apigateway.trusted.proxies", " 10.0.0.1 , 10.0.0.2 ");
        try {
            applicationProperties.loadProperties(props);
            Set<String> proxies = gatewaySystemOptions.getTrustedProxies();
            Assertions.assertNotNull(proxies);
            Assertions.assertEquals(2, proxies.size(),
                    "#37: whitespace-padded entries must be trimmed and deduplication must hold");
            Assertions.assertTrue(proxies.contains("10.0.0.1"),
                    "#37: trimmed first IP must be present");
            Assertions.assertTrue(proxies.contains("10.0.0.2"),
                    "#37: trimmed second IP must be present");
            // The padded form must NOT appear in the set
            Assertions.assertFalse(proxies.contains(" 10.0.0.1 "),
                    "#37: untrimmed IP must not be stored in the set");
        } finally {
            applicationProperties.unloadProperties(props);
        }
    }

    // ------------------------------------------------------------------
    // #37 — getTrustedProxies(): returned set is unmodifiable
    // ------------------------------------------------------------------

    /**
     * The returned set must be unmodifiable so callers cannot accidentally mutate the options state.
     * UnsupportedOperationException is the expected signal from Collections.unmodifiableSet().
     */
    @Test
    @Order(7)
    void getTrustedProxies_returnedSet_isUnmodifiable() {
        Properties props = new Properties();
        props.setProperty("water.apigateway.trusted.proxies", "10.0.0.1");
        try {
            applicationProperties.loadProperties(props);
            Set<String> proxies = gatewaySystemOptions.getTrustedProxies();
            Assertions.assertThrows(UnsupportedOperationException.class,
                    () -> proxies.add("10.0.0.99"),
                    "#37: the trusted-proxy set must be unmodifiable");
        } finally {
            applicationProperties.unloadProperties(props);
        }
    }

    // ------------------------------------------------------------------
    // Existing options regression guards
    // ------------------------------------------------------------------

    @Test
    @Order(8)
    void getProxyTimeoutMs_default_returnsPositiveValue() {
        long timeout = gatewaySystemOptions.getProxyTimeoutMs();
        Assertions.assertTrue(timeout > 0,
                "getProxyTimeoutMs() must return a positive value");
    }

    @Test
    @Order(9)
    void getCircuitBreakerFailureThreshold_default_returnsAtLeastOne() {
        int threshold = gatewaySystemOptions.getCircuitBreakerFailureThreshold();
        Assertions.assertTrue(threshold >= 1,
                "getCircuitBreakerFailureThreshold() must be at least 1");
    }

    @Test
    @Order(10)
    void getCircuitBreakerTimeoutMs_default_returnsNonNegative() {
        long timeout = gatewaySystemOptions.getCircuitBreakerTimeoutMs();
        Assertions.assertTrue(timeout >= 0,
                "getCircuitBreakerTimeoutMs() must be non-negative");
    }

    @Test
    @Order(11)
    void getDefaultRateLimiterRequestsPerMinute_default_returnsNonNegative() {
        int rpm = gatewaySystemOptions.getDefaultRateLimiterRequestsPerMinute();
        Assertions.assertTrue(rpm >= 0,
                "getDefaultRateLimiterRequestsPerMinute() must be non-negative (0 means disabled)");
    }

    @Test
    @Order(12)
    void getServiceDiscoveryUrl_default_returnsNonNull() {
        String url = gatewaySystemOptions.getServiceDiscoveryUrl();
        Assertions.assertNotNull(url,
                "getServiceDiscoveryUrl() must return a non-null value");
    }
}
