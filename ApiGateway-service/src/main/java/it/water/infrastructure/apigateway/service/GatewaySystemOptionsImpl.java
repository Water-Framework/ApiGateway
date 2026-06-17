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

package it.water.infrastructure.apigateway.service;

import it.water.core.api.bundle.ApplicationProperties;
import it.water.core.interceptors.annotations.FrameworkComponent;
import it.water.core.interceptors.annotations.Inject;
import it.water.infrastructure.apigateway.api.options.GatewaySystemOptions;
import it.water.infrastructure.apigateway.model.GatewayConstants;
import lombok.Setter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Default implementation of {@link GatewaySystemOptions} reading values from
 * {@link ApplicationProperties}. Returns an empty string for the service
 * discovery URL when not configured: the caller can then decide whether
 * fallback-over-HTTP is viable.
 */
@FrameworkComponent
public class GatewaySystemOptionsImpl implements GatewaySystemOptions {

    @Inject
    @Setter
    private ApplicationProperties applicationProperties;

    @Override
    public String getServiceDiscoveryUrl() {
        if (applicationProperties == null) {
            return "";
        }
        return applicationProperties.getPropertyOrDefault(
                GatewayConstants.PROP_SERVICE_DISCOVERY_URL, "").trim();
    }

    @Override
    public long getProxyTimeoutMs() {
        if (applicationProperties == null) {
            return 30000L;
        }
        return Math.max(1L, applicationProperties.getPropertyOrDefault(
                GatewayConstants.PROP_PROXY_TIMEOUT_MS, 30000L));
    }

    @Override
    public int getCircuitBreakerFailureThreshold() {
        if (applicationProperties == null) {
            return 5;
        }
        return (int) Math.max(1L, applicationProperties.getPropertyOrDefault(
                GatewayConstants.PROP_CIRCUIT_BREAKER_FAILURE_THRESHOLD, 5L));
    }

    @Override
    public long getCircuitBreakerTimeoutMs() {
        if (applicationProperties == null) {
            return 30000L;
        }
        return Math.max(0L, applicationProperties.getPropertyOrDefault(
                GatewayConstants.PROP_CIRCUIT_BREAKER_TIMEOUT_MS, 30000L));
    }

    @Override
    public int getDefaultRateLimiterRequestsPerMinute() {
        if (applicationProperties == null) {
            return 0;
        }
        return (int) Math.max(0L, applicationProperties.getPropertyOrDefault(
                GatewayConstants.PROP_RATE_LIMITER_DEFAULT_RPM, 0L));
    }

    @Override
    public Set<String> getTrustedProxies() {
        if (applicationProperties == null) {
            return Collections.emptySet();
        }
        String raw = applicationProperties.getPropertyOrDefault(
                GatewayConstants.PROP_TRUSTED_PROXIES, "").trim();
        if (raw.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> proxies = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String ip = token.trim();
            if (!ip.isEmpty()) {
                proxies.add(ip);
            }
        }
        return Collections.unmodifiableSet(proxies);
    }
}
