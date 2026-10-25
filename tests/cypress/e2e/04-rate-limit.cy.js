// Runs last (alphabetical order): it exhausts the GET /product/get-all quota of this client.
describe('rate limiting on GET /product/get-all', () => {
  it('eventually answers 429 with Retry-After while other routes keep working', () => {
    const url = `${Cypress.env('apiUrl')}/product/get-all`;

    const hammer = (remaining) => {
      expect(remaining, 'a 429 within 400 requests').to.be.greaterThan(0);
      return cy.request({ url, failOnStatusCode: false }).then((res) => {
        if (res.status === 429) return res;
        return hammer(remaining - 1);
      });
    };

    hammer(400).then((res) => {
      expect(res.status).to.eq(429);
      expect(Number(res.headers['retry-after'])).to.be.greaterThan(0);
      expect(res.body.message).to.match(/too many requests/i);
    });

    cy.request(`${Cypress.env('apiUrl')}/category/get-all`).its('status').should('eq', 200);
  });
});
