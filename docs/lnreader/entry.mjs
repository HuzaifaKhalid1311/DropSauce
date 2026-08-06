import { load } from 'cheerio';
import { Parser } from 'htmlparser2';
import dayjs from 'dayjs';
import { gcm } from '@noble/ciphers/aes.js';
import { utf8ToBytes, bytesToUtf8 } from '@noble/ciphers/utils.js';

// urlencode: hand-written instead of the npm package, which pulls iconv-lite -> node's
// buffer/string_decoder. A WebView's TextDecoder already supports gbk/big5/shift_jis/euc-kr
// natively, so legacy-charset DECODE is fully covered with zero bytes of polyfill.
const hex = n => '%' + n.toString(16).toUpperCase().padStart(2, '0');

function encode(str, charset) {
  const s = String(str);
  // ponytail: TextEncoder is utf-8 only, so encoding TO a legacy charset falls back to utf-8.
  // Only affects sites that want a gbk-encoded search query; switch to a gbk table if one shows up.
  if (charset && !/^utf-?8$/i.test(charset)) return encodeURIComponent(s);
  let out = '';
  for (const b of new TextEncoder().encode(s)) {
    out += (b >= 0x30 && b <= 0x39) || (b >= 0x41 && b <= 0x5a) || (b >= 0x61 && b <= 0x7a) ||
      b === 0x2d || b === 0x5f || b === 0x2e || b === 0x7e ? String.fromCharCode(b) : hex(b);
  }
  return out;
}

function decode(str, charset) {
  const s = String(str).replace(/\+/g, ' ');
  const bytes = [];
  for (let i = 0; i < s.length; i++) {
    if (s[i] === '%' && i + 2 < s.length) {
      const v = parseInt(s.slice(i + 1, i + 3), 16);
      if (!isNaN(v)) { bytes.push(v); i += 2; continue; }
    }
    for (const b of new TextEncoder().encode(s[i])) bytes.push(b);
  }
  try {
    return new TextDecoder(charset || 'utf-8').decode(new Uint8Array(bytes));
  } catch {
    return new TextDecoder('utf-8').decode(new Uint8Array(bytes));
  }
}

globalThis.__lnlibs = {
  'cheerio': { load },
  'htmlparser2': { Parser },
  'dayjs': dayjs.default || dayjs,
  'urlencode': { encode, decode },
  '@libs/aes': { gcm },
  '@libs/utils': { utf8ToBytes, bytesToUtf8 },
};
