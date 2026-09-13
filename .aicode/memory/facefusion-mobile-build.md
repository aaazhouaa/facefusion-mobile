---
name: facefusion-mobile-build
description: facefusion-mobile 项目构建配置：2026-09-12 升级为 AGP 8.9.1 + Robolectric 4.16 + JDK 21，及验证限制
---
# facefusion-mobile 构建配置（~/workspace/work/android）

2026-09-12 应用户要求升级：
- AGP 8.7.3 → **8.9.1**（root build.gradle.kts）；Gradle wrapper 8.13 满足 AGP 8.9.1 的 ≥8.11.1 要求，未动。
- app compileOptions/kotlinOptions：Java 17 → **21**。
- dependencies 新增 `testImplementation("junit:junit:4.13.2")` + `testImplementation("org.robolectric:robolectric:4.16")`（此前项目完全没有测试依赖）。

验证情况：
- 配置评估 + 测试依赖解析通过（gradle 8.14.2，BUILD SUCCESSFUL）。
- **完整构建已打通（2026-09-12）**：用户 root shell 把 bench 机器的暂存产物（QAIRT 2.50.0.260828）直接拷到 `/opt/QNN`（include/QNN + jniLibs/arm64-v8a 14 个 .so + QNN_STAGED.txt）；我复制进项目：`app/src/main/cpp/include/QNN/`、`app/src/main/jniLibs/`（含从 /opt/QNN 顶层补回的 `QNN_STAGED.txt` 标记，qnnStage 见文件齐全即跳过）。构建链路上修了两个容器环境问题（见全局记忆 android-env-aarch64）：`/opt/android-sdk/ndk/29.0.14206865` 符号链接到适配版 NDK；clang++ wrapper 加 `--driver-mode=g++`。最终 `assembleDebug` ✅：62.3MB APK，含 libffnative.so + libc++_shared.so + 全部 QNN HTP 库。
- Robolectric 测试运行时依赖全局 init 脚本（robolectric-aarch64-shim.gradle：aarch64 上注入 shim jar + graphicsMode=LEGACY + conscryptMode=OFF），项目写测试时无需再配置；若测试要访问 app 资源需另加 `testOptions.unitTests.isIncludeAndroidResources = true`。
- Robolectric 4.16 在 aarch64 上的限制（SQLite/NATIVE 图形不可用）见全局记忆 android-env-aarch64。

## 2026-09-13 项目配置对齐本地环境（已验证）

- `gradle-wrapper.properties`：8.13 → **8.14.2**。本机 dists 里只有 8.14.2（旧的 8.13 钉法会让 `./gradlew` 现下 146MB 发行版）；改后 `./gradlew --version` 零下载即可跑。
- **新增 `work/android/local.properties`（gitignored）`sdk.dir=/opt/android-sdk`**：`ANDROID_HOME` 只由 `/etc/profile.d/android-sdk.sh` 导出，非登录 shell（工具链/IDE 起的）拿不到，AGP 直接报 `SDK location not found` 而构建失败。加该文件后 `./gradlew :app:preBuild --offline` → BUILD SUCCESSFUL 且 `:app:qnnStage` 正确跳过。
- `stage_qnn.sh` 增加“预暂存扁平布局”分支：本机 `QNN_SDK_ROOT=/opt/QNN` 不是标准 QAIRT 解包（无 `lib/aarch64-android`、无 `lib/hexagon-vNN/unsigned`），而是 `include/QNN` + `jniLibs/arm64-v8a`（Stub 与 Skel 混放）。两种布局现在都支持；`QNN_STAGED.txt` 的 SDK 版本号改从 `$SDK/QNN_STAGED.txt` 读（basename 只能得到 “QNN”）。两条分支均在 /tmp 隔离跑过，产物与项目内 staged 树逐字节一致。
- `BUILDING.md` 里过时的工具链声明（JDK 17 / build tools 34.0.0 / NDK 27.2.12479018 / CMake 3.22.1）改为本地实际的 JDK 21 / 35.0.0 / 29.0.14206865 / 3.28.3，并补上 Gradle 8.14.2、AGP 8.9.1 与 SDK 路径来源。

验证终点：`./gradlew :app:assembleDebug --offline --no-daemon --max-workers=2` → BUILD SUCCESSFUL（1m25s，60MB debug APK，含 libffnative + libc++_shared + 全 QNN HTP 库，versionCode 88 / 0.9.16-mic-debug）。

### 容器线程上限 pitfall（已复现+已解）
同一机器上并存多个 Gradle daemon（wrapper dist 与 /usr/bin/gradle 各起一个，每个 -Xmx3072m）时，`assembleDebug` 会在配置/执行中途失败于 `unable to create native thread: possibly out of memory or process/resource limits reached`（容器 cgroup 不可读、/proc 仅可见少数进程，属宿主 pids 限制；内存其实还剩 6GB+）。对策：`./gradlew --stop` 清干净后用 `--no-daemon --max-workers=2 -Dkotlin.compiler.execution.strategy=in-process` 跑，一次通过（日志中 `w: Detected multiple Kotlin daemon sessions` 是 daemon 堆积的信号）。