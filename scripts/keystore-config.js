'use strict';

/*
 * Resolves the release signing configuration the *same* way app/build.gradle.kts does,
 * so the npm build wrappers can fail fast (with actionable guidance) before Gradle is
 * even started when a release build is requested but no keystore is available.
 *
 * Per-field precedence (matching the Gradle `signingValue` helper):
 *   1. keystore.properties at the repo root
 *   2. environment variables
 *        ASTIMER_KEYSTORE_FILE / ASTIMER_KEYSTORE_PASSWORD / ASTIMER_KEY_ALIAS / ASTIMER_KEY_PASSWORD
 *   3. the decrypted local bundle (~/.astimer-keystore or $ASTIMER_KEYSTORE_DIR)
 *
 * Zero dependencies (Node built-ins only).
 */

const fs = require('fs');
const path = require('path');
const os = require('os');

/** Minimal .properties parser; returns {} for a missing/unreadable file. */
function parseProperties(file) {
  const props = {};
  let text;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch (e) {
    return props;
  }
  for (const line of text.split(/\r?\n/)) {
    const m = line.match(/^\s*([^#=\s][^=]*)=(.*)$/);
    if (m) props[m[1].trim()] = m[2].trim();
  }
  return props;
}

function nonBlank(value) {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

/**
 * @param {string} repoRoot Absolute path to the repository root.
 * @returns {{
 *   ok: boolean,
 *   storeFile: string|null,
 *   resolvedStoreFile: string|null,
 *   storeFileExists: boolean,
 *   missingFields: string[],
 *   localDir: string,
 *   localPropsPath: string,
 * }}
 */
function resolveSigning(repoRoot) {
  const repoProps = parseProperties(path.join(repoRoot, 'keystore.properties'));
  const localDir =
    process.env.ASTIMER_KEYSTORE_DIR || path.join(os.homedir(), '.astimer-keystore');
  const localPropsPath = path.join(localDir, 'keystore.properties');
  const localProps = parseProperties(localPropsPath);

  const pick = (propKey, envKey) =>
    nonBlank(repoProps[propKey]) ||
    nonBlank(process.env[envKey]) ||
    nonBlank(localProps[propKey]);

  const storeFile = pick('storeFile', 'ASTIMER_KEYSTORE_FILE');
  const storePassword = pick('storePassword', 'ASTIMER_KEYSTORE_PASSWORD');
  const keyAlias = pick('keyAlias', 'ASTIMER_KEY_ALIAS');
  const keyPassword = pick('keyPassword', 'ASTIMER_KEY_PASSWORD');

  // Gradle resolves storeFile via rootProject.file(...), i.e. relative to the repo root.
  let resolvedStoreFile = null;
  let storeFileExists = false;
  if (storeFile) {
    resolvedStoreFile = path.isAbsolute(storeFile)
      ? storeFile
      : path.join(repoRoot, storeFile);
    storeFileExists = fs.existsSync(resolvedStoreFile);
  }

  const missingFields = [];
  if (!storeFile) missingFields.push('storeFile');
  if (!storePassword) missingFields.push('storePassword');
  if (!keyAlias) missingFields.push('keyAlias');
  if (!keyPassword) missingFields.push('keyPassword');

  return {
    ok: missingFields.length === 0 && storeFileExists,
    storeFile,
    resolvedStoreFile,
    storeFileExists,
    missingFields,
    localDir,
    localPropsPath,
  };
}

/** Builds the actionable, multi-line error shown when a release build has no keystore. */
function missingKeystoreMessage(status) {
  const lines = [
    'ERROR: No release keystore found - cannot build a signed release.',
    '',
  ];

  if (status.storeFile && !status.storeFileExists) {
    lines.push('The configured keystore file does not exist:');
    lines.push(`  ${status.resolvedStoreFile}`);
  } else if (status.missingFields.length) {
    lines.push(`Missing required signing settings: ${status.missingFields.join(', ')}.`);
  }

  lines.push('');
  lines.push('Signing material is looked up (in order) from:');
  lines.push('  1. keystore.properties at the repo root');
  lines.push(
    '  2. env vars ASTIMER_KEYSTORE_FILE / ASTIMER_KEYSTORE_PASSWORD / ASTIMER_KEY_ALIAS / ASTIMER_KEY_PASSWORD'
  );
  lines.push(`  3. ${status.localPropsPath} (decrypted bundle)`);
  lines.push('');
  lines.push('You most likely just need to decrypt the signing bundle first. Run:');
  lines.push('');
  lines.push('    npm run decrypt-keystore');
  lines.push('');
  lines.push('and enter the keystore encryption passphrase when prompted, then re-run this build.');

  return lines.join('\n');
}

module.exports = { resolveSigning, missingKeystoreMessage, parseProperties };
