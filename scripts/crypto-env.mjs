#!/usr/bin/env node
/**
 * Encrypted .env workflow (shared across VIBES repos).
 *
 *   node scripts/crypto-env.mjs encrypt [encFile] [envFile]
 *     Reads the plaintext .env, AES-256-GCM encrypts it, writes credentials/env.enc
 *     (default), then publishes ONLY that file to origin/main via git plumbing
 *     (hash-object / read-tree / commit-tree / push). No pull, stash, or worktree.
 *     Afterwards, if you are on `main` and origin/main differs from your local
 *     main by nothing but credentials/env.enc, the local main ref is
 *     fast-forwarded and the identical file is adopted into the index so the
 *     working tree is left CLEAN (no lingering untracked env.enc, no "behind"
 *     branch). Any real local commits or other WIP are always left untouched.
 *
 *   node scripts/crypto-env.mjs publish
 *     Publish an existing credentials/env.enc to origin/main the same way (no re-encrypt).
 *
 *   node scripts/crypto-env.mjs decrypt [encFile] [envFile]
 *     Decrypts the bundle and writes/overwrites the plaintext .env.
 *
 * Passphrase: $STFUAI_ENV_PASSPHRASE if set, otherwise an interactive hidden prompt.
 * Paths: optional argv, else $STFUAI_ENC_FILE / $STFUAI_ENV_FILE, else repo defaults.
 * Repo root override (tests): $STFUAI_ENV_REPO_ROOT
 *
 * Zero dependencies (Node built-ins only). Adapted from auto-silent-timer's crypto-keystore.js.
 */

import { createCipheriv, createDecipheriv, randomBytes, scryptSync } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import process from 'node:process';

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(process.env.STFUAI_ENV_REPO_ROOT || join(__dirname, '..'));
const REL_ENC = join('credentials', 'env.enc');
const DEFAULT_ENC = join(repoRoot, REL_ENC);
const DEFAULT_ENV = join(repoRoot, '.env');
const ENC_GIT_PATH = 'credentials/env.enc';
const COMMIT_SUBJECT = 'chore: refresh encrypted env bundle';
const COMMIT_TRAILER = 'Changelog: none';

const SCRYPT = { N: 1 << 15, r: 8, p: 1, keylen: 32, maxmem: 256 * 1024 * 1024 };

function fail(msg) {
  console.error(`ERROR: ${msg}`);
  process.exit(1);
}

function deriveKey(passphrase, salt, params) {
  const p = params || SCRYPT;
  return scryptSync(Buffer.from(passphrase, 'utf8'), salt, SCRYPT.keylen, {
    N: p.N || SCRYPT.N,
    r: p.r || SCRYPT.r,
    p: p.p || SCRYPT.p,
    maxmem: SCRYPT.maxmem,
  });
}

let skipLineFeed = false;
function hiddenQuestion(query) {
  return new Promise((resolvePromise) => {
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
      resolvePromise(input);
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
  const fromEnv = process.env.STFUAI_ENV_PASSPHRASE;
  if (fromEnv && fromEnv.length) return fromEnv;
  if (!process.stdin.isTTY) {
    fail('No passphrase available. Set STFUAI_ENV_PASSPHRASE or run in an interactive terminal.');
  }
  const p1 = await hiddenQuestion('Env passphrase: ');
  if (!p1 || p1.length < 8) fail('Passphrase must be at least 8 characters.');
  if (confirm) {
    const p2 = await hiddenQuestion('Confirm passphrase: ');
    if (p1 !== p2) fail('Passphrases do not match.');
  }
  return p1;
}

function resolvePaths(encArg, envArg) {
  const encPath = resolve(encArg || process.env.STFUAI_ENC_FILE || DEFAULT_ENC);
  const envPath = resolve(envArg || process.env.STFUAI_ENV_FILE || DEFAULT_ENV);
  return { encPath, envPath };
}

function git(args, options = {}) {
  try {
    return execFileSync('git', args, {
      cwd: repoRoot,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      ...options,
    }).trim();
  } catch (error) {
    const stderr = error && error.stderr ? String(error.stderr).trim() : '';
    const stdout = error && error.stdout ? String(error.stdout).trim() : '';
    const detail = stderr || stdout || (error && error.message) || 'unknown git error';
    fail(`git ${args.join(' ')} failed: ${detail}`);
  }
}

function gitAllowFail(args, options = {}) {
  try {
    return {
      ok: true,
      out: execFileSync('git', args, {
        cwd: repoRoot,
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'pipe'],
        ...options,
      }).trim(),
    };
  } catch (error) {
    const stderr = error && error.stderr ? String(error.stderr).trim() : '';
    const stdout = error && error.stdout ? String(error.stdout).trim() : '';
    return { ok: false, out: stderr || stdout || '' };
  }
}

function isDefaultEncPath(encPath) {
  const rel = relative(repoRoot, encPath);
  return rel === REL_ENC || rel.split(sep).join('/') === ENC_GIT_PATH;
}

function shouldPublishAfterEncrypt(encPath) {
  if (process.env.STFUAI_ENV_SKIP_COMMIT === '1') return false;
  if (process.argv.includes('--no-commit')) return false;
  return isDefaultEncPath(encPath);
}

function shouldPushToOrigin() {
  if (process.env.STFUAI_ENV_SKIP_PUSH === '1') return false;
  if (process.argv.includes('--no-push')) return false;
  return true;
}

/**
 * Local-only pathspec commit used by --no-push / tests. Does not touch remotes.
 */
function commitEncryptedEnvLocally() {
  const branch = git(['branch', '--show-current']);
  if (branch !== 'main') {
    fail(
      `Local-only commit requires branch main (currently on '${branch || '(detached)'}'). ` +
        'Switch to main, or omit --no-push to publish via plumbing.',
    );
  }

  git(['add', '--', ENC_GIT_PATH]);
  const staged = git(['diff', '--cached', '--name-only', '--', ENC_GIT_PATH]);
  if (!staged) {
    console.log(`No changes in ${ENC_GIT_PATH}; nothing to commit.`);
    return;
  }

  git([
    'commit',
    '-m',
    COMMIT_SUBJECT,
    '-m',
    COMMIT_TRAILER,
    '--',
    ENC_GIT_PATH,
  ]);
  console.log(`Committed ${ENC_GIT_PATH} on local main (--no-push).`);
}

/**
 * Publish credentials/env.enc onto origin/main without pull/stash/worktree.
 * Builds a commit whose parent is origin/main and whose only file change is the
 * encrypted env, then pushes that commit ref to origin/main. Working tree and
 * local branch tip are left untouched.
 */
function publishEncryptedEnvToOriginMain() {
  const encAbs = join(repoRoot, ENC_GIT_PATH);
  if (!existsSync(encAbs)) {
    fail(`Expected ${ENC_GIT_PATH} to exist before publishing.`);
  }

  if (!shouldPushToOrigin()) {
    commitEncryptedEnvLocally();
    console.log('Skipped push (--no-push or STFUAI_ENV_SKIP_PUSH=1).');
    return;
  }

  git(['fetch', 'origin', 'main']);
  const parentSha = git(['rev-parse', 'origin/main']);

  const remoteBlobResult = gitAllowFail(['rev-parse', `origin/main:${ENC_GIT_PATH}`]);
  const remoteBlob = remoteBlobResult.ok ? remoteBlobResult.out : '';

  const blob = git(['hash-object', '-w', ENC_GIT_PATH]);
  if (remoteBlob && remoteBlob === blob) {
    console.log(`${ENC_GIT_PATH} on origin/main already matches local file; nothing to push.`);
    reconcileLocalMainAfterPublish(blob);
    return;
  }

  const indexPath = join(repoRoot, '.git', 'crypto-env-temp-index');
  rmSync(indexPath, { force: true });

  const indexEnv = { ...process.env, GIT_INDEX_FILE: indexPath };
  try {
    git(['read-tree', parentSha], { env: indexEnv });
    git(
      ['update-index', '--add', '--cacheinfo', `100644,${blob},${ENC_GIT_PATH}`],
      { env: indexEnv },
    );
    const tree = git(['write-tree'], { env: indexEnv });
    const commit = git([
      'commit-tree',
      tree,
      '-p',
      parentSha,
      '-m',
      COMMIT_SUBJECT,
      '-m',
      COMMIT_TRAILER,
    ]);
    git(['push', 'origin', `${commit}:refs/heads/main`]);
    console.log(`Pushed ${ENC_GIT_PATH} to origin/main (no PR).`);
  } finally {
    rmSync(indexPath, { force: true });
  }

  reconcileLocalMainAfterPublish(blob);
}

/**
 * Bring local `main` back in sync after the encrypted bundle lands on
 * origin/main, so the working tree is not left "dirty" by an
 * untracked-but-identical credentials/env.enc (and so `git pull` / sync is not
 * blocked by an untracked-file collision).
 *
 * This ONLY acts when it is provably safe:
 *   - the current branch is `main`, and
 *   - origin/main is strictly ahead of local main (local is an ancestor), and
 *   - the ONLY path differing between local main and origin/main is
 *     credentials/env.enc.
 * In that case it fast-forwards the local main ref and adopts the identical
 * env.enc blob into the index WITHOUT rewriting the working-tree file or
 * touching any other staged/unstaged/untracked WIP.
 *
 * In every other situation (not on main, real diverging commits, or other files
 * also differ) it deliberately leaves the local branch and working tree alone.
 */
function reconcileLocalMainAfterPublish(blob) {
  const branchResult = gitAllowFail(['branch', '--show-current']);
  const currentBranch = branchResult.ok ? branchResult.out : '';
  if (currentBranch !== 'main') {
    console.log('Local branch tip and other WIP were not modified.');
    return;
  }

  const localMain = git(['rev-parse', 'HEAD']);
  const originMain = git(['rev-parse', 'origin/main']);
  if (localMain === originMain) {
    console.log('Local main already matches origin/main; working tree is clean.');
    return;
  }

  const isAncestor = gitAllowFail(['merge-base', '--is-ancestor', localMain, originMain]);
  const changedPaths = git(['diff', '--name-only', localMain, originMain])
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
  const onlyEnvChanged = changedPaths.length === 1 && changedPaths[0] === ENC_GIT_PATH;

  if (!isAncestor.ok || !onlyEnvChanged) {
    console.log('Local branch tip and other WIP were not modified.');
    console.log(
      `origin/main now carries the bundle; run 'git pull --ff-only' on main when convenient.`,
    );
    return;
  }

  git(['update-ref', 'refs/heads/main', originMain, localMain]);
  git(['update-index', '--add', '--cacheinfo', `100644,${blob},${ENC_GIT_PATH}`]);
  console.log(`Local main fast-forwarded to ${originMain.slice(0, 7)}; working tree is clean.`);
}

async function encrypt(encPath, envPath) {
  if (!existsSync(envPath)) {
    fail(`.env not found at ${envPath}. Create it first, then encrypt.`);
  }

  const plaintext = readFileSync(envPath);
  if (plaintext.length === 0) fail(`.env at ${envPath} is empty; refusing to encrypt nothing.`);

  const passphrase = await getPassphrase(true);
  const salt = randomBytes(16);
  const iv = randomBytes(12);
  const key = deriveKey(passphrase, salt, SCRYPT);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
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

  mkdirSync(dirname(encPath), { recursive: true });
  writeFileSync(encPath, `${JSON.stringify(envelope, null, 2)}\n`, { encoding: 'utf8' });
  console.log(`Encrypted .env written: ${encPath}`);
  console.log('Plaintext .env was not modified.');
  console.log('Store the passphrase in your password manager.');

  if (shouldPublishAfterEncrypt(encPath)) {
    publishEncryptedEnvToOriginMain();
  } else {
    console.log('Skipped publish (custom path, --no-commit, or STFUAI_ENV_SKIP_COMMIT=1).');
  }
}

async function decrypt(encPath, envPath) {
  if (!existsSync(encPath)) {
    fail(`Encrypted file not found: ${encPath}`);
  }

  let envelope;
  try {
    envelope = JSON.parse(readFileSync(encPath, 'utf8'));
  } catch (e) {
    fail(`Could not parse encrypted file: ${e.message}`);
  }

  if (!envelope || envelope.cipher !== 'aes-256-gcm' || envelope.kdf !== 'scrypt') {
    fail('Encrypted file is missing expected cipher metadata.');
  }

  const salt = Buffer.from(envelope.salt, 'base64');
  const iv = Buffer.from(envelope.iv, 'base64');
  const tag = Buffer.from(envelope.tag, 'base64');
  const data = Buffer.from(envelope.data, 'base64');

  const passphrase = await getPassphrase(false);
  const key = deriveKey(passphrase, salt, envelope);

  let plaintext;
  try {
    const decipher = createDecipheriv('aes-256-gcm', key, iv);
    decipher.setAuthTag(tag);
    plaintext = Buffer.concat([decipher.update(data), decipher.final()]);
  } catch {
    fail('Decryption failed - wrong passphrase or corrupted file.');
  }

  mkdirSync(dirname(envPath), { recursive: true });
  const existed = existsSync(envPath);
  writeFileSync(envPath, plaintext);
  try {
    chmodSync(envPath, 0o600);
  } catch {
    /* chmod is best-effort on Windows */
  }

  console.log(
    existed
      ? `Overwrote existing .env at: ${envPath}`
      : `Wrote .env to: ${envPath}`,
  );
}

const cmd = process.argv[2];
const pathArgs = process.argv.slice(3).filter((arg) => !arg.startsWith('--'));
const { encPath, envPath } = resolvePaths(pathArgs[0], pathArgs[1]);

if (cmd === 'encrypt') {
  await encrypt(encPath, envPath);
} else if (cmd === 'decrypt') {
  await decrypt(encPath, envPath);
} else if (cmd === 'publish') {
  publishEncryptedEnvToOriginMain();
} else {
  console.error(
    'Usage: node scripts/crypto-env.mjs <encrypt|decrypt|publish> [encFile] [envFile] [--no-commit] [--no-push]',
  );
  process.exit(1);
}
