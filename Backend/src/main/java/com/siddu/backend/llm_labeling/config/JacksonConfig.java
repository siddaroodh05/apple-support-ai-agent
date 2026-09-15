package com.siddu.backend.llm_labeling.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper classificationObjectMapper() {
        return new ObjectMapper();
    }
}