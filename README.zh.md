# YOLO: AI Agents Extender

一款 IntelliJ IDEA 插件,为你提供一个独立的 **YOLO** 面板 —— 一个专门的工具窗口(右侧,**y** 图标),
它列出你的 AI 命令行工具,并只需**一次点击**就在 IDE 内**真实的、可交互的终端**中启动其中任意一个。
它的独特之处:**智能体打印的一切都变得可点击** —— 文件路径、堆栈跟踪、类型名和 URL 统统变成导航链接,
让你能从智能体的输出直接跳转到对应代码。

它完全基于 **IntelliJ 公开 API** 构建,因此能通过 JetBrains 官方市场审核,可以像普通插件一样发布。
它**没有**接入或依赖 IDEA 内置的 Terminal。

---

## 功能特性

### YOLO 面板

![yolo-panel.png](screenshots/yolo-panel.png)

一个工具窗口(右侧,**y** 图标),在不触碰任何内部 Terminal API 的前提下,复刻了 Terminal 的 **AI Agents** 体验:

- 列出你**已安装**的智能体 —— 包括官方推荐的智能体(Claude Code、Codex、CodeBuddy……)以及你自己的自定义工具。
  在 `PATH` 上检测不到的智能体不会显示,因此列表始终与当前机器相关。
- 每一行显示智能体的图标、名称以及其配置的跳过(skip)与恢复(resume)标志。
- 下拉列表从**已缓存的安装扫描结果**瞬间加载 —— 复用上一次运行所做的检测,只有当已安装智能体的集合真正发生变化时,
  才在后台重新扫描刷新。
- 面板标题栏中有两个开关 —— **YOLO(跳过权限)** 和 **Resume Session** —— 以及设置齿轮和一个 **Launch(启动)** 按钮。

### YOLO 模式

面板标题栏里的 **YOLO(跳过权限)** 开关。打开它,下一次启动智能体时就会在命令后追加其权限绕过标志 ——
Claude Code 是 `--dangerously-skip-permissions`,Codex 是 `--yolo`,CodeBuddy 是 `-y`,以此类推。

该标志是**每个智能体独立配置**的,且完全可定制。插件会为已知智能体预填正确的标志,
但每个值都可编辑,运行时没有任何硬编码。少数智能体(如 Goose)通过环境变量而非标志来绕过权限,这些也都已处理。

**该开关默认关闭,且永远不会自行打开。**

### 恢复会话

面板标题栏里的 **Resume Session** 开关。打开它,下一次启动智能体时就会在命令后追加其恢复标志 ——
Claude Code / CodeBuddy / Copilot / Goose / Hermes / Kimi / Pi 为 `-r`,Codex 为 `resume --last`,Cursor / TraeCode 为 `--resume`,
Cline 为 `--taskId` —— 于是智能体会继续上一次的会话,而不是从头开始。

该标志是**每个智能体独立配置**的,且完全可定制(见[配置](#配置))。插件已为常见智能体预填好正确的恢复标志;
自定义工具则在 **恢复会话参数(Resume flag)** 列中自行填写 —— 自定义工具没有内置的 `agents.json` 条目,
那一列正是你为它设置恢复参数的唯一途径。没有命令行恢复能力的智能体(例如 OpenCode 用的是 TUI 的 `/resume`)
在开关打开时仍照常启动、不会注入任何标志。

**该开关默认关闭,且永远不会自行打开。**

### 运行在面板内(真实终端)

**先在下拉列表中选择一个智能体,然后点击 Launch(启动)** —— 下拉只做选择;当你按下 **Launch** 时,智能体在一个
**直接嵌入 YOLO 面板的、真实的、可交互的终端**中打开:一个真正的 PTY(通过 JediTerm + PTY4J,即 IDE 自带的那套终端模拟器),
原地渲染智能体的 TUI。提示符、编辑器以及你在 rc 中定义的 `PATH`(nvm / fnm / npm 全局 bin……)都能正常工作,
因为智能体运行在一个交互式登录 shell 中。

由于"选择"与"执行"分离,**跳过权限** / **Resume Session** 开关总是作用于下一次启动,且改动下拉列表绝不会杀掉正在运行的终端。
你上次启动过的智能体会被记住,并在下次打开面板时自动重新选中。

**每次点击 Launch 都会新开一个标签页** —— 一个独立的、真实的 PTY —— 因此多个智能体可以并行运行,
你可以像使用 IDEA 自带的 Terminal 一样在标签页之间切换。每个标签页都带有一个关闭(✕)按钮,用于终止该智能体的进程;
关闭最后一个标签页后,面板会回到空状态占位提示。

- 智能体启动时,**光标自动落到终端里**,你可以立刻开始输入。
- **Ctrl+C 不再被 IDEA 的复制快捷键劫持。** 终端获得焦点时,Ctrl+C 会直接穿透到嵌入式终端,
  而不是弹出 IDEA 的"快捷键冲突"对话框。它是否会中断正在运行的智能体,取决于智能体自身的 TUI。

> 这完全基于**公开 API**:JediTerm 和 PTY4J 是随 IntelliJ 平台一同发布的第三方库(并非
> `@ApiStatus.Internal` / `@Experimental` 的 Terminal API),因此插件在 JetBrains 市场仍可发布。
> IDE 自带的 `ConsoleView` 是只读输出(没有交互式输入),所以一个真实的智能体只能靠嵌入真正的终端
> 才能活在面板里 —— 而这正是本插件所做的。

### 可点击的终端输出

智能体运行期间,它的输出会被扫描并识别为引用、转换成超链接。点击链接即可跳转到对应位置,并**自动隐藏 YOLO 面板**,
使其不再遮挡编辑器。

| 你打印…… | 变成指向……的链接 |
|---|---|
| `src/foo/Bar.kt:42`、`/abs/Bar.kt:42:13`、`C:\foo\Bar.kt:7` | 该文件对应行 / 列 |
| `./Makefile:10`、`~/x/y.kt:3`、`file:///abs/x.kt` | 该文件(支持家目录相对路径和 `file://` URI) |
| `path:12-18` | 行范围起点的文件 |
| `"/path with space/Bar.kt":5` | 含空格的带引号路径 |
| `Bar.java:123`、`Bar.kt:12` | 裸堆栈帧 |
| `File "app/main.py", line 42` | Python / JS 回溯帧 |
| `plugin.xml`、`build.gradle.kts`、`README.md` | 项目中任意位置的裸文件名 |
| `com.foo.Bar` / `Bar` | 类声明(限定名或项目内的简单名) |
| `Bar.method` / `Bar#method` | 具体的成员 / 字段 / 内部类 |
| `https://example.com` | 该 URL,在你的系统浏览器中打开(面板**不会**隐藏) |

- **行 / 列导航** 对路径、堆栈帧和成员引用都有效。
- 支持**无扩展名文件**(`Makefile`、`Dockerfile`)和 **Windows 路径**。
- **你输入的内容不会变成链接。** 链接只加在智能体的**输出**上 —— 你正在智能体输入框里敲的文字保持纯文本,
  不会在光标下变成链接。
- URL 是例外:点击它会打开浏览器,但保持面板打开。

> ### 警告 —— 关于 YOLO 模式
>
> 让 AI 智能体**不经确认**地运行命令、编辑文件,可能带来不可逆的改动、执行不受信任的代码,
> 或暴露你的系统。这些风险来自智能体本身以及那个绕过权限的标志。**本插件只是帮你打开那个标志 —— 它自身
> 不添加任何此类行为**,不会代表你执行任何操作,也不对智能体所做之事负责。请只在你信任的环境中开启 YOLO 模式。

---

## 环境要求

| | |
|---|---|
| IDE | IntelliJ IDEA **2023.3**(构建号 `233`)或更高版本 |
| 依赖 | 除 IntelliJ 平台本身外无其他依赖 —— **无需** IDEA 内置 Terminal |

---

## 构建

使用标准 Gradle 任务构建:

```bash
./gradlew buildPlugin
# → build/distributions/yolo-{version}.zip
```

**针对本地安装的 IDEA 构建** —— 默认情况下插件编译所依赖的是 `gradle.properties` 中固定的 IntelliJ SDK。
如果你想改为针对机器上已安装的 IDE 构建,可在项目根目录创建一个 `local.properties` 文件,指向它的安装位置:

```properties
localIdeaPath=/Applications/IntelliJ IDEA.app
```

## 安装

**从市场安装** —— 在 *Settings | Plugins* 中搜索 **YOLO** 并安装。

或从官网安装:[https://plugins.jetbrains.com/plugin/33442-yolo-ai-agents-extender/](https://plugins.jetbrains.com/plugin/33442-yolo-ai-agents-extender/)

**从磁盘安装** —— 构建或下载 `yolo-<version>.zip`(见[构建](#构建)),然后
`Settings | Plugins | ⚙ | Install Plugin from Disk…` 并选择该 zip。按提示重启。

---

## 实验版(`exp` 分支)

`exp` 分支是本插件的一个**备选的、实验性的构建**,采用了不同的架构。它不像本 `main` 主线那样提供独立的 YOLO 面板,
而是**直接扩展 IDEA 内置 Terminal 的 "AI Agents" 下拉菜单** —— 把你的 CLI 工具追加进那个菜单,
并在 Terminal 工具栏上加一个"跳过权限"(YOLO)开关。

它之所以"实验性",是因为它接入了**内部** Terminal 扩展点
(`terminalAgentProvider`、`shellExecOptionsCustomizer`、`toolWindowInitializer`,来自
`org.jetbrains.plugins.terminal`),这些扩展点目前仍是 `@ApiStatus.Internal`,没有公开的替代方案。

### 与主线的取舍对比

| | 主线(`main`) | 实验版(`exp`) |
|---|---|---|
| 集成方式 | 独立的 YOLO 面板 + 嵌入式终端 | IDEA Terminal 的 AI Agents 下拉菜单 |
| 智能体输出可点击 | 是 —— 文件 / 堆栈 / 类型 / URL 链接 | 否(不含链接过滤器) |
| 所用 API | 仅公开 IntelliJ API | 内部 Terminal API |
| 市场发布 | 已发布 | **未发布**(无法通过审核) |
| 最低 IDE 版本 | 2023.3(`233`) | 2026.1(`261`) |
| IDE 家族 | 所有 IntelliJ 平台 IDE | IntelliJ IDEA |

### 如何获取

因为它依赖内部 API,JetBrains 市场审核会拒绝实验版,因此它改由仓库的 **Releases** 页面分发:

1. 切到 `exp` 分支并构建:
   ```bash
   git checkout exp
   ./gradlew buildPlugin
   # → build/distributions/yolo-<version>.zip
   ```
2. 从仓库的 **Releases** 页面下载该 zip,或使用你刚构建出来的那个。
3. 通过 `Settings | Plugins | ⚙ | Install Plugin from Disk…` 安装并重启。

> 需要 **IntelliJ IDEA 2026.1 或更高版本**(`since-build 261`),且需启用自带的 **Terminal** 插件

该分支是出于实验目的的衍生版本;已发布、受支持的主线始终是 `main`。

---

## 配置

**`Settings | Tools | YOLO: AI Agents Extender`** —— 或点击 YOLO 面板标题栏中的齿轮。

![yolo-settings.png](screenshots/yolo-settings.png)

所有配置都集中在一张表里。每一行是一个智能体,且每行都带有自己的 skip 标志:

| 列 | 含义 |
|---|---|
| 图标 | 推荐智能体自带的图标;自定义工具则为你提供的文件或默认闪电图标。命令不在 `PATH` 上时,此列在表中显示为灰色 |
| ID | 唯一标识符 |
| 显示名 | 在 YOLO 面板中显示的名称 |
| 命令 | 可执行文件名 —— 必须在 `PATH` 上可解析 |
| 基础参数 | 始终传入的参数,以空格分隔 |
| 跳过标志 | 权限绕过参数,在 YOLO 开关打开时追加 |
| 恢复会话参数 | 恢复参数,在 Resume Session 开关打开时追加(如 `-r`、`--resume`) |

行分两种,按优先级从高到低排列:

1. **推荐智能体**(Claude Code、Codex、CodeBuddy……)—— 只读,且 **Claude Code** 和 **Codex** 固定在最上方,不可删除。
2. **你自己的自定义工具** —— 完全可编辑,按你创建的顺序排列。

推荐智能体上只有跳过标志与恢复会话参数可编辑。这是有意为之:插件应当扩展面板,而不是接管它。

### 便捷功能

- **跳过标志与恢复参数自动预填。** 打开设置,已知智能体已经带上了正确的标志。在新建行中键入已知 ID 或命令时,它们会随输入自动补全。你手动设置的值永远不会被覆盖。
- **重复项在你输入时即被捕获。** 重复的 ID 或命令会立刻把状态行变红,且应用(Apply)会拒绝保存。命令按可执行文件名比较,因此 `/usr/bin/claude` 和 `claude.cmd` 算作同一个工具。
- **每次启动时检测已安装的智能体。** 插件在后台检查每个已知智能体的命令 —— 先查 `PATH`,再实际运行一次(`--version`) —— 然后把检测到的推荐智能体加入列表。这发生在**每次启动**,而不只是第一次,因此你之后安装的某个工具(例如通过 npm 安装的 OpenCode)会自动出现。它**不会**自动发现你自己写的任意工具 —— 那些请作为自定义工具添加。结果会被缓存,因此面板之后能立即打开。
- **校验(Validate)** 以相同方式(先查 PATH,再运行一次)检查某行的命令,并在有图标 URL 时下载该图标。

### 已知智能体

此列表只是方便起见 —— 并非唯一真相来源。最终运行的是设置里所写的内容。

| id | 显示名 | 命令 | 官网 |
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

任何未列出的工具都可以作为自定义工具正常使用;只需自己填好它的标志即可。

---

## 故障排查

插件会记录它所启动的每一次运行。要查看实际运行了什么,可 grep IDE 日志
(`<version>` 为 IDE 的构建号,例如 `2026.2`):

```bash
# macOS
grep "Agent YOLO" ~/Library/Logs/JetBrains/IntelliJIdea<version>/idea.log

# Linux
grep "Agent YOLO" ~/.cache/JetBrains/IntelliJIdea<version>/log/idea.log

# Windows (PowerShell)
grep "Agent YOLO" "$env:LOCALAPPDATA\JetBrains\IntelliJIdea<version>\log\idea.log"
```

**某个智能体没有出现在面板中。** 它的命令在 `PATH` 上解析不到。面板只列出被检测为已安装的智能体;
请对该行执行一次设置中的 **Validate**,或检查该命令在启动 IDE 的那个 shell 中能否解析
(IDE 可能继承了与你交互式 shell 不同的 `PATH`)。

**标志没有被应用。** 确认对应的开关已打开(跳过权限对应 YOLO(跳过权限) 开关,恢复会话对应 Resume Session 开关),且该行在该列中配置了值。每次启动的日志行会显示最终命令,包括是否有内容被注入。

**某个打印出来的路径 / 类型名不可点击。** 链接只在该引用能解析为当前项目中真实存在的文件或类时才出现
(因此随机的单词不会被链起来)。请确保文件位于内容根(content root)内,且对于类型名,Java 模块已启用。

**设置改动不生效。** 插件注册了一个 `Configurable`,而 IDEA 无法动态加载它。安装或更新后请重启 IDE。
