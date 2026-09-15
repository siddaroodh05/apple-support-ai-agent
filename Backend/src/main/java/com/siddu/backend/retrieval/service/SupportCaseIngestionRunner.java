package com.siddu.backend.retrieval.service;

import com.siddu.backend.retrieval.config.IngestionProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class SupportCaseIngestionRunner implements CommandLineRunner {

    private final IngestionProperties properties;
    private final SupportCaseIngestionService ingestionService;

    public SupportCaseIngestionRunner(
            IngestionProperties properties,
            SupportCaseIngestionService ingestionService) {
        this.properties = properties;
        this.ingestionService = ingestionService;
    }

    @Override
    public void run(String... args) {
        if (properties.enabled()) {
            ingestionService.start();
        }
    }
}