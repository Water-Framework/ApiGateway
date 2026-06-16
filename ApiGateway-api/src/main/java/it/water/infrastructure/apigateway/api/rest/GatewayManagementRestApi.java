package it.water.infrastructure.apigateway.api.rest;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import it.water.core.api.service.rest.FrameworkRestApi;
import it.water.core.api.service.rest.RestApi;
import it.water.infrastructure.apigateway.model.ServiceStats;
import it.water.service.rest.api.security.LoggedIn;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Map;

/**
 * REST API interface for Gateway management operations.
 * <p>
 * Authorization is NOT declared here. The Water permission model enforces access in the SERVICE
 * layer: this REST contract is implemented by a {@code @FrameworkRestController} that delegates to
 * the permission-checked {@code GatewayApi} (a {@code @FrameworkComponent} service), where the
 * {@code @AllowGenericPermissions} checks against the Route resource actually fire. Keeping this
 * interface free of permission annotations also keeps {@code ApiGateway-api} free of any dependency
 * on {@code Core-permission}.
 */
@Path("/api/gateway/management")
@Api(produces = MediaType.APPLICATION_JSON, tags = "Gateway Management API")
@FrameworkRestApi
public interface GatewayManagementRestApi extends RestApi {

    @LoggedIn
    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "/health", notes = "Gateway Health Check", httpMethod = "GET")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Healthy"),
            @ApiResponse(code = 401, message = "Not authorized")
    })
    Map<String, Object> health();

    @LoggedIn
    @GET
    @Path("/metrics")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "/metrics", notes = "Service Metrics", httpMethod = "GET")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Metrics retrieved"),
            @ApiResponse(code = 401, message = "Not authorized")
    })
    Map<String, ServiceStats> metrics();

    @LoggedIn
    @GET
    @Path("/circuit-breakers")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "/circuit-breakers", notes = "Circuit Breaker States", httpMethod = "GET")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "States retrieved"),
            @ApiResponse(code = 401, message = "Not authorized")
    })
    Map<String, String> circuitBreakers();

    @LoggedIn
    @POST
    @Path("/sync")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "/sync", notes = "Sync with ServiceDiscovery", httpMethod = "POST")
    @ApiResponses(value = {
            @ApiResponse(code = 204, message = "Synced"),
            @ApiResponse(code = 502, message = "Sync failed"),
            @ApiResponse(code = 401, message = "Not authorized")
    })
    Response syncServiceDiscovery();
}
