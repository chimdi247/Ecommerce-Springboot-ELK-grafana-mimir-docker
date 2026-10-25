const { defineConfig } = require('cypress');

module.exports = defineConfig({
  e2e: {
    baseUrl: process.env.CYPRESS_baseUrl || 'http://localhost:3000',
    specPattern: 'cypress/e2e/**/*.cy.js',
    supportFile: 'cypress/support/e2e.js',
    video: false,
    screenshotsFolder: 'cypress/screenshots',
    defaultCommandTimeout: 12000,
    requestTimeout: 20000,
    retries: { runMode: 1, openMode: 0 },
    // The Stripe payment form lives in a cross-origin iframe; payments are completed through
    // Stripe's API with the test card instead of typing into the iframe (see 03-checkout-payment.cy.js).
    chromeWebSecurity: false,
  },
  env: {
    apiUrl: process.env.CYPRESS_apiUrl || 'http://localhost:3000/api',
    STRIPE_SECRET_KEY: process.env.CYPRESS_STRIPE_SECRET_KEY || process.env.STRIPE_SECRET_KEY || '',
  },
});
