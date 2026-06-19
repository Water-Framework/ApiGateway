package it.water.infrastructure.apigateway;

import it.water.core.api.bundle.ApplicationProperties;
import it.water.infrastructure.apigateway.api.CircuitBreakerApi;
import it.water.infrastructure.apigateway.model.CircuitBreakerConfig;
import it.water.infrastructure.apigateway.model.CircuitState;
import it.water.core.api.registry.ComponentRegistry;
import it.water.core.api.service.Service;
import it.water.core.interceptors.annotations.Inject;
import it.water.core.testing.utils.junit.WaterTestExtension;
import it.water.core.testing.utils.runtime.TestRuntimeUtils;
import lombok.Setter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Properties;

/**
 * Unit tests for Circuit Breaker service.
 */
@ExtendWith(WaterTestExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CircuitBreakerApiTest implements Service {

    @Inject
    @Setter
    private ComponentRegistry componentRegistry;

    @Inject
    @Setter
    private CircuitBreakerApi circuitBreakerApi;

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
        Assertions.assertNotNull(circuitBreakerApi);
    }

    @Test
    @Order(2)
    void initialStateIsClosed() {
        CircuitState state = circuitBreakerApi.getState("new-service", "instance-1");
        Assertions.assertEquals(CircuitState.CLOSED, state);
    }

    @Test
    @Order(3)
    void closedCircuitAllowsRequests() {
        Assertions.assertTrue(circuitBreakerApi.allowRequest("svc-a", "inst-1"));
    }

    @Test
    @Order(4)
    void failureThresholdOpensCircuit() {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .serviceName("svc-b")
                .failureThreshold(3)
                .successThreshold(2)
                .timeoutSeconds(60)
                .windowSizeSeconds(60)
                .build();
        circuitBreakerApi.configure("svc-b", config);

        // Record 3 failures to open circuit
        circuitBreakerApi.recordFailure("svc-b", "inst-1");
        circuitBreakerApi.recordFailure("svc-b", "inst-1");
        Assertions.assertEquals(CircuitState.CLOSED, circuitBreakerApi.getState("svc-b", "inst-1"));
        circuitBreakerApi.recordFailure("svc-b", "inst-1");
        Assertions.assertEquals(CircuitState.OPEN, circuitBreakerApi.getState("svc-b", "inst-1"));
    }

    @Test
    @Order(5)
    void openCircuitBlocksRequests() {
        // Circuit was opened for svc-b in previous test
        Assertions.assertFalse(circuitBreakerApi.allowRequest("svc-b", "inst-1"));
    }

    @Test
    @Order(6)
    void halfOpenAfterTimeout() {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .serviceName("svc-c")
                .failureThreshold(2)
                .successThreshold(2)
                .timeoutSeconds(-1) // timeout already elapsed
                .windowSizeSeconds(60)
                .build();
        circuitBreakerApi.configure("svc-c", config);

        circuitBreakerApi.recordFailure("svc-c", "inst-1");
        circuitBreakerApi.recordFailure("svc-c", "inst-1");
        Assertions.assertEquals(CircuitState.OPEN, circuitBreakerApi.getState("svc-c", "inst-1"));

        // allowRequest should transition to HALF_OPEN
        boolean allowed = circuitBreakerApi.allowRequest("svc-c", "inst-1");
        Assertions.assertTrue(allowed);
        Assertions.assertEquals(CircuitState.HALF_OPEN, circuitBreakerApi.getState("svc-c", "inst-1"));
    }

    @Test
    @Order(7)
    void halfOpenSuccessesCloseCircuit() {
        // svc-c is in HALF_OPEN from previous test, needs 2 successes
        circuitBreakerApi.recordSuccess("svc-c", "inst-1");
        Assertions.assertEquals(CircuitState.HALF_OPEN, circuitBreakerApi.getState("svc-c", "inst-1"));
        circuitBreakerApi.recordSuccess("svc-c", "inst-1");
        Assertions.assertEquals(CircuitState.CLOSED, circuitBreakerApi.getState("svc-c", "inst-1"));
    }

    @Test
    @Order(8)
    void halfOpenFailureReopensCircuit() {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .serviceName("svc-d")
                .failureThreshold(1)
                .successThreshold(3)
                .timeoutSeconds(-1)
                .windowSizeSeconds(60)
                .build();
        circuitBreakerApi.configure("svc-d", config);

        circuitBreakerApi.recordFailure("svc-d", "inst-1");
        Assertions.assertEquals(CircuitState.OPEN, circuitBreakerApi.getState("svc-d", "inst-1"));

        circuitBreakerApi.allowRequest("svc-d", "inst-1"); // transition to HALF_OPEN
        Assertions.assertEquals(CircuitState.HALF_OPEN, circuitBreakerApi.getState("svc-d", "inst-1"));

        // Failure in HALF_OPEN reopens
        circuitBreakerApi.recordFailure("svc-d", "inst-1");
        Assertions.assertEquals(CircuitState.OPEN, circuitBreakerApi.getState("svc-d", "inst-1"));
    }

    @Test
    @Order(9)
    void successInClosedCircuitResetsFailureCount() {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .serviceName("svc-e")
                .failureThreshold(3)
                .successThreshold(2)
                .timeoutSeconds(30)
                .windowSizeSeconds(60)
                .build();
        circuitBreakerApi.configure("svc-e", config);

        circuitBreakerApi.recordFailure("svc-e", "inst-1");
        circuitBreakerApi.recordFailure("svc-e", "inst-1");
        circuitBreakerApi.recordSuccess("svc-e", "inst-1"); // should reset count
        // Now circuit should remain closed even with more failures (count was reset)
        circuitBreakerApi.recordFailure("svc-e", "inst-1");
        Assertions.assertEquals(CircuitState.CLOSED, circuitBreakerApi.getState("svc-e", "inst-1"));
    }

    @Test
    @Order(10)
    void configurePerService() {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .serviceName("svc-custom")
                .failureThreshold(10)
                .successThreshold(5)
                .timeoutSeconds(120)
                .windowSizeSeconds(300)
                .build();
        circuitBreakerApi.configure("svc-custom", config);
        CircuitBreakerConfig retrieved = circuitBreakerApi.getConfig("svc-custom");
        Assertions.assertEquals(10, retrieved.getFailureThreshold());
        Assertions.assertEquals(5, retrieved.getSuccessThreshold());
        Assertions.assertEquals(120, retrieved.getTimeoutSeconds());
    }

    @Test
    @Order(11)
    void recordSuccessInOpenStateKeepsCircuitOpen() {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .serviceName("svc-open-success")
                .failureThreshold(1)
                .successThreshold(3)
                .timeoutSeconds(60)
                .windowSizeSeconds(60)
                .build();
        circuitBreakerApi.configure("svc-open-success", config);
        // Open the circuit
        circuitBreakerApi.recordFailure("svc-open-success", "inst-1");
        Assertions.assertEquals(CircuitState.OPEN, circuitBreakerApi.getState("svc-open-success", "inst-1"));
        // recordSuccess while OPEN: neither HALF_OPEN nor CLOSED branch is taken
        Assertions.assertDoesNotThrow(() -> circuitBreakerApi.recordSuccess("svc-open-success", "inst-1"));
        // Circuit should still be OPEN (timeout=60s has not passed)
        Assertions.assertEquals(CircuitState.OPEN, circuitBreakerApi.getState("svc-open-success", "inst-1"));
    }

    @Test
    @Order(12)
    void defaultConfigReadsFailureThresholdAndTimeoutFromProperties() {
        Properties props = new Properties();
        props.setProperty("water.apigateway.circuit.breaker.failure.threshold", "7");
        props.setProperty("water.apigateway.circuit.breaker.timeout.ms", "4500");
        try {
            applicationProperties.loadProperties(props);
            CircuitBreakerConfig config = circuitBreakerApi.getConfig("svc-props");
            Assertions.assertEquals(7, config.getFailureThreshold());
            Assertions.assertEquals(5, config.getTimeoutSeconds());
        } finally {
            applicationProperties.unloadProperties(props);
        }
    }
}
