
package io.infranexum.server.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "infranexum.entitlements.enabled=false",
    "infranexum.persistence.mode=MEMORY"
})
@AutoConfigureMockMvc
class SystemControllerTest {
    @Autowired private MockMvc mockMvc;

    @Test
    void contextStarts() {
        assertThat(mockMvc).isNotNull();
    }

    @Test
    void exposesNonSensitiveBuildIdentity() throws Exception {
        mockMvc.perform(get("/api/v1/system/build"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product").value("InfraNexum"))
                .andExpect(jsonPath("$.version").value("2.0.0-alpha.0.39"))
                .andExpect(jsonPath("$.component").value("SERVER"))
                .andExpect(jsonPath("$.mode").value("STANDALONE"));
    }
}
