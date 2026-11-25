export const SUPPORTED_CURRENCIES = ['USD', 'EUR', 'GBP', 'TRY'];

const MAX_MAGNITUDE_CENTS = 9999999999999999999n; // 99999999999999999.99

const CURRENCY_SYMBOLS = {
  USD: '$',
  EUR: '€',
  GBP: '£',
  TRY: '₺',
};

/**
 * Documented synthetic exercise opening balances for FS1-20261018.
 * Must only be applied via explicit user action, never silently defaulted.
 */
export const FS1_SYNTHETIC_PRESETS = {
  'FS1-20261018-A': {
    key: 'FS1-20261018-A',
    openingDate: '2024-01-01',
    endDate: '2025-12-31',
    asOfDate: '2025-12-31',
    openingAmount: '5000.00',
    currency: 'USD',
  },
  'FS1-20261018-B': {
    key: 'FS1-20261018-B',
    openingDate: '2024-01-01',
    endDate: '2025-12-31',
    asOfDate: '2025-12-31',
    openingAmount: '12000.00',
    currency: 'USD',
  },
  'FS1-20261018-C': {
    key: 'FS1-20261018-C',
    openingDate: '2024-01-01',
    endDate: '2025-12-31',
    asOfDate: '2025-12-31',
    openingAmount: '8000.00',
    currency: 'USD',
  },
};

export function isSyntheticSme(smeOrName) {
  const name = typeof smeOrName === 'string' ? smeOrName : smeOrName?.name;
  return Boolean(name && name.toUpperCase().includes('SYNTHETIC'));
}

export function getFs1PresetForSme(sme) {
  if (!sme?.name) return null;
  const upper = sme.name.toUpperCase();
  for (const [key, preset] of Object.entries(FS1_SYNTHETIC_PRESETS)) {
    if (upper.includes(key)) {
      return preset;
    }
  }
  return null;
}

/**
 * Validates a user-entered monetary string with exact 2-decimal precision.
 * Rejects fractional minor units (> 2 decimal places) and floating-point drift.
 */
export function validateMoneyInput(raw, { allowNegative = true, allowZero = true } = {}) {
  const trimmed = String(raw ?? '').trim();
  if (!trimmed) {
    return { valid: false, normalized: null, error: 'Monetary amount is required.' };
  }

  const match = /^([+-])?(?:(\d+)(?:\.(\d*))?|\.(\d+))$/.exec(trimmed);
  if (!match) {
    return {
      valid: false,
      normalized: null,
      error: 'Enter a valid numeric monetary amount (e.g. 5000.00).',
    };
  }

  const sign = match[1] === '-' ? '-' : '';
  const intPartRaw = match[2] ?? '0';
  const fracRaw = match[3] ?? match[4] ?? '';

  if (fracRaw.length > 2) {
    return {
      valid: false,
      normalized: null,
      error: 'Fractional minor units are not allowed (maximum 2 decimal places).',
    };
  }

  const intPart = intPartRaw.replace(/^0+(?=\d)/, '') || '0';
  const fracPart = fracRaw.padEnd(2, '0');
  const cents = BigInt(intPart) * 100n + BigInt(fracPart);

  if (cents > MAX_MAGNITUDE_CENTS) {
    return {
      valid: false,
      normalized: null,
      error: 'Amount exceeds maximum supported magnitude (99999999999999999.99).',
    };
  }

  if (cents === 0n && !allowZero) {
    return {
      valid: false,
      normalized: null,
      error: 'Amount must be greater than 0.00.',
    };
  }

  if (sign === '-' && cents > 0n && !allowNegative) {
    return {
      valid: false,
      normalized: null,
      error: 'Amount cannot be negative.',
    };
  }

  const normalized = cents === 0n ? '0.00' : `${sign}${intPart}.${fracPart}`;
  return { valid: true, normalized, error: null };
}

export function parseMoneyToCents(amountStr) {
  const check = validateMoneyInput(amountStr, { allowNegative: true, allowZero: true });
  if (!check.valid) {
    throw new Error(check.error);
  }
  const normalized = check.normalized;
  const negative = normalized.startsWith('-');
  const unsigned = negative ? normalized.slice(1) : normalized;
  const [whole, frac] = unsigned.split('.');
  const cents = BigInt(whole) * 100n + BigInt(frac);
  return negative ? -cents : cents;
}

export function formatCentsToRaw(cents) {
  const negative = cents < 0n;
  const abs = negative ? -cents : cents;
  const whole = abs / 100n;
  const frac = (abs % 100n).toString().padStart(2, '0');
  return `${negative ? '-' : ''}${whole.toString()}.${frac}`;
}

export function sumMoneyStrings(values) {
  let total = 0n;
  for (const val of values) {
    if (val !== null && val !== undefined && val !== '') {
      total += parseMoneyToCents(val);
    }
  }
  return formatCentsToRaw(total);
}

export function subtractMoneyStrings(a, b) {
  return formatCentsToRaw(parseMoneyToCents(a) - parseMoneyToCents(b));
}

export function compareMoneyStrings(a, b) {
  const diff = parseMoneyToCents(a) - parseMoneyToCents(b);
  if (diff < 0n) return -1;
  if (diff > 0n) return 1;
  return 0;
}

/**
 * Formats an exact 2-decimal API string for UI display without floating-point conversion.
 */
export function formatMoneyDisplay(amountStr, currency = 'USD', { showSign = false } = {}) {
  if (amountStr === null || amountStr === undefined || amountStr === '') {
    return '—';
  }
  const check = validateMoneyInput(amountStr, { allowNegative: true, allowZero: true });
  if (!check.valid) {
    return String(amountStr);
  }
  const normalized = check.normalized;
  const isNegative = normalized.startsWith('-');
  const unsigned = isNegative ? normalized.slice(1) : normalized;
  const [whole, frac] = unsigned.split('.');
  const groupedWhole = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const symbol = CURRENCY_SYMBOLS[currency] || `${currency} `;
  const signPrefix = isNegative ? '-' : showSign && normalized !== '0.00' ? '+' : '';
  return `${signPrefix}${symbol}${groupedWhole}.${frac}`;
}

export function isValidIsoDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(value ?? ''))) {
    return false;
  }
  const [y, m, d] = value.split('-').map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d));
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === m - 1 && dt.getUTCDate() === d;
}

export function daysBetweenInclusive(startIso, endIso) {
  if (!isValidIsoDate(startIso) || !isValidIsoDate(endIso)) return null;
  const start = Date.parse(`${startIso}T00:00:00Z`);
  const end = Date.parse(`${endIso}T00:00:00Z`);
  return Math.floor((end - start) / 86400000) + 1;
}

export function validateOpeningParams({ openingDate, openingAmount, currency, endDate }) {
  const errors = {};
  if (!isValidIsoDate(openingDate)) {
    errors.openingDate = 'Valid opening date (YYYY-MM-DD) is required.';
  }
  if (!isValidIsoDate(endDate)) {
    errors.endDate = 'Valid end date (YYYY-MM-DD) is required.';
  } else if (isValidIsoDate(openingDate) && endDate < openingDate) {
    errors.endDate = 'End date must be on or after opening date.';
  }
  const moneyCheck = validateMoneyInput(openingAmount, { allowNegative: true, allowZero: true });
  if (!moneyCheck.valid) {
    errors.openingAmount = moneyCheck.error;
  }
  if (!SUPPORTED_CURRENCIES.includes(currency)) {
    errors.currency = `Currency must be one of ${SUPPORTED_CURRENCIES.join(', ')}.`;
  }
  const valid = Object.keys(errors).length === 0;
  return {
    valid,
    errors,
    normalized: valid
      ? {
          openingDate,
          openingAmount: moneyCheck.normalized,
          currency,
          endDate,
        }
      : null,
  };
}

export function exportCsv(filename, headers, rows) {
  const escapeCell = (cell) => {
    const str = String(cell ?? '');
    if (/[",\n\r]/.test(str)) {
      return `"${str.replace(/"/g, '""')}"`;
    }
    return str;
  };
  const lines = [headers.map(escapeCell).join(','), ...rows.map((r) => r.map(escapeCell).join(','))];
  const blob = new Blob([lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.setAttribute('download', filename);
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}
