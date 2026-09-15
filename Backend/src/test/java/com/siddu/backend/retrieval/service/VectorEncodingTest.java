package com.siddu.backend.retrieval.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VectorEncodingTest {

    @Test
    void encodesVectorInPgVectorFormat() {
        assertEquals("[0.100000001,-0.250000000,1.000000000]",
                VectorEncoding.toPgVector(new float[]{0.1f, -0.25f, 1.0f}));
    }

    @Test
    void rejectsEmptyVector() {
        Throwable exception = assertThrows(IllegalArgumentException.class,
                () -> VectorEncoding.toPgVector(new float[0]));
        assertEquals("Embedding vector must not be empty", exception.getMessage());
    }
}