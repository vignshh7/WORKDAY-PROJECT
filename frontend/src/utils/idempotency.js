// book / switch-interviewer / ai-confirm need one UUID per *user action*, reused
// verbatim on a retry of that same action. A fresh key on retry can double-book when
// the original request actually succeeded but its response was lost in transit.

export function newIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  // Fallback for non-secure contexts, where crypto.randomUUID is undefined.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

/**
 * Holds a key stable across retries of one action. Read the current key with `key()`
 * (minted on first use); call `reset()` once the action genuinely succeeds, or when
 * the user abandons it and starts a different one.
 */
export function createIdempotencyHolder() {
  let current = null;
  return {
    key() {
      if (!current) current = newIdempotencyKey();
      return current;
    },
    reset() {
      current = null;
    },
  };
}
