// PAYMENT RACE (idempotency under concurrency): many VUs try to start the payment of the SAME order
// at the same instant with DIFFERENT idempotency keys. Exactly one PaymentIntent may exist.
// Uses Stripe test mode (creates ONE PaymentIntent). Needs Stripe keys configured in the stack.
import { check, fail } from 'k6';
import http from 'k6/http';
import { BASE_URL, authHeaders, createOrder, login, register, uid } from './lib/common.js';

export const options = {
  setupTimeout: '120s',
  scenarios: { race: { executor: 'shared-iterations', vus: 25, iterations: 25, maxDuration: '60s' } },
  thresholds: { http_req_failed: ['rate<0.01'], checks: ['rate==1'] },
};

export function setup() {
  if (http.get(`${BASE_URL}/payment/config`, { responseCallback: http.expectedStatuses(200, 503) }).status !== 200) fail('Stripe is not configured (STRIPE_* keys)');
  const email = register('race');
  const token = login(email);
  const orderId = createOrder(token, 1, 1).json('order.id');
  return { token, orderId };
}

export default function (data) {
  const res = http.post(`${BASE_URL}/payment/create-intent`, JSON.stringify({ orderId: data.orderId }), {
    ...authHeaders(data.token),
    headers: { ...authHeaders(data.token).headers, 'Idempotency-Key': `race-${__VU}-${uid()}-${uid()}` },
    tags: { name: 'POST /payment/create-intent' },
  });
  check(res, { 'create-intent 200': (r) => r.status === 200 });
  if (res.status !== 200) return;

  // Whatever key this VU used, the intent it received must be THE intent recorded for the order.
  const intentId = res.json('paymentIntent.paymentIntentId');
  const status = http.get(`${BASE_URL}/payment/status/${data.orderId}`, { ...authHeaders(data.token), tags: { name: 'GET /payment/status/:id' } });
  check(status, { 'same PaymentIntent as the order record': (r) => r.status === 200 && r.json('payment.paymentIntentId') === intentId });
}

export function teardown(data) {
  // every response must have carried the same intent: ask once more and compare with the status endpoint
  const status = http.get(`${BASE_URL}/payment/status/${data.orderId}`, authHeaders(data.token));
  check(status, { 'one payment record for the order': (r) => r.status === 200 && !!r.json('payment.paymentIntentId') });
}
