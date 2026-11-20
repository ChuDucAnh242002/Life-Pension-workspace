package fi.adviser.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class BriefingBackendConfigurationTest {
    @TempDir Path directory;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(BriefingProtocol.class, () -> new BriefingProtocol(new ObjectMapper()))
            .withUserConfiguration(DisabledBriefingModel.class, AzureBriefingModel.class, BedrockBriefingModel.class);

    @Test void exactlyOneConfiguredAdapterAndDisabledNeedsNoCloudCredentials() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(BriefingModel.class);
            assertThat(context.getBean(BriefingModel.class).enabled()).isFalse();
        });
        runner.withPropertyValues("app.ai.backend=azure").run(context -> assertThat(context)
                .hasSingleBean(BriefingModel.class).hasSingleBean(AzureBriefingModel.class));
        runner.withPropertyValues("app.ai.backend=bedrock").run(context -> assertThat(context)
                .hasSingleBean(BriefingModel.class).hasSingleBean(BedrockBriefingModel.class));
    }

    @Test void enablingAiRequiresExplicitCloudBackend() {
        runner.withPropertyValues("app.ai.enabled=true", "app.ai.backend=none")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseMessage("AI_ENABLED requires AI_BACKEND=azure or AI_BACKEND=bedrock."));
    }

    @Test void unknownBackendNeverSilentlyDefaultsToAnotherProvider() {
        runner.withPropertyValues("app.ai.backend=typo").run(context -> assertThat(context)
                .doesNotHaveBean(BriefingModel.class));
    }

    @Test void enabledAzureAndBedrockOnlyRequireTheirOwnConfiguration() throws Exception {
        Path key = directory.resolve("azure-test-key");
        Files.writeString(key, "synthetic-key-not-a-real-credential");
        runner.withPropertyValues("app.ai.enabled=true", "app.ai.backend=azure",
                "app.ai.azure.endpoint=https://test.openai.azure.com",
                "app.ai.azure.deployment=test-deployment", "app.ai.azure.key-file=" + key).run(context -> {
                    assertThat(context).hasSingleBean(AzureBriefingModel.class).hasSingleBean(BriefingModel.class);
                    assertThat(context.getBean(BriefingModel.class).enabled()).isTrue();
                });
        runner.withPropertyValues("app.ai.enabled=true", "app.ai.backend=bedrock",
                "app.ai.bedrock.region=eu-north-1", "app.ai.bedrock.model=test-model").run(context -> {
                    assertThat(context).hasSingleBean(BedrockBriefingModel.class).hasSingleBean(BriefingModel.class);
                    assertThat(context.getBean(BriefingModel.class).enabled()).isTrue();
                });
    }
}
