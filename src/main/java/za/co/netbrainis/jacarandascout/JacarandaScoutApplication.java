package za.co.netbrainis.jacarandascout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JacarandaScoutApplication {

    public static void main(String[] args) {
        SpringApplication.run(JacarandaScoutApplication.class, args);
    }
}
