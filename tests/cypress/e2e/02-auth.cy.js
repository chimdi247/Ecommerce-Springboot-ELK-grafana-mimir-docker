describe('authentication', () => {
  it('registers through the UI and can then log in', () => {
    const id = Math.random().toString(36).slice(2, 10);
    const user = { name: `ui ${id}`, email: `ui.${id}@e2e.local`, password: 'Password123!' };

    cy.visit('/register');
    cy.get('input[name="email"]').type(user.email);
    cy.get('input[name="name"]').type(user.name);
    cy.get('input[name="phoneNumber"]').type('5550100');
    cy.get('input[name="password"]').type(user.password);
    cy.contains('button', 'Register').click();
    cy.location('pathname', { timeout: 15000 }).should('eq', '/login');

    cy.uiLogin(user);
    cy.authToken().should('be.a', 'string').and('not.be.empty');
  });

  it('redirects anonymous users away from protected pages', () => {
    cy.visit('/notifications');
    cy.location('pathname').should('eq', '/login');
  });
});
