// BREAKPOINT: ramp the request RATE (open model) until thresholds fail; k6 aborts and the last
// stable rate is the capacity. Do not run against production.
import { prepare, shopperIteration } from './lib/common.js';

export const options = {
  setupTimeout: '300s',
  scenarios: {
    breakpoint: {
      executor: 'ramping-arrival-rate', startRate: 10, timeUnit: '1s', preAllocatedVUs: 100, maxVUs: 1000,
      stages: [{ duration: __ENV.BREAKPOINT_DURATION || '15m', target: Number(__ENV.MAX_RPS || 300) }],
    },
  },
  thresholds: {
    http_req_failed: [{ threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: '30s' }],
    http_req_duration: [{ threshold: 'p(95)<2000', abortOnFail: true, delayAbortEval: '30s' }],
  },
};

export const setup = () => prepare(10);
export default function (data) { shopperIteration(data); }
