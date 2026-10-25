import { Builder, By, until } from 'selenium-webdriver';
import chrome from 'selenium-webdriver/chrome.js';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const BASE = process.env.FRONTEND_URL ?? 'http://localhost:3000';
const API = process.env.API_URL ?? `${BASE}/api`;
const uid = () => Math.random().toString(36).slice(2, 10);
let driver;

beforeAll(async () => {
  const options = new chrome.Options().addArguments('--headless=new', '--no-sandbox', '--disable-dev-shm-usage', '--window-size=1400,900');
  const builder = new Builder().forBrowser('chrome').setChromeOptions(options);
  if (process.env.SELENIUM_REMOTE_URL) builder.usingServer(process.env.SELENIUM_REMOTE_URL);
  driver = await builder.build();
});

afterAll(async () => { await driver?.quit(); });

const register = async (id) => {
  const user = { name: `sel ${id}`, email: `sel.${id}@e2e.local`, password: 'Password123!', phoneNumber: '5550100' };
  const res = await fetch(`${API}/auth/register`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(user) });
  expect(res.status).toBe(200);
  return user;
};

describe('Selenium: storefront', () => {
  it('lists the seeded products', async () => {
    await driver.get(BASE);
    const items = await driver.wait(until.elementsLocated(By.css('.product-item')), 20000);
    expect(items.length).toBeGreaterThan(0);
  });

  it('logs in, adds a product to the cart and reaches the checkout button', async () => {
    const user = await register(uid());

    await driver.get(`${BASE}/login`);
    await driver.findElement(By.css('input[name="email"]')).sendKeys(user.email);
    await driver.findElement(By.css('input[name="password"]')).sendKeys(user.password);
    await driver.findElement(By.xpath('//button[normalize-space()="Login"]')).click();
    await driver.wait(until.urlContains('/profile'), 20000);

    await driver.get(BASE);
    await driver.wait(until.elementsLocated(By.css('.product-item')), 20000);
    await driver.findElement(By.xpath('(//div[contains(@class,"product-item")]//button[normalize-space()="Add To Cart"])[1]')).click();

    await driver.get(`${BASE}/cart`);
    const checkout = await driver.wait(until.elementLocated(By.css('[data-testid="checkout-button"]')), 15000);
    expect(await checkout.isDisplayed()).toBe(true);
  });

  it('shows the login page to anonymous users on a protected route', async () => {
    await driver.get(`${BASE}/notifications`);
    await driver.wait(until.urlContains('/login'), 15000);
  });
});
