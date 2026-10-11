package za.co.netbrainis.jacarandascout;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full offline run: no network, no model. Proves the "no signal on the trail" path works. */
@SpringBootTest(properties = {
        "scout.offline=true",
        "scout.ollama.enabled=false",
        "scout.bloom-model.enabled=false",
        "scout.data-dir=target/test-data"})
@AutoConfigureMockMvc
class PlanApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void plansOfflineWithDemoDataAndTemplates() throws Exception {
        mvc.perform(post("/api/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"5 km run\",\"lat\":-26.17,\"lon\":28.03}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activity").value("run"))
                .andExpect(jsonPath("$.sources.data").value("demo-synthetic"))
                .andExpect(jsonPath("$.sources.routing").value("straight-line-estimate"))
                .andExpect(jsonPath("$.stops[0].cue").isNotEmpty());
    }
}
