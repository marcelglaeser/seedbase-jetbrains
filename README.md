# SeedBase for JetBrains IDEs

Browse your [SeedBase](https://seedba.se) projects, generate realistic synthetic test data and pull SQL/CSV/JSON seeds straight into your project — without leaving the IDE. Works in IntelliJ IDEA, PyCharm, DataGrip, GoLand, PhpStorm, WebStorm, RubyMine and every other IntelliJ-platform IDE (2024.2+).

## Features (MVP)

- **SeedBase tool window** (right side): all your projects with their generations (status and date) at a glance.
- **Generate Data**: start a generation for the selected project and get notified when it completes.
- **Pull SQL / CSV / JSON**: download a generation into the project root and open it in the editor.
- **Open in Browser**: jump to the project's schema page on seedba.se.
- **Secure login**: browser-based authorization (one-time code), token stored in the IDE's Password Safe (OS keychain) — never in plaintext config files.

## Getting started

1. Open the **SeedBase** tool window (right-hand sidebar).
2. Click the **user icon** (Login) in the tool window toolbar.
3. Your browser opens with a one-time authorization code. Confirm it while the IDE waits.
4. Your projects appear in the tree — select a project to generate, select a completed generation to pull.

Use the **Logout** action to remove the stored token.

## Configuration

The plugin talks to `https://seedba.se` and stores your auth token in the IDE's Password Safe. There is **no API-URL setting** (a committed project setting could otherwise silently redirect your token). For local development against a self-hosted instance, set the `SEEDBASE_API_URL` environment variable before launching the IDE — an explicit, per-shell override that cannot be injected by a repository.

## Building from source

Requires JDK 17+.

```bash
./gradlew buildPlugin       # → build/distributions/seedbase-jetbrains-<version>.zip
./gradlew runIde            # launch a sandbox IDE with the plugin
```

Install the zip via *Settings → Plugins → ⚙ → Install Plugin from Disk…*.

## Roadmap

- Load into Database / Reset & Reseed (shell-out to psql/mysql/sqlite3, parity with the VS Code extension)
- Create project / push schema from a workspace schema file
- DataGrip database integration (run a generation straight into a configured data source)
- JetBrains Marketplace publishing

## License

MIT
