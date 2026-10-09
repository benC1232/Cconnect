# Cconnect

An Android app for starting and managing [Claude Code](https://code.claude.com) **Remote Control**
sessions on your own machines over SSH: a home server, a Raspberry Pi, a dev box.

Pick a folder on the machine, tap **Start**, and Claude Code runs there with remote control on.
You then use the session from the Claude app or claude.ai. Cconnect doesn't show a terminal.
It only lists your running sessions and lets you start and close them.

## Features

- **Session list:** see every Claude session started from the app on that machine. Tap one to open
  its remote-control link, or tap ✕ to stop it. If a session has exited, tapping it shows why.
- **New session:** browse the machine's folders over SFTP and start Claude in the one you pick.
- **Folder trust from the app:** the first time Claude runs in a folder it asks whether to trust it.
  Cconnect shows that question as a dialog.
- **Key-based login:** the phone generates its own SSH key. Log in once with a password and the key
  is added to the server automatically (like `ssh-copy-id`), so later logins need no password.
- **Recent logins:** past successful logins appear in a dropdown on the login screen.
- **Sessions survive disconnects:** each session runs in a detached `tmux` session on the server.

## Requirements

On the machine you connect to:

- An SSH server
- [Claude Code](https://code.claude.com), installed and signed in, with `claude` on your `PATH`
- `tmux`, e.g. `sudo apt install tmux`

On the phone: Android 8.0 (API 26) or newer.

## Install

Download the latest `Cconnect-<version>.apk` from the [Releases](../../releases) page and open it on
your phone. You may need to allow installing apps from your browser or file manager.

## Build

```sh
./gradlew assembleDebug
```

This needs JDK 17+ and the Android SDK. The easiest way to get both is to open the project in
Android Studio. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Every push to `main` and every pull request is built by GitHub Actions. The debug APK is attached
to each run under **Actions**.

## Releasing

Releases are built and published by GitHub Actions when a version tag is pushed.

1. Set `versionName` (and raise `versionCode`) in `app/build.gradle.kts`, then commit.
2. Tag the commit and push the tag. The tag must be `v` plus the `versionName`:
   ```sh
   git tag v1.0.0
   git push origin v1.0.0
   ```
3. The **Release** workflow builds a signed APK and publishes it as `Cconnect-1.0.0.apk` on the
   Releases page.

### Signing setup (once per repository)

Release APKs must always be signed with the same key, or phones won't install them as updates.
Add these repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | The keystore file, base64-encoded (`base64 -w0 release.jks`) |
| `KEYSTORE_PASSWORD` | The keystore password |
| `KEY_ALIAS` | The key's alias |
| `KEY_PASSWORD` | The key's password |

Create a keystore with:

```sh
keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias cconnect \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Cconnect"
```

Keep the keystore and its password backed up, and never commit them. If the key is lost, existing
users have to uninstall the app before they can install a newer version.

## How it works

- **Starting a session** creates a detached tmux session named `claude-<folder>-<time>`. It runs
  `claude --remote-control` through your login shell (`$SHELL -ilc`), so tools installed with nvm
  or into `~/.local/bin` are found. To watch a session from the server itself, run
  `tmux attach -t <name>`.
- **The session list** is the server's tmux sessions whose names start with `claude-`. The app
  refreshes it every few seconds.
- **Exited sessions** stay listed (tmux `remain-on-exit`) so the app can show Claude's last output.
  Remove them from the list when you're done.
- **Tapping a session** opens the most recent `https://` link Claude printed, which is the
  remote-control session link.

## Security

- **Host keys** are trusted the first time you connect, then checked on every later connection.
  If a server's key changes, the app refuses to connect until you choose to trust the new key.
- **The phone's SSH key** (RSA 3072) is stored in the app's private storage and never leaves the
  device. Only its public half is installed on servers.
- **Passwords** are never stored. A password is only used to log in and install the key.
- **Folder trust:** only trust folders whose contents you know. Claude can read, edit, and run
  files in a trusted folder.

## License

[MIT](LICENSE)
