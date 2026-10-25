// RATE LIMIT: verifies GET /product/get-all is limited per client (default 30 / 60 s) and that the
// limit is enforced consistently under concurrency (never far more than `limit` 200s per window).
//   k6 run -e LIMIT=30 tests/k6/rate-limit.js
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import http from 'k6/http';
import { BASE_URL } from './lib/common.js';

const LIMIT = Number(__ENV.LIMIT || 30);
const ok = new Counter('get_all_200');
const limited = new Counter('get_all_429');

export const options = {
  scenarios: { burst: { executor: 'per-vu-iterations', vus: 10, iterations: Math.ceil((LIMIT * 2) / 10), maxDuration: '30s' } },
  thresholds: {
    get_all_429: ['count>0'],                       // the limiter engaged
    get_all_200: [`count<=${LIMIT + 2}`],          // ...and was not leaky under concurrency (tiny slack for window rollover)
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/product/get-all`, { responseCallback: http.expectedStatuses(200, 429), tags: { name: 'GET /product/get-all' } });
  if (res.status === 200) ok.add(1);
  if (res.status === 429) {
    limited.add(1);
    check(res, { '429 has Retry-After': (r) => Number(r.headers['Retry-After']) > 0 });
  }
}
