describe('smoke', () => {
  it('serves the SPA and the nginx health endpoint', () => {
    cy.request('/nginx-health').its('status').should('eq', 200);
    cy.visit('/');
    cy.get('body').should('be.visible');
  });

  it('backend is UP behind the /api proxy', () => {
    cy.request(`${Cypress.env('apiUrl')}/actuator/health`).its('body.status').should('eq', 'UP');
  });

  it('shows the product catalog (seeded demo data)', () => {
    cy.visit('/');
    cy.get('.product-item', { timeout: 15000 }).should('have.length.greaterThan', 0);
  });
});
