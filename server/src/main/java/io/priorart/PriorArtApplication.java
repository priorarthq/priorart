package io.priorart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PriorArtApplication {
    public static void main(String[] args) {
        SpringApplication.run(PriorArtApplication.class, args);
    }
}
