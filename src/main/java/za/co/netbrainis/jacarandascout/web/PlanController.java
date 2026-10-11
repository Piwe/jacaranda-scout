package za.co.netbrainis.jacarandascout.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.netbrainis.jacarandascout.ScoutProperties;
import za.co.netbrainis.jacarandascout.ai.BloomPredictor;
import za.co.netbrainis.jacarandascout.ai.OllamaClient;
import za.co.netbrainis.jacarandascout.plan.Plan;
import za.co.netbrainis.jacarandascout.plan.PlanRequest;
import za.co.netbrainis.jacarandascout.plan.PlanService;
import za.co.netbrainis.jacarandascout.plan.PlanStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PlanController {

    private final PlanService service;
    private final PlanStore store;
    private final OllamaClient ollama;
    private final BloomPredictor bloomModel;
    private final ScoutProperties props;

    public PlanController(PlanService service, PlanStore store, OllamaClient ollama, BloomPredictor bloomModel,
                          ScoutProperties props) {
        this.service = service;
        this.store = store;
        this.ollama = ollama;
        this.bloomModel = bloomModel;
        this.props = props;
    }

    @PostMapping("/plan")
    public Plan plan(@RequestBody PlanRequest request) {
        return service.plan(request);
    }

    @GetMapping("/plan/{id}")
    public ResponseEntity<Plan> get(@PathVariable String id) {
        return store.find(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/plan/{id}/gpx")
    public ResponseEntity<String> gpx(@PathVariable String id) {
        return store.find(id)
                .map(p -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"jacaranda-loop-" + id + ".gpx\"")
                        .contentType(MediaType.parseMediaType("application/gpx+xml"))
                        .body(service.gpx(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/sync")
    public Map<String, String> sync() {
        return service.sync();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        List<String> models = ollama.installedModels();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("ollamaReachable", !models.isEmpty());
        s.put("chatModel", props.ollama().model());
        s.put("chatModelInstalled", models.stream().anyMatch(m -> m.equals(props.ollama().model())
                || m.equals(props.ollama().model() + ":latest")));
        s.put("visionEnabled", props.vision().enabled());
        s.put("visionModel", props.vision().model());
        s.put("installedModels", models);
        s.put("bloomModelEnabled", bloomModel.enabled());
        s.put("bloomModel", bloomModel.health().orElse(null));
        s.put("offlineMode", props.offline());
        return s;
    }
}
