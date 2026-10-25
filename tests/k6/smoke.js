// SMOKE: 1 VU, a minimal journey. Run after every deploy.
import { check, sleep } from 'k6';
import http from 'k6/http';
import { BASE_URL, addToCart, createOrder, getAllProducts, getCart, getCategories, getProduct, login, register } from './lib/common.js';

export const options = {
  vus: 1,
  duration: __ENV.SMOKE_DURATION || '1m',
  thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<1000'], checks: ['rate>0.99'] },
};

export default function () {
  check(http.get(`${BASE_URL}/actuator/health`), { 'backend UP': (r) => r.status === 200 && r.json('status') === 'UP' });
  getCategories();
  getAllProducts();
  getProduct(1);

  const email = register('smoke');
  const token = email && login(email);
  if (token) {
    addToCart(token, 1);
    check(getCart(token), { 'cart has the item': (r) => r.json('cart.totalItems') >= 1 });
    check(createOrder(token, 1), { 'order id returned': (r) => !!r.json('order.id') });
  }
  sleep(1);
}
