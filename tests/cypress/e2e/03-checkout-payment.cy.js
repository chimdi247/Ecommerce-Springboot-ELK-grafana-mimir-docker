/**
 * Full purchase: add to cart -> checkout (Stripe Elements loads) -> pay -> success page -> receipt
 * notification -> and the same order can NOT be paid a second time.
 * Needs the stack running with Stripe TEST keys and CYPRESS_STRIPE_SECRET_KEY / STRIPE_SECRET_KEY set.
 */
describe('checkout & payment (Stripe test mode)', function () {
  before(function () {
    if (!Cypress.env('STRIPE_SECRET_KEY')) {
      this.skip();   // payments are not configured for the test runner
    }
  });

  it('pays an order once and refuses to pay it again', () => {
    cy.apiRegister('buyer').then((user) => {
      cy.uiLogin(user);

      cy.intercept('POST', '**/payment/create-intent').as('createIntent');

      // add the first product to the cart and go to checkout
      cy.visit('/');
      cy.get('.product-item', { timeout: 15000 }).first().contains('button', 'Add To Cart').click();
      cy.visit('/cart');
      cy.get('[data-testid="checkout-button"]').click();

      cy.location('pathname', { timeout: 15000 }).should('match', /^\/checkout\/\d+$/);
      cy.get('[data-testid="checkout-loading"]').should('not.exist');
      cy.get('[data-testid="checkout-error"]').should('not.exist');
      cy.get('[data-testid="payment-form"]', { timeout: 20000 }).should('be.visible');
      cy.get('[data-testid="order-summary"]').should('be.visible');

      cy.wait('@createIntent').then(({ request, response }) => {
        expect(response.statusCode).to.eq(200);
        const key = request.headers['idempotency-key'];
        expect(key, 'Idempotency-Key sent by the browser').to.match(/^[A-Za-z0-9_.:-]{8,128}$/);

        const { clientSecret, orderId } = response.body.paymentIntent;
        const intentId = clientSecret.split('_secret_')[0];

        // pay like the browser would, with Stripe's test card
        cy.request({
          method: 'POST',
          url: `https://api.stripe.com/v1/payment_intents/${intentId}/confirm`,
          form: true,
          headers: { Authorization: `Bearer ${Cypress.env('STRIPE_SECRET_KEY')}` },
          body: { payment_method: 'pm_card_visa' },
        }).its('body.status').should('eq', 'succeeded');

        // success page verifies server-side and finalizes the order
        cy.visit(`/payment/success?orderId=${orderId}`);
        cy.get('[data-testid="payment-success"]', { timeout: 30000 }).should('be.visible');
        cy.get('[data-testid="paid-amount"]').should('not.be.empty');

        // refreshing the page is harmless (idempotent confirmation)
        cy.reload();
        cy.get('[data-testid="payment-success"]', { timeout: 30000 }).should('be.visible');

        // the cart is emptied after a successful payment
        cy.window().then((win) => {
          const cart = JSON.parse(win.localStorage.getItem('cart') || '[]');
          expect(cart).to.have.length(0);
        });

        // the order is PAID: a second payment attempt (same key AND a new key) is refused with 409
        cy.authToken().then((token) => {
          [key, `retry-${Date.now()}-${Math.random().toString(36).slice(2)}`].forEach((k) => {
            cy.request({
              method: 'POST',
              url: `${Cypress.env('apiUrl')}/payment/create-intent`,
              headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': k },
              body: { orderId },
              failOnStatusCode: false,
            }).its('status').should('eq', 409);
          });
        });

        // visiting the checkout of a paid order goes straight to the receipt, no payment form
        cy.visit(`/checkout/${orderId}`);
        cy.get('[data-testid="payment-success"]', { timeout: 30000 }).should('be.visible');
        cy.get('[data-testid="payment-form"]').should('not.exist');

        // the receipt shows up in the notification inbox (e-mailed, or "failed" if SMTP is not configured)
        cy.visit('/notifications');
        cy.get('[data-testid="notification-list"] li', { timeout: 30000 }).should('have.length', 1)
          .and('contain.text', `order #${orderId}`);
      });
    });
  });
});
