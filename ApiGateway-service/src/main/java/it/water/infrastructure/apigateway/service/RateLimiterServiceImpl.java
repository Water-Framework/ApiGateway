package it.water.infrastructure.apigateway.service;

import it.water.infrastructure.apigateway.api.RateLimiterApi;
import it.water.infrastructure.apigateway.api.options.GatewaySystemOptions;
import it.water.infrastructure.apigateway.model.*;
import it.water.core.interceptors.annotations.FrameworkComponent;
import it.water.core.api.interceptors.OnActivate;
import it.water.core.api.interceptors.OnDeactivate;
import it.water.core.interceptors.annotations.Inject;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter service implementation supporting TOKEN_BUCKET, SLIDING_WINDOW, FIXED_WINDOW.
 */
@FrameworkComponent
public class RateLimiterServiceImpl implements RateLimiterApi {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterServiceImpl.class);
    private static final String DEFAULT_RULE_ID = "__default_rate_limit__";

    private final Map<String, RateLimitRule> rules = new ConcurrentHashMap<>();

    @Inject
    @Setter
    private GatewaySystemOptions gatewaySystemOptions;

    // Token bucket state per key
    private static class TokenBucket {
        final AtomicLong tokens;
        final AtomicLong lastRefill;
        final int capacity;
        final double refillRate; // tokens per ms

        TokenBucket(int capacity, int maxRequests, int windowSeconds) {
            this.tokens = new AtomicLong(capacity);
            this.lastRefill = new AtomicLong(System.currentTimeMillis());
            this.capacity = capacity;
            this.refillRate = maxRequests / (windowSeconds * 1000.0);
        }

        synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefill.get();
            long newTokens = (long) (elapsed * refillRate);
            if (newTokens > 0) {
                tokens.set(Math.min(capacity, tokens.get() + newTokens));
                lastRefill.set(now);
            }
            if (tokens.get() > 0) {
                tokens.decrementAndGet();
                return true;
            }
            return false;
        }

        int remaining() {
            return (int) Math.max(0, tokens.get());
        }

        long resetAfterMs() {
            if (tokens.get() > 0) return 0;
            return (long) (1.0 / refillRate);
        }
    }

    // Sliding window: deque of timestamps per key
    private final Map<String, Deque<Long>> slidingWindows = new ConcurrentHashMap<>();
    // Fixed window: counter + window start per key
    private static class FixedWindowState {
        final AtomicInteger count = new AtomicInteger(0);
        final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
    }
    private final Map<String, FixedWindowState> fixedWindows = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> tokenBuckets = new ConcurrentHashMap<>();

    @OnActivate
    public void activate() {
        log.info("RateLimiterServiceImpl activated");
    }

    @OnDeactivate
    public void deactivate() {
        log.info("RateLimiterServiceImpl deactivating: clearing rate limiter state ({} sliding windows, {} fixed windows, {} token buckets)",
                slidingWindows.size(), fixedWindows.size(), tokenBuckets.size());
        slidingWindows.clear();
        fixedWindows.clear();
        tokenBuckets.clear();
    }

    @Override
    public RateLimitResult checkRateLimit(String key, GatewayRequest request) {
        RateLimitRule defaultRule = getDefaultRule();
        if (rules.isEmpty()) {
            return defaultRule != null
                    ? applyRule(key, defaultRule)
                    : RateLimitResult.builder().allowed(true).remaining(Integer.MAX_VALUE).resetAfterMs(0).build();
        }
        // Apply first matching rule
        for (RateLimitRule rule : rules.values()) {
            if (!rule.isEnabled()) continue;
            if (matchesKey(key, rule)) {
                return applyRule(key, rule);
            }
        }
        return defaultRule != null
                ? applyRule(key, defaultRule)
                : RateLimitResult.builder().allowed(true).remaining(Integer.MAX_VALUE).resetAfterMs(0).build();
    }

    @Override
    public void configureLimit(String ruleId, RateLimitRule rule) {
        log.info("Configuring rate limit rule: {}", ruleId);
        if (rule == null) {
            rules.remove(ruleId);
        } else {
            rules.put(ruleId, rule);
        }
    }

    @Override
    public RateLimitRule getRule(String ruleId) {
        return rules.get(ruleId);
    }

    @Override
    public List<RateLimitRule> getAllRules() {
        return new ArrayList<>(rules.values());
    }

    private boolean matchesKey(String key, RateLimitRule rule) {
        if (rule.getKeyPattern() == null || rule.getKeyPattern().isEmpty()) return true;
        return key.matches(rule.getKeyPattern());
    }

    private RateLimitResult applyRule(String key, RateLimitRule rule) {
        String bucketKey = rule.getRuleId() + ":" + key;
        return switch (rule.getAlgorithm()) {
            case TOKEN_BUCKET -> checkTokenBucket(bucketKey, rule);
            case SLIDING_WINDOW -> checkSlidingWindow(bucketKey, rule);
            case FIXED_WINDOW -> checkFixedWindow(bucketKey, rule);
        };
    }

    private RateLimitResult checkTokenBucket(String key, RateLimitRule rule) {
        TokenBucket bucket = tokenBuckets.computeIfAbsent(key,
                k -> new TokenBucket(rule.getBurstCapacity() > 0 ? rule.getBurstCapacity() : rule.getMaxRequests(),
                        rule.getMaxRequests(), rule.getWindowSeconds()));
        boolean allowed = bucket.tryConsume();
        int remaining = bucket.remaining();
        long resetAfterMs = allowed ? 0 : bucket.resetAfterMs();
        return RateLimitResult.builder()
                .allowed(allowed)
                .remaining(remaining)
                .resetAfterMs(resetAfterMs)
                .rule(rule)
                .build();
    }

    private synchronized RateLimitResult checkSlidingWindow(String key, RateLimitRule rule) {
        Deque<Long> timestamps = slidingWindows.computeIfAbsent(key, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        long windowMs = rule.getWindowSeconds() * 1000L;
        // Remove old timestamps
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMs) {
            timestamps.pollFirst();
        }
        if (timestamps.size() < rule.getMaxRequests()) {
            timestamps.addLast(now);
            return RateLimitResult.builder()
                    .allowed(true)
                    .remaining(rule.getMaxRequests() - timestamps.size())
                    .resetAfterMs(0)
                    .rule(rule)
                    .build();
        }
        long resetAfterMs = timestamps.isEmpty() ? 0 : windowMs - (now - timestamps.peekFirst());
        return RateLimitResult.builder()
                .allowed(false)
                .remaining(0)
                .resetAfterMs(Math.max(0, resetAfterMs))
                .rule(rule)
                .build();
    }

    private RateLimitResult checkFixedWindow(String key, RateLimitRule rule) {
        FixedWindowState state = fixedWindows.computeIfAbsent(key, k -> new FixedWindowState());
        long now = System.currentTimeMillis();
        long windowMs = rule.getWindowSeconds() * 1000L;
        synchronized (state) {
            if (now - state.windowStart.get() > windowMs) {
                state.count.set(0);
                state.windowStart.set(now);
            }
            int current = state.count.get();
            if (current < rule.getMaxRequests()) {
                state.count.incrementAndGet();
                return RateLimitResult.builder()
                        .allowed(true)
                        .remaining(rule.getMaxRequests() - state.count.get())
                        .resetAfterMs(0)
                        .rule(rule)
                        .build();
            }
            long resetAfterMs = windowMs - (now - state.windowStart.get());
            return RateLimitResult.builder()
                    .allowed(false)
                    .remaining(0)
                    .resetAfterMs(Math.max(0, resetAfterMs))
                    .rule(rule)
                    .build();
        }
    }

    private RateLimitRule getDefaultRule() {
        if (gatewaySystemOptions == null) {
            return null;
        }
        int rpm = gatewaySystemOptions.getDefaultRateLimiterRequestsPerMinute();
        if (rpm <= 0) {
            return null;
        }
        RateLimitRule defaultRule = new RateLimitRule(
                DEFAULT_RULE_ID,
                RateLimitKeyType.CLIENT_IP,
                rpm,
                60,
                RateLimitAlgorithm.TOKEN_BUCKET);
        defaultRule.setBurstCapacity(rpm);
        defaultRule.setEnabled(true);
        return defaultRule;
    }
}
