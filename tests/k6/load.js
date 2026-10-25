// LOAD: expected production traffic, sustained. Establishes the baseline.
//   VUS=20 HOLD=5m k6 run tests/k6/load.js
import { sleep } from 'k6';
import { prepare, shopperIteration } from './lib/common.js';

const VUS = Number(__ENV.VUS || 20);

export const options = {
  setupTimeout: '300s',
  stages: [
    { duration: __ENV.RAMP || '1m', target: VUS },
    { duration: __ENV.HOLD || '5m', target: VUS },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<800', 'p(99)<1500'],
    'http_req_duration{name:GET /product/get-by-id/:id}': ['p(95)<300'],   // Redis-backed
    checks: ['rate>0.99'],
  },
};

export const setup = () => prepare(Number(__ENV.FIXTURE_USERS || 5));

export default function (data) {
  shopperIteration(data);
  sleep(Math.random() * 2 + 0.5);
}
