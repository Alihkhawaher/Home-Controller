# Security policy

## Reporting a vulnerability

Please report security problems **privately**. Do not open a public issue.

Use GitHub's private vulnerability reporting: open the repository's **Security** tab and choose
**Report a vulnerability**. Include:

- what the problem is and what an attacker could do with it;
- the steps or a proof of concept to reproduce it;
- the app version, your Android version, and the connector or device involved.

Remove real IP addresses, MAC addresses, Wi-Fi names and device keys from anything you send, or replace them with
made-up values.

We aim to acknowledge a report within a week, keep you informed while we work on a fix, and credit you in the release
notes unless you prefer otherwise.

## Supported versions

Security fixes go into the latest release. Please update before reporting.

## Scope

In scope: the Home Controller app, its connectors, and this repository's build and release workflows.

Out of scope: weaknesses in the devices' own firmware or protocols. Report those to the device maker. We still welcome
a heads-up if Home Controller can reduce the risk.

## What you should know about local device protocols

Home Controller talks to devices directly on your local network, using the protocols the devices themselves offer.
Many of those protocols were designed for a trusted home network and have **little or no authentication**.

- **Gree air conditioners.** The Gree LAN protocol has no authentication. Any client on the same Wi-Fi can find a
  unit, ask it for its encryption key, and control it. This is how the protocol works, with or without this app;
  it is not a Home Controller vulnerability.
- Treat everyone on your Wi-Fi as able to control such devices. Use WPA2 or WPA3 with a strong password, and put
  visitors on a guest network that cannot reach your home devices.

## What Home Controller does to protect you

- It has no cloud service, no account and no analytics. Nothing about your home leaves your local network.
- Device keys, tokens and passwords are never logged or shown, and are kept on the phone only. Where a device hands
  out its key on the LAN, as Gree units do, the key is fetched when needed and not stored.
- Connectors are compiled into the app. There is no code loaded at run time.
- Release signing keys are never stored in this repository.
