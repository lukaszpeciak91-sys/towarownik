# Progress

## Current phase

**V0.1 — Google Play Internal Testing packaging**

The app keeps the merged unified search and OBI diagnostic behavior unchanged. Release signing is now configured from environment variables only, and a manual GitHub Actions workflow can build a signed Android App Bundle for Google Play after repository signing secrets are configured.

The release workflow reconstructs the upload keystore only in the GitHub runner temporary directory, runs `./gradlew check bundleRelease`, uploads `towarownik-play-release-aab`, and removes the transient keystore. No keystore, credential, APK, or AAB is committed.

## Next implementation milestone

**Produce and upload the first Internal Testing AAB**

After this release configuration is merged, add the four documented GitHub Actions secrets, run **Build signed Play AAB**, download the `towarownik-play-release-aab` artifact, and upload its `app-release.aab` to Google Play Console Internal Testing. Signed `bundleRelease` cannot be validated until real signing secrets exist.

## Not started

- Live OBI integration repair based on diagnostic evidence
- Camera barcode scanning
- Local and nearby-store fallback behavior
- External product-link behavior
- Persistence/history/favorites
- Product images
- AI
- Final Play release polish
