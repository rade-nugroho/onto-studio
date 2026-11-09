# Issues

Open work items for Onto Studio. Keep this list short; move items to the
tracker of your choice when the repository has issues enabled.

## 1. Sign and notarize the macOS app before distribution

**Type:** release / security · **Priority:** high before external distribution

The packaged app is signed ad-hoc only. That is enough for internal use, but
Gatekeeper warns or blocks it on other machines. The bundled JRE and engine jar
live inside the bundle, so both must be covered by the signature.

- Bundle: `apps/desktop/src-tauri/target/release/bundle/macos/Onto Studio.app`
- Ad-hoc signature, no Developer ID, no notarization.

Options:

1. Developer ID Application certificate, `codesign` over the app including
   `Contents/Resources/resources/jre`, then `notarytool submit` + `stapler staple`.
2. Hardened runtime with JVM entitlements (`com.apple.security.cs.allow-jit`,
   `allow-unsigned-executable-memory`).
3. Signing steps in `scripts/package.sh`, gated by env vars so local builds stay
   unsigned.

Done when:

- [ ] `spctl --assess --type execute` passes on a clean machine.
- [ ] App launches from a quarantined download without manual override.
- [ ] `scripts/package.sh` supports signed builds without breaking local builds.

Never commit signing secrets; read them from the environment or CI secrets.

## 2. Reduce the 154 MB macOS app bundle (POI/PDFBox)

**Type:** packaging · **Priority:** product decision

The bundle grew to **154 MB** after adding document reading (PDFBox for PDF,
Apache POI for DOCX).

- `engine.jar`: **91 MB**
- `Onto Studio.app`: **154 MB** (includes the jlink runtime)
- Largest contributors: `org/apache/poi/schemas` (1,637 entries), `poi/ss`,
  `pdfbox/pdmodel`, `poi/hssf`

Options:

1. Trim POI: depend on `poi-ooxml-lite`, drop unused OOXML schemas, keep DOCX
   and drop XLSX/PPTX if not needed.
2. Split the document reader into a separate process or optional module fetched
   on first use, keeping the base app small.
3. Accept the size and document it as the cost of document reading.

Done when:

- [ ] Supported document formats decided (PDF, DOCX, others?).
- [ ] Bundle size target agreed (e.g. under 100 MB) or explicitly waived.
- [ ] `scripts/package.sh` still produces a working app whose engine starts from
      the bundled runtime.

## 3. Packaging smoke test that starts the bundled engine

**Type:** testing · **Priority:** high (prevents a repeat of the `java.net.http` incident)

The first 91 MB package crashed at launch because the jlink runtime was missing
`java.net.http`, which `Assistant` and the plugin installer use. The failure only
showed up when the app ran, and it surfaced as a Rust panic on a missing handshake.

Add a check that:

1. Builds the app image.
2. Launches the bundled binary with `PATH` stripped of any system Java.
3. Waits for the engine handshake and calls `GET /health` with the session token.
4. Fails the build if the engine does not answer within N seconds.

Done when:

- [ ] `scripts/package.sh` (or a separate script) runs the check.
- [ ] Removing a required module from the jlink list makes the check fail.
