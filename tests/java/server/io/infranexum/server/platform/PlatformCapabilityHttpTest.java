package io.infranexum.server.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "infranexum.entitlements.enabled=false",
    "infranexum.persistence.mode=MEMORY"
})
@AutoConfigureMockMvc
class PlatformCapabilityHttpTest {
    @Autowired private MockMvc mockMvc;

    @Test
    void exposesAuthoritativeCapabilityAndQuotaViews() throws Exception {
        mockMvc.perform(get("/api/v1/platform/capabilities"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.catalogVersion").value("2.0.0-draft.21"))
                .andExpect(jsonPath("$.profileVersion").value(1))
                .andExpect(jsonPath("$.capabilities.length()").value(21));
        mockMvc.perform(get("/api/v1/platform/capabilities/iam.local-auth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.profile").value("LITE"));
        mockMvc.perform(get("/api/v1/platform/quotas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotas['iam.users.max']").value(5));
    }
}
