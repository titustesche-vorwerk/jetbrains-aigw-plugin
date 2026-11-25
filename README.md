# AIGW Sandbox

IntelliJ Platform plugin (id `dev.aigw.sandbox`, IDE 2026.2+) that manages Docker Sandboxes (`sbx`) running Claude Code through the Databricks AI Gateway (`ug claude`). It adds a **Sandboxes** tool window (create/start, sign in to Databricks, connect in an IDE terminal tab, pull, push, diff, stop, reset), a relay for the Databricks OAuth callback on `127.0.0.1:8020`, and a status bar widget showing sign-in state and `ug usage`.

## Prerequisites

- The `sbx` Docker Sandboxes CLI (found via the configured path, `PATH`, `/opt/homebrew/bin` or `/usr/local/bin`) and `git`.
- A repository with an `sbxenv.yaml` (plus the `ug` overlay env file, e.g. `sandbox_config/sbxenv.ug-claude.yaml`) that defines the sandbox.
- Access to a Databricks workspace with the AI Gateway.

## Install

```
./gradlew buildPlugin
```

Then Settings | Plugins | gear icon | Install Plugin from Disk, and pick `build/distributions/aigw-sandbox-<version>.zip`.

## Settings

Settings | Tools | AIGW Sandbox.

Application-wide:

| Field | Default | Notes |
|---|---|---|
| Workspace host | empty | Databricks workspace URL; `https://` is added if missing. Required for sign-in. |
| Profile | empty | Databricks CLI profile name used inside the sandbox. Required for sign-in. |
| sbx path | empty (auto-detect) | Absolute path to `sbx`. |
| git path | empty (auto-detect) | Absolute path to `git`. |
| Callback port | `8020` | Host port for the OAuth callback relay; values outside 1024-65535 fall back to 8020. |

Per project:

| Field | Default | Notes |
|---|---|---|
| Sandbox name | `ug-claude-<project dir name>` | |
| Env files (one per line) | `sbxenv.yaml` | Relative to the project root. |
| Env args (one per line) | empty | Passed as `--env-arg`, e.g. `ugWorkspace=https://<host>`. Added automatically when an env file declares the arg and none is set here: `sandbox=<name>`, and `ugWorkspace=https://<workspace host>` (from the workspace host setting). |

Example for a repo with a `ug claude` overlay env file:

- Env files: `sbxenv.yaml` and `sandbox_config/sbxenv.ug-claude.yaml`
- Env arg: `ugWorkspace=https://<your-workspace>.cloud.databricks.com`

## The Sandboxes panel

Each sandbox is a card:

- **Header:** name, status, a Start/Stop toggle (run/stop icon), Connect (terminal icon) and `⋯` (Reset).
- **Sign-in button** (project sandbox): "Sign in to Databricks" when the running sandbox is signed out; greyed out "Signed in" when it is signed in; greyed out while the sandbox is stopped.
- **Git row** (project sandbox): a dropdown with the sandbox's branches (refresh icon next to it) and Pull / Push / Diff. The local side is always the branch checked out in the IDE; the dropdown picks the sandbox branch Pull and Diff read from and Push writes into. It defaults to the sandbox branch with the same name as yours.

Greyed-out controls say why in their tooltip.

## Buttons

`<env args>` below means: the env files, `--name <sandbox>`, and one `--env-arg` per env arg. "Project sandbox" is the one named in the project settings; other sandboxes listed by `sbx ls` get the subset of actions marked.

| Button | What it runs / does | Safety |
|---|---|---|
| Start / Create | Project sandbox: `sbx --no-input env run --detach <env args>` in a Run console. Other sandboxes: `sbx exec <name> true` (wakes it). On success it relays the OAuth callback and signs in if needed. | If sbx wants confirmation, a notification offers "Apply anyway", which reruns with `--force`. |
| Sign in to Databricks | In the sandbox: if the profile is missing from `~/.databrickscfg`, appends `[<profile>]` with `host = <host>`; then `ug configure --agents claude --profile <profile>`. Project sandbox only. | One browser login. |
| Connect | Opens an IDE Terminal tab named after the sandbox (or focuses the existing one) whose process is `sbx env run <env args>` (project sandbox, attached) or `sbx run --name <name>` (others), started in the project dir without a shell. | The tab keeps the output after the agent exits; Connect then replaces it with a fresh one. |
| Pull | Fetches the selected sandbox branch from the `sandbox-<name>` git remote (`git fetch`), sets `remote.pushDefault=origin`, and merges it into the current local branch via the IDE's Git merge. Offers to start a stopped sandbox first. | Never stashes or resets. Refuses on a detached HEAD or uncommitted changes (checked again right before the merge). Never pushes. |
| Push | In the sandbox, in the project dir: `git branch --show-current`, `git status --porcelain --untracked-files=no`, `git fetch /run/sandbox/source <your branch>`, `git merge --no-edit FETCH_HEAD` into the selected sandbox branch. Only committed changes are pushed. | Refuses unless the sandbox has the selected branch checked out ("Claude is on <x>. Pick that branch or switch the sandbox first.") or if it is dirty; a conflicting merge is aborted (`git merge --abort`). Nothing is ever pushed to GitLab or any remote. Running sandbox only. |
| Diff | Fetches the selected sandbox branch and shows the changes from the merge-base of HEAD and that branch to the branch in a read-only changes dialog (only what Claude committed). If there are none, it says the branch has no commits yours doesn't have yet. Running sandbox only. | Never merges, checks out or stashes. |
| Stop | `sbx stop <name>`. | |
| Reset | After confirmation: project sandbox `sbx --no-input env rm --force <env args>` in a Run console (then offers "Create again"); others `sbx --no-input rm <name>`. | Uncommitted work inside the sandbox is lost; the dialog says so. |

## How sign-in works

`ug configure` uses an OAuth callback to `http://localhost:8020`, which is published by the sandbox on the host. When a sandbox starts, the plugin runs `sbx port unpublish <name> 8020` and starts a small relay on `127.0.0.1:8020`. During sign-in the sandbox prints the authorize URL; the plugin opens it in your browser. When the browser hits the relay, the plugin hands the callback into the sandbox with `sbx exec <name> curl http://localhost:8020<path>`. The result is one browser login. Requests such as `/favicon.ico` are not forwarded.

## Troubleshooting

- **Port in use / "Databricks sign-in callback unavailable"**: something else listens on the callback port, or the sandbox still publishes it. Run `sbx port unpublish <name> 8020`, stop the other process, or change the callback port in settings.
- **`sbx not found`** (also `git not found`): common when the IDE is launched from the Dock, because it has no shell `PATH`. Set the full path in Settings | Tools | AIGW Sandbox. `/opt/homebrew/bin` and `/usr/local/bin` are checked automatically.
- **"usage not available"** in the status bar: `ug usage` exited non-zero or printed nothing in the sandbox. Check that you are signed in and run `ug usage` via Connect. It does not affect other features.
- **"Env file not found"**: an env file listed in settings does not exist in the project root.

## Development

- `./gradlew test` runs the unit tests.
- `./gradlew runIde` starts a sandbox IDE with the plugin.
- `./gradlew verifyPlugin` runs the Plugin Verifier against IntelliJ IDEA 2026.2 and 2026.3 (downloads the IDEs).
- `./gradlew buildPlugin` builds the zip.

JDK 21 toolchain (auto-provisioned by Gradle).
