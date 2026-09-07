// Token lives in memory (survives re-renders, dies with the tab process) with a
// sessionStorage mirror so a page refresh doesn't log the user out. sessionStorage
// rather than localStorage: it's scoped to the tab and cleared when the tab closes,
// which narrows the XSS exposure window compared to a persistent localStorage token.

const TOKEN_KEY = 'is.token';
const USER_KEY = 'is.user';

let memoryToken = null;
let memoryUser = null;

function safeRead(key) {
  try {
    return sessionStorage.getItem(key);
  } catch {
    return null; // private mode / storage blocked
  }
}

function safeWrite(key, value) {
  try {
    if (value == null) sessionStorage.removeItem(key);
    else sessionStorage.setItem(key, value);
  } catch {
    // Storage unavailable — the in-memory copy still carries this tab's session.
  }
}

export function getToken() {
  if (memoryToken) return memoryToken;
  memoryToken = safeRead(TOKEN_KEY);
  return memoryToken;
}

/** The identity half of LoginResponse: {userId, email, role}. */
export function getStoredUser() {
  if (memoryUser) return memoryUser;
  const raw = safeRead(USER_KEY);
  if (!raw) return null;
  try {
    memoryUser = JSON.parse(raw);
  } catch {
    memoryUser = null;
  }
  return memoryUser;
}

export function saveSession(token, user) {
  memoryToken = token;
  memoryUser = user;
  safeWrite(TOKEN_KEY, token);
  safeWrite(USER_KEY, JSON.stringify(user));
}

export function clearSession() {
  memoryToken = null;
  memoryUser = null;
  safeWrite(TOKEN_KEY, null);
  safeWrite(USER_KEY, null);
}
