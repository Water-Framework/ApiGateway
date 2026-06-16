package it.water.infrastructure.apigateway;

import it.water.core.interceptors.annotations.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Architecture test — #17 regression guard.
 * <p>
 * <b>Rule:</b> No {@code *RestControllerImpl} class may have an {@code @Inject}-annotated field
 * whose declared type's simple name ends with {@code SystemApi}.
 * <p>
 * The Water security model has a strict layering contract: {@code *SystemApi} implementations are
 * TRUSTED, permission-bypassing services. Injecting one directly into a REST controller silently
 * removes all authorization for external callers. REST controllers must only inject the
 * permission-checked {@code *Api} interfaces. This is the defect class addressed by #32:
 * before the fix, {@code GatewayManagementRestControllerImpl} injected {@code GatewaySystemApi}
 * instead of {@code GatewayApi}, bypassing VIEW_METRICS / REFRESH_ROUTES permission enforcement.
 * <p>
 * <b>Scope:</b> scans all packages below {@code it.water} on the test classpath, covering every
 * Water Framework module present in the build.
 */
class RestControllerArchitectureTest {

    private static final String WATER_ROOT_PACKAGE = "it.water";
    private static final String CONTROLLER_SUFFIX = "RestControllerImpl";
    private static final String SYSTEM_API_SUFFIX = "SystemApi";

    @Test
    void noRestControllerImplShouldInjectSystemApi() {
        // Reflections 0.10.2: forPackages() + SubTypes scanner
        Reflections reflections = new Reflections(
                new ConfigurationBuilder()
                        .forPackages(WATER_ROOT_PACKAGE)
                        .addScanners(Scanners.SubTypes.filterResultsBy(s -> true))
        );

        // getAll(SubTypes) returns all discovered class names (String) in 0.10.x
        Set<String> allClassNames = reflections.getAll(Scanners.SubTypes);

        List<Class<?>> controllers = new ArrayList<>();
        for (String className : allClassNames) {
            // Simple name is the last segment after the last '.'
            int dot = className.lastIndexOf('.');
            String simpleName = dot >= 0 ? className.substring(dot + 1) : className;
            if (!simpleName.endsWith(CONTROLLER_SUFFIX)) {
                continue;
            }
            try {
                Class<?> clazz = Class.forName(className);
                controllers.add(clazz);
            } catch (ClassNotFoundException | NoClassDefFoundError | ExceptionInInitializerError ignored) {
                // class not loadable from this classloader — skip gracefully
            }
        }

        List<String> violations = new ArrayList<>();

        for (Class<?> clazz : controllers) {
            Class<?> current = clazz;
            while (current != null && !current.equals(Object.class)) {
                for (Field field : current.getDeclaredFields()) {
                    if (!field.isAnnotationPresent(Inject.class)) {
                        continue;
                    }
                    if (isSystemApiType(field.getType())) {
                        violations.add(
                                clazz.getName()
                                        + " injects "
                                        + field.getType().getSimpleName()
                                        + " (field: " + field.getName() + ")"
                                        + " — REST controllers must inject *Api, never *SystemApi"
                        );
                    }
                }
                current = current.getSuperclass();
            }
        }

        Assertions.assertTrue(
                violations.isEmpty(),
                "Architecture violation — *RestControllerImpl class(es) found injecting *SystemApi:\n  "
                        + String.join("\n  ", violations)
        );
    }

    /**
     * Returns true when the given type is or wraps a SystemApi by name convention.
     * Checks the type itself and its directly implemented interfaces (the field is often
     * declared as the interface type, e.g. {@code GatewaySystemApi}).
     */
    private boolean isSystemApiType(Class<?> type) {
        if (type == null) {
            return false;
        }
        if (type.getSimpleName().endsWith(SYSTEM_API_SUFFIX)) {
            return true;
        }
        for (Class<?> iface : type.getInterfaces()) {
            if (iface.getSimpleName().endsWith(SYSTEM_API_SUFFIX)) {
                return true;
            }
        }
        return false;
    }
}
