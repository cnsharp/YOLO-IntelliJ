---
name: yolo-agent-icons
description: Use when adding or replacing an agent icon in the YOLO IntelliJ plugin — sourcing a real brand logo (SVG preferred, PNG favicon fallback) for a new/existing entry in agents.json, installing it under src/main/resources/icons/agents/, and wiring it into the registry. Encodes the hard icon-acceptance rules the user established so we stop hand-tracing SVGs and stop shipping wordmark/text logos.
---

# YOLO Agent Icons

This skill codifies how to source and install an agent icon for the YOLO plugin
(`AgentRegistry` / `agents.json` + `src/main/resources/icons/agents/`). Follow it
verbatim when asked to "find an icon", "add an icon for <agent>", or "replace the
icon for <agent>". The rules are non-negotiable — they come from repeated rejections.

## Hard rules (do NOT violate)

These are the user's firm requirements. Breaking any one means the icon gets rejected and redone.

1. **No hand-drawn / hand-traced SVGs.** Never reconstruct a logo by tracing pixels or
   drawing paths yourself. If you cannot find a genuine vector, use the real favicon
   (raster) instead (rule 4). A traced mosaic of tiny squares or a re-imagined glyph is
   *not* acceptable even if it "looks close".
2. **No text / wordmark.** The icon must be a *symbol glyph* only — no lettering, no
   logo wordmark, no tagline. If the only official asset is `wordmark + symbol`, crop to
   the symbol (see §Crop). Rejected examples from this project: `antigravity`, `continue`,
   `rovo`, `grok` wordmark SVGs, `prime-agent` wordmark.
3. **Keep the original brand colors.** Do not recolor a colored symbol to black/mono
   (the `rovo` incident: it was originally orange/blue/purple/green, and forcibly turning
   it black was rejected). If the genuine asset is colored, keep it colored. Only accept a
   monochrome asset if that is genuinely how the brand ships the symbol.
4. **No genuine SVG → use the real favicon.** When a clean text-free SVG does not exist
   (or is unreachable), take the site's actual favicon (`.png`/`.ico`) rather than faking a
   vector. Examples shipped this way: `qwen-code` (qianwen.com favicon), `prime-agent`
   (primeintellect.ai `logo-icon.png`), `antigravity` (google `apple-touch-icon.png`).

## Where icons live

- **Bundled (built-in agents):** `src/main/resources/icons/agents/<id>.svg` or `.png`.
  The `<id>` must match the `id` field in `agents.json` (lower-case, e.g. `claude`,
  `codex`, `grok`, `rovo`).
- **agents.json reference:** each entry's `icon` field is the classpath path, e.g.
  `"icon": "/icons/agents/claude.svg"`. The registry reads it via
  `AgentRegistry.iconFor(id)` (`AgentRegistry.kt:43`); `AgentIcons.forAgent` (`terminal/AgentIcons.kt:53`)
  falls back to this, then to `AllIcons.Actions.Lightning`.
- **User-added custom tools:** their icon is NOT bundled — it is a local path / http URL
  archived by `IconResolver` to `<IDE config>/yolo/icons/<id>.svg|png`. This skill is about
  the *bundled* registry icons. Do not add user icons here.
- **Size:** dropdown icons are scaled to 16×16 by `AgentIcons` (`SIZE = 16`,
  `terminal/AgentIcons.kt:29`). Source assets may be larger; keep aspect ratio square-ish
  so the 16×16 crop looks right. Avoid tiny source glyphs (the first `continue.svg` was a
  13×13 plugin icon — too small, had to be re-cropped from the full logo).

## Sourcing tactics (try in order)

1. **Homepage HTML grep.** Fetch the agent's site and grep for the icon link:
   `<link rel="icon" ...>`, `rel="apple-touch-icon"`, `og:image`, or a `logo`/`icon` asset
   path. Often yields a direct favicon URL (rule 4) or a logo SVG.
2. **GitHub repo tree.** For OSS agents, list assets:
   `gh api "repos/<owner>/<repo>/git/trees/<branch>?recursive=1"` (quote the URL — the `?`
   expands under zsh). Grep for `logo`, `icon`, `.svg`.
3. **Wikimedia Commons.** Search `https://commons.wikimedia.org` for `<brand> logo` —
   frequently hosts a clean "without text" SVG variant (used for `grok`).
4. **Vendor / CDN asset URLs.** Brand sites and Webflow/Atlassian CDNs expose raw asset
   URLs (used for `rovo` colored symbol via `dam-cdn.atl.orangelogic.com`, `continue` via
   `continue.dev/continue-logo-black.svg`).
5. **WebFetch for VPN-gated sites.** If `curl` returns 000 / fetch fails on a blocked host,
   use WebFetch to pull the page and extract the asset URL.

### Crop (when only a wordmark+symbol asset exists)

Strip the `<text>` / wordmark and keep only the symbol paths. Verify:
- Remove any `<title>`/`<text>` elements.
- Recompute `viewBox` to tightly bound the remaining symbol (bbox of its paths), so it is
  not a tiny glyph floating in a huge canvas.
- Keep original `fill` colors (rule 3). Do NOT substitute a monochrome fill.

### Shape-match across candidates (when unsure which CDN file is the symbol)

When you have many candidate SVGs and must pick the one matching a known symbol, rasterize
each to a bitmap and compare silhouettes. A self-contained pure-Python rasterizer
(path parser: M/L/H/V/C/S/Q/T/A + relative, cubic flattening, scanline fill) can ASCII-preview
a shape without a system renderer (cairo/svglib were unavailable on this machine). Match the
filled silhouette, then install the *genuine colored* source — not a re-trace.

### Dark-theme (`_dark`) variants

IntelliJ's `IconLoader.getIcon` automatically selects `<name>_dark.svg` on dark themes
(already wired through `AgentIcons`). Only some icons need one — this is the rule that was
missed when `copilot_dark` / `grok_dark` were wrongly monochromed:

- **Create a `_dark` ONLY when the base symbol is monochrome / near-black** and disappears on a
  dark background: recolor the symbol to light gray `#BDBDBD` (matches the existing `codex_dark`
  / `omp_dark` convention). Keep the exact same paths and `viewBox` — recolor fills only, do not
  reshape. Examples: `command-code` (and the pre-existing `codex`).
- **Do NOT create a `_dark` for colored brand symbols** (e.g. `copilot`, `grok`): they stay
  visible on dark, and recoloring them to mono would violate rule 3.
- **Do NOT create a `_dark` for two-tone marks whose light part is already visible on dark**
  (e.g. `cursor`: black tile + white arrow — the white arrow shows on dark). Keep the base.
- **No `_dark` for favicon PNG fallbacks** (`kimi`, `opencode`): you cannot recolor a raster
  without tracing (rule 1). Ship the real favicon as-is.
- CSS `@media (prefers-color-scheme: dark)` inside an SVG is **ignored** by `IconLoader` — it
  renders the default (usually black) fill on dark themes, so such an icon still needs a `_dark`
  (e.g. `aug`, `autohand`).

## Validation gate

After installing, confirm there is no dangling `icon` path in `agents.json` (every referenced
`/icons/agents/<id>.<ext>` file must exist on disk, and no stray `.DS_Store`), and that the
project still compiles:

```bash
./gradlew compileKotlin --offline -q
```

If `src/test/kotlin/com/cnsharp/yolo/settings/AgentRegistryTest.kt` exists, also run it — it
asserts `agents[0].id == "claude"`, `agents[1].id == "codex"`, and
`claude.icon == "/icons/agents/claude.svg"`. NOTE: a copy may appear under `bin/test/` but that
is a stale, git-ignored IDEA output dir (not Gradle's `build/`) — do NOT rely on it, and do not
treat its presence as the test source.

## Checklist

- [ ] Genuine asset found (SVG symbol, or real favicon if no SVG) — not hand-traced.
- [ ] No text / wordmark in the final file.
- [ ] Original brand colors preserved.
- [ ] `_dark` variant added only where the base is monochrome/near-black (colored & two-tone-visible bases keep none); `_dark` is a `#BDBDBD` recolor of the same paths, not a reshape.
- [ ] File at `src/main/resources/icons/agents/<id>.<svg|png>`, square-ish, not tiny.
- [ ] `agents.json` `icon` field points at `/icons/agents/<id>.<ext>`.
- [ ] No dangling/missing icon path; `AgentRegistryTest` green.
