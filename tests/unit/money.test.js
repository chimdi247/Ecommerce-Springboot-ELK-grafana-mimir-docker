import { describe, expect, it } from 'vitest';
import { formatMoney } from '../../react/src/service/money.js';

describe('formatMoney', () => {
  it('formats USD', () => expect(formatMoney(19.99, 'usd')).toBe('$19.99'));
  it('treats missing amounts as zero', () => expect(formatMoney(undefined, 'usd')).toBe('$0.00'));
  it('does not throw on an unknown currency code', () => expect(formatMoney(5, 'zzzz')).toContain('5.00'));
});
