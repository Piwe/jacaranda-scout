package za.co.netbrainis.jacarandascout.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import za.co.netbrainis.jacarandascout.ScoutProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Keeps plans in memory and on disk so a GPX link still works after a restart. */
@Component
public class PlanStore {

    private static final Logger log = LoggerFactory.getLogger(PlanStore.class);
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9-]{4,40}");

    private final Map<String, Plan> plans = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Path dir;

    public PlanStore(ObjectMapper mapper, ScoutProperties props) {
        this.mapper = mapper;
        this.dir = Path.of(props.dataDir(), "plans");
    }

    public void save(Plan plan) {
        plans.put(plan.id(), plan);
        try {
            Files.createDirectories(dir);
            mapper.writeValue(dir.resolve(plan.id() + ".json").toFile(), plan);
        } catch (IOException e) {
            log.warn("Could not persist plan {}: {}", plan.id(), e.getMessage());
        }
    }

    public Optional<Plan> find(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            return Optional.empty();
        }
        Plan p = plans.get(id);
        if (p != null) {
            return Optional.of(p);
        }
        Path f = dir.resolve(id + ".json");
        if (Files.exists(f)) {
            try {
                p = mapper.readValue(f.toFile(), Plan.class);
                plans.put(id, p);
                return Optional.of(p);
            } catch (IOException e) {
                log.warn("Could not read plan {}: {}", id, e.getMessage());
            }
        }
        return Optional.empty();
    }
}
