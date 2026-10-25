import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:3000/api';
export const PASSWORD = 'Password123!';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

// 4xx/5xx count as failures (http_req_failed) unless a request opts out via `responseCallback`.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }));
export const rateLimited = new Counter('rate_limited_responses');

export const uid = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
const named = (name) => ({ tags: { name } });
export const authHeaders = (token) => ({ headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } });

export function register(prefix = 'k6') {
  const id = uid();
  const email = `${prefix}.${id}@loadtest.local`;
  const res = http.post(`${BASE_URL}/auth/register`,
    JSON.stringify({ name: `${prefix} ${id}`, email, password: PASSWORD, phoneNumber: '5550100' }),
    { headers: JSON_HEADERS, ...named('POST /auth/register') });
  check(res, { 'register 200': (r) => r.status === 200 });
  return res.status === 200 ? email : null;
}

export function login(email) {
  const res = http.post(`${BASE_URL}/auth/login`, JSON.stringify({ email, password: PASSWORD }),
    { headers: JSON_HEADERS, ...named('POST /auth/login') });
  check(res, { 'login 200': (r) => r.status === 200 });
  return res.status === 200 ? res.json('token') : null;
}

export function getProduct(id) {
  const res = http.get(`${BASE_URL}/product/get-by-id/${id}`, named('GET /product/get-by-id/:id'));
  check(res, { 'product 200': (r) => r.status === 200 });
  return res;
}

/** The list route is rate limited per IP: 429 is the EXPECTED answer once the quota is used up. */
export function getAllProducts() {
  const res = http.get(`${BASE_URL}/product/get-all`, {
    ...named('GET /product/get-all'),
    responseCallback: http.expectedStatuses({ min: 200, max: 399 }, 429),
  });
  if (res.status === 429) rateLimited.add(1);
  check(res, { 'list 200 or 429': (r) => r.status === 200 || r.status === 429 });
  return res;
}

export function getCategories() {
  const res = http.get(`${BASE_URL}/category/get-all`, named('GET /category/get-all'));
  check(res, { 'categories 200': (r) => r.status === 200 });
  return res;
}

export function addToCart(token, productId, quantity = 1) {
  const res = http.post(`${BASE_URL}/cart/items`, JSON.stringify({ productId, quantity }),
    { ...authHeaders(token), ...named('POST /cart/items') });
  check(res, { 'cart add 200': (r) => r.status === 200 });
  return res;
}

export function getCart(token) {
  const res = http.get(`${BASE_URL}/cart`, { ...authHeaders(token), ...named('GET /cart') });
  check(res, { 'cart 200': (r) => r.status === 200 });
  return res;
}

export function createOrder(token, productId, quantity = 1) {
  const res = http.post(`${BASE_URL}/order/create`, JSON.stringify({ totalPrice: 1, items: [{ productId, quantity }] }),
    { ...authHeaders(token), ...named('POST /order/create') });
  check(res, { 'order 200': (r) => r.status === 200 });
  return res;
}

/** Discovers the catalog by id (cached lookups, never the rate-limited list) and prepares users. */
export function prepare(userCount = 5) {
  const productIds = [];
  for (let id = 1; id <= 60 && productIds.length < 12; id += 1) {
    const res = http.get(`${BASE_URL}/product/get-by-id/${id}`, { responseCallback: http.expectedStatuses(200, 404) });
    if (res.status === 200) productIds.push(id);
  }
  if (productIds.length === 0) fail('no products: start the stack with APP_SEED_ENABLED=true');

  const users = [];
  for (let i = 0; i < userCount; i += 1) {
    const email = register('fixture');
    const token = email && login(email);
    if (token) users.push({ email, token });
  }
  if (users.length === 0) fail('could not create fixture users');
  return { productIds, users };
}

/** Realistic shopper iteration: reads dominate, some cart writes, rare list/login/register. */
export function shopperIteration(data) {
  const productId = data.productIds[Math.floor(Math.random() * data.productIds.length)];
  const user = data.users[Math.floor(Math.random() * data.users.length)];
  const roll = Math.random();

  if (roll < 0.01) { register('iter'); return; }
  if (roll < 0.03) { login(user.email); return; }

  getProduct(productId);
  if (roll < 0.20) getCategories();
  if (roll < 0.25) getAllProducts();
  if (roll < 0.55) addToCart(user.token, productId);
  if (roll < 0.65) getCart(user.token);
  if (roll < 0.70) createOrder(user.token, productId);
}
