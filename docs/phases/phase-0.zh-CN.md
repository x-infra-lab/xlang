# 阶段 0 — 骨架

**状态：** 已交付。
**Tag：** `phase-0`。

P0 的唯一任务是把“工作间”布置好。它自身不包含编译器、虚拟机、链接器的
真正逻辑，但为后续每个阶段都留好了落地的位置。

## P0 交付内容

1. Gradle 多模块工作区，Java 21 toolchain + JUnit 5：
   `xlang-cli`、`xlangc`、`xlangvm`、`xld`、`xrt`。
2. 单一入口命令 `xlang`，负责分发到未来的子命令。P0 只有三个子命令是
   真的做事：`version`、`help`、`phase`。
3. 所有计划中的子命令都有占位实现（`compile`、`link`、`run`、`trace`、
   `mem`、`layout`、`syscall-trace`）。占位命令会大声失败并明确告诉你
   它计划在哪个阶段落地——这样 CLI 表面从第一天起就稳定，后续阶段只是
   把占位替换成真实逻辑。
4. 每个子模块都有一个 `scaffoldLoads` 冒烟测试；CLI 还额外校验 `help`
   输出以及 `phase` 报告。
5. 语言规范 v0.1 —— P1 词法/语法分析器要消化的文法。现在冻结，避免 P1
   设计漂移。
6. `CURRENT_PHASE` 文件记录当前阶段号；`./gradlew phaseInfo` 会打印它。

## 如何验证 P0

```bash
./gradlew build                            # 全部测试通过
./gradlew :xlang-cli:run --args="version"  # 输出 "xlang 0.1.0-P0"
./gradlew :xlang-cli:run --args="help"     # 列出所有计划中的子命令
./gradlew :xlang-cli:run --args="phase"    # 输出 "P0 (scaffold)"
./gradlew :xlang-cli:run --args="compile x.xl"
# -> 非零退出，并打印："planned for P6"
```

## 保留到 P1 的设计约定

- CLI 的 `run(String[])` 从不直接调用 `System.exit`。测试用 `Main.run`
  取返回值判定，此约定贯穿所有阶段。
- 每个子项目自足：根 `build.gradle.kts` 只统一 Java 21、`--release 21`、
  `-Werror`、UTF-8，不添加跨模块依赖。只有 `xlang-cli` 依赖其它模块。
- 包结构与模块一一对应：`com.xlang.cli` / `com.xlang.compiler` /
  `com.xlang.vm` / `com.xlang.linker` / `com.xlang.runtime`。
- `Main.plannedPhaseFor` 是“哪个占位命令属于哪个阶段”的唯一事实来源。
  某阶段落地时，直接替换占位实现，不要再新加一张表。

## P0 的非目标

- 不写词法器、语法分析器、字节码、虚拟机；这些从 P1 起。
- 除了 `greeting()` 这种标记方法外，不在模块之间发明接口。我们不愿在
  还不知道要传什么的时候就先造抽象。
