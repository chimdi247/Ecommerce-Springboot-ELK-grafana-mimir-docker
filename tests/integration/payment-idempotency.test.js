import { beforeAll, describe, expect, it } from 'vitest';
import { api, createOrder, firstProductIds, payWithTestCard, registerAndLogin, sleep, stripePaymentsConfigured, uid } from './api.js';

// Needs STRIPE_SECRET_KEY (test mode) in this shell and the stack running with Stripe keys.
let enabled = false;
beforeAll(async () => { enabled = await stripePaymentsConfigured(); });

const run = (name, fn) => it(name, async (ctx) => { if (!enabled) return ctx.skip(); await fn(ctx); });

describe('Stripe payment + idempotency (an order can never be paid twice)', () => {
  run('requires an Idempotency-Key header', async () => {
    const { token } = await registerAndLogin('pay');
    const [id] = await firstProductIds(1);
    const order = await createOrder(token, [{ productId: id, quantity: 1 }]);
    const res = await api('/payment/create-intent', { body: { orderId: order.id }, token });
    expect(res.status).toBe(400);
  });

  run('the same key returns the same PaymentIntent; a different key reuses the open one', async () => {
    const { token } = await registerAndLogin('pay');
    const [id] = await firstProductIds(1);
    const order = await createOrder(token, [{ productId: id, quantity: 1 }]);
    const key = `it-${uid()}-${uid()}`;

    const first = await api('/payment/create-intent', { body: { orderId: order.id }, token, headers: { 'Idempotency-Key': key } });
    const replay = await api('/payment/create-intent', { body: { orderId: order.id }, token, headers: { 'Idempotency-Key': key } });
    const otherKey = await api('/payment/create-intent', { body: { orderId: order.id }, token, headers: { 'Idempotency-Key': `other-${uid()}-${uid()}` } });

    expect(first.status).toBe(200);
    expect(replay.body.paymentIntent.paymentIntentId).toBe(first.body.paymentIntent.paymentIntentId);
    expect(otherKey.body.paymentIntent.paymentIntentId).toBe(first.body.paymentIntent.paymentIntentId);
    expect(replay.body.paymentIntent.reused).toBe(true);
  });

  run('concurrent requests all resolve to ONE PaymentIntent', async () => {
    const { token } = await registerAndLogin('pay');
    const [id] = await firstProductIds(1);
    const order = await createOrder(token, [{ productId: id, quantity: 1 }]);
    const results = await Promise.all(Array.from({ length: 8 }, (_, i) =>
      api('/payment/create-intent', { body: { orderId: order.id }, token, headers: { 'Idempotency-Key': `race-${i}-${uid()}-${uid()}` } })));
    const ids = new Set(results.map((r) => r.body.paymentIntent?.paymentIntentId));
    expect(results.every((r) => r.status === 200)).toBe(true);
    expect(ids.size).toBe(1);
  });

  run('full payment: pays once, refuses a second payment, notifies the customer once', async () => {
    const { token } = await registerAndLogin('pay');
    const [id] = await firstProductIds(1);
    const order = await createOrder(token, [{ productId: id, quantity: 2 }]);
    const key = `full-${uid()}-${uid()}`;

    const intent = await api('/payment/create-intent', { body: { orderId: order.id }, token, headers: { 'Idempotency-Key': key } });
    expect(intent.status).toBe(200);
    const paid = await payWithTestCard(intent.body.paymentIntent.clientSecret);
    expect(paid.status).toBe('succeeded');

    const confirmed = await api(`/payment/confirm/${order.id}`, { method: 'POST', token });
    expect(confirmed.body.payment.status).toBe('PAID');
    const again = await api(`/payment/confirm/${order.id}`, { method: 'POST', token });   // refresh / double click
    expect(again.body.payment.status).toBe('PAID');

    // the order is PAID: no new payment may ever be created, with the same OR a different key
    expect((await api('/payment/create-intent', { body: { orderId: order.id }, token, headers: { 'Idempotency-Key': key } })).status).toBe(409);
    expect((await api('/payment/create-intent', { body: { orderId: order.id }, token, headers: { 'Idempotency-Key': `new-${uid()}-${uid()}` } })).status).toBe(409);

    // exactly ONE e-mail notification record for the order (SENT, or FAILED if SMTP is not configured)
    let notifications = [];
    for (let i = 0; i < 20 && notifications.length === 0; i += 1) {
      await sleep(1000);
      notifications = (await api('/notification/my', { token })).body.notificationList.filter((n) => n.orderId === order.id);
    }
    expect(notifications).toHaveLength(1);
    expect(['SENT', 'FAILED']).toContain(notifications[0].status);
  });

  run("a user cannot pay or read someone else's order", async () => {
    const owner = await registerAndLogin('pay');
    const intruder = await registerAndLogin('pay');
    const [id] = await firstProductIds(1);
    const order = await createOrder(owner.token, [{ productId: id, quantity: 1 }]);
    const res = await api('/payment/create-intent', { body: { orderId: order.id }, token: intruder.token, headers: { 'Idempotency-Key': `x-${uid()}-${uid()}` } });
    expect(res.status).toBe(404);
    expect((await api(`/payment/status/${order.id}`, { token: intruder.token })).status).toBe(404);
  });
});
