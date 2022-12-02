package io.quarkiverse.openapi.generator.deployment.codegen;

import static io.quarkiverse.openapi.generator.deployment.CodegenConfig.EXCLUDE_FILES;
import static io.quarkiverse.openapi.generator.deployment.CodegenConfig.INCLUDE_FILES;
import static io.quarkiverse.openapi.generator.deployment.CodegenConfig.INPUT_BASE_DIR;
import static io.quarkiverse.openapi.generator.deployment.CodegenConfig.VALIDATE_SPEC_PROPERTY_NAME;
import static io.quarkiverse.openapi.generator.deployment.CodegenConfig.VERBOSE_PROPERTY_NAME;
import static io.quarkiverse.openapi.generator.deployment.CodegenConfig.getAdditionalModelTypeAnnotationsPropertyName;
import static io.quarkiverse.openapi.generator.deployment.CodegenConfig.getBasePackagePropertyName;
import static io.quarkiverse.openapi.generator.deployment.CodegenConfig.getCustomRegisterProvidersFormat;
import static io.quarkiverse.openapi.generator.deployment.CodegenConfig.getImportMappingsPropertyName;
import static io.quarkiverse.openapi.generator.deployment.CodegenConfig.getSanitizedFileName;
import static io.quarkiverse.openapi.generator.deployment.CodegenConfig.getSkipFormModelPropertyName;
import static io.quarkiverse.openapi.generator.deployment.CodegenConfig.getTypeMappingsPropertyName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.Config;
import org.openapitools.codegen.config.GlobalSettings;

import io.quarkiverse.openapi.generator.OpenApiGeneratorException;
import io.quarkiverse.openapi.generator.deployment.CodegenConfig;
import io.quarkiverse.openapi.generator.deployment.circuitbreaker.CircuitBreakerConfigurationParser;
import io.quarkiverse.openapi.generator.deployment.wrapper.OpenApiClientGeneratorWrapper;
import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.deployment.CodeGenContext;
import io.quarkus.deployment.CodeGenProvider;
import io.smallrye.config.SmallRyeConfig;

/**
 * Code generation for OpenApi Client. Generates Java classes from OpenApi spec files located in src/main/openapi or
 * src/test/openapi
 * <p>
 * Wraps the <a href="https://openapi-generator.tech/docs/generators/java">OpenAPI Generator Client for Java</a>
 */
public abstract class OpenApiGeneratorCodeGenBase implements CodeGenProvider {

    static final String YAML = ".yaml";
    static final String YML = ".yml";
    static final String JSON = ".json";

    private static final String DEFAULT_PACKAGE = "org.openapi.quarkus";

    /**
     * The input base directory from
     *
     * <pre>
     * src/main
     *
     * <pre>
     * directory.
     * Ignored if INPUT_BASE_DIR is specified.
     **/
    @Override
    public String inputDirectory() {
        return "openapi";
    }

    @Override
    public boolean shouldRun(Path sourceDir, Config config) {
        String inputBaseDir = getInputBaseDirRelativeToModule(sourceDir, config);
        if (inputBaseDir != null && !Files.isDirectory(Path.of(inputBaseDir))) {
            throw new OpenApiGeneratorException(String.format("Invalid path on %s: %s", INPUT_BASE_DIR, inputBaseDir));
        }
        return inputBaseDir != null || Files.isDirectory(sourceDir);
    }

    @Override
    public boolean trigger(CodeGenContext context) throws CodeGenException {
        final Path outDir = context.outDir();
        String inputBaseDir = getInputBaseDirRelativeToModule(context.inputDir(), context.config());
        final Path openApiDir = inputBaseDir != null ? Path.of(inputBaseDir) : context.inputDir();
        final List<String> filesToInclude = context.config().getOptionalValues(INCLUDE_FILES, String.class).orElse(List.of());
        final List<String> filesToExclude = context.config().getOptionalValues(EXCLUDE_FILES, String.class).orElse(List.of());

        if (Files.isDirectory(openApiDir)) {
            try (Stream<Path> openApiFilesPaths = Files.walk(openApiDir)) {
                openApiFilesPaths
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String fileName = path.getFileName().toString();
                            return fileName.endsWith(inputExtension())
                                    && !filesToExclude.contains(fileName)
                                    && (filesToInclude.isEmpty() || filesToInclude.contains(fileName));
                        })
                        .forEach(openApiFilePath -> generate(context.config(), openApiFilePath, outDir));
            } catch (IOException e) {
                throw new CodeGenException("Failed to generate java files from OpenApi files in " + openApiDir.toAbsolutePath(),
                        e);
            }
            return true;
        }
        return false;
    }

    // TODO: do not generate if the output dir has generated files and the openapi file has the same checksum of the previous run
    protected void generate(final Config config, final Path openApiFilePath, final Path outDir) {
        final String basePackage = getBasePackage(config, openApiFilePath);
        final Boolean verbose = config.getOptionalValue(VERBOSE_PROPERTY_NAME, Boolean.class).orElse(false);
        final Boolean validateSpec = config.getOptionalValue(VALIDATE_SPEC_PROPERTY_NAME, Boolean.class).orElse(true);
        GlobalSettings.setProperty(OpenApiClientGeneratorWrapper.DEFAULT_SECURITY_SCHEME,
                config.getOptionalValue(CodegenConfig.DEFAULT_SECURITY_SCHEME, String.class).orElse(""));

        final OpenApiClientGeneratorWrapper generator = new OpenApiClientGeneratorWrapper(
                openApiFilePath.normalize(),
                outDir,
                verbose,
                validateSpec)
                .withClassesCodeGenConfig(ClassCodegenConfigParser.parse(config, basePackage))
                .withCircuitBreakerConfig(CircuitBreakerConfigurationParser.parse(
                        config));

        config.getOptionalValue(getSkipFormModelPropertyName(openApiFilePath), String.class)
                .ifPresent(generator::withSkipFormModelConfig);

        config.getOptionalValue(getAdditionalModelTypeAnnotationsPropertyName(openApiFilePath), String.class)
                .ifPresent(generator::withAdditionalModelTypeAnnotationsConfig);

        config.getOptionalValue(getCustomRegisterProvidersFormat(openApiFilePath), String.class)
                .ifPresent(generator::withCustomRegisterProviders);

        SmallRyeConfig smallRyeConfig = config.unwrap(SmallRyeConfig.class);
        smallRyeConfig.getOptionalValues(getTypeMappingsPropertyName(openApiFilePath), String.class, String.class)
                .ifPresent(generator::withTypeMappings);

        smallRyeConfig.getOptionalValues(getImportMappingsPropertyName(openApiFilePath), String.class, String.class)
                .ifPresent(generator::withImportMappings);

        generator.generate(basePackage);
    }

    private String getBasePackage(final Config config, final Path openApiFilePath) {
        return config
                .getOptionalValue(getBasePackagePropertyName(openApiFilePath), String.class)
                .orElse(String.format("%s.%s", DEFAULT_PACKAGE, getSanitizedFileName(openApiFilePath)));
    }

    private String getInputBaseDirRelativeToModule(final Path sourceDir, final Config config) {
        String baseModuleDirectory = sourceDir.toString().substring(0, sourceDir.toString().lastIndexOf("src"));
        return config.getOptionalValue(INPUT_BASE_DIR, String.class).map(s -> baseModuleDirectory + s).orElse(null);
    }
}
