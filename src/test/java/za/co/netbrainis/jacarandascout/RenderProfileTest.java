package za.co.netbrainis.jacarandascout;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** render.yaml hands the planner private-network host:port values; the render profile turns them into URLs. */
@SpringBootTest(properties = {
        "OLLAMA_HOSTPORT=scout-ollama:11434",
        "BLOOM_MODEL_HOSTPORT=scout-bloom-model:8090",
        "scout.offline=true",
        "scout.data-dir=target/test-data"})
@ActiveProfiles("render")
class RenderProfileTest {

    @Autowired
    ScoutProperties props;

    @Test
    void privateServiceUrlsResolve() {
        assertEquals("http://scout-ollama:11434", props.ollama().baseUrl());
        assertEquals("http://scout-bloom-model:8090", props.bloomModel().baseUrl());
        assertEquals("qwen2.5:1.5b", props.ollama().model());
    }
}
