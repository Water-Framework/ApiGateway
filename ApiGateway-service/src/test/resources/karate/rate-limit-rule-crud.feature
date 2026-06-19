# The Goal of feature test is to ensure the correct format of json responses
# Functional tests are in RateLimitRuleApiTest
Feature: Check RateLimitRule Rest Api Response

  Scenario: RateLimitRule CRUD Operations

    * def ruleId = 'karate-rule-' + randomSeed

    # --------------- SAVE -----------------------------
    Given header Content-Type = 'application/json'
    And header Accept = 'application/json'
    Given url serviceBaseUrl + '/water/gateway/rate-limits'
    And request
    """
    {
      "entityVersion": 1,
      "ruleId": "#(ruleId)",
      "keyType": "CLIENT_IP",
      "maxRequests": 100,
      "windowSeconds": 60,
      "algorithm": "TOKEN_BUCKET",
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
      "ruleId": '#string',
      "keyType": "CLIENT_IP",
      "keyPattern": '#ignore',
      "maxRequests": 100,
      "windowSeconds": 60,
      "algorithm": "TOKEN_BUCKET",
      "burstCapacity": '#ignore',
      "enabled": true
    }
    """
    * def ruleEntityId = response.id

    # --------------- FIND -----------------------------
    Given header Content-Type = 'application/json'
    And header Accept = 'application/json'
    Given url serviceBaseUrl + '/water/gateway/rate-limits/' + ruleEntityId
    When method GET
    Then status 200
    And match response.id == ruleEntityId

    # --------------- FIND ALL -------------------------
    Given header Content-Type = 'application/json'
    And header Accept = 'application/json'
    Given url serviceBaseUrl + '/water/gateway/rate-limits'
    When method GET
    Then status 200
    And match response.results[*].id contains ruleEntityId

    # --------------- DELETE ---------------------------
    Given header Content-Type = 'application/json'
    And header Accept = 'application/json'
    Given url serviceBaseUrl + '/water/gateway/rate-limits/' + ruleEntityId
    When method DELETE
    Then status 204
