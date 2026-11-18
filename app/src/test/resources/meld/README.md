Test data for `MeldCryptoTest`. Fictional notes and passwords only, never real secrets.

- `legacy-whole.mdenc`, `legacy-inline.md`: copied unchanged from the Meld Encrypt plugin
  (meld-cp/obsidian-encrypt, `tests/fixtures/smoke`, v3.1.1). v2 format, password
  `legacy-release-password`, text `Legacy release secret café 🥑`.
- `v3-whole.mdenc` and the inline v3 strings in the test: written with WebCrypto (as the plugin does)
  by an independent script following the plugin's v3 format, file layout as `JSON.stringify(FileData, null, 2)`.
- `plugin-v3-whole.mdenc`, `plugin-v3-inline.md`: real v3 output of Meld Encrypt 3.1.1, created in Obsidian.
  Password `plugin-test-password`, texts `Plugin v3 Testtext café 🥑` and `Inline v3 Testtext`.
