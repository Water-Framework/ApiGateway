package it.water.infrastructure.apigateway;

import it.water.core.api.bundle.Runtime;
import it.water.core.api.model.Role;
import it.water.core.api.registry.ComponentRegistry;
import it.water.core.api.role.RoleManager;
import it.water.core.api.service.Service;
import it.water.core.api.user.UserManager;
import it.water.core.interceptors.annotations.Inject;
import it.water.core.permission.exceptions.UnauthorizedException;
import it.water.core.testing.utils.bundle.TestRuntimeInitializer;
import it.water.core.testing.utils.junit.WaterTestExtension;
import it.water.core.testing.utils.runtime.TestRuntimeUtils;
import it.water.infrastructure.apigateway.api.GatewayApi;
import it.water.infrastructure.apigateway.model.Route;
import it.water.infrastructure.apigateway.model.ServiceStats;
import lombok.Setter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

/**
 * Service-layer permission tests for GatewayApi (fix #32).
 * <p>
 * {@code GatewayServiceImpl} is a {@code @FrameworkComponent} that wraps {@code GatewaySystemApi} behind
 * {@code @AllowGenericPermissions} checks. These tests verify that:
 * <ul>
 *   <li>{@code getServiceStatistics()} requires the {@code view-metrics} action on the Route resource.</li>
 *   <li>{@code syncWithServiceDiscovery()} requires the {@code refresh-routes} action on the Route resource.</li>
 *   <li>Admin, gatewayManager, gatewayViewer, and gatewayOperator bypass / hold those permissions.</li>
 *   <li>A plain authenticated user with no gateway role is rejected with {@code UnauthorizedException}.</li>
 * </ul>
 * REST-boundary assertions (shape/status of HTTP responses) remain in
 * {@code gateway-management.feature} exercised by {@code ApiGatewayRestApiTest}.
 */
@ExtendWith(WaterTestExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayManagementApiTest implements Service {

    @Inject
    @Setter
    private ComponentRegistry componentRegistry;

    @Inject
    @Setter
    private GatewayApi gatewayApi;

    @Inject
    @Setter
    private Runtime runtime;

    @Inject
    @Setter
    private UserManager userManager;

    @Inject
    @Setter
    private RoleManager roleManager;

    private it.water.core.api.model.User managerUser;
    private it.water.core.api.model.User viewerUser;
    private it.water.core.api.model.User operatorUser;
    /** Plain user — no gateway role, should be rejected. */
    private it.water.core.api.model.User plainUser;

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

        managerUser = userManager.addUser("gwMgmtManager", "GW", "Manager", "gwmgmt.manager@test.com", "TempPassword1_", "salt", false);
        viewerUser = userManager.addUser("gwMgmtViewer", "GW", "Viewer", "gwmgmt.viewer@test.com", "TempPassword1_", "salt", false);
        operatorUser = userManager.addUser("gwMgmtOperator", "GW", "Operator", "gwmgmt.operator@test.com", "TempPassword1_", "salt", false);
        plainUser = userManager.addUser("gwMgmtPlain", "GW", "Plain", "gwmgmt.plain@test.com", "TempPassword1_", "salt", false);

        roleManager.addRole(managerUser.getId(), managerRole);
        roleManager.addRole(viewerUser.getId(), viewerRole);
        roleManager.addRole(operatorUser.getId(), operatorRole);
        // plainUser intentionally gets no gateway role

        TestRuntimeUtils.impersonateAdmin(componentRegistry);
    }

    // ------------------------------------------------------------------
    // getServiceStatistics — VIEW_METRICS action on Route
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void gatewayApiComponentInstantiatedCorrectly() {
        Assertions.assertNotNull(gatewayApi);
    }

    /**
     * #32: admin can always call getServiceStatistics.
     */
    @Test
    @Order(2)
    void getServiceStatistics_asAdmin_succeeds() {
        TestRuntimeUtils.impersonateAdmin(componentRegistry);
        Map<String, ServiceStats> stats = Assertions.assertDoesNotThrow(() -> gatewayApi.getServiceStatistics());
        Assertions.assertNotNull(stats);
    }

    /**
     * #32: gatewayManager role holds VIEW_METRICS — must succeed.
     */
    @Test
    @Order(3)
    void getServiceStatistics_asGatewayManager_succeeds() {
        TestRuntimeInitializer.getInstance().impersonate(managerUser, runtime);
        Map<String, ServiceStats> stats = Assertions.assertDoesNotThrow(() -> gatewayApi.getServiceStatistics());
        Assertions.assertNotNull(stats);
    }

    /**
     * #32: gatewayViewer role holds VIEW_METRICS — must succeed.
     */
    @Test
    @Order(4)
    void getServiceStatistics_asGatewayViewer_succeeds() {
        TestRuntimeInitializer.getInstance().impersonate(viewerUser, runtime);
        Map<String, ServiceStats> stats = Assertions.assertDoesNotThrow(() -> gatewayApi.getServiceStatistics());
        Assertions.assertNotNull(stats);
    }

    /**
     * #32: gatewayOperator role holds VIEW_METRICS — must succeed.
     */
    @Test
    @Order(5)
    void getServiceStatistics_asGatewayOperator_succeeds() {
        TestRuntimeInitializer.getInstance().impersonate(operatorUser, runtime);
        Map<String, ServiceStats> stats = Assertions.assertDoesNotThrow(() -> gatewayApi.getServiceStatistics());
        Assertions.assertNotNull(stats);
    }

    /**
     * #32: a user with NO gateway role must be rejected with UnauthorizedException when calling
     * getServiceStatistics — this is the primary regression test for the #32 fix.
     */
    @Test
    @Order(6)
    void getServiceStatistics_asPlainUser_throwsUnauthorized() {
        TestRuntimeInitializer.getInstance().impersonate(plainUser, runtime);
        Assertions.assertThrows(UnauthorizedException.class,
                () -> gatewayApi.getServiceStatistics());
    }

    // ------------------------------------------------------------------
    // syncWithServiceDiscovery — REFRESH_ROUTES action on Route
    // ------------------------------------------------------------------

    /**
     * #32: admin can call syncWithServiceDiscovery (note: in the test environment there is no
     * real ServiceDiscovery, so the call throws IllegalStateException from the system layer —
     * but it must NOT throw UnauthorizedException, which would indicate an auth rejection).
     */
    @Test
    @Order(7)
    void syncWithServiceDiscovery_asAdmin_authorizationPasses() {
        TestRuntimeUtils.impersonateAdmin(componentRegistry);
        // No ServiceDiscovery in test env → system throws IllegalStateException (not Unauthorized)
        Exception thrown = Assertions.assertThrows(Exception.class,
                () -> gatewayApi.syncWithServiceDiscovery());
        Assertions.assertFalse(thrown instanceof UnauthorizedException,
                "Admin must not be rejected by authorization; got: " + thrown.getClass().getSimpleName());
    }

    /**
     * #32: gatewayManager role holds REFRESH_ROUTES — auth must pass (downstream may throw
     * IllegalStateException due to missing ServiceDiscovery in test env, but NOT UnauthorizedException).
     */
    @Test
    @Order(8)
    void syncWithServiceDiscovery_asGatewayManager_authorizationPasses() {
        TestRuntimeInitializer.getInstance().impersonate(managerUser, runtime);
        Exception thrown = Assertions.assertThrows(Exception.class,
                () -> gatewayApi.syncWithServiceDiscovery());
        Assertions.assertFalse(thrown instanceof UnauthorizedException,
                "gatewayManager must not be rejected by authorization; got: " + thrown.getClass().getSimpleName());
    }

    /**
     * #32: gatewayOperator role holds REFRESH_ROUTES — auth must pass.
     */
    @Test
    @Order(9)
    void syncWithServiceDiscovery_asGatewayOperator_authorizationPasses() {
        TestRuntimeInitializer.getInstance().impersonate(operatorUser, runtime);
        Exception thrown = Assertions.assertThrows(Exception.class,
                () -> gatewayApi.syncWithServiceDiscovery());
        Assertions.assertFalse(thrown instanceof UnauthorizedException,
                "gatewayOperator must not be rejected by authorization; got: " + thrown.getClass().getSimpleName());
    }

    /**
     * #32: gatewayViewer does NOT hold REFRESH_ROUTES — must be rejected with UnauthorizedException.
     * This is the key regression: before fix #32 the management controller injected GatewaySystemApi
     * directly, so any authenticated user could trigger a sync. Now viewers are correctly denied.
     */
    @Test
    @Order(10)
    void syncWithServiceDiscovery_asGatewayViewer_throwsUnauthorized() {
        TestRuntimeInitializer.getInstance().impersonate(viewerUser, runtime);
        Assertions.assertThrows(UnauthorizedException.class,
                () -> gatewayApi.syncWithServiceDiscovery());
    }

    /**
     * #32: a plain user with no gateway role must be rejected with UnauthorizedException
     * when calling syncWithServiceDiscovery.
     */
    @Test
    @Order(11)
    void syncWithServiceDiscovery_asPlainUser_throwsUnauthorized() {
        TestRuntimeInitializer.getInstance().impersonate(plainUser, runtime);
        Assertions.assertThrows(UnauthorizedException.class,
                () -> gatewayApi.syncWithServiceDiscovery());
    }
}
