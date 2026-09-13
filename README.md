# YOLO: AI Agents Extender

An IntelliJ IDEA plugin that gives you a standalone **YOLO** panel — a dedicated tool window (right side, **y** icon)
that lists your AI CLI tools and launches any of them in a **real, interactive terminal inside the IDE**, with a single
click. What makes it different: **everything the agent prints becomes clickable** — file paths, stack traces, type names,
and URLs all turn into navigation links — so you can jump from the agent's output straight to the code.

It is built entirely on **public IntelliJ APIs**, so it passes JetBrains Marketplace verification and can be published
like any normal plugin. It does **not** hook into or depend on IDEA's built-in Terminal.


---

## Features

### The YOLO panel

![yolo-panel.png](screenshots/yolo-panel.png)

A tool window (right side, **y** icon) that replicates the Terminal's **AI Agents** experience without touching
any internal Terminal API:

- Lists your **installed** agents — promoted agents (Claude Code, Codex, CodeBuddy, …) plus your own custom tools.
  Agents that aren't detected on `PATH` simply aren't shown, so the list stays relevant to this machine.
- Each row shows the agent's icon, name, and its configured skip and resume flags.
- The dropdown loads **instantly from a cached install scan** — the detection done on a previous run is reused, and a
  background re-scan refreshes the list only when the set of installed agents actually changes.
- The panel header holds two toggles — **YOLO (Skip Permissions)** and **Resume Session** — plus the settings gear, and a **Launch** button.

### YOLO mode

A **YOLO (Skip Permissions)** toggle in the panel header. Turn it on and the next launch starts the agent with its
permission-bypass flag appended — `--dangerously-skip-permissions` for Claude Code, `--yolo` for Codex, `-y` for
CodeBuddy, and so on.

The flag is **per agent** and fully configurable. The plugin pre-fills the correct flag for known agents,
but every value is editable and nothing is hardcoded at runtime. A few agents (Goose) bypass via an
environment variable instead of a flag; those are handled too.

**The toggle is off by default and never turns itself on.**

### Resume session

A **Resume Session** toggle in the panel header. Turn it on and the next launch starts the agent with its
resume flag appended — `-r` for Claude Code / CodeBuddy / Copilot / Goose / Hermes / Kimi / Pi, `resume --last` for
Codex, `--resume` for Cursor / TraeCode, `--taskId` for Cline — so the agent continues a previous session instead of starting fresh.

The flag is **per agent** and fully configurable (see [Configuration](#configuration)). The plugin knows the correct
resume flag for the common agents and pre-fills it, and custom tools set their own in the **Resume flag** column — a
custom tool has no bundled `agents.json` entry, so that column is the only way to give it a resume flag. Agents without a
CLI resume capability (e.g. OpenCode uses a TUI `/resume` instead of a flag) launch unchanged when the
toggle is on.

**The toggle is off by default and never turns itself on.**

### Runs inside the panel (real terminal)

**Select an agent, then click Launch** — the dropdown only selects; the agent opens in a
**real, interactive terminal embedded directly inside the YOLO panel** when you press **Launch**: a genuine PTY (via JediTerm + PTY4J, the same
terminal emulator the IDE itself bundles) that renders the agent's TUI in place. Prompts, editors, and your rc-defined
`PATH` (nvm / fnm / npm global bin, …) all work because the agent runs through an interactive login shell.

Because selection and execution are separate, the **Skip Permissions** / **Resume Session** toggles always apply to the next
launch, and changing the dropdown never kills a running terminal. The last agent you launched is remembered and re-selected
the next time you open the panel.

**Every Launch opens a new tab** — a separate, real PTY — so several agents can run side by side and you switch between
them with the tabs (like IDEA's built-in Terminal). Each tab has a close (✕) button that tears down that agent's process;
closing the last tab returns the panel to its empty placeholder.

- **The caret lands in the terminal automatically** when an agent launches, so you can type right away.
- **Ctrl+C is no longer hijacked by IDEA's Copy shortcut.** With the terminal focused, Ctrl+C passes through to the
  embedded terminal instead of popping IDEA's "Shortcuts conflicts" dialog. Whether it interrupts the running agent
  depends on the agent's own TUI.

> This is built entirely on **public APIs**: JediTerm and PTY4J are third-party libraries shipped with the IntelliJ
> Platform (not `@ApiStatus.Internal` / `@Experimental` Terminal APIs), so the plugin stays publishable on the
> JetBrains Marketplace. The IDE's own `ConsoleView` is output-only (no interactive input), so a real agent can only
> live in the panel by embedding a true terminal — which is exactly what this does.

### Clickable terminal output

While the agent runs, its output is scanned for references and turned into hyperlinks. Clicking a link jumps you to the
right place and **auto-hides the YOLO panel** so it no longer covers the editor.

| You print… | Becomes a link to… |
|---|---|
| `src/foo/Bar.kt:42`, `/abs/Bar.kt:42:13`, `C:\foo\Bar.kt:7` | the file at that line / column |
| `./Makefile:10`, `~/x/y.kt:3`, `file:///abs/x.kt` | the file (home-relative and `file://` URIs supported) |
| `path:12-18` | the file at the start of the line range |
| `"/path with space/Bar.kt":5` | a quoted path containing spaces |
| `Bar.java:123`, `Bar.kt:12` | a bare stack-trace frame |
| `File "app/main.py", line 42` | a Python / JS traceback frame |
| `plugin.xml`, `build.gradle.kts`, `README.md` | a bare file name anywhere in the project |
| `com.foo.Bar` / `Bar` | the class declaration (qualified or project-local simple name) |
| `Bar.method` / `Bar#method` | the specific method / field / inner class |
| `https://example.com` | the URL, opened in your system browser (panel is **not** hidden) |

- **Line/column navigation** works for paths, stack frames, and member references.
- **No-extension files** (`Makefile`, `Dockerfile`) and **Windows paths** are handled.
- **What you type is never linked.** Links are painted on the agent's *output* only — the text you are
  typing into the agent's own input box stays plain, so it can't turn into a link under your cursor.
- URLs are the exception: clicking one opens your browser but keeps the panel open.

> ### Warning — about YOLO mode
>
> Letting an AI agent run commands and edit files **without confirmation** can make irreversible
> changes, execute untrusted code, or expose your system. Those risks come from the agent and the
> bypass flag itself. **This plugin only flips that flag for you — it adds no such behavior of its
> own**, performs no actions on your behalf, and is not responsible for what the agent does.
> Enable YOLO mode only in environments you trust.

---

## Requirements

| | |
|---|---|
| IDE | IntelliJ IDEA **2023.3** (build `233`) or later |
| Dependencies | None beyond the IntelliJ Platform itself — the built-in Terminal is **not** required |

---

## Build

Build with the standard Gradle task:

```bash
./gradlew buildPlugin
# → build/distributions/yolo-{version}.zip
```

**Building against a locally installed IDEA** — by default the plugin compiles against the IntelliJ
SDK pinned in `gradle.properties`. To build against the IDE on your machine instead, create a
`local.properties` file at the project root pointing at its installation:

```properties
localIdeaPath=/Applications/IntelliJ IDEA.app
```

## Installation

**From the Marketplace** — search for **YOLO** in *Settings | Plugins* and install.

or from website: [https://plugins.jetbrains.com/plugin/33442-yolo-ai-agents-extender/](https://plugins.jetbrains.com/plugin/33442-yolo-ai-agents-extender/)

**From disk** — build or download the `yolo-<version>.zip` (see [Build](#build)), then
`Settings | Plugins | ⚙ | Install Plugin from Disk…` and pick the zip. Restart when prompted.

---

## Experimental edition (`exp` branch)

The `exp` branch is an **alternative, experimental build** of this plugin with a different architecture.
Rather than the standalone YOLO panel (this `main` line), it **extends IDEA's built-in Terminal
"AI Agents" dropdown** directly — appending your own CLI tools to that menu and adding a "Skip
permissions" (YOLO) toggle to the Terminal toolbar.

It is "experimental" because it hooks **internal** Terminal extension points
(`terminalAgentProvider`, `shellExecOptionsCustomizer`, `toolWindowInitializer` from
`org.jetbrains.plugins.terminal`), which are still `@ApiStatus.Internal` and have no public alternative.

### Trade-offs vs. the main edition

| | Main (`main`) | Experimental (`exp`) |
|---|---|---|
| Integration | Standalone YOLO panel + embedded terminal | IDEA Terminal's AI Agents dropdown |
| Clickable agent output | Yes — file / stack-trace / type / URL links | No (the link filters are not included) |
| APIs used | Public IntelliJ APIs only | Internal Terminal APIs |
| Marketplace | Published | **Not published** (fails verification) |
| Min. IDE version | 2023.3 (`233`) | 2026.1 (`261`) |
| IDE family | All IntelliJ-platform IDEs | IntelliJ IDEA |

### How to get it

Because it depends on internal APIs, the experimental edition is **rejected by JetBrains Marketplace
verification**, so it is distributed from the repository's **Releases** page instead:

1. Check out the `exp` branch and build it:
   ```bash
   git checkout exp
   ./gradlew buildPlugin
   # → build/distributions/yolo-<version>.zip
   ```
2. Download the zip from the repo's **Releases** page, or use the one you just built.
3. Install via `Settings | Plugins | ⚙ | Install Plugin from Disk…` and restart.

> Requires **IntelliJ IDEA 2026.1 or later** (`since-build 261`) with the bundled **Terminal** plugin

This branch is a divergence for experimentation; the published, supported line remains `main`.

---

## Configuration

**`Settings | Tools | YOLO: AI Agents Extender`** — or click the gear in the YOLO panel header.

![yolo-settings.png](screenshots/yolo-settings.png)

Everything lives in one table. Each row is an agent, and each row carries its own skip flag:

| Column | Meaning |
|---|---|
| Icon | The bundled icon for promoted agents; your file or a default bolt for custom tools. Greyed out in this table when the command isn't on `PATH` |
| ID | Unique identifier |
| Display name | The name shown in the YOLO panel |
| Command | Executable name — must resolve on `PATH` |
| Base args | Arguments always passed, space separated |
| Skip flag | The permission-bypass argument, appended when the YOLO toggle is on |
| Resume flag | The resume argument, appended when the Resume Session toggle is on (e.g. `-r`, `--resume`) |

Rows come in two kinds, listed in descending priority:

1. **Promoted agents** (Claude Code, Codex, CodeBuddy, …) — read-only, sorted with **Claude Code** and
   **Codex** pinned at the top, cannot be removed.
2. **Your own custom tools** — fully editable, in the order you created them.

Only the Skip flag and Resume flag are editable on promoted agents. That's deliberate: the plugin should extend the panel, not take it over.

### Conveniences

- **Skip and resume flags pre-fill themselves.** Open the settings and known agents already have the right
  flags. Type a known ID or command into a new row and they fill in as you go. Values you set by hand
  are never overwritten.
- **Duplicates are caught while you type.** A repeated ID or command turns the status line red
  immediately, and Apply refuses to save. Commands are compared by executable name, so
  `/usr/bin/claude` and `claude.cmd` count as the same tool.
- **Installed agents are detected on each startup.** In the background the plugin checks every known agent's command — first on `PATH`, then by actually running it once (`--version`) — and adds the promoted agents it finds installed. This runs on **every startup, not just the first**, so a tool you install later (e.g. OpenCode installed via npm) shows up automatically. It does **not** auto-discover arbitrary tools you wrote yourself — add those as custom tools. The result is cached so the panel opens instantly afterwards.
- **Validate** checks a row's command the same way (PATH first, then running it once) and downloads its icon URL if it has one.

### Known agents

This list is a convenience — not the source of truth. What runs is whatever the settings say.

| id | display name | command | website |
|---|---|---|---|
| claude | Claude Code | `claude` | <a href="https://claude.ai/"><img src="src/main/resources/icons/agents/claude.svg" height="20" alt="Claude Code"></a> |
| codex | Codex | `codex` | <a href="https://openai.com/codex"><img src="src/main/resources/icons/agents/codex.svg" height="20" alt="Codex"></a> |
| cursor | Cursor | `cursor-agent` | <a href="https://cursor.com/"><img src="src/main/resources/icons/agents/cursor.svg" height="20" alt="Cursor"></a> |
| copilot | GitHub Copilot | `copilot` | <a href="https://github.com/features/copilot"><img src="src/main/resources/icons/agents/copilot.svg" height="20" alt="GitHub Copilot"></a> |
| opencode | OpenCode | `opencode` | <a href="https://opencode.ai/"><img src="src/main/resources/icons/agents/opencode.svg" height="20" alt="OpenCode"></a> |
| aider | Aider | `aider` | <a href="https://aider.chat/"><img src="src/main/resources/icons/agents/aider.svg" height="20" alt="Aider"></a> |
| cline | Cline | `cline` | <a href="https://cline.bot/"><img src="src/main/resources/icons/agents/cline.svg" height="20" alt="Cline"></a> |
| continue | Continue | `cn` | <a href="https://continue.dev/"><img src="src/main/resources/icons/agents/continue.svg" height="20" alt="Continue"></a> |
| openclaw | OpenClaw | `openclaw` | <a href="https://openclaw.ai/"><img src="src/main/resources/icons/agents/openclaw.svg" height="20" alt="OpenClaw"></a> |
| kiro | Kiro | `kiro-cli` | <a href="https://kiro.dev/"><img src="src/main/resources/icons/agents/kiro.svg" height="20" alt="Kiro"></a> |
| goose | Goose | `goose` | <a href="https://block.github.io/goose/"><img src="src/main/resources/icons/agents/goose.svg" height="20" alt="Goose"></a> |
| crush | Charm Crush | `crush` | <a href="https://charm.sh/crush"><img src="src/main/resources/icons/agents/crush.png" height="20" alt="Charm Crush"></a> |
| amp | Amp | `amp` | <a href="https://ampcode.com/"><img src="src/main/resources/icons/agents/amp.svg" height="20" alt="Amp"></a> |
| kimi | Kimi | `kimi` | <a href="https://kimi.moonshot.cn/"><img src="src/main/resources/icons/agents/kimi.svg" height="20" alt="Kimi"></a> |
| qwen-code | Qwen Code | `qwen` | <a href="https://qwen.ai/qwencode"><img src="src/main/resources/icons/agents/qwen-code.png" height="20" alt="Qwen Code"></a> |
| trae | TraeCode | `traecli` | <a href="https://www.trae.ai/"><img src="src/main/resources/icons/agents/trae.svg" height="20" alt="TraeCode"></a> |
| codebuddy | CodeBuddy | `codebuddy` | <a href="https://www.codebuddy.ai/"><img src="src/main/resources/icons/agents/codebuddy.svg" height="20" alt="CodeBuddy"></a> |
| qoder | Qoder | `qoder` | <a href="https://qoder.com/"><img src="src/main/resources/icons/agents/qoder.svg" height="20" alt="Qoder"></a> |
| devin | Devin | `devin` | <a href="https://devin.ai/"><img src="src/main/resources/icons/agents/devin.svg" height="20" alt="Devin"></a> |
| grok | Grok | `grok` | <a href="https://grok.com/"><img src="src/main/resources/icons/agents/grok.svg" height="20" alt="Grok"></a> |
| antigravity | Antigravity | `agy` | <a href="https://antigravity.google/"><img src="src/main/resources/icons/agents/antigravity.png" height="20" alt="Antigravity"></a> |
| mistral-vibe | Mistral Vibe | `vibe` | <a href="https://mistral.ai/"><img src="src/main/resources/icons/agents/mistral-vibe.svg" height="20" alt="Mistral Vibe"></a> |
| kilo | Kilo Code | `kilo` | <a href="https://kilocode.ai/"><img src="src/main/resources/icons/agents/kilo.svg" height="20" alt="Kilo Code"></a> |
| hermes | Hermes | `hermes` | <a href="https://hermes-agent.nousresearch.com/"><img src="src/main/resources/icons/agents/hermes.png" height="20" alt="Hermes"></a> |
| pi | Pi | `pi` | <a href="https://pi.dev/"><img src="src/main/resources/icons/agents/pi.svg" height="20" alt="Pi"></a> |
| droid | Droid | `droid` | <a href="https://factory.ai/"><img src="src/main/resources/icons/agents/droid.svg" height="20" alt="Droid"></a> |
| aug | Auggie | `auggie` | <a href="https://augmentcode.com/"><img src="src/main/resources/icons/agents/aug.svg" height="20" alt="Auggie"></a> |
| rovo | Rovo Dev | `rovo` | <a href="https://rovo.atlassian.com/"><img src="src/main/resources/icons/agents/rovo.svg" height="20" alt="Rovo Dev"></a> |
| prime-agent | Prime Agent | `prime-agent` | <a href="https://www.primeintellect.ai/"><img src="src/main/resources/icons/agents/prime-agent.png" height="20" alt="Prime Agent"></a> |
| autohand | Autohand | `autohand` | <a href="https://autohand.ai/"><img src="src/main/resources/icons/agents/autohand.svg" height="20" alt="Autohand"></a> |
| command-code | Command Code | `command-code` | <a href="https://commandcode.ai/"><img src="src/main/resources/icons/agents/command-code.svg" height="20" alt="Command Code"></a> |
| ante | Ante | `ante` | <a href="https://antigma.ai/"><img src="src/main/resources/icons/agents/ante.svg" height="20" alt="Ante"></a> |
| codebuff | Codebuff | `codebuff` | <a href="https://codebuff.com/"><img src="src/main/resources/icons/agents/codebuff.png" height="20" alt="Codebuff"></a> |
| omp | OMP | `omp` | <a href="https://ohmyposh.dev/"><img src="src/main/resources/icons/agents/omp.svg" height="20" alt="OMP"></a> |

Anything not listed here works fine as a custom tool; just fill in its flag yourself.

---

## Troubleshooting

The plugin logs every launch it touches. To see what actually ran, grep the IDE log
(`<version>` is the IDE's build, e.g. `2026.2`):

```bash
# macOS
grep "Agent YOLO" ~/Library/Logs/JetBrains/IntelliJIdea<version>/idea.log

# Linux
grep "Agent YOLO" ~/.cache/JetBrains/IntelliJIdea<version>/log/idea.log

# Windows (PowerShell)
grep "Agent YOLO" "$env:LOCALAPPDATA\JetBrains\IntelliJIdea<version>\log\idea.log"
```

**An agent is missing from the panel.** Its command isn't resolving on `PATH`. The panel only lists
agents detected as installed; run a settings **Validate** on the row, or check that the command resolves in the
shell that launched the IDE (the IDE may inherit a different `PATH` than your interactive shell).

**The flag isn't being applied.** Confirm the relevant toggle is on (YOLO for the skip flag, Resume Session for the
resume flag) and the row has a value in that column. The log line for each launch shows the final command, including
whether anything was injected.

**A printed path / type name isn't clickable.** Links only appear when the reference resolves to a real file or
class in the current project (so random words aren't linked). Make sure the file is inside a content root and, for
type names, that the Java module is enabled.

**Settings changes don't take effect.** The plugin registers a `Configurable`, which IDEA cannot
load dynamically. Restart the IDE after installing or updating.
