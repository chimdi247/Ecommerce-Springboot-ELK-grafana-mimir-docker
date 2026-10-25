const PASSWORD = 'Password123!';

// Registers a fresh customer through the API and returns the credentials.
Cypress.Commands.add('apiRegister', (prefix = 'cy') => {
  const id = Math.random().toString(36).slice(2, 10);
  const user = { name: `${prefix} ${id}`, email: `${prefix}.${id}@e2e.local`, password: PASSWORD, phoneNumber: '5550100' };
  return cy.request('POST', `${Cypress.env('apiUrl')}/auth/register`, user).then(() => user);
});

// Logs in through the real login form.
Cypress.Commands.add('uiLogin', (user) => {
  cy.visit('/login');
  cy.get('input[name="email"]').type(user.email);
  cy.get('input[name="password"]').type(user.password);
  cy.contains('button', 'Login').click();
  cy.location('pathname', { timeout: 15000 }).should('eq', '/profile');
});

Cypress.Commands.add('authToken', () => cy.window().then((win) => win.localStorage.getItem('token')));
