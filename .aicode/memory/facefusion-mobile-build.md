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