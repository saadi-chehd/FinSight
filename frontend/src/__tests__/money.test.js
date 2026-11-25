import { describe, expect, it } from 'vitest';
import {
  FS1_SYNTHETIC_PRESETS,
  compareMoneyStrings,
  formatMoneyDisplay,
  getFs1PresetForSme,
  isSyntheticSme,
  subtractMoneyStrings,
  sumMoneyStrings,
  validateMoneyInput,
  validateOpeningParams,
} from '../utils/money';

describe('validateMoneyInput', () => {
  it('accepts valid integers, 2-decimal positives, negatives, and zero by default', () => {
    expect(validateMoneyInput('5000')).toEqual({
      valid: true,
      normalized: '5000.00',
      error: null,
    });
    expect(validateMoneyInput('5000.00')).toEqual({
      valid: true,
      normalized: '5000.00',
      error: null,
    });
    expect(validateMoneyInput('-4.70')).toEqual({
      valid: true,
      normalized: '-4.70',
      error: null,
    });
    expect(validateMoneyInput('0.00')).toEqual({
      valid: true,
      normalized: '0.00',
      error: null,
    });
  });

  it('rejects fractional minor units (> 2 decimal places), empty strings, and non-numeric strings', () => {
    const frac = validateMoneyInput('100.123');
    expect(frac.valid).toBe(false);
    expect(frac.normalized).toBeNull();
    expect(frac.error).toMatch(/2 decimal places/i);

    expect(validateMoneyInput('').valid).toBe(false);
    expect(validateMoneyInput('   ').valid).toBe(false);
    expect(validateMoneyInput('abc').valid).toBe(false);
  });

  it('enforces allowNegative and allowZero options', () => {
    const negRejected = validateMoneyInput('-4.70', { allowNegative: false });
    expect(negRejected.valid).toBe(false);
    expect(negRejected.error).toMatch(/negative/i);

    const zeroRejected = validateMoneyInput('0.00', { allowZero: false });
    expect(zeroRejected.valid).toBe(false);
    expect(zeroRejected.error).toMatch(/greater than 0\.00/i);
  });
});

describe('BigInt monetary arithmetic and display formatting', () => {
  it('avoids floating-point drift in sumMoneyStrings and subtractMoneyStrings', () => {
    expect(sumMoneyStrings(['0.10', '0.20'])).toBe('0.30');
    expect(sumMoneyStrings(['5000.00', '12000.00', '8000.00'])).toBe('25000.00');
    expect(sumMoneyStrings(['99999999999999.10', '0.20'])).toBe('99999999999999.30');

    expect(subtractMoneyStrings('0.30', '0.10')).toBe('0.20');
    expect(subtractMoneyStrings('10.00', '14.70')).toBe('-4.70');
  });

  it('compares exact money strings reliably', () => {
    expect(compareMoneyStrings('0.10', '0.20')).toBe(-1);
    expect(compareMoneyStrings('5000', '5000.00')).toBe(0);
    expect(compareMoneyStrings('100.01', '-500.00')).toBe(1);
  });

  it('formats positive, negative, signed, and empty amounts accurately', () => {
    expect(formatMoneyDisplay('5000.00', 'USD')).toBe('$5,000.00');
    expect(formatMoneyDisplay('-4.70', 'USD')).toBe('-$4.70');
    expect(formatMoneyDisplay('1250.50', 'EUR', { showSign: true })).toBe('+€1,250.50');
    expect(formatMoneyDisplay('0.00', 'GBP', { showSign: true })).toBe('£0.00');
    expect(formatMoneyDisplay('', 'USD')).toBe('—');
  });
});

describe('FS1 synthetic presets and opening parameter validation', () => {
  it('exposes exact FS1_SYNTHETIC_PRESETS values and helpers', () => {
    expect(FS1_SYNTHETIC_PRESETS['FS1-20261018-A'].openingAmount).toBe('5000.00');
    expect(FS1_SYNTHETIC_PRESETS['FS1-20261018-B'].openingAmount).toBe('12000.00');
    expect(FS1_SYNTHETIC_PRESETS['FS1-20261018-C'].openingAmount).toBe('8000.00');
    expect(FS1_SYNTHETIC_PRESETS['FS1-20261018-A'].openingDate).toBe('2024-01-01');
    expect(FS1_SYNTHETIC_PRESETS['FS1-20261018-A'].endDate).toBe('2025-12-31');

    expect(isSyntheticSme({ name: 'SYNTHETIC FS1-20261018-A Alpha' })).toBe(true);
    expect(isSyntheticSme({ name: 'Acme Bakery LLC' })).toBe(false);
    expect(getFs1PresetForSme({ name: 'SYNTHETIC FS1-20261018-B' })?.openingAmount).toBe('12000.00');
    expect(getFs1PresetForSme({ name: 'Acme Bakery LLC' })).toBeNull();
  });

  it('validates complete opening params and rejects fractional minor units', () => {
    const ok = validateOpeningParams({
      openingDate: '2024-01-01',
      openingAmount: '5000',
      currency: 'USD',
      endDate: '2025-12-31',
    });
    expect(ok.valid).toBe(true);
    expect(ok.normalized.openingAmount).toBe('5000.00');

    const bad = validateOpeningParams({
      openingDate: '2024-01-01',
      openingAmount: '5000.123',
      currency: 'USD',
      endDate: '2025-12-31',
    });
    expect(bad.valid).toBe(false);
    expect(bad.errors.openingAmount).toBeDefined();
  });
});
