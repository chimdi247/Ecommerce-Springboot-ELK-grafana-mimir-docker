import { describe, expect, it } from 'vitest';
import { api } from './api.js';

// Named zz-* so it runs last: it deliberately exhausts the quota of GET /product/get-all for this client IP.
describe('rate limiting: GET /product/get-all', () => {
  it('returns 429 with Retry-After once the per-IP limit is exceeded (other routes unaffected)', async () => {
    let limited = null;
    for (let i = 0; i < 400 && !limited; i += 1) {
      const res = await api('/product/get-all');
      if (res.status === 429) limited = res;
    }
    expect(limited, 'no 429 within 400 requests: is RATE_LIMIT_GET_ALL_PER_WINDOW very high?').not.toBeNull();
    expect(Number(limited.headers.get('retry-after'))).toBeGreaterThan(0);
    expect(limited.body.message).toMatch(/too many requests/i);

    expect((await api('/category/get-all')).status).toBe(200);   // an un-limited route still works
  });
});
