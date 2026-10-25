import { randomBytes } from 'node:crypto';

export const API = process.env.API_URL ?? 'http://localhost:3000/api';
export const STRIPE_SECRET_KEY = process.env.STRIPE_SECRET_KEY ?? '';
export const PASSWORD = 'Password123!';

export const uid = () => randomBytes(5).toString('hex');
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export const api = async (path, { method, body, token, headers = {} } = {}) => {
  const res = await fetch(`${API}${path}`, {
    method: method ?? (body ? 'POST' : 'GET'),
    headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}), ...headers },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json;
  try { json = text ? JSON.parse(text) : undefined; } catch { json = text; }
  return { status: res.status, body: json, headers: res.headers };
};

export const registerAndLogin = async (prefix = 'it') => {
  const id = uid();
  const email = `${prefix}.${id}@integration.local`;
  const reg = await api('/auth/register', { body: { name: `${prefix} ${id}`, email, password: PASSWORD, phoneNumber: '5550100' } });
  if (reg.status !== 200) throw new Error(`register failed ${reg.status} ${JSON.stringify(reg.body)}`);
  const login = await api('/auth/login', { body: { email, password: PASSWORD } });
  if (login.status !== 200) throw new Error(`login failed ${login.status}`);
  return { email, token: login.body.token };
};

/** Product ids from the catalog (uses the cached by-id route, never the rate-limited list). */
export const firstProductIds = async (count = 2) => {
  const ids = [];
  for (let id = 1; ids.length < count && id < 60; id += 1) {
    const res = await api(`/product/get-by-id/${id}`);
    if (res.status === 200) ids.push(id);
  }
  if (ids.length < count) throw new Error('catalog is empty: start the stack with APP_SEED_ENABLED=true');
  return ids;
};

export const createOrder = async (token, items) => {
  const res = await api('/order/create', { body: { totalPrice: 0.01, items }, token });   // client total is ignored by the server
  if (res.status !== 200 || !res.body.order?.id) throw new Error(`order failed ${res.status} ${JSON.stringify(res.body)}`);
  return res.body.order;
};

export const stripePaymentsConfigured = async () => {
  const cfg = await api('/payment/config');
  return cfg.status === 200 && STRIPE_SECRET_KEY.startsWith('sk_');
};

/** Pays a PaymentIntent the way the browser would, using Stripe's test card. */
export const payWithTestCard = async (clientSecret) => {
  const intentId = clientSecret.split('_secret_')[0];
  const res = await fetch(`https://api.stripe.com/v1/payment_intents/${intentId}/confirm`, {
    method: 'POST',
    headers: { authorization: `Bearer ${STRIPE_SECRET_KEY}`, 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ payment_method: 'pm_card_visa' }),
  });
  return res.json();
};
