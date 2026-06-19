package it.water.infrastructure.apigateway.service.rest.spring;

import it.water.infrastructure.apigateway.api.rest.RouteRestApi;
import it.water.infrastructure.apigateway.model.Route;
import it.water.core.api.model.PaginableResult;
import it.water.core.api.service.rest.FrameworkRestApi;
import it.water.core.api.service.rest.WaterJsonView;
import it.water.service.rest.api.security.LoggedIn;
import com.fasterxml.jackson.annotation.JsonView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Spring REST API interface for Route - adds Spring annotations on top of JAX-RS.
 */
@RequestMapping("/gateway/routes")
@FrameworkRestApi
public interface RouteSpringRestApi extends RouteRestApi {

    @LoggedIn
    @PostMapping
    @JsonView(WaterJsonView.Public.class)
    Route save(@RequestBody Route route);

    @LoggedIn
    @PutMapping
    @JsonView(WaterJsonView.Public.class)
    Route update(@RequestBody Route route);

    @LoggedIn
    @GetMapping("/{id}")
    @JsonView(WaterJsonView.Public.class)
    Route find(@PathVariable("id") long id);

    @LoggedIn
    @GetMapping
    @JsonView(WaterJsonView.Public.class)
    PaginableResult<Route> findAll();

    @LoggedIn
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable("id") long id);

    @LoggedIn
    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void refreshRoutes();
}
