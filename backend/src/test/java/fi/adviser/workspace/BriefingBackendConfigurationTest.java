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
            .withUserConfiguration(HuggingFaceBriefingModel.class, AzureBriefingModel.class, BedrockBriefingModel.class);

    @Test void exactlyOneConfiguredAdapterAndDisabledNeedsNoCloudCredentials() {
        runner.run(context -> assertThat(context).hasSingleBean(BriefingModel.class)
                .hasSingleBean(HuggingFaceBriefingModel.class));
        runner.withPropertyValues("app.ai.backend=azure").run(context -> assertThat(context)
                .hasSingleBean(BriefingModel.class).hasSingleBean(AzureBriefingModel.class)
                .doesNotHaveBean(HuggingFaceBriefingModel.class).doesNotHaveBean(BedrockBriefingModel.class));
        runner.withPropertyValues("app.ai.backend=bedrock").run(context -> assertThat(context)
                .hasSingleBean(BriefingModel.class).hasSingleBean(BedrockBriefingModel.class)
                .doesNotHaveBean(HuggingFaceBriefingModel.class).doesNotHaveBean(AzureBriefingModel.class));
    }

    @Test void unknownBackendNeverSilentlyDefaultsToAnotherProvider() {
        runner.withPropertyValues("app.ai.backend=typo").run(context -> assertThat(context)
                .doesNotHaveBean(BriefingModel.class));
    }

    @Test void enabledAzureAndBedrockDoNotRequireInactiveHuggingFaceSecrets() throws Exception {
        Path key = directory.resolve("azure-test-key");
        Files.writeString(key, "synthetic-key-not-a-real-credential");
        runner.withPropertyValues("app.ai.enabled=true", "app.ai.backend=azure",
                "app.ai.azure.endpoint=https://test.openai.azure.com",
                "app.ai.azure.deployment=test-deployment", "app.ai.azure.key-file=" + key,
                "app.ai.token-file=/nonexistent-hf-secret").run(context -> {
                    assertThat(context).hasSingleBean(AzureBriefingModel.class).hasSingleBean(BriefingModel.class);
                    assertThat(context.getBean(BriefingModel.class).enabled()).isTrue();
                });
        runner.withPropertyValues("app.ai.enabled=true", "app.ai.backend=bedrock",
                "app.ai.bedrock.region=eu-north-1", "app.ai.bedrock.model=test-model",
                "app.ai.token-file=/nonexistent-hf-secret").run(context -> {
                    assertThat(context).hasSingleBean(BedrockBriefingModel.class).hasSingleBean(BriefingModel.class);
                    assertThat(context.getBean(BriefingModel.class).enabled()).isTrue();
                });
    }
}
