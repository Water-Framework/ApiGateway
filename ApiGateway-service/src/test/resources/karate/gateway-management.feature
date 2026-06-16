# The Goal of feature test is to ensure the correct format of json responses and endpoint wiring.
# Permission-level authorization tests (who can/cannot call these endpoints) live in GatewayManagementApiTest.
# In this Karate runtime water.testMode=true and jwt.validate=false with admin impersonation,
# so authorization is effectively bypassed here — do NOT assert 403 responses in this file.
Feature: Check Gateway Management Rest Api Response

  Scenario: Health Check Endpoint returns correct shape

    Given header Content-Type = 'application/json'
    And header Accept = 'application/json'
    Given url serviceBaseUrl + '/water/api/gateway/management/health'
    When method GET
    Then status 200
    And match response ==
    """
    {
      "status": "UP",
      "timestamp": '#number',
      "service": "ApiGateway"
    }
    """

  Scenario: Metrics Endpoint returns a non-null object

    Given header Content-Type = 'application/json'
    And header Accept = 'application/json'
    Given url serviceBaseUrl + '/water/api/gateway/management/metrics'
    When method GET
    Then status 200
    # #32: endpoint now delegates to GatewayApi (permission-checked), not GatewaySystemApi directly.
    # In test-mode (admin impersonation) the call succeeds and returns a JSON object.
    And match response == '#object'

  Scenario: Circuit Breakers Endpoint returns a non-null object

    Given header Content-Type = 'application/json'
    And header Accept = 'application/json'
    Given url serviceBaseUrl + '/water/api/gateway/management/circuit-breakers'
    When method GET
    Then status 200
    And match response == '#object'

  Scenario: Sync Service Discovery Endpoint is wired through GatewayApi

    Given header Content-Type = 'application/json'
    And header Accept = 'application/json'
    Given url serviceBaseUrl + '/water/api/gateway/management/sync'
    When method POST
    # #32: endpoint now delegates to GatewayApi.syncWithServiceDiscovery() (REFRESH_ROUTES-protected).
    # In the test environment there is no live ServiceDiscovery, so the system layer throws
    # IllegalStateException which is surfaced as HTTP 502.  Authorization has already passed
    # (admin impersonation) — the 502 confirms endpoint wiring is correct, NOT an authz rejection.
    Then status 502
    And match response.error contains 'ServiceDiscovery sync failed'
