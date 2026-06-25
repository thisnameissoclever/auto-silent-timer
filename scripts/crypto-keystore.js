#!/usr/bin/env node
'use strict';

/*
 * Encrypted keystore workflow for auto-silent-timer.
 *
 *   node scripts/crypto-keystore.js encrypt [encFile]
 *     Reads the working keystore + credentials (from keystore.properties at the repo root),
 *     bundles them, and writes an AES-256-GCM encrypted file (default: keystore/release-signing.enc).
 *     That .enc file is safe to keep on Google Drive or commit to git.
 *
 *   node scripts/crypto-keystore.js decrypt [encFile]
 *     Decrypts the bundle into a LOCAL, non-synced folder (~/.astimer-keystore by default, or
 *     $ASTIMER_KEYSTORE_DIR) as the keystore .jks + a keystore.properties. Gradle reads that
 *     location automatically (local fallback), so `npm run build-release-apk` then signs.
 *
 * Passphrase: taken from $ASTIMER_KEYSTORE_PASSPHRASE if set, otherwise prompted (hidden input).
 * Keep that passphrase in your password manager - it is the only secret you carry between machines.
 *
 * Zero dependencies (Node built-ins only).
 */

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');

const repoRoot = path.resolve(__dirname, '..');
const DEFAULT_ENC = path.join(repoRoot, 'keystore', 'release-signing.enc');
const KEYSTORE_NAME = 'auto-silent-timer-release.jks';
const OUT_DIR = process.env.ASTIMER_KEYSTORE_DIR || path.join(os.homedir(), '.astimer-keystore');

const SCRYPT = { N: 1 << 15, r: 8, p: 1, keylen: 32, maxmem: 256 * 1024 * 1024 };

function fail(msg) {
  console.error(`ERROR: ${msg}`);
  process.exit(1);
}

function deriveKey(passphrase, salt, params) {
  const p = params || SCRYPT;
  return crypto.scryptSync(Buffer.from(passphrase, 'utf8'), salt, SCRYPT.keylen, {
    N: p.N || SCRYPT.N,
    r: p.r || SCRYPT.r,
    p: p.p || SCRYPT.p,
    maxmem: SCRYPT.maxmem,
  });
}

// Hidden, no-echo passphrase prompt that works on Windows + *nix.
let skipLineFeed = false;
function hiddenQuestion(query) {
  return new Promise((resolve) => {
    const { stdin, stdout } = process;
    stdout.write(query);
    stdin.resume();
    if (stdin.setRawMode) stdin.setRawMode(true);
    let input = '';
    const finish = () => {
      if (stdin.setRawMode) stdin.setRawMode(false);
      stdin.pause();
      stdin.removeListener('data', onData);
      stdout.write('\n');
      resolve(input);
    };
    const onData = (chunk) => {
      const s = chunk.toString('utf8');
      for (const ch of s) {
        if (skipLineFeed && ch === '\n') {
          skipLineFeed = false;
          continue;
        }
        skipLineFeed = false;
        if (ch === '\r') {
          skipLineFeed = true;
          finish();
          return;
        }
        if (ch === '\n' || ch === '\u0004') {
          finish();
          return;
        }
        if (ch === '\u0003') {
          stdout.write('\n');
          process.exit(1);
        }
        if (ch === '\u0008' || ch === '\u007f') {
          if (input.length) {
            input = input.slice(0, -1);
            stdout.write('\b \b');
          }
          continue;
        }
        if (ch >= ' ') {
          input += ch;
          stdout.write('*');
        }
      }
    };
    stdin.on('data', onData);
  });
}

async function getPassphrase(confirm) {
  const fromEnv = process.env.ASTIMER_KEYSTORE_PASSPHRASE;
  if (fromEnv && fromEnv.length) return fromEnv;
  if (!process.stdin.isTTY) {
    fail('No passphrase available. Set ASTIMER_KEYSTORE_PASSPHRASE or run in an interactive terminal.');
  }
  const p1 = await hiddenQuestion('Keystore passphrase: ');
  if (!p1 || p1.length < 8) fail('Passphrase must be at least 8 characters.');
  if (confirm) {
    const p2 = await hiddenQuestion('Confirm passphrase: ');
    if (p1 !== p2) fail('Passphrases do not match.');
  }
  return p1;
}

function loadRepoProps() {
  const propsPath = path.join(repoRoot, 'keystore.properties');
  if (!fs.existsSync(propsPath)) {
    fail(
      'keystore.properties not found at the repo root. Set up release signing first ' +
        '(see keystore.properties.example), then run encrypt.'
    );
  }
  const props = {};
  for (const line of fs.readFileSync(propsPath, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([^#=\s][^=]*)=(.*)$/);
    if (m) props[m[1].trim()] = m[2].trim();
  }
  return props;
}

async function encrypt(encPath) {
  const props = loadRepoProps();
  const storeFileRef = props.storeFile || path.join('keystore', KEYSTORE_NAME);
  const jksPath = path.isAbsolute(storeFileRef) ? storeFileRef : path.join(repoRoot, storeFileRef);
  if (!fs.existsSync(jksPath)) fail(`Keystore file not found: ${jksPath}`);

  const bundle = {
    keystoreName: path.basename(jksPath) || KEYSTORE_NAME,
    jks: fs.readFileSync(jksPath).toString('base64'),
    storePassword: props.storePassword || '',
    keyAlias: props.keyAlias || '',
    keyPassword: props.keyPassword || props.storePassword || '',
  };

  const passphrase = await getPassphrase(true);
  const salt = crypto.randomBytes(16);
  const iv = crypto.randomBytes(12);
  const key = deriveKey(passphrase, salt, SCRYPT);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const plaintext = Buffer.from(JSON.stringify(bundle), 'utf8');
  const enc = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  const tag = cipher.getAuthTag();

  const envelope = {
    v: 1,
    cipher: 'aes-256-gcm',
    kdf: 'scrypt',
    N: SCRYPT.N,
    r: SCRYPT.r,
    p: SCRYPT.p,
    salt: salt.toString('base64'),
    iv: iv.toString('base64'),
    tag: tag.toString('base64'),
    data: enc.toString('base64'),
  };

  fs.mkdirSync(path.dirname(encPath), { recursive: true });
  fs.writeFileSync(encPath, JSON.stringify(envelope, null, 2) + '\n', { encoding: 'utf8' });
  console.log(`Encrypted signing bundle written: ${encPath}`);
  console.log('Safe to keep on Drive / commit to git. Store the passphrase in your password manager.');
}

async function decrypt(encPath) {
  if (!fs.existsSync(encPath)) fail(`Encrypted file not found: ${encPath}`);
  let envelope;
  try {
    envelope = JSON.parse(fs.readFileSync(encPath, 'utf8'));
  } catch (e) {
    fail(`Could not parse encrypted file: ${e.message}`);
  }

  const salt = Buffer.from(envelope.salt, 'base64');
  const iv = Buffer.from(envelope.iv, 'base64');
  const tag = Buffer.from(envelope.tag, 'base64');
  const data = Buffer.from(envelope.data, 'base64');

  const passphrase = await getPassphrase(false);
  const key = deriveKey(passphrase, salt, envelope);

  let bundle;
  try {
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
    decipher.setAuthTag(tag);
    const dec = Buffer.concat([decipher.update(data), decipher.final()]);
    bundle = JSON.parse(dec.toString('utf8'));
  } catch (e) {
    fail('Decryption failed - wrong passphrase or corrupted file.');
  }

  fs.mkdirSync(OUT_DIR, { recursive: true });
  const outJks = path.join(OUT_DIR, bundle.keystoreName || KEYSTORE_NAME);
  fs.writeFileSync(outJks, Buffer.from(bundle.jks, 'base64'));

  // Forward slashes: a .properties file treats backslash as an escape char.
  const propsBody =
    [
      `storeFile=${outJks.replace(/\\/g, '/')}`,
      `storePassword=${bundle.storePassword}`,
      `keyAlias=${bundle.keyAlias}`,
      `keyPassword=${bundle.keyPassword}`,
    ].join('\n') + '\n';
  fs.writeFileSync(path.join(OUT_DIR, 'keystore.properties'), propsBody, { encoding: 'utf8' });

  try {
    fs.chmodSync(outJks, 0o600);
    fs.chmodSync(path.join(OUT_DIR, 'keystore.properties'), 0o600);
  } catch (e) {
    /* chmod is best-effort (no-op on Windows) */
  }

  console.log(`Decrypted signing material to: ${OUT_DIR}`);
  console.log('Gradle reads this location automatically. Now run: npm run build-release-apk');
}

(async () => {
  const cmd = process.argv[2];
  const encArg = process.argv[3] || process.env.ASTIMER_ENC_FILE;
  const encPath = encArg ? path.resolve(encArg) : DEFAULT_ENC;

  if (cmd === 'encrypt') {
    await encrypt(encPath);
  } else if (cmd === 'decrypt') {
    await decrypt(encPath);
  } else {
    console.error('Usage: node scripts/crypto-keystore.js <encrypt|decrypt> [encFile]');
    process.exit(1);
  }
})();
