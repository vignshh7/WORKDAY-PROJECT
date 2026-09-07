import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Fetch-on-mount with the four states every page needs to keep distinct:
 * loading, error, empty, and loaded. `deps` re-runs the fetch, exactly like useEffect.
 */
export function useFetch(fn, deps = [], { skip = false } = {}) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(!skip);
  const fnRef = useRef(fn);
  const reqId = useRef(0);

  // Keep the latest fetcher without re-triggering the fetch: callers pass an inline
  // arrow, so a dependency on `fn` itself would loop forever. Declared before the
  // fetching effect so the ref is current by the time it runs.
  useEffect(() => {
    fnRef.current = fn;
  });

  const run = useCallback(async () => {
    const id = ++reqId.current;
    setLoading(true);
    setError(null);
    try {
      const result = await fnRef.current();
      if (id === reqId.current) setData(result);
      return result;
    } catch (err) {
      // Ignore a stale request's failure — a newer one is already in flight.
      if (id === reqId.current) setError(err);
      return undefined;
    } finally {
      if (id === reqId.current) setLoading(false);
    }
  }, []);

  // Fetch on mount and whenever deps change. `run` sets loading synchronously, which
  // costs one extra render — unavoidable for fetch-on-mount, and the alternative
  // (rendering without a loading state first) is worse.
  useEffect(() => {
    if (skip) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    run();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, skip]);

  // Derived rather than set inside the effect: a skipped fetch is never "loading".
  return { data, setData, error, loading: skip ? false : loading, reload: run };
}

/**
 * A user-triggered mutation. Never auto-retries: a blind retry of book/confirm with a
 * new idempotency key can double-book if the first request succeeded but its response
 * was lost, so retrying is always the user's explicit re-click.
 */
export function useAction(fn) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState(null);
  const fnRef = useRef(fn);

  useEffect(() => {
    fnRef.current = fn;
  });

  const run = useCallback(async (...args) => {
    setPending(true);
    setError(null);
    try {
      return await fnRef.current(...args);
    } catch (err) {
      setError(err);
      throw err;
    } finally {
      setPending(false);
    }
  }, []);

  const clearError = useCallback(() => setError(null), []);

  return { run, pending, error, clearError };
}
