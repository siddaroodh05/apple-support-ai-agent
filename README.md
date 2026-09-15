# AI Customer Support Agent — Apple Support

An AI-powered customer-support agent built using the Customer Support on Twitter
dataset. The system classifies incoming customer queries, retrieves relevant
historical support cases using semantic search, generates a grounded response,
and decides whether the request can be auto-handled or should be escalated to a
human.

## Project at a Glance

| Item | Count |
|---|---:|
| Original dataset | ~3M tweets |
| Apple Support cases | ~100K |
| Cases initially selected | 8,000 |
| Additional targeted cases | 2,000 |
| Total working pool | 10,000 |
| Cases in final retrieval corpus | ~5,026 |
| Gold evaluation cases | 200 |


The 10,000-case working pool was constructed from the larger dataset, with additional targeted sampling used to improve representation of account/data, purchase/billing, and third-party-app issues. After filtering and preparation, 5,026 cases were used as the final retrieval corpus.

### Retrieval Corpus Distribution

The final retrieval corpus contains cases across all eight intents:

| Intent | Cases |
|---|---:|
| Software issue | 1,405 |
| General complaint / vague | 670 |
| How-to question | 647 |
| Hardware issue | 626 |
| Purchase / billing | 549 |
| No actionable content | 522 |
| Account / data issue | 384 |
| Third-party app issue | 223 |
| **Total** | **5,026** |


## System Architecture

The online support agent is exposed through a single retrieval-and-answer
endpoint. A customer query is first classified into an intent and converted
into an embedding. The embedding is used to retrieve historical support cases
from PostgreSQL using pgvector. The top-5 retrieved cases, together with the
customer query and predicted intent, are then passed to the final response LLM.

                         ┌──────────────────────┐
                         │   Customer Query     │
                         └──────────┬───────────┘
                                    │
              ┌─────────────────────┼────────────────────┐
              │                     │                    │
              ▼                     ▼                    │
       ┌─────────────┐       ┌─────────────┐             │
       │ Intent LLM  │       │  Embedding  │             │
       └──────┬──────┘       └──────┬──────┘             │
              │                     │                    │
              ▼                     ▼                    │
       Predicted Intent          Query Vector            │
                                    │                    │
                                    ▼                    │
                           ┌─────────────────┐           │
                           │ PostgreSQL +    │           │
                           │ pgvector        │           │
                           └────────┬────────┘           │
                                    │                    |
                                    ▼                    │
                              Top-5 Cases                │
                                    │                    │
              ┌─────────────────────┴────────────────────┘
              │
              ▼
       ┌─────────────────────────┐
       │   Final Response LLM    │
       │                         │
       │ Query + Intent + Top-5  │
       └────────────┬────────────┘
                    │
                    ▼
       ┌─────────────────────────┐
       │ evidence_sufficient     │
       │ response                │
       │ decision                │
       │ reason                  │
       └─────────────────────────┘


### Evidence-Based Escalation

The agent does not generate a response solely because it can produce a
plausible answer. After retrieving historical support cases, the final LLM
evaluates whether the retrieved evidence is sufficiently relevant to the
customer's specific request.

If sufficient evidence is available, the agent generates a grounded response
and can return `AUTO_HANDLE`.

If the retrieved evidence is insufficient or unrelated to the customer's
request, the agent returns `ESCALATE` with a reason rather than generating an
unsupported response.

For example, an unrelated Android/Samsung product recommendation was
correctly escalated because the retrieved historical cases did not provide
relevant evidence.

👉 [View Escalation Example](./evidence/live-agent)



## Evaluation & Metrics

A manually gold-labeled evaluation set of 200 customer-support cases was used
to evaluate retrieval performance. The evaluation cases were drawn from the
working dataset and were excluded from the retrieval corpus to prevent
self-retrieval.

For each customer query, the system retrieves historical support cases using
semantic similarity. Retrieval performance is measured at different retrieval
depths by checking whether at least one retrieved case has the same intent as
the query's gold intent.

### Retrieval Results

| Retrieval Depth | Correct-Intent Cases Retrieved | Recall |
|---|---:|---:|
| Top-1 | 118 / 200 | 59.0% |
| Top-3 | 161 / 200 | 80.5% |
| Top-5 | 168 / 200 | **84.0%** |
| Top-10 | 183 / 200 | 91.5% |
| Top-20 | 191 / 200 | 95.5% |

The primary operational metric is Top-5 recall because the live support
pipeline provides the final response LLM with five retrieved historical cases.
At Top-5, the system retrieves at least one case matching the gold intent for
168 of 200 evaluation queries.

Increasing the retrieval depth improves coverage substantially, reaching
95.5% at Top-20. However, this metric measures retrieval coverage rather than
final response correctness.

### Per-Intent Retrieval Performance

| Intent | Cases | Top-5 Recall | Top-20 Recall |
|---|---:|---:|---:|
| Software issue | 55 | 94.5% | 100.0% |
| Hardware issue | 19 | 84.2% | 94.7% |
| Account/data issue | 27 | 85.2% | 96.3% |
| How-to question | 22 | 68.2% | 90.9% |
| Purchase/billing | 19 | 89.5% | 94.7% |
| Third-party app issue | 18 | 61.1% | 83.3% |
| General complaint/vague | 21 | 85.7% | 95.2% |
| No actionable content | 19 | 84.2% | 100.0% |

Retrieval is strongest for software issues and purchase/billing cases. 
Third-party app issues and how-to questions are more challenging, indicating
that semantic similarity alone can confuse closely related support needs.

### Retrieval Diagnostics

| Diagnostic | Result |
|---|---:|
| Evaluation cases | 200 |
| Successfully evaluated | 200 |
| Failed evaluations | 0 |
| Average Top-1 similarity | 0.762 |
| Minimum Top-1 similarity | 0.551 |
| Maximum Top-1 similarity | 1.000 |
| Average rank of correct-intent case | 2.48 |
| Correct intent not found in Top-20 | 9 / 200 (4.5%) |
| Self-retrievals | 0 |
| Duplicate retrieved IDs | 0 |


## Live Support Agent Examples

The following examples demonstrate the complete live support pipeline,
including intent classification, retrieval, grounded response generation,
evidence sufficiency, and the AUTO_HANDLE / ESCALATE decision.

The complete request/response examples are available here:
👉 [View Live Support Agent Examples](./evidence/live-agent)



## Retrieval Search Examples

These examples show the semantic retrieval stage independently, including the
customer query and the historical cases returned by PostgreSQL/pgvector.

👉 [View Retrieval Search Examples](./evidence/retrieval)


## Data & Evaluation Files

The repository includes CSV files used for offline dataset preparation,
LLM-based intent labeling, and evaluation. The live retrieval pipeline does
not read these CSV files directly; the verified retrieval corpus is stored in
PostgreSQL with pgvector.

| File / Dataset                                                           | Purpose                                                                                                           |
|--------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| [working_pool](./data/working_pool.csv)                                  | Initial ~8,000-case working pool with LLM-generated intent labels                                                 |
| Targeted candidate CSVs                                                  | Additional cases selected to improve representation of account/data, third-party-app, and purchase/billing issues |
| [gold_intent_Cases](./data/gold_intent_labeled_cases.csv)                | 200 manually verified cases with gold intents used for retrieval evaluation                                       |
| [PostgreSQL retrieval corpus](./data/support_cases_retrieval_corpus.csv) | ~5,026 verified and embedded historical cases used by the live retrieval pipeline                                 |

The initial working pool contained approximately 8,000 cases. Additional
targeted sampling brought the working pool to approximately 10,000 cases.
After filtering, verification, and preparation, approximately 5,026 cases
were embedded and stored in PostgreSQL for retrieval.

The gold evaluation set is kept separate from the retrieval corpus during
evaluation to avoid self-retrieval.



## Baselines

To understand the performance of the retrieval system, I evaluate semantic
retrieval at different retrieval depths using the 200-case gold evaluation
set.

The retrieval baseline uses embedding-based semantic search against the
PostgreSQL/pgvector corpus. No reranking is applied.

| Retrieval Depth | Correct-Intent Cases Retrieved | Recall |
|---|---:|---:|
| Top-1 | 118 / 200 | 59.0% |
| Top-3 | 161 / 200 | 80.5% |
| Top-5 | 168 / 200 | **84.0%** |
| Top-10 | 183 / 200 | 91.5% |
| Top-20 | 191 / 200 | 95.5% |

Top-5 is the operational retrieval depth used by the live support pipeline,
where the five retrieved historical cases are passed to the final response
LLM as supporting evidence.

The results show that increasing retrieval depth improves the probability of
finding a case with the correct intent. However, retrieval recall measures
evidence coverage rather than final response correctness.


## Response Quality Evaluation

Retrieval quality alone does not guarantee a useful support response. A small
manually reviewed sample of 15 live interactions was used to qualitatively
evaluate the final support agent.

The sample covers technical issues, hardware problems, account issues, how-to
requests, billing, third-party applications, vague complaints, and
out-of-domain requests.

| Evaluation Area | Result |
|---|---:|
| Live interactions reviewed | 15 |
| AUTO_HANDLE | 7 |
| ESCALATE | 8 |
| Appropriate handling based on manual review | 15 / 15 |

The evaluation focuses on whether the system:

- provides a response when sufficient evidence is available;
- avoids unsupported troubleshooting;
- produces useful next steps;
- correctly escalates when evidence is insufficient or unrelated.

This is a small qualitative sample and is not intended to represent a
statistically significant production accuracy estimate.

👉 [View Live Support Agent Examples](./evidence/live-agent)

---


## Failure Analysis

The evaluation highlights several areas where retrieval is more challenging.

### Third-party App Issues

Third-party application issues have the lowest Top-5 recall at 61.1%.
Some cases are retrieved as general software issues because both categories
can contain similar symptoms such as crashes, freezing, or application
failures.

### How-to Questions

How-to questions have a Top-5 recall of 68.2%. Retrieval can confuse
instructions for performing an action with historical cases describing an
existing software problem.

### Account/Data Issues

Account/data issues are sometimes retrieved as software issues, particularly
when the customer mentions iCloud, backup, syncing, or data loss without
explicitly describing an account-access problem.

### Insufficient Evidence

Some customer requests have no sufficiently relevant historical cases in the
retrieval corpus. Instead of generating a potentially unsupported response,
the final LLM returns `ESCALATE`.

This evidence-gated behavior is intentional: when the system cannot find
adequate supporting evidence, escalation is preferred over hallucinating
troubleshooting instructions.

---
### Reliability & Retry Handling

The pipeline includes retry handling for transient LLM and embedding API
failures. Retryable requests are automatically retried with the configured
retry policy before the operation is considered failed.

This prevents temporary model-service failures from unnecessarily causing
customer requests to fail.


## Technology Stack & Models

### Technology Stack

| Component | Technology |
|---|---|
| Backend | Spring Boot |
| Database | PostgreSQL |
| Vector Search | pgvector |
| API | REST |

### Models

| Pipeline Component | Model | Purpose |
|---|---|---|
| Live intent classification | `openai/gpt-oss-20b` | Classifies incoming customer queries into one of 8 intents |
| Batch intent labeling | `openai/gpt-oss-20b` | Labels historical support cases during offline dataset preparation |
| Embeddings | `nomic-embed-text` | Converts customer queries and historical cases into vectors for semantic retrieval |
| Final support response | `openai/gpt-oss-20b` | Generates a grounded response and determines evidence sufficiency and handling decision |

The batch intent-labeling process is an offline data-preparation step. Live
intent classification, embedding generation, retrieval, and final response
generation are part of the online support pipeline.




