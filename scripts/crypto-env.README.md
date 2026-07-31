# Encrypted .env onboarding

Tracked ciphertext: `credentials/env.enc` (safe to commit with a strong passphrase).
Plaintext `.env` stays gitignored.

```powershell
npm run env:encrypt   # encrypt local .env and publish only credentials/env.enc to origin/main
npm run env:decrypt   # restore/overwrite local .env from credentials/env.enc
npm run env:publish   # publish an existing credentials/env.enc without re-encrypting
npm run test:crypto-env
```

Passphrase: `$env:ENV_ENC_PASSPHRASE` (preferred) or `$env:STFUAI_ENV_PASSPHRASE`, else interactive prompt.
Publish uses git plumbing (no pull/stash/worktree). Refuses when local main has unpushed commits.
Unit tests use the disposable passphrase `pickles` against temp fixtures only.
