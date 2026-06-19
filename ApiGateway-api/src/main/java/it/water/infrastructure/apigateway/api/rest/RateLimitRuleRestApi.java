package it.water.infrastructure.apigateway.api.rest;

import it.water.infrastructure.apigateway.model.RateLimitRule;
import it.water.core.api.model.PaginableResult;
import it.water.core.api.service.rest.FrameworkRestApi;
import it.water.core.api.service.rest.RestApi;
import it.water.core.api.service.rest.WaterJsonView;
import it.water.service.rest.api.security.LoggedIn;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.annotations.*;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

/**
 * REST API interface for RateLimitRule entity.
 */
@Path("/gateway/rate-limits")
@Api(produces = MediaType.APPLICATION_JSON, tags = "Gateway Rate Limit API")
@FrameworkRestApi
public interface RateLimitRuleRestApi extends RestApi {

    @LoggedIn
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @JsonView(WaterJsonView.Public.class)
    @ApiOperation(value = "/", notes = "RateLimitRule Save", httpMethod = "POST")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Successful operation"),
            @ApiResponse(code = 401, message = "Not authorized")
    })
    RateLimitRule save(RateLimitRule rule);

    @LoggedIn
    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @JsonView(WaterJsonView.Public.class)
    @ApiOperation(value = "/", notes = "RateLimitRule Update", httpMethod = "PUT")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Successful operation"),
            @ApiResponse(code = 401, message = "Not authorized")
    })
    RateLimitRule update(RateLimitRule rule);

    @LoggedIn
    @Path("/{id}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @JsonView(WaterJsonView.Public.class)
    @ApiOperation(value = "/{id}", notes = "RateLimitRule Find", httpMethod = "GET")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Successful operation"),
            @ApiResponse(code = 401, message = "Not authorized")
    })
    RateLimitRule find(@PathParam("id") long id);

    @LoggedIn
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @JsonView(WaterJsonView.Public.class)
    @ApiOperation(value = "/", notes = "RateLimitRule Find All", httpMethod = "GET")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Successful operation"),
            @ApiResponse(code = 401, message = "Not authorized")
    })
    PaginableResult<RateLimitRule> findAll();

    @LoggedIn
    @Path("/{id}")
    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "/{id}", notes = "RateLimitRule Delete", httpMethod = "DELETE")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Successful operation"),
            @ApiResponse(code = 401, message = "Not authorized")
    })
    void remove(@PathParam("id") long id);
}
