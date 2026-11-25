package com.distribuidora.config;

import com.distribuidora.shared.time.BusinessTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfig {
    @Bean
    public Clock businessClock() {
        return Clock.system(BusinessTime.ZONE);
    }
}
