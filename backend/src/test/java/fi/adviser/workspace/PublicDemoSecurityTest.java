package fi.adviser.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:publicdemo;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=test",
        "app.adviser-user=adviser", "app.adviser-password=test-adviser-password",
        "app.public-demo=true"
})
@AutoConfigureMockMvc
class PublicDemoSecurityTest {
    @Autowired MockMvc mvc;

    @Test void publicSessionAndWritesHaveAnActorAndRequireCsrf() throws Exception {
        mvc.perform(get("/api/session")).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("public-demo"))
                .andExpect(jsonPath("$.publicDemo").value(true));
        mvc.perform(get("/api/cases")).andExpect(status().isOk());
        String profile = "{\"customerAlias\":\"Synthetic public test\",\"age\":38,\"employment\":\"EMPLOYED\"}";
        mvc.perform(post("/api/cases").contentType("application/json").content(profile))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/cases").with(csrf()).contentType("application/json").content(profile))
                .andExpect(status().isCreated());
    }

    @Test void publicAccessCannotEnableExternalIntegrations() {
        var config = new SecurityConfiguration();
        assertThrows(IllegalStateException.class, () -> config.security(null, true, "mq", false));
        assertThrows(IllegalStateException.class, () -> config.security(null, true, "demo", true));
    }
}
