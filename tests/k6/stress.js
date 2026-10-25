// STRESS: step the load well beyond normal to find where latency/errors degrade and how it recovers.
// Watch container CPU/memory (alerts at 40%), the JVM and MySQL connections in Grafana.
import { sleep } from 'k6';
import { prepare, shopperIteration } from './lib/common.js';

export const options = {
  setupTimeout: '300s',
  stages: [
    { duration: '2m', target: 20 }, { duration: '3m', target: 50 }, { duration: '3m', target: 100 },
    { duration: '3m', target: 150 }, { duration: '3m', target: 200 }, { duration: '3m', target: 0 },
  ],
  thresholds: { http_req_failed: ['rate<0.10'], http_req_duration: ['p(95)<3000'] },
};

export const setup = () => prepare(10);
export default function (data) { shopperIteration(data); sleep(Math.random()); }
