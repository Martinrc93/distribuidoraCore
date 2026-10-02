package com.distribuidora;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DistribuidoraApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(DistribuidoraApplication.class, args);
        if (context.getEnvironment().getProperty("app.seed-only", Boolean.class, false)) {
            context.close();
        }
    }
}
