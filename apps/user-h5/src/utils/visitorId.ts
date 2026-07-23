const ANONYMOUS_USER_KEY = "jianda_anonymous_user";

type CryptoLike = Pick<Crypto, "getRandomValues"> & {
  randomUUID?: () => string;
};

type StorageLike = Pick<Storage, "getItem" | "setItem">;

function formatUuid(bytes: Uint8Array): string {
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, "0"));
  return [
    hex.slice(0, 4).join(""),
    hex.slice(4, 6).join(""),
    hex.slice(6, 8).join(""),
    hex.slice(8, 10).join(""),
    hex.slice(10, 16).join(""),
  ].join("-");
}

function fallbackUuid(): string {
  let seed = Date.now() + Math.floor((globalThis.performance?.now?.() || 0) * 1000);
  const bytes = new Uint8Array(16);
  for (let index = 0; index < bytes.length; index += 1) {
    seed = (seed * 1664525 + 1013904223) >>> 0;
    bytes[index] = (seed >>> 24) ^ Math.floor(Math.random() * 256);
  }
  return formatUuid(bytes);
}

/**
 * Creates a best-effort UUID for local anonymous data association.
 * It is not cryptographic identity and must never be used as an authentication credential.
 */
export function createUuid(cryptoSource?: CryptoLike): string {
  let availableCrypto = cryptoSource;
  if (arguments.length === 0) {
    try {
      availableCrypto = globalThis.crypto;
    } catch {
      availableCrypto = undefined;
    }
  }

  try {
    if (typeof availableCrypto?.randomUUID === "function") {
      return availableCrypto.randomUUID();
    }
  } catch {
    // Some insecure-context browsers expose the method but throw when it is called.
  }

  try {
    if (typeof availableCrypto?.getRandomValues === "function") {
      return formatUuid(availableCrypto.getRandomValues(new Uint8Array(16)));
    }
  } catch {
    // Keep the H5 demonstrable even when the Web Crypto implementation is unavailable.
  }

  return fallbackUuid();
}

/**
 * Returns the stable visitor ID used only by favorites, history and preferences.
 * This value is not an authentication credential.
 */
export function getOrCreateAnonymousUserId(
  storage?: StorageLike,
): string {
  let availableStorage = storage;
  if (arguments.length === 0) {
    try {
      availableStorage = globalThis.localStorage;
    } catch {
      availableStorage = undefined;
    }
  }

  try {
    const existing = availableStorage?.getItem(ANONYMOUS_USER_KEY);
    if (existing) return existing;
  } catch {
    // Storage can be blocked by browser privacy settings; an in-memory ID still avoids a blank page.
  }

  const visitorId = createUuid();
  try {
    availableStorage?.setItem(ANONYMOUS_USER_KEY, visitorId);
  } catch {
    // Persistence is best effort and must not prevent Vue from mounting.
  }
  return visitorId;
}
