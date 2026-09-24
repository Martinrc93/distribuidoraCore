package com.distribuidora.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ModuleBoundaryTest {

    @Test
    void moduleLayersRespectInboundDependencyDirection() {
        JavaClasses classes = productionClasses();

        ArchRule apiDoesNotDependOnInfrastructure = noClasses()
            .that().resideInAPackage("com.distribuidora..api..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.distribuidora..infrastructure..");
        ArchRule domainDoesNotDependOnDeliveryLayers = noClasses()
            .that().resideInAPackage("com.distribuidora..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.distribuidora..api..", "com.distribuidora..application..",
                "com.distribuidora..infrastructure..");
        ArchRule sharedDoesNotDependOnCatalog = noClasses()
            .that().resideInAPackage("com.distribuidora.shared..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.distribuidora.catalog..");
        ArchRule applicationDoesNotDependOnApi = noClasses()
            .that().resideInAPackage("com.distribuidora..application..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.distribuidora..api..");

        apiDoesNotDependOnInfrastructure.check(classes);
        domainDoesNotDependOnDeliveryLayers.check(classes);
        sharedDoesNotDependOnCatalog.check(classes);
        applicationDoesNotDependOnApi.check(classes);
    }

    @Test
    void modulesAreFreeOfDependencyCycles() {
        JavaClasses classes = productionClasses();

        slices().matching("com.distribuidora.(*)..")
            .should().beFreeOfCycles()
            .check(classes);
    }

    private JavaClasses productionClasses() {
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
