# The Goal of feature test is to ensure the correct format of json responses
# Functional tests are in RouteApiTest
Feature: Check Route Rest Api Response

  Scenario: Route CRUD Operations

    * def routeId = 'karate-route-' + randomSeed

    # --------------- SAVE -----------------------------
    Given header Content-Type = 'application/json'
    And header Accept = 'application/json'
    Given url serviceBaseUrl + '/water/gateway/routes'
    And request
    """
    {
      "entityVersion": 1,
      "routeId": "#(routeId)",
      "pathPattern": "/api/karate/**",
      "method": "ANY",
      "targetServiceName": "karate-backend",
      "priority": 10,
      "enabled": true
    }
    """
    When method POST
    Then status 200
    And match response ==
    """
    {
      "id": #number,
      "entityVersion": 1,
      "entityCreateDate": '#number',
      "entityModifyDate": '#number',
      "categoryIds": '#ignore',
      "tagIds": '#ignore',
      "routeId": '#string',
      "pathPattern": "/api/karate/**",
      "method": "ANY",
      "targetServiceName": "karate-backend",
      "rewritePath": '#ignore',
      "priority": 10,
      "enabled": true,
      "predicates": '#ignore',
      "filters": '#ignore'
    }
    """
    * def routeEntityId = response.id

    # --------------- FIND -----------------------------
    Given header Content-Type = 'application/json'
    And header Accept = 'application/json'
    Given url serviceBaseUrl + '/water/gateway/routes/' + routeEntityId
    When method GET
    Then status 200
    And match response.id == routeEntityId

    # --------------- FIND ALL -------------------------
    Given header Content-Type = 'application/json'
    And header Accept = 'application/json'
    Given url serviceBaseUrl + '/water/gateway/routes'
    When method GET
    Then status 200
    And match response.results[*].id contains routeEntityId

    # --------------- DELETE ---------------------------
    Given header Content-Type = 'application/json'
    And header Accept = 'application/json'
    Given url serviceBaseUrl + '/water/gateway/routes/' + routeEntityId
    When method DELETE
    Then status 204

  Scenario: Refresh Routes Endpoint

    Given header Content-Type = 'application/json'
    And header Accept = 'application/json'
    Given url serviceBaseUrl + '/water/gateway/routes/refresh'
    When method POST
    Then status 204
