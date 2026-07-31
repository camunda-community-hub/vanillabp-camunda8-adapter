package io.vanillabp.camunda8.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Regression net for {@link DisableCamundaSpringAutoConfigurationImportFilter}. Written against
 * Spring Boot 3 to document the current behaviour before the Spring Boot 4 migration.
 *
 * <p>Two things have to keep working, and both fail silently if they break:
 * <ol>
 * <li>the filter has to be <em>registered</em> - it is declared in {@code META-INF/spring.factories}
 * under the key {@code org.springframework.boot.autoconfigure.AutoConfigurationImportFilter}. Spring
 * Boot 4 loads auto-configuration <em>candidates</em> from the {@code .imports} file instead of
 * {@code spring.factories}; whether import <em>filters</em> are still read from {@code spring.factories}
 * is the open question of the migration;</li>
 * <li>the filtered class name has to still exist in the Camunda client. If Camunda renames or moves
 * {@code AnnotationProcessorConfiguration}, the filter keeps returning "no match" for a class that is
 * no longer offered, and the real one passes through unfiltered.</li>
 * </ol>
 */
class DisableCamundaSpringAutoConfigurationImportFilterTest {

    private static final String FILTERED_CLASS =
            "io.camunda.client.spring.configuration.AnnotationProcessorConfiguration";

    private final DisableCamundaSpringAutoConfigurationImportFilter filter =
            new DisableCamundaSpringAutoConfigurationImportFilter();

    @Test
    void theCamundaAnnotationProcessorConfigurationIsFilteredOut() {

        final var matches = filter.match(new String[] { FILTERED_CLASS }, null);

        assertThat(matches).containsExactly(false);

    }

    @Test
    void otherAutoConfigurationsPassThrough() {

        final var matches = filter.match(
                new String[] { "com.example.SomeAutoConfiguration", FILTERED_CLASS, null },
                null);

        assertThat(matches).containsExactly(true, false, true);

    }

    @Test
    void theFilteredClassExistsOnTheClasspath() {

        // if this fails, Camunda renamed or moved the class and the filter silently stopped doing
        // anything - exactly the kind of breakage the starter split (camunda-spring-boot-3-starter vs
        // camunda-spring-boot-starter) can cause
        assertThat(getClass().getClassLoader().getResource(FILTERED_CLASS.replace('.', '/') + ".class"))
                .as("filtered Camunda class must exist, otherwise the filter is a no-op")
                .isNotNull();

    }

    @Test
    void theFilterIsRegisteredInSpringFactories() throws IOException {

        final var properties = new Properties();
        try (final var in = new ClassPathResource("META-INF/spring.factories").getInputStream()) {
            properties.load(in);
        }

        final var registered = properties.getProperty(
                "org.springframework.boot.autoconfigure.AutoConfigurationImportFilter");

        assertThat(registered)
                .as("the import filter must be registered, otherwise Camunda's own job-worker "
                        + "auto-configuration runs in parallel to the adapter")
                .isEqualTo(DisableCamundaSpringAutoConfigurationImportFilter.class.getName());

    }

    @Test
    void theAutoConfigurationImportsFileIsTheOnlyPlaceRegisteringTheAdapterConfiguration()
            throws IOException {

        final var imports = new String(
                new ClassPathResource(
                        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                        .getInputStream().readAllBytes());

        assertThat(imports.lines().filter(line -> !line.isBlank()).toList())
                .containsExactly("io.vanillabp.camunda8.Camunda8AdapterConfiguration");

        // spring.factories used to carry an EnableAutoConfiguration key as well, which Spring Boot has
        // ignored since 3.0 and which merely duplicated the line above. It was removed in T10, so the
        // file now has exactly one purpose: registering the import filter. Asserted so that nobody
        // reintroduces the dead key by copying an old example.
        final var properties = new Properties();
        try (final var in = new ClassPathResource("META-INF/spring.factories").getInputStream()) {
            properties.load(in);
        }
        assertThat(properties.stringPropertyNames())
                .containsExactly("org.springframework.boot.autoconfigure.AutoConfigurationImportFilter");

    }

    @Test
    void theListOfSkippedClassesIsExplicit() {

        // guards against accidentally widening the filter: only one Camunda class may be suppressed
        assertThat(filter.match(new String[] {
                "io.camunda.client.spring.configuration.CamundaAutoConfiguration",
                "io.camunda.client.spring.configuration.CamundaClientAllAutoConfiguration" }, null))
                .containsOnly(true);

        assertThat(List.of(FILTERED_CLASS)).hasSize(1);

    }

}
