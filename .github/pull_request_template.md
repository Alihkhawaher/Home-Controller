## What this changes

<!-- One or two sentences: what changes and why. Link the issue: "Closes #123". -->

## Type of change

- [ ] Bug fix
- [ ] New connector
- [ ] New feature
- [ ] Documentation
- [ ] Build or CI

## How it was tested

<!-- Unit tests added, devices tried (brand and model, no addresses), Android versions. -->

## Checklist

- [ ] `./gradlew test assembleDebug` passes locally.
- [ ] New behaviour in `core` or a connector has unit tests.
- [ ] No real IP addresses, MAC addresses, Wi-Fi names, keys or personal names in code, tests, docs or screenshots.
- [ ] Connectors follow [the rules](https://github.com/Alihkhawaher/Home-Controller/blob/main/docs/writing-a-connector.md#the-rules): local network only, no secrets in
      logs, `read()` never changes a device, changes validated before sending.
- [ ] Docs updated (README devices table, `docs/`), and a line added to `CHANGELOG.md` for user-visible changes.
