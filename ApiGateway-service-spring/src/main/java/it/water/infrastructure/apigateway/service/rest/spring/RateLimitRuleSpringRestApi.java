package it.water.infrastructure.apigateway.service.rest.spring;

import it.water.infrastructure.apigateway.api.rest.RateLimitRuleRestApi;
import it.water.infrastructure.apigateway.model.RateLimitRule;
import it.water.core.api.model.PaginableResult;
import it.water.core.api.service.rest.FrameworkRestApi;
import it.water.core.api.service.rest.WaterJsonView;
import it.water.service.rest.api.security.LoggedIn;
import com.fasterxml.jackson.annotation.JsonView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Spring REST API interface for RateLimitRule.
 */
@RequestMapping("/gateway/rate-limits")
@FrameworkRestApi
public interface RateLimitRuleSpringRestApi extends RateLimitRuleRestApi {

    @LoggedIn
    @PostMapping
    @JsonView(WaterJsonView.Public.class)
    RateLimitRule save(@RequestBody RateLimitRule rule);

    @LoggedIn
    @PutMapping
    @JsonView(WaterJsonView.Public.class)
    RateLimitRule update(@RequestBody RateLimitRule rule);

    @LoggedIn
    @GetMapping("/{id}")
    @JsonView(WaterJsonView.Public.class)
    RateLimitRule find(@PathVariable("id") long id);

    @LoggedIn
    @GetMapping
    @JsonView(WaterJsonView.Public.class)
    PaginableResult<RateLimitRule> findAll();

    @LoggedIn
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable("id") long id);
}
