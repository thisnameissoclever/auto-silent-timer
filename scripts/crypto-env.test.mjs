#!/usr/bin/env node
/**
 * Portable crypto-env tests (Node built-in test runner; no Jest required).
 * Run: node --test ./scripts/crypto-env.test.mjs
 */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, readFile, rm, writeFile, access } from 'node:fs/promises';
import { constants as fsConstants, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const __dirname = dirname(fileURLToPath(import.meta.url));
const scriptPath = resolve(__dirname, 'crypto-env.mjs');
const TEST_PASSPHRASE = 'pickles';
const tempDirs = [];

test.afterEach(async () => {
  await Promise.all(tempDirs.map((dir) => rm(dir, { recursive: true, force: true })));
  tempDirs.length = 0;
});

function runCrypto(args, envExtra = {}) {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(process.execPath, [scriptPath, ...args], {
      shell: false,
      env: {
        ...process.env,
        STFUAI_ENV_PASSPHRASE: TEST_PASSPHRASE,
        STFUAI_ENV_SKIP_PUSH: '1',
        ...envExtra,
      },
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => { stdout += String(chunk); });
    child.stderr.on('data', (chunk) => { stderr += String(chunk); });
    child.on('error', rejectPromise);
    child.on('close', (code) => {
      resolvePromise({ code: code ?? -1, stdout, stderr });
    });
  });
}

function runGit(args, cwd) {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn('git', args, { cwd, shell: false });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => { stdout += String(chunk); });
    child.stderr.on('data', (chunk) => { stderr += String(chunk); });
    child.on('error', rejectPromise);
    child.on('close', (code) => {
      resolvePromise({ code: code ?? -1, stdout, stderr });
    });
  });
}

async function runGitOrThrow(args, cwd) {
  const result = await runGit(args, cwd);
  if (result.code !== 0) {
    throw new Error(`git ${args.join(' ')} failed: ${result.stderr || result.stdout}`);
  }
  return result;
}

async function createFixtureEnv(contents) {
  const dir = await mkdtemp(join(tmpdir(), 'crypto-env-'));
  tempDirs.push(dir);
  const envPath = join(dir, '.env');
  const encPath = join(dir, 'env.enc');
  await writeFile(envPath, contents, 'utf8');
  return { dir, envPath, encPath };
}

async function createMainRepoFixture(contents) {
  const dir = await mkdtemp(join(tmpdir(), 'crypto-env-repo-'));
  tempDirs.push(dir);
  await runGitOrThrow(['init', '-b', 'main'], dir);
  await runGitOrThrow(['config', 'user.email', 'crypto-env-test@example.com'], dir);
  await runGitOrThrow(['config', 'user.name', 'crypto-env-test'], dir);
  await mkdir(join(dir, 'credentials'), { recursive: true });
  await writeFile(join(dir, '.env'), contents, 'utf8');
  await writeFile(join(dir, 'README.md'), 'fixture\n', 'utf8');
  await runGitOrThrow(['add', 'README.md'], dir);
  await runGitOrThrow(['commit', '-m', 'chore: initial fixture'], dir);
  return {
    dir,
    envPath: join(dir, '.env'),
    encPath: join(dir, 'credentials', 'env.enc'),
  };
}

test('encrypts and decrypts a round-trip with passphrase pickles', async () => {
  const plaintext = 'SUPABASE_URL=https://example.supabase.co\nTEST_USER_EMAIL=test@example.com\n';
  const { envPath, encPath } = await createFixtureEnv(plaintext);

  const encryptResult = await runCrypto(['encrypt', encPath, envPath]);
  assert.equal(encryptResult.code, 0);
  assert.match(encryptResult.stdout, /Encrypted \.env written/);
  assert.match(encryptResult.stdout, /Plaintext \.env was not modified/);

  const envelopeRaw = await readFile(encPath, 'utf8');
  const envelope = JSON.parse(envelopeRaw);
  assert.equal(envelope.cipher, 'aes-256-gcm');
  assert.equal(envelope.kdf, 'scrypt');
  assert.ok(envelope.data);
  assert.equal(envelopeRaw.includes('SUPABASE_URL'), false);
  assert.equal(envelopeRaw.includes('test@example.com'), false);
  assert.equal(await readFile(envPath, 'utf8'), plaintext);

  await writeFile(envPath, 'STALE=1\n', 'utf8');
  const decryptResult = await runCrypto(['decrypt', encPath, envPath]);
  assert.equal(decryptResult.code, 0);
  assert.match(decryptResult.stdout, /Overwrote existing \.env/);
  assert.equal(await readFile(envPath, 'utf8'), plaintext);
});

test('overwrites an existing .env on decrypt without extra flags', async () => {
  const original = 'FOO=bar\nBAZ=qux\n';
  const { envPath, encPath } = await createFixtureEnv(original);
  assert.equal((await runCrypto(['encrypt', encPath, envPath])).code, 0);
  await writeFile(envPath, 'OLD=value\nSHOULD=be-gone\n', 'utf8');
  const decryptResult = await runCrypto(['decrypt', encPath, envPath]);
  assert.equal(decryptResult.code, 0);
  assert.equal(await readFile(envPath, 'utf8'), original);
});

test('writes a new .env when the plaintext file is missing', async () => {
  const plaintext = 'ONLY=fresh\n';
  const { envPath, encPath } = await createFixtureEnv(plaintext);
  assert.equal((await runCrypto(['encrypt', encPath, envPath])).code, 0);
  await rm(envPath, { force: true });
  await assert.rejects(() => access(envPath, fsConstants.F_OK));
  const decryptResult = await runCrypto(['decrypt', encPath, envPath]);
  assert.equal(decryptResult.code, 0);
  assert.match(decryptResult.stdout, /Wrote \.env to/);
  assert.equal(await readFile(envPath, 'utf8'), plaintext);
});

test('fails decrypt with the wrong passphrase', async () => {
  const { envPath, encPath } = await createFixtureEnv('SECRET=value\n');
  assert.equal((await runCrypto(['encrypt', encPath, envPath])).code, 0);
  const bad = await runCrypto(['decrypt', encPath, envPath], {
    STFUAI_ENV_PASSPHRASE: 'not-pickles',
  });
  assert.equal(bad.code, 1);
  assert.match(bad.stderr, /Decryption failed/);
});

test('fails encrypt when the plaintext .env is missing', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'crypto-env-missing-'));
  tempDirs.push(dir);
  const result = await runCrypto(['encrypt', join(dir, 'env.enc'), join(dir, '.env')]);
  assert.equal(result.code, 1);
  assert.match(result.stderr, /\.env not found/);
});

test('rejects unknown commands', async () => {
  const result = await runCrypto(['nope']);
  assert.equal(result.code, 1);
  assert.match(result.stderr, /Usage:/);
});

test('auto-commits only credentials/env.enc on local main with --no-push', async () => {
  const { dir, envPath, encPath } = await createMainRepoFixture('ALPHA=1\n');
  await writeFile(join(dir, 'noise.txt'), 'do-not-commit\n', 'utf8');

  const encryptResult = await runCrypto(['encrypt'], {
    STFUAI_ENV_REPO_ROOT: dir,
    STFUAI_ENV_SKIP_PUSH: '1',
  });
  assert.equal(encryptResult.code, 0);
  assert.match(encryptResult.stdout, new RegExp(`Encrypted \\.env written: ${encPath.replace(/\\/g, '\\\\')}`));
  assert.match(encryptResult.stdout, /Committed credentials\/env\.enc on local main/);
  assert.match(encryptResult.stdout, /Skipped push/);

  const log = await runGitOrThrow(['log', '-1', '--name-only', '--pretty=format:%s%n%b'], dir);
  assert.match(log.stdout, /chore: refresh encrypted env bundle/);
  assert.match(log.stdout, /Changelog: none/);
  assert.match(log.stdout, /credentials\/env\.enc/);
  assert.equal(log.stdout.includes('noise.txt'), false);
  assert.equal(log.stdout.includes('.env'), false);
  assert.equal(await readFile(envPath, 'utf8'), 'ALPHA=1\n');

  const status = await runGitOrThrow(['status', '--short'], dir);
  assert.match(status.stdout, /\?\? noise\.txt/);
});

test('refuses local-only commit when not on main', async () => {
  const { dir } = await createMainRepoFixture('BETA=2\n');
  await runGitOrThrow(['checkout', '-b', 'feature/not-main'], dir);
  const encryptResult = await runCrypto(['encrypt'], {
    STFUAI_ENV_REPO_ROOT: dir,
    STFUAI_ENV_SKIP_PUSH: '1',
  });
  assert.equal(encryptResult.code, 1);
  assert.match(encryptResult.stderr, /Local-only commit requires branch main/);
});

test('publishes only env.enc to origin/main while behind with dirty WIP', async () => {
  const root = await mkdtemp(join(tmpdir(), 'crypto-env-remote-'));
  tempDirs.push(root);
  const bare = join(root, 'bare.git');
  const seed = join(root, 'seed');
  const work = join(root, 'work');

  await runGitOrThrow(['init', '--bare', '-b', 'main', bare], root);
  await runGitOrThrow(['clone', bare, seed], root);
  await runGitOrThrow(['config', 'user.email', 'crypto-env-test@example.com'], seed);
  await runGitOrThrow(['config', 'user.name', 'crypto-env-test'], seed);
  await writeFile(join(seed, 'README.md'), 'seed-a\n', 'utf8');
  await runGitOrThrow(['add', 'README.md'], seed);
  await runGitOrThrow(['commit', '-m', 'chore: seed A'], seed);
  await runGitOrThrow(['push', 'origin', 'main'], seed);

  await runGitOrThrow(['clone', bare, work], root);
  await runGitOrThrow(['config', 'user.email', 'crypto-env-test@example.com'], work);
  await runGitOrThrow(['config', 'user.name', 'crypto-env-test'], work);

  await writeFile(join(seed, 'README.md'), 'seed-b-remote-ahead\n', 'utf8');
  await runGitOrThrow(['add', 'README.md'], seed);
  await runGitOrThrow(['commit', '-m', 'chore: seed B on remote'], seed);
  await runGitOrThrow(['push', 'origin', 'main'], seed);

  await writeFile(join(work, 'README.md'), 'local-wip-readme\n', 'utf8');
  await writeFile(join(work, 'noise.txt'), 'do-not-publish\n', 'utf8');
  await writeFile(join(work, '.env'), 'PUBLISH=1\n', 'utf8');
  const headBefore = (await runGitOrThrow(['rev-parse', 'HEAD'], work)).stdout.trim();

  const encryptResult = await runCrypto(['encrypt'], {
    STFUAI_ENV_REPO_ROOT: work,
    STFUAI_ENV_SKIP_PUSH: '0',
  });
  assert.equal(encryptResult.stderr, '');
  assert.equal(encryptResult.code, 0);
  assert.match(encryptResult.stdout, /Pushed credentials\/env\.enc to origin\/main/);
  assert.match(encryptResult.stdout, /Local branch tip and other WIP were not modified/);

  const headAfter = (await runGitOrThrow(['rev-parse', 'HEAD'], work)).stdout.trim();
  assert.equal(headAfter, headBefore);

  const status = await runGitOrThrow(['status', '--short'], work);
  assert.match(status.stdout, /README\.md/);
  assert.match(status.stdout, /\?\? noise\.txt/);
  assert.equal(await readFile(join(work, 'README.md'), 'utf8'), 'local-wip-readme\n');

  const verify = join(root, 'verify');
  await runGitOrThrow(['clone', bare, verify], root);
  assert.match(await readFile(join(verify, 'credentials', 'env.enc'), 'utf8'), /aes-256-gcm/);
  assert.equal(
    (await readFile(join(verify, 'README.md'), 'utf8')).replace(/\r\n/g, '\n'),
    'seed-b-remote-ahead\n',
  );
  assert.equal(existsSync(join(verify, 'noise.txt')), false);

  const remoteNames = (await runGitOrThrow(['ls-tree', '-r', '--name-only', 'HEAD'], verify)).stdout;
  assert.match(remoteNames, /credentials\/env\.enc/);
  assert.equal(remoteNames.includes('noise.txt'), false);
  assert.equal(remoteNames.includes('.env'), false);
});

test('fast-forwards local main and leaves a clean tree when in sync with origin', async () => {
  const root = await mkdtemp(join(tmpdir(), 'crypto-env-sync-'));
  tempDirs.push(root);
  const bare = join(root, 'bare.git');
  const seed = join(root, 'seed');
  const work = join(root, 'work');

  await runGitOrThrow(['init', '--bare', '-b', 'main', bare], root);
  await runGitOrThrow(['clone', bare, seed], root);
  await runGitOrThrow(['config', 'user.email', 'crypto-env-test@example.com'], seed);
  await runGitOrThrow(['config', 'user.name', 'crypto-env-test'], seed);
  await writeFile(join(seed, 'README.md'), 'seed\n', 'utf8');
  await runGitOrThrow(['add', 'README.md'], seed);
  await runGitOrThrow(['commit', '-m', 'chore: seed'], seed);
  await runGitOrThrow(['push', 'origin', 'main'], seed);

  await runGitOrThrow(['clone', bare, work], root);
  await runGitOrThrow(['config', 'user.email', 'crypto-env-test@example.com'], work);
  await runGitOrThrow(['config', 'user.name', 'crypto-env-test'], work);

  // Local main is fully in sync with origin/main, but there is unrelated WIP.
  await writeFile(join(work, 'README.md'), 'local-wip-readme\n', 'utf8');
  await writeFile(join(work, 'noise.txt'), 'keep-me\n', 'utf8');
  await writeFile(join(work, '.env'), 'PUBLISH=1\n', 'utf8');
  const headBefore = (await runGitOrThrow(['rev-parse', 'HEAD'], work)).stdout.trim();

  const encryptResult = await runCrypto(['encrypt'], {
    STFUAI_ENV_REPO_ROOT: work,
    STFUAI_ENV_SKIP_PUSH: '0',
  });
  assert.equal(encryptResult.stderr, '');
  assert.equal(encryptResult.code, 0);
  assert.match(encryptResult.stdout, /Pushed credentials\/env\.enc to origin\/main/);
  assert.match(encryptResult.stdout, /Local main fast-forwarded to [0-9a-f]{7}; working tree is clean/);

  // Local main advanced to the pushed commit (now in sync with origin/main).
  const headAfter = (await runGitOrThrow(['rev-parse', 'HEAD'], work)).stdout.trim();
  const originAfter = (await runGitOrThrow(['rev-parse', 'origin/main'], work)).stdout.trim();
  assert.notEqual(headAfter, headBefore);
  assert.equal(headAfter, originAfter);

  // env.enc is tracked and no longer shows as dirty; only the real WIP remains.
  const tracked = (await runGitOrThrow(['ls-files', 'credentials/env.enc'], work)).stdout;
  assert.match(tracked, /credentials\/env\.enc/);
  const status = await runGitOrThrow(['status', '--short'], work);
  assert.equal(status.stdout.includes('credentials/env.enc'), false);
  assert.match(status.stdout, /README\.md/);
  assert.match(status.stdout, /\?\? noise\.txt/);

  // WIP content is untouched.
  assert.equal(await readFile(join(work, 'README.md'), 'utf8'), 'local-wip-readme\n');
  assert.equal(await readFile(join(work, 'noise.txt'), 'utf8'), 'keep-me\n');
});
