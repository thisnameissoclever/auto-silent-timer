#!/usr/bin/env node
'use strict';

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

function adbInSdk(sdkRoot) {
  if (!sdkRoot) return null;
  const adbExe = process.platform === 'win32' ? 'adb.exe' : 'adb';
  const candidate = path.join(sdkRoot, 'platform-tools', adbExe);
  return fs.existsSync(candidate) ? candidate : null;
}

function resolveAdb() {
  const home = os.homedir();
  const sdkRoots = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT];

  if (process.platform === 'win32') {
    const localAppData = process.env.LOCALAPPDATA || path.join(home, 'AppData', 'Local');
    sdkRoots.push(path.join(localAppData, 'Android', 'Sdk'));
  } else if (process.platform === 'darwin') {
    sdkRoots.push(path.join(home, 'Library', 'Android', 'sdk'));
  } else {
    sdkRoots.push(path.join(home, 'Android', 'Sdk'));
  }

  for (const root of sdkRoots) {
    const adbPath = adbInSdk(root);
    if (adbPath) return adbPath;
  }

  console.log('No Android SDK adb found; falling back to "adb" on PATH.');
  return 'adb';
}

const adbPath = resolveAdb();
const child = spawnSync(adbPath, process.argv.slice(2), { stdio: 'inherit' });

if (child.error) {
  console.error(`Failed to run adb: ${child.error.message}`);
  process.exit(1);
}

process.exit(child.status ?? 1);
