package com.interviewscheduler.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewscheduler.common.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Generic idempotency-key support for a write operation that must be safely retryable
 * (a client resubmitting after a timeout, a double-click, two racing tabs). {@link #claim}
 * must be called first, inside the same transaction that will perform the operation - so
 * if that operation later fails and the transaction rolls back, the claim rolls back with
 * it and the same key can be retried; only a transaction that reaches {@link #complete}
 * permanently remembers the key.
 *
 * <p>The {@code (scope, key_value)} unique constraint is what actually prevents two
 * concurrent transactions from both proceeding: the second transaction's insert blocks
 * on the database until the first commits or rolls back (Postgres MVCC), then either
 * fails with a unique violation (first one committed - correctly rejected here as a
 * {@link ConflictException}) or succeeds (first one rolled back - correctly allowed to
 * proceed). Nothing in this class needs its own locking; the constraint does the work.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    /**
     * Returns the replayed response if {@code (scope, key)} already completed successfully;
     * otherwise claims the key for the current transaction and returns empty. Throws if a
     * duplicate is already in flight (same key, not yet completed).
     */
    @Transactional
    public <T> Optional<T> claim(String scope, String key, Class<T> responseType) {
        Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByScopeAndKeyValue(scope, key);
        if (existing.isPresent()) {
            IdempotencyKey record = existing.get();
            if (record.getStatus() == IdempotencyStatus.COMPLETED) {
                return Optional.of(readResponse(record, responseType));
            }
            throw new ConflictException("A request with idempotency key '" + key + "' is already in progress");
        }

        IdempotencyKey claimed = new IdempotencyKey();
        claimed.setScope(scope);
        claimed.setKeyValue(key);
        try {
            idempotencyKeyRepository.saveAndFlush(claimed);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A request with idempotency key '" + key + "' is already in progress");
        }
        return Optional.empty();
    }

    /** Marks {@code (scope, key)} completed and stores the response to replay on a future retry. */
    @Transactional
    public void complete(String scope, String key, Object response) {
        IdempotencyKey record = idempotencyKeyRepository.findByScopeAndKeyValue(scope, key)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency key disappeared mid-transaction: " + scope + "/" + key));
        record.setResponseBody(writeResponse(response));
        record.setStatus(IdempotencyStatus.COMPLETED);
        record.setCompletedAt(OffsetDateTime.now());
        idempotencyKeyRepository.saveAndFlush(record);
    }

    private String writeResponse(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize idempotent response", e);
        }
    }

    private <T> T readResponse(IdempotencyKey record, Class<T> responseType) {
        try {
            return objectMapper.readValue(record.getResponseBody(), responseType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt idempotency record for key: " + record.getKeyValue(), e);
        }
    }
}
