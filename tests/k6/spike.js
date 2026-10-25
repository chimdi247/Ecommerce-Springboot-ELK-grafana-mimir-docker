// SPIKE: sudden surge (campaign, flash sale) then back to normal; latency must recover.
import { sleep } from 'k6';
import { prepare, shopperIteration } from './lib/common.js';

const SPIKE = Number(__ENV.SPIKE_VUS || 200);

export const options = {
  setupTimeout: '300s',
  stages: [
    { duration: '1m', target: 10 }, { duration: '20s', target: SPIKE }, { duration: '1m', target: SPIKE },
    { duration: '20s', target: 10 }, { duration: '3m', target: 10 }, { duration: '20s', target: 0 },
  ],
  thresholds: { http_req_failed: ['rate<0.05'], http_req_duration: ['p(95)<2500'] },
};

export const setup = () => prepare(10);
export default function (data) { shopperIteration(data); sleep(Math.random() * 0.5); }
