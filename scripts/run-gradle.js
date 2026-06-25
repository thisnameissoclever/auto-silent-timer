#!/usr/bin/env node
'use strict';

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

const repoRoot = path.resolve(__dirname, '..');

function javaBinExists(javaHome) {
  if (!javaHome) return false;
  const javaExe = process.platform === 'win32' ? 'java.exe' : 'java';
  return fs.existsSync(path.join(javaHome, 'bin', javaExe));
}

function resolveJavaHome() {
  if (process.env.JAVA_HOME && javaBinExists(process.env.JAVA_HOME)) {
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
    if (javaBinExists(candidate)) return candidate;
  }

  return null;
}

function javaOnPath() {
  const result = spawnSync('java', ['-version'], { stdio: 'ignore' });
  return !result.error && result.status === 0;
}

const tasks = process.argv.slice(2);

const env = { ...process.env };
const jh = resolveJavaHome();
if (jh) {
  env.JAVA_HOME = jh;
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
}

process.exit(child.status ?? 1);
