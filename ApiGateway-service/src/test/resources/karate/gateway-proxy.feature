# The goal of this feature test is to exercise the real /proxy/** REST boundary.
# Functional routing logic stays covered in GatewayRouterApiTest.
Feature: Check Gateway Proxy Rest Api Response

  Scenario: Proxy returns 404 when no route matches

    Given header Accept = 'text/plain'
    Given url serviceBaseUrl + '/water/proxy/karate/no-route'
    When method GET
    Then status 404
    And match response == 'No route found for: /karate/no-route'

  Scenario: Proxy returns 503 when route exists but no healthy instance is available

    * def routeId = 'karate-proxy-route-' + randomSeed

    Given header Content-Type = 'application/json'
    And header Accept = 'application/json'
    Given url serviceBaseUrl + '/water/gateway/routes'
    And request
    """
    {
      "entityVersion": 1,
      "routeId": "#(routeId)",
      "pathPattern": "/karate/proxy/**",
      "method": "GET",
      "targetServiceName": "karate-proxy-backend",
      "priority": 50,
      "enabled": true
    }
    """
    When method POST
    Then status 200
    * def routeEntityId = response.id

    Given header Content-Type = 'application/json'
    And header Accept = 'application/json'
    Given url serviceBaseUrl + '/water/gateway/routes/refresh'
    When method POST
    Then status 204

    Given header Accept = 'text/plain'
    Given url serviceBaseUrl + '/water/proxy/karate/proxy/test'
    When method GET
    Then status 503
    And match response == 'No healthy instance available for service: karate-proxy-backend'

    Given header Content-Type = 'application/json'
    And header Accept = 'application/json'
    Given url serviceBaseUrl + '/water/gateway/routes/' + routeEntityId
    When method DELETE
    Then status 204
