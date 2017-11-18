package ua.net.tokar.json.filters.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import ua.net.tokar.json.rainbowrest.RainbowRestBatchFilter;
import ua.net.tokar.json.rainbowrest.RainbowRestWebFilter;

@SpringBootApplication
public class Application {
    public static void main( String[] args ) {
        ApplicationContext ctx = SpringApplication.run( Application.class, args );

    }

    @Bean
    public RainbowRestWebFilter getJsonFilter() {
        return new RainbowRestWebFilter();
    }

    @Bean
    public RainbowRestBatchFilter getBatchFilter() {
        return new RainbowRestBatchFilter();
    }
}
