#!/usr/bin/env node
'use strict';

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { resolveSigning, missingKeystoreMessage } = require('./keystore-config');

const repoRoot = path.resolve(__dirname, '..');

// Tasks that produce/install a *release* artifact and therefore need the signing
// keystore. Debug builds, clean and lint do not, so they are never blocked.
const RELEASE_SIGNING_TASK = /(assemble|bundle|install|package|publish).*release/i;

function requiresReleaseSigning(tasks) {
  return tasks.some((t) => RELEASE_SIGNING_TASK.test(t));
}

function javaBinExists(javaHome) {
  if (!javaHome) return false;
  const javaExe = process.platform === 'win32' ? 'java.exe' : 'java';
  return fs.existsSync(path.join(javaHome, 'bin', javaExe));
}

function javaMajorVersion(javaHome) {
  if (!javaHome) return null;
  try {
    const release = fs.readFileSync(path.join(javaHome, 'release'), 'utf8');
    const match = release.match(/^JAVA_VERSION="(\d+)/m);
    return match ? Number.parseInt(match[1], 10) : null;
  } catch {
    return null;
  }
}

function isSupportedGradleJavaHome(javaHome) {
  if (!javaBinExists(javaHome)) return false;
  const major = javaMajorVersion(javaHome);
  return major === null || (major >= 17 && major <= 23);
}

function resolveJavaHome() {
  if (process.env.JAVA_HOME && isSupportedGradleJavaHome(process.env.JAVA_HOME)) {
    return process.env.JAVA_HOME;
  }

  const home = os.homedir();
  let candidates = [];

  if (process.platform === 'win32') {
    const programFiles = process.env.ProgramFiles || 'C:\\Program Files';
    const localAppData = process.env.LOCALAPPDATA || path.join(home, 'AppData', 'Local');
    candidates = [
      path.join(programFiles, 'Android', 'Android Studio', 'jbr'),
      path.join(programFiles, 'Android', 'Android Studio Preview', 'jbr'),
      path.join(localAppData, 'Programs', 'Android Studio', 'jbr'),
    ];
  } else if (process.platform === 'darwin') {
    candidates = [
      '/Applications/Android Studio.app/Contents/jbr/Contents/Home',
      path.join(home, 'Applications', 'Android Studio.app', 'Contents', 'jbr', 'Contents', 'Home'),
    ];
  } else {
    candidates = [
      '/opt/android-studio/jbr',
      path.join(home, 'android-studio', 'jbr'),
    ];
  }

  for (const candidate of candidates) {
    if (isSupportedGradleJavaHome(candidate)) return candidate;
  }

  return null;
}

function javaOnPath() {
  const result = spawnSync('java', ['-version'], { stdio: 'ignore' });
  return !result.error && result.status === 0;
}

const tasks = process.argv.slice(2);

// Fail fast (before spinning up Gradle) when a release/signing build is requested
// but no usable keystore can be resolved, pointing the user at the decrypt step.
if (requiresReleaseSigning(tasks)) {
  const signing = resolveSigning(repoRoot);
  if (!signing.ok) {
    console.error(missingKeystoreMessage(signing));
    process.exit(1);
  }
  console.log(`Release signing keystore found: ${signing.resolvedStoreFile}`);
}

const env = { ...process.env };
const jh = resolveJavaHome();
if (jh) {
  env.JAVA_HOME = jh;
  if (process.env.JAVA_HOME && process.env.JAVA_HOME !== jh) {
    const configuredMajor = javaMajorVersion(process.env.JAVA_HOME);
    console.log(
      `Ignoring unsupported JAVA_HOME for this Gradle build` +
        `${configuredMajor ? ` (Java ${configuredMajor})` : ''}: ${process.env.JAVA_HOME}`
    );
  }
  console.log(`Using JAVA_HOME: ${jh}`);
} else if (javaOnPath()) {
  console.log('No Android Studio JBR found; falling back to "java" on PATH.');
} else {
  console.error(
    'ERROR: Could not find a JDK. No JAVA_HOME is set, no Android Studio JBR was found, ' +
      'and "java" is not on PATH.\n' +
      'Install a JDK (or open this project once in Android Studio to install its bundled JBR), ' +
      'then try again.'
  );
  process.exit(1);
}

let child;
if (process.platform === 'win32') {
  child = spawnSync('cmd.exe', ['/c', 'gradlew.bat', ...tasks], {
    cwd: repoRoot,
    stdio: 'inherit',
    env,
  });
} else {
  child = spawnSync(path.join(repoRoot, 'gradlew'), tasks, {
    cwd: repoRoot,
    stdio: 'inherit',
    env,
  });
}

if (child.error) {
  console.error(`Failed to run Gradle wrapper: ${child.error.message}`);
  process.exit(1);
}

if (child.status === 0) {
  const wanted = [];
  if (tasks.some((t) => /assembleDebug/i.test(t))) wanted.push('debug');
  if (tasks.some((t) => /assembleRelease/i.test(t))) wanted.push('release');
  for (const variant of wanted) {
    const dir = path.join(repoRoot, 'app', 'build', 'outputs', 'apk', variant);
    const candidates =
      variant === 'release'
        ? ['app-release.apk', 'app-release-unsigned.apk']
        : ['app-debug.apk'];
    const found = candidates
      .map((name) => path.join(dir, name))
      .find((p) => fs.existsSync(p));
    if (found) {
      const sizeMb = (fs.statSync(found).size / (1024 * 1024)).toFixed(2);
      const note = found.endsWith('-unsigned.apk') ? ' [UNSIGNED]' : '';
      console.log(`APK: ${found} (${sizeMb} MB)${note}`);
    }
  }

  if (tasks.some((t) => /bundleRelease/i.test(t))) {
    const bundle = path.join(repoRoot, 'app', 'build', 'outputs', 'bundle', 'release', 'app-release.aab');
    if (fs.existsSync(bundle)) {
      const sizeMb = (fs.statSync(bundle).size / (1024 * 1024)).toFixed(2);
      console.log(`AAB: ${bundle} (${sizeMb} MB)`);
    }
  }
}

process.exit(child.status ?? 1);
