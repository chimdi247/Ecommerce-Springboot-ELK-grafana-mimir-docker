# Ecommerce Platform
## Clone the repository and check out the branch corresponding to the technology you are familiar with to view the source code

<img width="1382" alt="home" src="https://github.com/user-attachments/assets/cee35157-33ac-472c-9137-7021ae168a9b">

---

# Run everything with Docker (React + Spring Boot + MySQL + Redis + observability)

```bash
cp .env.example .env        # then edit: Stripe test keys, Gmail address + App Password, passwords
docker compose up --build
```

| What | URL | Login |
|---|---|---|
| Store (React) | http://localhost:3000 | register, or `customer@example.com` / `password123` (demo seed) |
| Admin | same app | `admin@example.com` / `password123` (demo seed) |
| Backend API | http://localhost:2424 (also `/api/...` through the frontend) | |
| Grafana (metrics, dashboards, alerts) | http://localhost:3001 | `admin` / `admin123` |
| Kibana (structured logs) | http://localhost:5601 | - |
| MinIO console (Mimir's S3 bucket) | http://localhost:9001 | `minioadmin` / `minioadmin123` |

The demo seed (`APP_SEED_ENABLED=true`, default in `.env.example`) creates the two users above and 12 products with
remote images, so the store works without an AWS S3 bucket. Set it to `false` in any real environment.

## What was added

**Payments (Stripe, test mode first)** - `POST /order/create` (amount recomputed server-side) -> `/checkout/:orderId`
(Stripe Elements) -> `/payment/success`. Endpoints: `GET /payment/config`, `POST /payment/create-intent`
(requires an `Idempotency-Key` header), `POST /payment/confirm/{orderId}`, `GET /payment/status/{orderId}`,
`POST /payment/webhook` (optional). An order can never be paid twice: row lock + `PAID` state guard + one payment
per order (unique constraint) + the idempotency key (also sent to Stripe) + idempotent finalization.

**Cart** - persistent per-user cart (`Cart`, `CartItem` entities; `/cart` API). The guest (localStorage) cart is merged
on login (idempotent).

**E-mail** - after a successful payment (after the DB commit, asynchronously) a Thymeleaf HTML receipt is sent over
Gmail SMTP and recorded in `Notification` (`/notification/my`, `/notification/unread-count`, admin list).

**Redis** - cache-aside product lookups (`X-Cache: HIT|MISS` header) and a Redis-backed rate limit on
`GET /product/get-all` (HTTP 429 + `Retry-After`).

**Observability** - see [`observability/README.md`](observability/README.md). **Tests** - see [`tests/README.md`](tests/README.md).

## Things you should know

* **Security issues found in the original code (not all changed):**
  * `application.properties` contained a database password and AWS access keys. They are now read from environment
    variables; **treat the old values as compromised and rotate them**.
  * `POST /auth/register` lets anyone create an `admin` account by sending `"role": "admin"` - **not changed**; fix before
    going live.
  * The client used to decide the order total. The server now always computes it from product prices.
  * `/order/**` was public; it now requires authentication (admin routes keep `@PreAuthorize`).
  * `registerUser` printed the saved user (including the password hash) to stdout; removed.
* The Angular app in `./angular` is untouched and not part of the compose stack (React is the frontend).
* **Nothing here was compiled or run** (the authoring environment had no Maven, npm registry or Docker). Java was
  syntax-checked with javac only, the logic was reasoned through and covered by new JUnit tests you can run with
  `cd backend && mvn test`. Expect small first-run fixes; start with `docker compose up --build` and read the logs.
* `react/package-lock.json` is out of date (Stripe packages added). The image builds with `npm install`; run
  `npm install` in `./react` once and commit the lock file.
* `backend` tests that load the full Spring context (`@SpringBootTest`) need a reachable MySQL, as before.

