package it.water.infrastructure.apigateway;

import it.water.infrastructure.apigateway.api.RouteApi;
import it.water.infrastructure.apigateway.api.RouteRepository;
import it.water.infrastructure.apigateway.api.RouteSystemApi;
import it.water.infrastructure.apigateway.model.HttpMethod;
import it.water.infrastructure.apigateway.model.Route;
import it.water.core.api.bundle.Runtime;
import it.water.core.api.model.PaginableResult;
import it.water.core.api.model.Role;
import it.water.core.api.permission.PermissionManager;
import it.water.core.api.registry.ComponentRegistry;
import it.water.core.api.repository.query.Query;
import it.water.core.api.role.RoleManager;
import it.water.core.api.service.Service;
import it.water.core.api.user.UserManager;
import it.water.core.interceptors.annotations.Inject;
import it.water.core.model.exceptions.ValidationException;
import it.water.core.model.exceptions.WaterRuntimeException;
import it.water.core.permission.exceptions.UnauthorizedException;
import it.water.core.testing.utils.bundle.TestRuntimeInitializer;
import it.water.core.testing.utils.junit.WaterTestExtension;
import it.water.core.testing.utils.runtime.TestRuntimeUtils;
import it.water.repository.entity.model.exceptions.DuplicateEntityException;
import it.water.repository.entity.model.exceptions.NoResultException;
import lombok.Setter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for Route entity CRUD operations with permission enforcement.
 */
@ExtendWith(WaterTestExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RouteApiTest implements Service {

    @Inject
    @Setter
    private ComponentRegistry componentRegistry;

    @Inject
    @Setter
    private RouteApi routeApi;

    @Inject
    @Setter
    private Runtime runtime;

    @Inject
    @Setter
    private RouteRepository routeRepository;

    @Inject
    @Setter
    private RouteSystemApi routeSystemApi;

    @Inject
    @Setter
    private PermissionManager permissionManager;

    @Inject
    @Setter
    private UserManager userManager;

    @Inject
    @Setter
    private RoleManager roleManager;

    private it.water.core.api.model.User adminUser;
    private it.water.core.api.model.User managerUser;
    private it.water.core.api.model.User viewerUser;
    private it.water.core.api.model.User operatorUser;

    private Role managerRole;
    private Role viewerRole;
    private Role operatorRole;

    @BeforeAll
    void beforeAll() {
        managerRole = roleManager.getRole(Route.DEFAULT_MANAGER_ROLE);
        viewerRole = roleManager.getRole(Route.DEFAULT_VIEWER_ROLE);
        operatorRole = roleManager.getRole(Route.DEFAULT_OPERATOR_ROLE);
        Assertions.assertNotNull(managerRole);
        Assertions.assertNotNull(viewerRole);
        Assertions.assertNotNull(operatorRole);

        adminUser = userManager.findUser("admin");
        managerUser = userManager.addUser("routeManager", "Route", "Manager", "routemanager@test.com", "TempPassword1_", "salt", false);
        viewerUser = userManager.addUser("routeViewer", "Route", "Viewer", "routeviewer@test.com", "TempPassword1_", "salt", false);
        operatorUser = userManager.addUser("routeOperator", "Route", "Operator", "routeoperator@test.com", "TempPassword1_", "salt", false);

        roleManager.addRole(managerUser.getId(), managerRole);
        roleManager.addRole(viewerUser.getId(), viewerRole);
        roleManager.addRole(operatorUser.getId(), operatorRole);

        TestRuntimeUtils.impersonateAdmin(componentRegistry);
    }

    @Test
    @Order(1)
    void componentsInstantiatedCorrectly() {
        Assertions.assertNotNull(routeApi);
        Assertions.assertNotNull(componentRegistry.findComponent(RouteSystemApi.class, null));
        Assertions.assertNotNull(routeRepository);
    }

    @Test
    @Order(2)
    void saveOk() {
        Route route = createRoute("route-1");
        route = routeApi.save(route);
        Assertions.assertEquals(1, route.getEntityVersion());
        Assertions.assertTrue(route.getId() > 0);
        Assertions.assertEquals("route-1", route.getRouteId());
        Assertions.assertEquals("/api/service1/**", route.getPathPattern());
    }

    @Test
    @Order(3)
    void updateShouldWork() {
        Query q = routeRepository.getQueryBuilderInstance().field("routeId").equalTo("route-1");
        Route route = routeApi.find(q);
        Assertions.assertNotNull(route);
        route.setTargetServiceName("updated-service");
        route = routeApi.update(route);
        Assertions.assertEquals("updated-service", route.getTargetServiceName());
        Assertions.assertEquals(2, route.getEntityVersion());
    }

    @Test
    @Order(4)
    void updateShouldFailWithWrongVersion() {
        Query q = routeRepository.getQueryBuilderInstance().field("routeId").equalTo("route-1");
        Route route = routeApi.find(q);
        route.setEntityVersion(1);
        Assertions.assertThrows(WaterRuntimeException.class, () -> routeApi.update(route));
    }

    @Test
    @Order(5)
    void findAllShouldWork() {
        PaginableResult<Route> all = routeApi.findAll(null, -1, -1, null);
        Assertions.assertFalse(all.getResults().isEmpty());
    }

    @Test
    @Order(6)
    void findAllPaginatedShouldWork() {
        for (int i = 2; i < 11; i++) {
            routeApi.save(createRoute("route-" + i));
        }
        PaginableResult<Route> paginated = routeApi.findAll(null, 7, 1, null);
        Assertions.assertEquals(7, paginated.getResults().size());
        Assertions.assertEquals(1, paginated.getCurrentPage());
        Assertions.assertEquals(2, paginated.getNextPage());
    }

    @Test
    @Order(7)
    void removeAllShouldWork() {
        PaginableResult<Route> all = routeApi.findAll(null, -1, -1, null);
        all.getResults().forEach(r -> routeApi.remove(r.getId()));
        Assertions.assertEquals(0, routeApi.countAll(null));
    }

    @Test
    @Order(8)
    void saveShouldFailOnDuplicatedEntity() {
        Route route = createRoute("dup-route");
        routeApi.save(route);
        Route dup = createRoute("dup-route");
        Assertions.assertThrows(DuplicateEntityException.class, () -> routeApi.save(dup));
    }

    @Test
    @Order(9)
    void saveShouldFailOnValidation() {
        Route route = new Route("<script>xss</script>", "/api/**", HttpMethod.GET, "service", 1, true);
        Assertions.assertThrows(ValidationException.class, () -> routeApi.save(route));
    }

    @Test
    @Order(10)
    void managerCanDoEverything() {
        TestRuntimeInitializer.getInstance().impersonate(managerUser, runtime);
        Route route = createRoute("mgr-route-100");
        Route saved = Assertions.assertDoesNotThrow(() -> routeApi.save(route));
        saved.setTargetServiceName("new-service");
        Assertions.assertDoesNotThrow(() -> routeApi.update(saved));
        Assertions.assertDoesNotThrow(() -> routeApi.find(saved.getId()));
        Assertions.assertDoesNotThrow(() -> routeApi.remove(saved.getId()));
    }

    @Test
    @Order(11)
    void viewerCannotSaveOrUpdateOrRemove() {
        TestRuntimeInitializer.getInstance().impersonate(viewerUser, runtime);
        Route route = createRoute("viewer-route-200");
        Assertions.assertThrows(UnauthorizedException.class, () -> routeApi.save(route));

        TestRuntimeUtils.impersonateAdmin(componentRegistry);
        Route systemRoute = routeSystemApi.save(createRoute("viewer-route-201"));

        TestRuntimeInitializer.getInstance().impersonate(viewerUser, runtime);
        systemRoute.setTargetServiceName("changed");
        long id = systemRoute.getId();
        Assertions.assertThrows(UnauthorizedException.class, () -> routeApi.update(systemRoute));
        Assertions.assertThrows(NoResultException.class, () -> routeApi.remove(id));
    }

    @Test
    @Order(12)
    void operatorCanUpdateButCannotSaveOrRemove() {
        TestRuntimeInitializer.getInstance().impersonate(operatorUser, runtime);
        Route route = createRoute("op-route-300");
        Assertions.assertThrows(UnauthorizedException.class, () -> routeApi.save(route));

        TestRuntimeUtils.impersonateAdmin(componentRegistry);
        Route systemRoute = routeSystemApi.save(createRoute("op-route-301"));

        TestRuntimeInitializer.getInstance().impersonate(operatorUser, runtime);
        Route found = routeRepository.find(systemRoute.getId());
        Assertions.assertNotNull(found);
        found.setTargetServiceName("op-updated");
        Assertions.assertDoesNotThrow(() -> routeRepository.update(found));

        long id = found.getId();
        Assertions.assertThrows(NoResultException.class, () -> routeApi.remove(id));
    }

    @Test
    @Order(13)
    void repositoryFindByRouteIdShouldWork() {
        TestRuntimeUtils.impersonateAdmin(componentRegistry);
        routeApi.save(createRoute("findme-route"));
        Route found = routeRepository.findByRouteId("findme-route");
        Assertions.assertNotNull(found);
        Assertions.assertEquals("findme-route", found.getRouteId());
    }

    @Test
    @Order(14)
    void repositoryFindByEnabledShouldWork() {
        TestRuntimeUtils.impersonateAdmin(componentRegistry);
        Route disabledRoute = createRoute("disabled-route");
        disabledRoute.setEnabled(false);
        routeApi.save(disabledRoute);

        var enabled = routeRepository.findByEnabled(true);
        var disabled = routeRepository.findByEnabled(false);
        Assertions.assertTrue(enabled.size() >= 1);
        Assertions.assertTrue(disabled.size() >= 1);
    }

    @Test
    @Order(15)
    void repositoryFindByTargetServiceNameShouldWork() {
        TestRuntimeUtils.impersonateAdmin(componentRegistry);
        Route r1 = createRoute("svc-route-1");
        r1.setTargetServiceName("my-backend-service");
        Route r2 = createRoute("svc-route-2");
        r2.setTargetServiceName("my-backend-service");
        routeApi.save(r1);
        routeApi.save(r2);

        var results = routeRepository.findByTargetServiceName("my-backend-service");
        Assertions.assertTrue(results.size() >= 2);
    }

    @Test
    @Order(16)
    void getActiveRoutesShouldWork() {
        TestRuntimeUtils.impersonateAdmin(componentRegistry);
        var active = routeApi.getActiveRoutes();
        Assertions.assertNotNull(active);
    }

    @Test
    @Order(17)
    void refreshRoutesShouldWork() {
        TestRuntimeUtils.impersonateAdmin(componentRegistry);
        Assertions.assertDoesNotThrow(() -> routeApi.refreshRoutes());
    }

    @Test
    @Order(18)
    void addDynamicRouteShouldPersistRoute() {
        TestRuntimeUtils.impersonateAdmin(componentRegistry);
        Route route = new Route("add-dynamic-route-1", "/add-dyn/**", HttpMethod.ANY, "dyn-svc", 50, true);
        Assertions.assertDoesNotThrow(() -> routeApi.addDynamicRoute(route));
        // Route should now be findable in DB via system API
        Route found = routeRepository.findByRouteId("add-dynamic-route-1");
        Assertions.assertNotNull(found);
        Assertions.assertEquals("add-dynamic-route-1", found.getRouteId());
    }

    @Test
    @Order(19)
    void removeDynamicRouteShouldDeleteExistingRoute() {
        TestRuntimeUtils.impersonateAdmin(componentRegistry);
        // "add-dynamic-route-1" was saved in test 18
        Assertions.assertDoesNotThrow(() -> routeApi.removeDynamicRoute("add-dynamic-route-1"));
        Route found = routeRepository.findByRouteId("add-dynamic-route-1");
        Assertions.assertNull(found);
    }

    @Test
    @Order(20)
    void removeDynamicRouteForNonExistingRouteIsIdempotent() {
        TestRuntimeUtils.impersonateAdmin(componentRegistry);
        Assertions.assertDoesNotThrow(() -> routeApi.removeDynamicRoute("nonexistent-route-id-xyz"));
    }

    @Test
    @Order(21)
    void routeWithNullMethodDefaultsToAnyOnCreate() {
        TestRuntimeUtils.impersonateAdmin(componentRegistry);
        // Constructor accepts null method; @PrePersist should set it to HttpMethod.ANY
        Route route = new Route("null-method-route", "/null-method/**", null, "svc-null", 1, true);
        Route saved = routeSystemApi.save(route);
        Assertions.assertNotNull(saved.getMethod());
        Assertions.assertEquals(HttpMethod.ANY, saved.getMethod());
        routeSystemApi.remove(saved.getId());
    }

    /**
     * #38 — update() must restore the persisted ownerUserId onto the incoming entity.
     *
     * <p>A client must never be able to give an OwnedResource away (or null the owner) by
     * supplying a tampered {@code ownerUserId} in an update payload. {@code BaseEntityServiceImpl}
     * reloads the persisted entity and copies its {@code ownerUserId} before delegating to the
     * system service, so the field must remain unchanged regardless of what the caller sets.
     *
     * <p>Route implements {@link it.water.core.api.entity.owned.OwnedResource} and is therefore
     * a valid fixture for this regression test. The admin security context is used so that all
     * permission checks pass and only the ownership-restore behavior is exercised.
     */
    @Test
    @Order(22)
    void update_tamperedOwnerUserId_ownerUserIdIsRestoredFromPersistedEntity() {
        TestRuntimeUtils.impersonateAdmin(componentRegistry);

        // Save a fresh route; save() sets ownerUserId from the security context (admin user id)
        Route saved = routeApi.save(createRoute("owner-restore-route-38"));
        Long originalOwnerUserId = saved.getOwnerUserId();

        // Build a DISTINCT update payload — as a deserialized REST client would send — carrying the
        // persisted id/version but a tampered owner. We must NOT mutate `saved` itself: the persisted
        // record is what update() reloads to restore the owner.
        final long TAMPERED_OWNER = 999_999L;
        Route payload = createRoute("owner-restore-route-38");
        payload.setId(saved.getId());
        payload.setEntityVersion(saved.getEntityVersion());
        payload.setOwnerUserId(TAMPERED_OWNER);

        // Call the Api (permission-checked) update — #38 must restore the original owner
        Route updated = routeApi.update(payload);

        Assertions.assertNotEquals(TAMPERED_OWNER, updated.getOwnerUserId(),
                "#38: update() must NOT persist the caller-supplied tampered ownerUserId");
        Assertions.assertEquals(originalOwnerUserId, updated.getOwnerUserId(),
                "#38: after update(), ownerUserId must match the value that was set by the original save()");

        // Confirm persisted state via system API (bypasses permission layer to read the actual DB row)
        Route persisted = routeSystemApi.find(updated.getId());
        Assertions.assertNotNull(persisted, "The updated route must still be findable in the DB");
        Assertions.assertEquals(originalOwnerUserId, persisted.getOwnerUserId(),
                "#38: the DB-persisted ownerUserId must be the original value, not the tampered one");

        // Clean up
        routeSystemApi.remove(persisted.getId());
    }

    /**
     * #38 — update() with ownerUserId explicitly set to 0 must also be rejected.
     *
     * <p>Setting ownerUserId to 0 (the sentinel for «no owner») is another way a client might
     * try to orphan a resource. The persisted value must be restored here too.
     */
    @Test
    @Order(23)
    void update_ownerUserIdSetToZero_ownerUserIdIsRestoredFromPersistedEntity() {
        TestRuntimeUtils.impersonateAdmin(componentRegistry);

        Route saved = routeApi.save(createRoute("owner-restore-zero-38"));
        Long originalOwnerUserId = saved.getOwnerUserId();

        // Distinct payload (do not mutate `saved`) that tries to orphan the resource by zeroing the owner.
        Route payload = createRoute("owner-restore-zero-38");
        payload.setId(saved.getId());
        payload.setEntityVersion(saved.getEntityVersion());
        payload.setOwnerUserId(0L);
        Route updated = routeApi.update(payload);

        Assertions.assertEquals(originalOwnerUserId, updated.getOwnerUserId(),
                "#38: setting ownerUserId to 0 in update payload must not persist — original owner must be restored");

        // Clean up
        routeSystemApi.remove(updated.getId());
    }

    private Route createRoute(String routeId) {
        return new Route(routeId, "/api/service1/**", HttpMethod.ANY, "backend-service", 100, true);
    }
}
