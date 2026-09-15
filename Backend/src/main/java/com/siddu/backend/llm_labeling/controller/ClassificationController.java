package com.siddu.backend.llm_labeling.controller;

import com.siddu.backend.llm_labeling.service.IntentClassificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/classification")
public class ClassificationController {

    private final IntentClassificationService classificationService;

    public ClassificationController(IntentClassificationService classificationService) {
        this.classificationService = classificationService;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> start() {
        if (!classificationService.start()) {
            return ResponseEntity.status(409).body(Map.of("status", "already_running"));
        }
        classificationService.processInBackground();
        return ResponseEntity.accepted().body(Map.of("status", "started"));
    }
}