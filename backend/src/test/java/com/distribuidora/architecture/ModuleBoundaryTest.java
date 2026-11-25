package com.distribuidora.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ModuleBoundaryTest {
    private static final JavaClasses PRODUCTION_CLASSES = productionClasses();
    private static final String BASE_PACKAGE = "com.distribuidora.";
    private static final String AUTH_CONTROLLER = BASE_PACKAGE + "identity.api.AuthController";
    private static final Set<String> PUBLIC_AUTH_ACTIONS = Set.of("login", "refresh", "logout", "activate");

    @Test
    void moduleLayersRespectInboundDependencyDirection() {
        ArchRule apiDoesNotDependOnInfrastructure = noClasses()
            .that().resideInAPackage("com.distribuidora..api..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.distribuidora..infrastructure..");
        ArchRule domainDoesNotDependOnDeliveryLayers = noClasses()
            .that().resideInAPackage("com.distribuidora..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.distribuidora..api..", "com.distribuidora..application..",
                "com.distribuidora..infrastructure..");
        ArchRule sharedDoesNotDependOnBusinessModules = noClasses()
            .that().resideInAPackage("com.distribuidora.shared..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "com.distribuidora.audit..", "com.distribuidora.catalog..", "com.distribuidora.customer..",
                "com.distribuidora.dashboard..", "com.distribuidora.demo..", "com.distribuidora.document..",
                "com.distribuidora.expense..", "com.distribuidora.identity..", "com.distribuidora.inventory..",
                "com.distribuidora.notification..", "com.distribuidora.order..", "com.distribuidora.payment..",
                "com.distribuidora.pricing..", "com.distribuidora.purchasing..", "com.distribuidora.sale..",
                "com.distribuidora.seller..", "com.distribuidora.settings..", "com.distribuidora.supplier..");
        ArchRule applicationDoesNotDependOnApi = noClasses()
            .that().resideInAPackage("com.distribuidora..application..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.distribuidora..api..");

        apiDoesNotDependOnInfrastructure.check(PRODUCTION_CLASSES);
        domainDoesNotDependOnDeliveryLayers.check(PRODUCTION_CLASSES);
        sharedDoesNotDependOnBusinessModules.check(PRODUCTION_CLASSES);
        applicationDoesNotDependOnApi.check(PRODUCTION_CLASSES);

        classes().that().resideInAPackage("com.distribuidora..").should(
            new ArchCondition<JavaClass>("depend only on their own module's infrastructure") {
                @Override
                public void check(JavaClass source, ConditionEvents events) {
                    String module = moduleOf(source.getPackageName());
                    for (var dependency : source.getDirectDependenciesFromSelf()) {
                        String target = dependency.getTargetClass().getPackageName();
                        if (target.startsWith(BASE_PACKAGE) && (target + ".").contains(".infrastructure.")
                            && !module.equals(moduleOf(target))) {
                            events.add(SimpleConditionEvent.violated(source, dependency.getDescription()));
                        }
                    }
                }
            }).check(PRODUCTION_CLASSES);
    }

    @Test
    void modulesAreFreeOfDependencyCycles() {
        slices().matching("com.distribuidora.(*)..")
            .should().beFreeOfCycles()
            .check(PRODUCTION_CLASSES);
    }

    @Test
    void apiControllersDeclareAuthorizationForProtectedEndpoints() {
        classes().that().areAnnotatedWith(RestController.class).should(
            new ArchCondition<JavaClass>("declare @PreAuthorize for protected endpoints") {
                @Override
                public void check(JavaClass controller, ConditionEvents events) {
                    if (controller.isAnnotatedWith(PreAuthorize.class)) return;
                    for (var method : controller.getMethods()) {
                        boolean isRoute = method.isAnnotatedWith(RequestMapping.class)
                            || method.isMetaAnnotatedWith(RequestMapping.class);
                        boolean publicAuth = controller.getName().equals(AUTH_CONTROLLER)
                            && PUBLIC_AUTH_ACTIONS.contains(method.getName());
                        if (isRoute && !publicAuth && !method.isAnnotatedWith(PreAuthorize.class)) {
                            events.add(SimpleConditionEvent.violated(method, method.getFullName()));
                        }
                    }
                }
            }).check(PRODUCTION_CLASSES);
    }

    private static String moduleOf(String packageName) {
        return packageName.startsWith(BASE_PACKAGE) ? packageName.substring(BASE_PACKAGE.length()).split("\\.")[0] : "";
    }

    private static JavaClasses productionClasses() {
        try {
            Path testClasses = Path.of(ModuleBoundaryTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            Path productionClasses = testClasses.getParent().resolve("classes");
            if (!Files.isDirectory(productionClasses)) {
                throw new IllegalStateException("Production classes directory not found: " + productionClasses);
            }
            return new ClassFileImporter().importPath(productionClasses);
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalStateException("Cannot locate production classes", exception);
        }
    }
}
