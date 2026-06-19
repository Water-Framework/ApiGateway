# ApiGateway Module — HTTP API Gateway

## Purpose

Production HTTP gateway for the Water Framework microservices ecosystem. Sits in front of downstream services, matches incoming requests against configured `Route` rules, resolves a live instance through `ServiceDiscovery`, and forwards the request. Also provides circuit breaking, load balancing, rate limiting, and runtime management endpoints.

Runtimes supported: **OSGi / Karaf** (via `bnd.bnd` + `WaterBundleActivator`) and **Spring Boot** (via `ApiGateway-service-spring`). Both runtimes expose the same logical endpoints through different technologies (JAX-RS vs Spring MVC).

## Sub-modules

| Sub-module | Runtime | Key classes |
|---|---|---|
| `ApiGateway-api` | All | `GatewayApi`, `GatewayRouterApi`, `GatewaySystemApi`, `LoadBalancerApi`, `CircuitBreakerApi`, `RateLimiterApi`, `ProxyHandlerApi`, `GatewayRouteApi`, `RateLimitRuleApi`, `GatewaySystemOptions`, REST contracts |
| `ApiGateway-model` | All | `Route`, `RateLimitRule`, `GatewayRequest`, `GatewayResponse`, `RouteResult`, `RateLimitResult`, `ServiceStats`, `CircuitBreakerConfig`, `HttpMethod`, `LoadBalancerStrategy`, `CircuitState`, `RateLimitAlgorithm`, `RateLimitKeyType`, `GatewayConstants`, `ApiGatewayActions` |
| `ApiGateway-service` | OSGi / Karaf | Service impls, JAX-RS controllers, `bnd.bnd`, `GatewaySystemOptionsImpl` |
| `ApiGateway-service-spring` | Spring Boot | Spring MVC REST controllers (constructor-injected) |

## Entity — Route

```java
@Entity
@Table(name = "gateway_route",
       uniqueConstraints = @UniqueConstraint(columnNames = {"routeId"}))
@AccessControl(
    availableActions = {
        CrudActions.SAVE, CrudActions.UPDATE, CrudActions.FIND, CrudActions.FIND_ALL, CrudActions.REMOVE,
        ApiGatewayActions.CONFIGURE_RATE_LIMIT,
        ApiGatewayActions.MANAGE_CIRCUIT_BREAKER,
        ApiGatewayActions.VIEW_METRICS,
        ApiGatewayActions.PROXY_REQUEST,
        ApiGatewayActions.REFRESH_ROUTES
    },
    rolesPermissions = { ... }
)
public class Route extends AbstractJpaEntity
    implements ProtectedEntity, OwnedResource { ... }
```

Fields:

| Field | Notes |
|---|---|
| `routeId` | unique logical id, `@NoMalitiusCode`, size 1-100 |
| `pathPattern` | regex against request path, size 1-500 |
| `method` | `HttpMethod` enum (`GET` ... `ANY`); default `ANY` (`@PrePersist`) |
| `targetServiceName` | logical service name resolved via ServiceDiscovery |
| `rewritePath` | optional, applied before forward |
| `priority` | higher wins |
| `enabled` | boolean, default `true` |
| `predicates` | `Map<String,String>` extension hook (EAGER) |
| `filters` | `Map<String,String>` extension hook (EAGER) |
| `ownerUserId` | hidden from the Public JSON view |

## Custom actions

`it.water.infrastructure.apigateway.actions.ApiGatewayActions`:

- `CONFIGURE_RATE_LIMIT = "configure-rate-limit"`
- `MANAGE_CIRCUIT_BREAKER = "manage-circuit-breaker"`
- `VIEW_METRICS = "view-metrics"`
- `PROXY_REQUEST = "proxy-request"`
- `REFRESH_ROUTES = "refresh-routes"`

## Default roles

| Role | Actions |
|---|---|
| `gatewayManager` | full CRUD + all custom actions |
| `gatewayViewer` | `find`, `find_all`, `view-metrics` |
| `gatewayOperator` | `find`, `find_all`, `update`, `view-metrics`, `proxy-request`, `refresh-routes` |

## Key services

### GatewayRouterServiceImpl
Matches request → resolves instance via `LoadBalancer` → checks `CircuitBreaker` → forwards via `ProxyHandler`. Regex patterns cached in `ConcurrentHashMap<String, Pattern>`. Routes held in a `volatile` priority-sorted list, atomically swapped on update.

### GatewaySystemServiceImpl
In-process `ServiceRegistrationApi` first, HTTP fallback to `/water/internal/serviceregistration/available` when the gateway runs in a separate runtime from `ServiceDiscovery`. Maintains an in-memory `serviceCache: Map<String, List<ServiceRegistration>>`. `HttpClient` lazily initialized in `@OnActivate`.

### LoadBalancerServiceImpl
`ROUND_ROBIN` and `WEIGHTED_ROUND_ROBIN`. Separate `AtomicInteger` counter per strategy per `serviceName`.

### CircuitBreakerServiceImpl
Per-instance state (key = `serviceName + ":" + instanceId`). A single failing Karaf does not poison the whole logical service.

### RateLimiterServiceImpl
Pluggable algorithm per `RateLimitRule`: `TokenBucket` and `FixedWindow`. Thread-safe via `ConcurrentHashMap`.

### ProxyHandlerImpl
JDK 11+ `HttpClient`. Hop-by-hop header stripping (`connection`, `keep-alive`, `transfer-encoding`, `te`, `upgrade`, `proxy-authorization`, `proxy-authenticate`, `trailers`). Injects `X-Forwarded-For`, `X-Forwarded-Host`, `X-Forwarded-Proto`.

## REST boundaries

### Management API

Base path: `/water/gateway/`. JWT-protected CRUD + operational endpoints.

| Method | Path |
|---|---|
| `POST` / `PUT` / `GET` / `DELETE` | `/water/gateway/routes[/{id}]` |
| `POST` / `PUT` / `GET` / `DELETE` | `/water/gateway/rate-limits[/{id}]` |
| `GET` | `/water/gateway/management/health` |
| `GET` | `/water/gateway/management/metrics` |
| `GET` | `/water/gateway/management/circuitBreakers` |
| `POST` | `/water/gateway/management/sync` |

### Proxy entrypoint

Base path: `/water/proxy/`. Supported verbs: `GET`, `POST`, `PUT`, `DELETE`, `OPTIONS`, `HEAD`; `@LoggedIn` on the Spring runtime.

```
GET | POST | PUT | DELETE | OPTIONS | HEAD  /water/proxy/{path:.+}
```

Routed to the live instance via the pipeline described above. `404` if no route matches, `502` on proxy failure.

## Reserved Gateway Authentication

The following types are kept as a future extension point and are not part of the current proxy path:

- `GatewayAuthenticationApi`
- `GatewayAuthenticationServiceImpl`
- `AuthResult`
- `AuthMethod`
- `ApiKeyConfig`

Rules:

- do not wire `GatewayAuthenticationServiceImpl` into `GatewayRouterServiceImpl` until JWT validation delegates to the real Water `JwtTokenService`
- JWT authentication must stay fail-closed; never decode unsigned JWT payloads locally and treat them as authenticated
- the current runtime boundary is protected by Water REST security annotations (`@LoggedIn`)

## Configuration

All properties read via `GatewaySystemOptionsImpl` through `ApplicationProperties`:

| Property | Default | Notes |
|---|---|---|
| `water.discovery.url` | `""` | Empty → use in-process registry only |
| `water.apigateway.proxy.timeout` | `30000` ms | Upstream HTTP timeout, min `1` ms |
| `water.apigateway.circuit.breaker.failure.threshold` | `5` | Min `1` |
| `water.apigateway.circuit.breaker.timeout.ms` | `30000` ms | Circuit OPEN timeout, min `0` |
| `water.apigateway.rate.limiter.default.rpm` | `0` | `0` = fallback rate limiter disabled |

Keys are centralized in `it.water.infrastructure.apigateway.model.GatewayConstants`.

## Dual-runtime REST controllers

Two sets of controllers coexist for the two supported runtimes:

- **JAX-RS** (`ApiGateway-service/.../rest/`): `@FrameworkRestController` + `@Inject @Setter`, wired through the Water `ComponentRegistry`. Used in OSGi / Karaf.
- **Spring MVC** (`ApiGateway-service-spring/.../rest/spring/`): `@RestController` + constructor injection with `final` fields. Used in Spring Boot.

This is intentional. Both controller sets implement the same logical contract (`*RestApi` interface) and expose the same paths. Do **not** "uniform" them to a single pattern — the duplication is how Water supports both runtimes.

## Gradle / packaging

- `ApiGateway-service` is published both as a regular JAR and as an OSGi bundle through `bnd.bnd` (activator: `it.water.implementation.osgi.bundle.WaterBundleActivator`). The BND descriptor imports Water Core / Repository / REST / ServiceDiscovery / Implementation packages.
- `ApiGateway-service-spring` is a regular Spring Boot module.

## Testing

- Unit tests: `WaterTestExtension` — service / router / load-balancer / rate-limiter behavior
- REST boundaries: **Karate only** — no direct JUnit calls to the REST controllers. Feature files: `route-crud.feature`, `rate-limit-rule-crud.feature`, `gateway-management.feature`, `gateway-proxy.feature`.
- `it.water.application.properties` sets `water.testMode=true` and `water.rest.security.jwt.validate=false` for the Karate runtime.

## Code generation rules

- New load-balancer strategies: implement `LoadBalancerApi` + register as `@FrameworkComponent`
- New rate-limit algorithms: add to `RateLimitAlgorithm` enum + branch in `RateLimiterServiceImpl`
- New circuit-breaker behavior: subclass / override in `CircuitBreakerServiceImpl`
- New route actions: add to `ApiGatewayActions` and extend the `@AccessControl` of `Route`
- Route regex patterns are compiled once and cached — always use the cache, never recompile on each request
- `@Path` on JAX-RS `*RestApi` must NOT include `/water` (CXF adds it automatically)
- Spring MVC controllers: **use constructor injection with `final` fields**, never `@Autowired` field injection
- REST controllers tested **exclusively via Karate** — no direct JUnit calls
