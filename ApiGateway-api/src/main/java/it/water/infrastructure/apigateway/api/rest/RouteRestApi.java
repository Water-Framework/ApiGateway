package it.water.infrastructure.apigateway.api.rest;

import it.water.infrastructure.apigateway.model.Route;
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
 * REST API interface for Route entity.
 */
@Path("/gateway/routes")
@Api(produces = MediaType.APPLICATION_JSON, tags = "Gateway Route API")
@FrameworkRestApi
public interface RouteRestApi extends RestApi {

    @LoggedIn
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @JsonView(WaterJsonView.Public.class)
    @ApiOperation(value = "/", notes = "Route Save", httpMethod = "POST", produces = MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Successful operation"),
            @ApiResponse(code = 401, message = "Not authorized"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    Route save(Route route);

    @LoggedIn
    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @JsonView(WaterJsonView.Public.class)
    @ApiOperation(value = "/", notes = "Route Update", httpMethod = "PUT", produces = MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Successful operation"),
            @ApiResponse(code = 401, message = "Not authorized"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    Route update(Route route);

    @LoggedIn
    @Path("/{id}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @JsonView(WaterJsonView.Public.class)
    @ApiOperation(value = "/{id}", notes = "Route Find", httpMethod = "GET", produces = MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Successful operation"),
            @ApiResponse(code = 401, message = "Not authorized"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    Route find(@PathParam("id") long id);

    @LoggedIn
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @JsonView(WaterJsonView.Public.class)
    @ApiOperation(value = "/", notes = "Route Find All", httpMethod = "GET", produces = MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Successful operation"),
            @ApiResponse(code = 401, message = "Not authorized"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    PaginableResult<Route> findAll();

    @LoggedIn
    @Path("/{id}")
    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "/{id}", notes = "Route Delete", httpMethod = "DELETE", produces = MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Successful operation"),
            @ApiResponse(code = 401, message = "Not authorized"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    void remove(@PathParam("id") long id);

    @LoggedIn
    @POST
    @Path("/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "/refresh", notes = "Refresh Routes", httpMethod = "POST", produces = MediaType.APPLICATION_JSON)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Routes refreshed"),
            @ApiResponse(code = 401, message = "Not authorized")
    })
    void refreshRoutes();
}
