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

package it.water.infrastructure.apigateway.model;

/**
 * Property keys used by the ApiGateway module.
 * Values are read via ApplicationProperties through the Options pattern
 * (see GatewaySystemOptions).
 */
public abstract class GatewayConstants {

    /**
     * URL of the ServiceDiscovery REST endpoint used by all runtimes,
     * including ApiGateway, to interact with ServiceDiscovery.
     * Example: http://service-discovery:8181/water
     */
    public static final String PROP_SERVICE_DISCOVERY_URL = "water.discovery.url";
    public static final String PROP_PROXY_TIMEOUT_MS = "water.apigateway.proxy.timeout";
    public static final String PROP_CIRCUIT_BREAKER_FAILURE_THRESHOLD =
            "water.apigateway.circuit.breaker.failure.threshold";
    public static final String PROP_CIRCUIT_BREAKER_TIMEOUT_MS =
            "water.apigateway.circuit.breaker.timeout.ms";
    public static final String PROP_RATE_LIMITER_DEFAULT_RPM =
            "water.apigateway.rate.limiter.default.rpm";
    /**
     * Comma-separated list of trusted proxy IP addresses. The X-Forwarded-For / X-Real-IP
     * headers are only honored when the immediate TCP peer (request.getRemoteAddr()) matches
     * one of these. Default empty: the forwarded headers are never trusted and the TCP source
     * address is always used as the rate-limit / client identity key (#37 - prevents
     * rate-limit-key spoofing via client-controlled forwarding headers).
     */
    public static final String PROP_TRUSTED_PROXIES =
            "water.apigateway.trusted.proxies";

    private GatewayConstants() {
        // prevent instantiation
    }
}
