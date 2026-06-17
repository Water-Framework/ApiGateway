/*
 * Copyright 2024 Aristide Cittadino
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.water.infrastructure.apigateway.api.options;

import it.water.core.api.service.Service;

import java.util.Set;

/**
 * Runtime configuration options for the ApiGateway system service.
 * Implementations read values from ApplicationProperties and expose typed
 * getters following the Water Options pattern.
 */
public interface GatewaySystemOptions extends Service {

    /**
     * @return base URL of the remote ServiceDiscovery (empty string when
     * the gateway runs in the same runtime as ServiceDiscovery and does
     * not need HTTP fallback).
     */
    String getServiceDiscoveryUrl();

    /**
     * @return upstream proxy timeout in milliseconds.
     */
    long getProxyTimeoutMs();

    /**
     * @return default failure threshold used for circuit breakers when no
     * explicit per-service configuration has been provided.
     */
    int getCircuitBreakerFailureThreshold();

    /**
     * @return default circuit-breaker open timeout in milliseconds when no
     * explicit per-service configuration has been provided.
     */
    long getCircuitBreakerTimeoutMs();

    /**
     * @return default rate limit in requests per minute applied only when no
     * explicit rate-limit rule matches. Zero disables the fallback limiter.
     */
    int getDefaultRateLimiterRequestsPerMinute();

    /**
     * @return the set of trusted proxy IP addresses. The X-Forwarded-For / X-Real-IP request
     * headers are honored only when the immediate TCP peer matches one of these addresses;
     * otherwise the TCP source address is used. Empty set (the default) means the forwarded
     * headers are never trusted (#37).
     */
    Set<String> getTrustedProxies();
}
