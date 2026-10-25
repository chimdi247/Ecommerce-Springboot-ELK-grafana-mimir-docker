# Tests

| Suite | Tool | Command | Needs the stack? |
|---|---|---|---|
| Backend unit tests | JUnit 5 + Mockito | `cd backend && mvn test` | No (the `@SpringBootTest` context tests need MySQL) |
| Frontend unit tests | Vitest | `cd tests && npm i && npm run test:unit` | No |
| Integration (API) | Vitest | `npm run test:integration` | Yes |
| End-to-end | **Cypress** | `npm run test:e2e` (or `docker compose --profile tests run --rm cypress`) | Yes |
| Browser | **Selenium** | `npm run test:selenium` | Yes |
| Smoke | k6 | `npm run k6:smoke` | Yes |
| Load | k6 | `npm run k6:load` | Yes |
| Stress | k6 | `npm run k6:stress` | Yes |
| Spike | k6 | `npm run k6:spike` | Yes |
| Soak | k6 | `SOAK_DURATION=4h npm run k6:soak` | Yes |
| Breakpoint (capacity) | k6 | `npm run k6:breakpoint` | Yes (never production) |
| Rate-limit verification | k6 | `npm run k6:ratelimit` | Yes |
| Payment idempotency race | k6 | `npm run k6:payment-race` | Yes + Stripe keys |

Defaults target `http://localhost:3000` (frontend, which proxies `/api` to the backend); copy `.env.example` to override.

## Payment tests

`integration/payment-idempotency.test.js` and `cypress/e2e/03-checkout-payment.cy.js` exercise real Stripe **test-mode** payments:
they need the stack running with your Stripe test keys and `STRIPE_SECRET_KEY` in the test runner's environment (they skip otherwise).
They verify: same key -> same PaymentIntent, different key -> the open intent is reused, 8 concurrent requests -> ONE intent,
paying, refusing a second payment (HTTP 409) with the same and with a new key, exactly one receipt notification, and that
other users cannot see or pay your order. The Stripe card form is a cross-origin iframe, so the tests pay through Stripe's API
with the `pm_card_visa` test method, exactly what Stripe.js does after the customer types the card.

## Running against the rate limit

`GET /product/get-all` allows `RATE_LIMIT_GET_ALL_PER_WINDOW` (default 30) requests per client IP per minute. Everything shares one IP
when tests run from one machine, so: the integration and Cypress rate-limit specs run last, and the k6 load scripts avoid the
list route (they treat 429 as expected there). For heavy load tests set `RATE_LIMIT_GET_ALL_PER_WINDOW=100000` in `.env`.

## k6 results in Grafana

```bash
docker compose --profile tests run --rm k6 run -o experimental-prometheus-rw --tag testid=load-1 /scripts/load.js
```

Then open the **Ecommerce - k6 load tests** dashboard.

## Notes

* Selenium uses local headless Chrome by default. With `--profile tests` the `selenium` service uses host networking (Linux):
  `SELENIUM_REMOTE_URL=http://localhost:4444/wd/hub npm run test:selenium`.
* These suites were written but **not executed** in the authoring environment.
