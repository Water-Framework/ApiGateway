package it.water.infrastructure.apigateway.service.rest;

import it.water.core.api.service.rest.FrameworkRestController;
import it.water.core.interceptors.annotations.Inject;
import it.water.infrastructure.apigateway.api.GatewayRouterApi;
import it.water.infrastructure.apigateway.api.options.GatewaySystemOptions;
import it.water.infrastructure.apigateway.api.rest.GatewayProxyRestApi;
import it.water.infrastructure.apigateway.model.GatewayRequest;
import it.water.infrastructure.apigateway.model.GatewayResponse;
import it.water.infrastructure.apigateway.model.HttpMethod;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * REST controller exposing a real HTTP gateway entrypoint under /water/proxy/*.
 */
@FrameworkRestController(referredRestApi = GatewayProxyRestApi.class)
public class GatewayProxyRestControllerImpl implements GatewayProxyRestApi {

    private static final Logger log = LoggerFactory.getLogger(GatewayProxyRestControllerImpl.class);

    @Inject
    @Setter
    private GatewayRouterApi gatewayRouterApi;

    @Inject
    @Setter
    private GatewaySystemOptions gatewaySystemOptions;

    /**
     * The JAX-RS runtime (CXF) injects a thread-bound proxy here even though this controller is a
     * Water singleton, so getRemoteAddr() returns the per-request immediate TCP peer. This is the
     * only trustworthy source of client identity: the X-Forwarded-* headers are client-controlled.
     * The servlet API is compileOnly (provided by the servlet container at runtime).
     */
    @Context
    private HttpServletRequest httpServletRequest;

    @Override
    public Response proxyGet(String path, HttpHeaders headers, UriInfo uriInfo) {
        return proxy(HttpMethod.GET, path, null, headers, uriInfo);
    }

    @Override
    public Response proxyPost(String path, byte[] body, HttpHeaders headers, UriInfo uriInfo) {
        return proxy(HttpMethod.POST, path, body, headers, uriInfo);
    }

    @Override
    public Response proxyPut(String path, byte[] body, HttpHeaders headers, UriInfo uriInfo) {
        return proxy(HttpMethod.PUT, path, body, headers, uriInfo);
    }

    public Response proxyDelete(String path, byte[] body, HttpHeaders headers, UriInfo uriInfo) {
        return proxy(HttpMethod.DELETE, path, body, headers, uriInfo);
    }

    @Override
    public Response proxyOptions(String path, byte[] body, HttpHeaders headers, UriInfo uriInfo) {
        return proxy(HttpMethod.OPTIONS, path, body, headers, uriInfo);
    }

    @Override
    public Response proxyHead(String path, HttpHeaders headers, UriInfo uriInfo) {
        return proxy(HttpMethod.HEAD, path, null, headers, uriInfo);
    }

    private Response proxy(HttpMethod method, String path, byte[] body, HttpHeaders headers, UriInfo uriInfo) {
        GatewayResponse gatewayResponse = gatewayRouterApi.route(buildGatewayRequest(method, path, body, headers, uriInfo));
        Response.ResponseBuilder builder = Response.status(gatewayResponse.getStatusCode());
        gatewayResponse.getHeaders().forEach(builder::header);
        if (method != HttpMethod.HEAD && gatewayResponse.getBody() != null) {
            builder.entity(gatewayResponse.getBody());
        }
        return builder.build();
    }

    private GatewayRequest buildGatewayRequest(HttpMethod method, String path, byte[] body, HttpHeaders headers, UriInfo uriInfo) {
        return GatewayRequest.builder()
                .method(method)
                .path(normalizePath(path))
                .queryString(uriInfo.getRequestUri().getRawQuery())
                .headers(extractHeaders(headers))
                .body(body == null || body.length == 0 ? null : body)
                .clientIp(extractClientIp(headers))
                .protocol(uriInfo.getRequestUri().getScheme())
                .build();
    }

    private Map<String, String> extractHeaders(HttpHeaders requestHeaders) {
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, java.util.List<String>> header : requestHeaders.getRequestHeaders().entrySet()) {
            if (!header.getValue().isEmpty()) {
                headers.put(header.getKey(), header.getValue().get(0));
            }
        }
        return headers;
    }

    /**
     * #37 - Resolves the client IP used as the rate-limit / identity key.
     * <p>
     * X-Forwarded-For / X-Real-IP are client-controlled and must NOT be trusted blindly: a caller
     * could forge them to evade per-IP rate limiting or impersonate another source. They are honored
     * ONLY when the immediate TCP peer (request.getRemoteAddr()) is a configured trusted proxy
     * (water.apigateway.trusted.proxies). With the default empty list, the forwarded headers are
     * never trusted and the TCP source address is always used.
     * <p>
     * Limitation: the TCP source is read from the injected {@link HttpServletRequest}; if it is not
     * available in the current runtime (e.g. when the request is not served through a servlet stack)
     * the peer cannot be resolved, so we fail closed and never trust the forwarded headers.
     */
    private String extractClientIp(HttpHeaders headers) {
        String tcpSource = resolveTcpSourceAddress();
        Set<String> trustedProxies = (gatewaySystemOptions != null)
                ? gatewaySystemOptions.getTrustedProxies() : Set.of();

        // Only honor forwarding headers when the immediate peer is a known trusted proxy.
        if (tcpSource != null && trustedProxies.contains(tcpSource)) {
            String forwardedFor = headers.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
            String realIp = headers.getRequestHeaders().getFirst("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        } else if (tcpSource == null && !trustedProxies.isEmpty()) {
            // trusted proxies configured but peer unknown: fail closed, do not trust forwarded headers
            log.warn("Trusted proxies configured but TCP source address is unavailable; ignoring forwarding headers");
        }

        return (tcpSource != null) ? tcpSource : "unknown";
    }

    /**
     * @return the immediate TCP peer address (getRemoteAddr()) or null if it cannot be resolved in
     * the current runtime. Reading via the injected per-request HttpServletRequest keeps this
     * controller a singleton while still seeing per-request state.
     */
    private String resolveTcpSourceAddress() {
        try {
            if (httpServletRequest != null) {
                return httpServletRequest.getRemoteAddr();
            }
        } catch (Exception e) {
            log.trace("Unable to resolve TCP source address: {}", e.getMessage());
        }
        return null;
    }

    private String normalizePath(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }
}
