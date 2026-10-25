/* global globalThis */
/**
 * Idempotency keys for payments.
 *
 * One key per order checkout session, kept in sessionStorage. Every request that tries to start the
 * payment of an order (page reload, double click, retry after a network error, React StrictMode's
 * double effect) sends the SAME key, so the backend/Stripe return the same PaymentIntent instead of
 * creating a second one. The server additionally refuses to create any payment for an order that is
 * already paid, so the key is one layer of defence, not the only one.
 */
const PREFIX = "idem:order:";

const randomKey = () => {
    if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
        return crypto.randomUUID();
    }
    // fallback for old browsers / non-secure contexts
    return "k-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 12);
};

export const getOrCreateIdempotencyKey = (orderId, storage = globalThis.sessionStorage) => {
    const name = PREFIX + orderId;
    let key = storage.getItem(name);
    if (!key) {
        key = randomKey();
        storage.setItem(name, key);
    }
    return key;
};

export const clearIdempotencyKey = (orderId, storage = globalThis.sessionStorage) => {
    storage.removeItem(PREFIX + orderId);
};
