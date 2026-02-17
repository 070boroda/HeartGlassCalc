package by.greenmobile.heartglasscalc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="honeycomb")
public class HoneycombProperties {
    private double coeff = 0.35;
}
