package com.siddu.backend.retrieval.repository;

import com.siddu.backend.retrieval.model.RetrievedSupportCase;
import com.siddu.backend.retrieval.model.SupportCase;
import com.siddu.backend.retrieval.service.VectorEncoding;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class SupportCaseRepository {

    private final JdbcClient jdbcClient;

    public SupportCaseRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public int insertIfAbsent(SupportCase supportCase) {
        String sql = """
                INSERT INTO support_cases
                    (tweet_id, created_at, customer_message, support_response, intent, embedding)
                VALUES
                    (:tweet_id, :created_at, :customer_message, :support_response, :intent,
                     CAST(:embedding AS vector))
                ON CONFLICT (tweet_id) DO NOTHING
                """;
        return jdbcClient.sql(sql)
                .param("tweet_id", supportCase.tweetId())
                .param("created_at", supportCase.createdAt())
                .param("customer_message", supportCase.customerMessage())
                .param("support_response", supportCase.supportResponse())
                .param("intent", supportCase.intent())
                .param("embedding", VectorEncoding.toPgVector(supportCase.embedding()))
                .update();
    }

    public Optional<SupportCase> findByTweetId(String tweetId) {
        return jdbcClient.sql("""
                SELECT tweet_id, created_at, customer_message, support_response, intent
                FROM support_cases
                WHERE tweet_id = :tweet_id
                """)
                .param("tweet_id", tweetId)
                .query((resultSet, rowNumber) -> new SupportCase(
                        resultSet.getString("tweet_id"),
                        resultSet.getObject("created_at", java.time.OffsetDateTime.class),
                        resultSet.getString("customer_message"),
                        resultSet.getString("support_response"),
                        resultSet.getString("intent"),
                        null))
                .optional();
    }

    public List<RetrievedSupportCase> findMostSimilar(float[] queryEmbedding, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Search limit must be positive");
        }
        return jdbcClient.sql("""
                SELECT tweet_id, customer_message, support_response, intent,
                       1 - (embedding <=> CAST(:query_embedding AS vector)) AS similarity
                FROM support_cases
                ORDER BY embedding <=> CAST(:query_embedding AS vector)
                LIMIT :result_limit
                """)
                .param("query_embedding", VectorEncoding.toPgVector(queryEmbedding))
                .param("result_limit", limit)
                .query((resultSet, rowNumber) -> new RetrievedSupportCase(
                        resultSet.getString("tweet_id"),
                        resultSet.getString("customer_message"),
                        resultSet.getString("support_response"),
                        resultSet.getString("intent"),
                        resultSet.getDouble("similarity")))
                .list();
    }

    public List<RetrievedSupportCase> findMostSimilarExcludingTweetId(
            float[] queryEmbedding, int limit, String excludedTweetId) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Search limit must be positive");
        }
        return jdbcClient.sql("""
                SELECT tweet_id, customer_message, support_response, intent,
                       1 - (embedding <=> CAST(:query_embedding AS vector)) AS similarity
                FROM support_cases
                WHERE tweet_id <> :excluded_tweet_id
                ORDER BY embedding <=> CAST(:query_embedding AS vector)
                LIMIT :result_limit
                """)
                .param("query_embedding", VectorEncoding.toPgVector(queryEmbedding))
                .param("excluded_tweet_id", excludedTweetId)
                .param("result_limit", limit)
                .query((resultSet, rowNumber) -> new RetrievedSupportCase(
                        resultSet.getString("tweet_id"),
                        resultSet.getString("customer_message"),
                        resultSet.getString("support_response"),
                        resultSet.getString("intent"),
                        resultSet.getDouble("similarity")))
                .list();
    }
}