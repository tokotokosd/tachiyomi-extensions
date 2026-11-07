# tokotokosd extensions

Personal Tachiyomi/Tachimanga extension repository. It holds the extension
source and the published repo that GitHub Pages serves.

## Add the repo

In Tachimanga: **More → Extensions → Extension repositories → +**, then enter

```
https://raw.githubusercontent.com/tokotokosd/tachiyomi-extensions/main/repo/index.min.json
```

Use this raw.githubusercontent.com address. Tachimanga rejects the
`tokotokosd.github.io` address before downloading anything, even though the
same files are served there.

Enable the **русский** language in the extension list's language filter to see ReManga.

## ReManga

Source for [remanga.org](https://remanga.org) (Russian), built against
extensions-lib 1.4 (the version Tachimanga runs).

- Popular, latest, text search, and search by pasted `remanga.org/manga/...` link or `slug:<dir>`
- Filters: sort (with direction), type, status, age rating, minimum chapters, genres and categories (include/exclude)
- All translations: each chapter carries its team name, so the app's scanlator filter can pick one. A setting limits it to the main translation.
- Paid chapters are marked 🔒 (or hidden via a setting). Bought chapters open after logging in through WebView, or with a token pasted into the settings.
- Image servers fall back to the reserve host when the primary fails.

## Layout

| Path | What |
|---|---|
| `src/ru/remanga/` | Extension source, unit tests (`test/`) and API fixtures (`test-resources/`) |
| `core/`, `buildSrc/`, `gradle/`, `common.gradle`, `*.gradle.kts` | Build setup taken from keiyoushi/extensions-source at `8f70beda0` (before its lib 1.6 rewrite) |
| `repo/` | Published repo: `index.min.json`, `repo.json`, `apk/`, `icon/` |
| `scripts/release.sh` | Test, build, check signature, publish into `repo/` |
| `scripts/update_index.py` | Copies an APK into `repo/apk/` and rewrites the index |
| `.github/workflows/release.yml` | CI: tests on every push; publishes when the version code goes up |

## Releasing an update

1. Change the code and bump `extVersionCode` in `src/ru/remanga/build.gradle`.
2. Either push to `main` and let GitHub Actions build, sign and publish, or run locally:

   ```bash
   scripts/release.sh --push
   ```

Local builds need JDK 17 and the Android SDK (`brew install openjdk@17` and
`brew install --cask android-commandlinetools`, then install `platforms;android-34`
and `build-tools;34.0.0`), plus `signingkey.jks` and `signing.env` in the repo root.
Both files are gitignored. CI reads the same values from the repository secrets
`SIGNING_KEY` (base64 of the keystore), `KEY_STORE_PASSWORD`, `ALIAS` and `KEY_PASSWORD`.

The APK must always be signed with the same key (SHA-256 `4a16e59b…`); `release.sh`
refuses to publish anything else, because installed copies would reject the update.
