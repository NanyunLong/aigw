# 上游基线与定制声明（UPSTREAM BASELINE）

> 本文件是"我们相对上游改了什么"的唯一权威声明。**任何从上游同步/合并前，先读这里。**
> 目的：拿到上游的新功能和 bug 修复，同时**保证我们的定制不被覆盖**。

## 1. 上游是谁

- 上游仓库：`https://github.com/xibai6668/Android-AI-gateway`
- 本仓库：`https://github.com/NanyunLong/aigw`

## 2. 关键事实（2026-11-24 核实）

- 本仓库是**一次性重新入库**：建仓时把上游整棵代码 + 完整历史导入，
  因此 **本仓库与上游 main 没有共同祖先**（`git merge-base` 为空）。
- **`git merge upstream/main` 永远会失败/大面积冲突**，禁止使用。
- 建仓基线提交：`f5170a9`（"aigw: 基于 Android-AI-gateway 的多商家反代构建"）。
- 建仓时对应的上游版本：`838fe76`（"feat(security): 脱敏覆盖手机号、身份证与银行卡"，2026-11-22）。
- 经核实，基线 `f5170a9` 与上游 `838fe76` 的**文件树差异只有 4 个文件**：
  - `ProviderPresets.kt`（我们独有）
  - `CustomProviderScreen.kt`（我们独有）
  - `.github/workflows/build-selfsigned.yml`（我们独有）
  - `MeScreen.kt`（上游独有，约 35 行"检查更新/项目地址/自动更新开关"）

> 结论：上游的历史提交（300+ 个）**成果早已在我们的代码里**；判断"上游有没有新东西"，
> 不能用提交数，只能用 `git diff HEAD upstream/main --name-only` 的文件树差异。

## 3. 我们的定制面（这些文件禁止被上游整文件覆盖）

以下文件是我们在建仓后改动/新增的（`git diff --name-only f5170a9 HEAD` 的结果），
**从上游同步时必须逐块手动挑取，绝不允许 `git checkout upstream/main -- <file>` 整文件覆盖**：

### 功能/代码（核心定制，最高优先级保护）
- `core/src/main/kotlin/dev/aigw/core/provider/custom/ProviderPresets.kt`  ← 多商家预设（上游无）
- `core/src/main/kotlin/dev/aigw/app/ui/CustomProviderScreen.kt`           ← 自定义供应商 UI（上游无）
- `core/src/main/kotlin/dev/aigw/core/gateway/GatewayEngine.kt`
- `core/src/main/kotlin/dev/aigw/core/provider/loomy/*`（LoomyAuthClient / LoomyConstants / LoomyProvider / LoomyWechatClient）← 微信扫码登录
- `app/src/main/kotlin/dev/aigw/app/ui/AppViewModel.kt`
- `app/src/main/kotlin/dev/aigw/app/ui/HomeScreen.kt`
- `app/src/main/kotlin/dev/aigw/app/ui/HomeWidgets.kt`
- `app/src/main/kotlin/dev/aigw/app/ui/MeScreen.kt`（注意：此文件上游也在改，最易冲突）
- `app/src/main/kotlin/dev/aigw/app/ui/ProviderDetailScreen.kt`
- `app/src/main/kotlin/dev/aigw/app/ui/providers/ProviderUi.kt`
- `app/src/main/kotlin/dev/aigw/app/ui/providers/loomy/LoomyUi.kt`
- `core/src/main/kotlin/dev/aigw/core/update/UpdateSource.kt`
- `core/src/test/kotlin/dev/aigw/core/update/UpdateTest.kt`

### 品牌/资源（禁止动）
- `app/src/main/res/values/strings.xml`（app_name = **SY**）
- `app/src/main/res/values/themes.xml`
- `app/src/main/res/mipmap-*/ic_launcher.png`（主人定稿图标，整图直出，不裁不改）
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- `app/src/main/res/drawable/ic_launcher_foreground.xml`

### 构建/CI（禁止动）
- `app/build.gradle.kts`（版本号由主人决定，**当前 2.38.5 / 235，不擅自改**）
- `.github/workflows/android-release.yml`
- `.github/workflows/build-release.yml`
- `.github/workflows/build-selfsigned.yml`（自签名 CI）
- `.github/workflows/sync-upstream.yml`
- `.gitignore`、`README.md`

## 4. 正确的同步流程（只做这个，不做 merge）

1. `git fetch upstream --prune --no-tags`（**绝不加 `--tags`**，否则撞本地同名 tag 必失败）
2. `git diff --name-only HEAD upstream/main` —— 看上游相对我们到底改了什么文件
3. 把上一步的文件与「第 3 节定制面」比对：
   - **不在定制面里的文件** → 可安全 `git checkout upstream/main -- <file>` 或 cherry-pick
   - **在定制面里的文件** → 必须人工逐块 diff、只挑上游确实必要的新增逻辑，保留我们的实现
4. 绝不 `git merge upstream/main`（无关历史，必炸）
5. 改完照常 commit → push → 触发 `android-release.yml` 出包

## 5. 自动报告

`.github/workflows/sync-upstream.yml` 每天 03:00（北京时间）运行，**只报告不合并**：
输出"上游相对我们改动的文件清单"，并标出哪些落在定制面上。不会再因为无关历史或 tag 报红。
