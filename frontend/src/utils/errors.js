// Every backend error is the same shape:
//   {timestamp, status, error, message, path}
// so error handling is one function, not per-call parsing.

export const NETWORK_ERROR = 'NETWORK_ERROR';

/** Normalizes anything axios throws into {status, code, message, path, isNetwork}. */
export function parseApiError(err) {
  if (err?.response) {
    const { status, data } = err.response;
    return {
      status,
      code: data?.error || null,
      message: data?.message || data?.error || `Request failed with status ${status}.`,
      path: data?.path || null,
      isNetwork: false,
    };
  }
  // No response at all — server unreachable, DNS failure, CORS, or a timeout.
  return {
    status: 0,
    code: NETWORK_ERROR,
    message: "Couldn't reach the server. Check that the backend is running, then try again.",
    path: null,
    isNetwork: true,
  };
}

export function errorMessage(err) {
  return parseApiError(err).message;
}

/** A 422 from /book or /switch-interviewer means the slot went stale mid-flow. */
export function isSlotTaken(err) {
  return parseApiError(err).status === 422;
}

/** A 409 from a reschedule means the configured maximum was exceeded. */
export function isConflict(err) {
  return parseApiError(err).status === 409;
}

export function isForbidden(err) {
  return parseApiError(err).status === 403;
}

export function isNotFound(err) {
  return parseApiError(err).status === 404;
}

export function isNetworkError(err) {
  return parseApiError(err).isNetwork;
}
