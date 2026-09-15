package com.siddu.backend.retrieval.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class SupportCaseIngestionWorker {

    @Async
    public void processInBackground(
            SupportCaseCsvIngestionService ingestionService,
            AtomicBoolean running) {
        try {
            ingestionService.ingest();
        } finally {
            running.set(false);
        }
    }
}