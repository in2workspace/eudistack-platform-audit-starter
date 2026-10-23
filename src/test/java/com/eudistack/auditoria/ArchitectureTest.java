package com.eudistack.auditoria;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Hexagonal layering inside the starter jar (DT-002 §3.2): the domain is plain Java, and the inner layers never
 * reach the auto-configuration.
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.eudistack.auditoria");
    }

    @Test
    void domain_dependsOnlyOnTheJdk() {
        classes().that().resideInAPackage("..auditoria.domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..auditoria.domain..")
                .check(classes);
    }

    @Test
    void innerLayers_doNotDependOnTheAutoConfiguration() {
        noClasses().that().resideInAnyPackage("..auditoria.domain..", "..auditoria.application..", "..auditoria.port..")
                .should().dependOnClassesThat().resideInAPackage("..auditoria.autoconfigure..")
                .check(classes);
    }

    @Test
    void domainAndPorts_doNotDependOnTheApplication() {
        noClasses().that().resideInAnyPackage("..auditoria.domain..", "..auditoria.port..")
                .should().dependOnClassesThat().resideInAPackage("..auditoria.application..")
                .check(classes);
    }
}
