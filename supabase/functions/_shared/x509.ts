// Just enough DER and X.509 to check an App Store certificate chain with
// WebCrypto: no dependencies, so it runs the same in `deno test` and on the
// Edge Runtime.

export interface Tlv {
  tag: number;
  /** The whole element, tag and length included. */
  raw: Uint8Array;
  /** The contents only. */
  value: Uint8Array;
}

/** Reads one DER element at `offset`. */
export function readTlv(bytes: Uint8Array, offset = 0): Tlv & { end: number } {
  const tag = bytes[offset];
  let len = bytes[offset + 1];
  let head = 2;
  if (len === undefined || tag === undefined) throw new Error("truncated DER");
  if (len & 0x80) {
    const n = len & 0x7f;
    if (n === 0 || n > 4) throw new Error("unsupported DER length");
    len = 0;
    for (let i = 0; i < n; i++) len = len * 256 + bytes[offset + 2 + i];
    head += n;
  }
  const start = offset + head;
  const end = start + len;
  if (end > bytes.length) throw new Error("truncated DER");
  return { tag, raw: bytes.subarray(offset, end), value: bytes.subarray(start, end), end };
}

/** The elements inside a constructed element. */
export function children(value: Uint8Array): Tlv[] {
  const out: Tlv[] = [];
  let at = 0;
  while (at < value.length) {
    const t = readTlv(value, at);
    out.push(t);
    at = t.end;
  }
  return out;
}

/** Dotted form of a DER OBJECT IDENTIFIER's contents. */
export function oidString(value: Uint8Array): string {
  const parts: number[] = [];
  let n = 0;
  for (let i = 0; i < value.length; i++) {
    n = n * 128 + (value[i] & 0x7f);
    if (!(value[i] & 0x80)) {
      if (parts.length === 0) {
        const first = n < 80 ? Math.floor(n / 40) : 2;
        parts.push(first, n - first * 40);
      } else {
        parts.push(n);
      }
      n = 0;
    }
  }
  return parts.join(".");
}

function parseTime(t: Tlv): Date {
  const s = new TextDecoder().decode(t.value);
  // UTCTime YYMMDDHHMMSSZ, GeneralizedTime YYYYMMDDHHMMSSZ.
  const m = t.tag === 0x17
    ? /^(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})Z$/.exec(s)
    : /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})Z$/.exec(s);
  if (!m) throw new Error(`unsupported time ${s}`);
  let year = Number(m[1]);
  if (t.tag === 0x17) year += year < 50 ? 2000 : 1900;
  return new Date(Date.UTC(year, Number(m[2]) - 1, Number(m[3]), Number(m[4]), Number(m[5]), Number(m[6])));
}

export interface Certificate {
  der: Uint8Array;
  tbs: Uint8Array;
  signatureAlgorithm: string;
  signature: Uint8Array;
  issuer: Uint8Array;
  subject: Uint8Array;
  notBefore: Date;
  notAfter: Date;
  spki: Uint8Array;
  /** Named curve of an EC key: P-256 or P-384. */
  curve?: "P-256" | "P-384";
  extensionOids: string[];
}

const CURVES: Record<string, "P-256" | "P-384"> = {
  "1.2.840.10045.3.1.7": "P-256",
  "1.3.132.0.34": "P-384",
};

export function parseCertificate(der: Uint8Array): Certificate {
  const cert = readTlv(der);
  const [tbsT, algT, sigT] = children(cert.value);
  if (!tbsT || !algT || !sigT) throw new Error("not a certificate");
  let fields = children(tbsT.value);
  if (fields[0]?.tag === 0xa0) fields = fields.slice(1); // explicit version
  const [, , issuer, validity, subject, spki, ...rest] = fields;
  const [nb, na] = children(validity.value);
  const keyAlg = children(children(spki.value)[0].value);
  const curve = keyAlg[1]?.tag === 0x06 ? CURVES[oidString(keyAlg[1].value)] : undefined;
  const extensionOids: string[] = [];
  for (const r of rest) {
    if (r.tag !== 0xa3) continue;
    for (const ext of children(children(r.value)[0].value)) {
      extensionOids.push(oidString(children(ext.value)[0].value));
    }
  }
  // BIT STRING: first byte is the count of unused bits.
  return {
    der,
    tbs: tbsT.raw,
    signatureAlgorithm: oidString(children(algT.value)[0].value),
    signature: sigT.value.subarray(1),
    issuer: issuer.raw,
    subject: subject.raw,
    notBefore: parseTime(nb),
    notAfter: parseTime(na),
    spki: spki.raw,
    curve,
    extensionOids,
  };
}

/** An ECDSA signature in DER (SEQUENCE of two INTEGERs) as raw r‖s. */
export function ecdsaDerToRaw(der: Uint8Array, size: number): Uint8Array {
  const [r, s] = children(readTlv(der).value);
  const out = new Uint8Array(size * 2);
  const put = (int: Uint8Array, at: number) => {
    let v = int;
    while (v.length > size && v[0] === 0) v = v.subarray(1);
    if (v.length > size) throw new Error("bad ECDSA integer");
    out.set(v, at + size - v.length);
  };
  put(r.value, 0);
  put(s.value, size);
  return out;
}

const HASHES: Record<string, string> = {
  "1.2.840.10045.4.3.2": "SHA-256",
  "1.2.840.10045.4.3.3": "SHA-384",
};

export async function importEcKey(cert: Certificate): Promise<CryptoKey> {
  if (!cert.curve) throw new Error("certificate key is not a supported EC key");
  return await crypto.subtle.importKey(
    "spki",
    cert.spki as Uint8Array<ArrayBuffer>,
    { name: "ECDSA", namedCurve: cert.curve },
    false,
    ["verify"],
  );
}

/** True if `issuer`'s key signed `cert`. */
export async function signedBy(cert: Certificate, issuer: Certificate): Promise<boolean> {
  const hash = HASHES[cert.signatureAlgorithm];
  if (!hash) throw new Error(`unsupported signature algorithm ${cert.signatureAlgorithm}`);
  if (!equalBytes(cert.issuer, issuer.subject)) return false;
  const key = await importEcKey(issuer);
  const size = issuer.curve === "P-384" ? 48 : 32;
  return await crypto.subtle.verify(
    { name: "ECDSA", hash },
    key,
    ecdsaDerToRaw(cert.signature, size) as Uint8Array<ArrayBuffer>,
    cert.tbs as Uint8Array<ArrayBuffer>,
  );
}

export function equalBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

export function fromBase64(s: string): Uint8Array {
  const clean = s.replace(/-/g, "+").replace(/_/g, "/").replace(/[\s=]/g, "");
  const bin = atob(clean + "===".slice((clean.length + 3) % 4));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function toBase64Url(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
