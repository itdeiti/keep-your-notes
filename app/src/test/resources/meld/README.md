Test data for `MeldCryptoTest`. Fictional notes and passwords only, never real secrets.

- `legacy-whole.mdenc`, `legacy-inline.md`: copied unchanged from the Meld Encrypt plugin
  (meld-cp/obsidian-encrypt, `tests/fixtures/smoke`, v3.1.1). v2 format, password
  `legacy-release-password`, text `Legacy release secret café 🥑`.
- `v3-whole.mdenc` and the inline v3 strings in the test: written with WebCrypto (as the plugin does)
  by an independent script following the plugin's v3 format, file layout as `JSON.stringify(FileData, null, 2)`.
