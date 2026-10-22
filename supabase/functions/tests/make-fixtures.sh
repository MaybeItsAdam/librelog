#!/usr/bin/env bash
# Makes a stand-in App Store certificate chain for the tests: a root, an
# intermediate with Apple's WWDR marker OID, a leaf with the App Store
# signing OID, and a leaf without it. Test-only keys; nothing trusts them.
# The output is committed, so this only needs running to change it.
set -euo pipefail
cd "$(dirname "$0")/fixtures"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

cat >"$tmp/ext.cnf" <<'CNF'
[wwdr]
basicConstraints = critical, CA:true, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
1.2.840.113635.100.6.2.1 = ASN1:NULL
[signing]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature
1.2.840.113635.100.6.11.1 = ASN1:NULL
[plain]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature
CNF

openssl ecparam -name secp384r1 -genkey -noout -out "$tmp/root.key"
openssl req -new -x509 -key "$tmp/root.key" -subj "/CN=Test Root CA - G3/O=Not Apple" -days 7300 \
  -set_serial 1 -sha384 -addext "basicConstraints=critical,CA:true" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" -out root.pem

issue() { # name curve issuer-cert issuer-key ext hash
  openssl ecparam -name "$2" -genkey -noout -out "$tmp/$1.key"
  openssl req -new -key "$tmp/$1.key" -subj "/CN=Test $1/O=Not Apple" -out "$tmp/$1.csr"
  openssl x509 -req -in "$tmp/$1.csr" -CA "$3" -CAkey "$4" -set_serial "$RANDOM" -days 7000 \
    -"$6" -extfile "$tmp/ext.cnf" -extensions "$5" -out "$1.pem" 2>/dev/null
}
issue intermediate secp384r1 root.pem "$tmp/root.key" wwdr sha384
issue leaf prime256v1 intermediate.pem "$tmp/intermediate.key" signing sha384
issue plainleaf prime256v1 intermediate.pem "$tmp/intermediate.key" plain sha256
# The leaf keys sign test JWSs, so keep them (PKCS#8, as WebCrypto imports).
openssl pkcs8 -topk8 -nocrypt -in "$tmp/leaf.key" -out leaf.key
openssl pkcs8 -topk8 -nocrypt -in "$tmp/plainleaf.key" -out plainleaf.key
