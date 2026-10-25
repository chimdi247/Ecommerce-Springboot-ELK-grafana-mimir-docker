import { describe, expect, it } from 'vitest';
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from '../../react/src/service/idempotency.js';

const memoryStorage = () => {
  const map = new Map();
  return { getItem: (k) => map.get(k) ?? null, setItem: (k, v) => map.set(k, v), removeItem: (k) => map.delete(k) };
};

describe('payment idempotency keys', () => {
  it('returns the same key for the same order (retries, reloads, double clicks)', () => {
    const storage = memoryStorage();
    expect(getOrCreateIdempotencyKey(7, storage)).toBe(getOrCreateIdempotencyKey(7, storage));
  });

  it('uses a different key per order', () => {
    const storage = memoryStorage();
    expect(getOrCreateIdempotencyKey(7, storage)).not.toBe(getOrCreateIdempotencyKey(8, storage));
  });

  it('produces keys accepted by the backend validator (8-128 chars of A-Za-z0-9_.:-)', () => {
    const key = getOrCreateIdempotencyKey(1, memoryStorage());
    expect(key).toMatch(/^[A-Za-z0-9_.:-]{8,128}$/);
  });

  it('issues a fresh key after the key was cleared', () => {
    const storage = memoryStorage();
    const first = getOrCreateIdempotencyKey(7, storage);
    clearIdempotencyKey(7, storage);
    expect(getOrCreateIdempotencyKey(7, storage)).not.toBe(first);
  });
});
