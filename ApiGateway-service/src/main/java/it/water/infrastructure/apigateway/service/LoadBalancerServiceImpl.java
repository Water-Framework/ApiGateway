package it.water.infrastructure.apigateway.service;

import it.water.infrastructure.apigateway.api.LoadBalancerApi;
import it.water.infrastructure.apigateway.model.GatewayRequest;
import it.water.infrastructure.apigateway.model.LoadBalancerStrategy;
import it.water.core.interceptors.annotations.FrameworkComponent;
import it.water.core.api.interceptors.OnActivate;
import it.water.service.discovery.model.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Load balancer service implementation supporting multiple strategies.
 */
@FrameworkComponent
public class LoadBalancerServiceImpl implements LoadBalancerApi {

    private static final Logger log = LoggerFactory.getLogger(LoadBalancerServiceImpl.class);

    private LoadBalancerStrategy strategy = LoadBalancerStrategy.ROUND_ROBIN;
    private final Map<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> weightedCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> leastConnectionsCounts = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @OnActivate
    public void activate() {
        log.info("LoadBalancerServiceImpl activated with strategy: {}", strategy);
    }

    @Override
    public ServiceRegistration selectInstance(String serviceName, GatewayRequest request, List<ServiceRegistration> instances) {
        if (instances == null || instances.isEmpty()) {
            return null;
        }
        if (instances.size() == 1) {
            return instances.get(0);
        }
        return switch (strategy) {
            case WEIGHTED_ROUND_ROBIN -> selectWeightedRoundRobin(serviceName, instances);
            case LEAST_CONNECTIONS -> selectLeastConnections(serviceName, instances);
            case RANDOM -> instances.get(random.nextInt(instances.size()));
            case IP_HASH -> selectByIpHash(request.getClientIp(), instances);
            default -> selectRoundRobin(serviceName, instances);
        };
    }

    @Override
    public void reportSuccess(String serviceName, String instanceId, long latencyMs) {
        log.debug("Success reported for {}/{} in {}ms", serviceName, instanceId, latencyMs);
        String key = serviceName + ":" + instanceId;
        AtomicLong counter = leastConnectionsCounts.get(key);
        if (counter != null && counter.get() > 0) {
            counter.decrementAndGet();
        }
    }

    @Override
    public void reportFailure(String serviceName, String instanceId, Exception error) {
        log.warn("Failure reported for {}/{}: {}", serviceName, instanceId, error.getMessage());
        String key = serviceName + ":" + instanceId;
        AtomicLong counter = leastConnectionsCounts.get(key);
        if (counter != null && counter.get() > 0) {
            counter.decrementAndGet();
        }
    }

    @Override
    public LoadBalancerStrategy getStrategy() {
        return strategy;
    }

    @Override
    public void setStrategy(LoadBalancerStrategy strategy) {
        log.info("Changing load balancer strategy to: {}", strategy);
        this.strategy = strategy;
    }

    private ServiceRegistration selectRoundRobin(String serviceName, List<ServiceRegistration> instances) {
        AtomicInteger counter = roundRobinCounters.computeIfAbsent(serviceName, k -> new AtomicInteger(0));
        int index = Math.floorMod(counter.getAndIncrement(), instances.size());
        return instances.get(index);
    }

    private ServiceRegistration selectWeightedRoundRobin(String serviceName, List<ServiceRegistration> instances) {
        // Use metadata["weight"] for weighted distribution, default weight=1
        int totalWeight = 0;
        for (ServiceRegistration reg : instances) {
            int weight = getWeight(reg);
            totalWeight += weight;
        }
        if (totalWeight <= 0) {
            return selectRoundRobin(serviceName, instances);
        }
        AtomicInteger counter = weightedCounters.computeIfAbsent(serviceName, k -> new AtomicInteger(0));
        int pos = Math.floorMod(counter.getAndIncrement(), totalWeight);
        int cumulative = 0;
        for (ServiceRegistration reg : instances) {
            cumulative += getWeight(reg);
            if (pos < cumulative) {
                return reg;
            }
        }
        return instances.get(0);
    }

    private ServiceRegistration selectLeastConnections(String serviceName, List<ServiceRegistration> instances) {
        ServiceRegistration best = null;
        long minConnections = Long.MAX_VALUE;
        for (ServiceRegistration reg : instances) {
            String key = serviceName + ":" + reg.getInstanceId();
            long connections = leastConnectionsCounts.computeIfAbsent(key, k -> new AtomicLong(0)).get();
            if (connections < minConnections) {
                minConnections = connections;
                best = reg;
            }
        }
        if (best != null) {
            String key = serviceName + ":" + best.getInstanceId();
            leastConnectionsCounts.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        }
        return best != null ? best : instances.get(0);
    }

    private ServiceRegistration selectByIpHash(String clientIp, List<ServiceRegistration> instances) {
        if (clientIp == null || clientIp.isEmpty()) {
            return instances.get(0);
        }
        return instances.get(Math.floorMod(clientIp.hashCode(), instances.size()));
    }

    private int getWeight(ServiceRegistration reg) {
        if (reg.getMetadata() != null) {
            String weightStr = reg.getMetadata().get("weight");
            if (weightStr != null) {
                try {
                    return Math.max(1, Integer.parseInt(weightStr));
                } catch (NumberFormatException e) {
                    // ignore, use default
                }
            }
        }
        return 1;
    }
}
