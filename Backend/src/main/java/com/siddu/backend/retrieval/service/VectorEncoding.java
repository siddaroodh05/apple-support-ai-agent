package com.siddu.backend.retrieval.service;

import java.util.Locale;

public final class VectorEncoding {

    private VectorEncoding() {
    }

    public static String toPgVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("Embedding vector must not be empty");
        }
        StringBuilder encoded = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                encoded.append(',');
            }
            encoded.append(String.format(Locale.ROOT, "%.9f", vector[index]));
        }
        return encoded.append(']').toString();
    }
}