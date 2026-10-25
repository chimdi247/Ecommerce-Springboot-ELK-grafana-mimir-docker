import { describe, expect, it } from 'vitest';
import { PASSWORD, api, firstProductIds, registerAndLogin } from './api.js';

describe('auth', () => {
  it('registers, logs in and rejects a wrong password', async () => {
    const { email, token } = await registerAndLogin('auth');
    expect(token).toBeTruthy();
    const bad = await api('/auth/login', { body: { email, password: 'wrong-' + PASSWORD } });
    expect(bad.status).toBeGreaterThanOrEqual(400);
  });

  it('protects private routes', async () => {
    expect((await api('/cart')).status).toBeGreaterThanOrEqual(401);
    expect((await api('/order/create', { body: { items: [] } })).status).toBeGreaterThanOrEqual(401);
    expect((await api('/payment/status/1')).status).toBeGreaterThanOrEqual(401);
  });

  it('exposes only the publishable key and currency on the public config route', async () => {
    const res = await api('/payment/config');
    if (res.status === 200) {
      expect(res.body.paymentConfig.publishableKey).toMatch(/^pk_/);
      expect(JSON.stringify(res.body)).not.toContain('sk_');
    } else {
      expect(res.status).toBe(503);   // Stripe keys not configured
    }
  });
});

describe('Redis cache-aside: product lookup', () => {
  it('serves the second lookup of a product from Redis (X-Cache: HIT)', async () => {
    const [id] = await firstProductIds(1);
    const first = await api(`/product/get-by-id/${id}`);
    const second = await api(`/product/get-by-id/${id}`);
    expect(['HIT', 'MISS']).toContain(first.headers.get('x-cache'));
    expect(second.headers.get('x-cache')).toBe('HIT');
    expect(second.body.product.id).toBe(id);
  });

  it('answers 404 for a missing product and never caches it', async () => {
    const res = await api('/product/get-by-id/99999999');
    expect(res.status).toBe(404);
    const again = await api('/product/get-by-id/99999999');
    expect(again.headers.get('x-cache')).not.toBe('HIT');
  });
});

describe('persistent cart', () => {
  it('adds, updates, removes and merges idempotently', async () => {
    const { token } = await registerAndLogin('cart');
    const [a, b] = await firstProductIds(2);

    await api('/cart/items', { body: { productId: a, quantity: 2 }, token });
    let cart = (await api('/cart/items', { body: { productId: a, quantity: 1 }, token })).body.cart;
    expect(cart.items.find((i) => i.product.id === a).quantity).toBe(3);

    cart = (await api(`/cart/items/${a}?quantity=5`, { method: 'PUT', token })).body.cart;
    expect(cart.totalItems).toBe(5);

    // a guest cart merged twice must not double the quantities (max(), not sum)
    const guest = { items: [{ productId: a, quantity: 5 }, { productId: b, quantity: 1 }] };
    await api('/cart/merge', { body: guest, token });
    cart = (await api('/cart/merge', { body: guest, token })).body.cart;
    expect(cart.items.find((i) => i.product.id === a).quantity).toBe(5);
    expect(cart.items.find((i) => i.product.id === b).quantity).toBe(1);

    cart = (await api(`/cart/items/${b}`, { method: 'DELETE', token })).body.cart;
    expect(cart.items.some((i) => i.product.id === b)).toBe(false);
  });
});

describe('orders', () => {
  it('ignores the client-supplied total (server recomputes from product prices)', async () => {
    const { token } = await registerAndLogin('order');
    const [id] = await firstProductIds(1);
    const product = (await api(`/product/get-by-id/${id}`)).body.product;
    const res = await api('/order/create', { body: { totalPrice: 0.01, items: [{ productId: id, quantity: 2 }] }, token });
    expect(res.status).toBe(200);
    expect(Number(res.body.order.totalPrice)).toBeCloseTo(Number(product.price) * 2, 2);
  });

  it('rejects an empty order and a non-positive quantity', async () => {
    const { token } = await registerAndLogin('order');
    expect((await api('/order/create', { body: { items: [] }, token })).status).toBe(400);
    const [id] = await firstProductIds(1);
    expect((await api('/order/create', { body: { items: [{ productId: id, quantity: 0 }] }, token })).status).toBe(400);
  });
});
