import { defineConfig } from 'vitest/config';

// Pure-logic unit tests of the frontend helpers (no browser, no stack needed).
// Backend unit tests are JUnit: `cd backend && mvn test` (PaymentServiceImplTest, CachingProductServiceTest, ...).
export default defineConfig({ test: { include: ['unit/**/*.test.js'], environment: 'node' } });
