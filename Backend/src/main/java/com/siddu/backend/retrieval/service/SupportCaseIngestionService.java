package com.siddu.backend.retrieval.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SupportCaseIngestionService {

    private final SupportCaseCsvIngestionService csvIngestionService;
    private final SupportCaseIngestionWorker worker;
    private final AtomicBoolean running = new AtomicBoolean();

    public SupportCaseIngestionService(
            SupportCaseCsvIngestionService csvIngestionService,
            SupportCaseIngestionWorker worker) {
        this.csvIngestionService = csvIngestionService;
        this.worker = worker;
    }

    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        worker.processInBackground(csvIngestionService, running);
        return true;
    }

    public boolean isRunning() {
        return running.get();
    }
}