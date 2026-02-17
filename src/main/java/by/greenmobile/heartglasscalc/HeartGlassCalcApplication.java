package by.greenmobile.heartglasscalc;

import by.greenmobile.heartglasscalc.config.HoneycombProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableConfigurationProperties(value = {HoneycombProperties.class})
@SpringBootApplication
public class HeartGlassCalcApplication {

    public static void main(String[] args) {
        SpringApplication.run(HeartGlassCalcApplication.class, args);
    }

}
