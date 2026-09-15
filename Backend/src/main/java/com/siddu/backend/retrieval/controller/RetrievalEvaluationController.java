package com.siddu.backend.retrieval.controller;

import com.siddu.backend.retrieval.model.RetrievalEvaluationReport;
import com.siddu.backend.retrieval.service.RetrievalEvaluationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/retrieval")
public class RetrievalEvaluationController {

    private final RetrievalEvaluationService evaluationService;

    public RetrievalEvaluationController(RetrievalEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping("/evaluation")
    public RetrievalEvaluationReport evaluate(@RequestParam(defaultValue = "10") int k) {
        return evaluationService.evaluate(k);
    }
}