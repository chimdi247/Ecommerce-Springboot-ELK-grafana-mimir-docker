import { defineConfig } from 'vitest/config';

// API integration tests against the running docker compose stack.
export default defineConfig({
  test: {
    include: ['integration/**/*.test.js'],
    environment: 'node',
    testTimeout: 60_000,
    hookTimeout: 120_000,
    fileParallelism: false,   // order matters: the rate-limit test runs last
    sequence: { shuffle: false },
  },
});
