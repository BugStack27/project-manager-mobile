---
date: 2026-11-26
description: 跨项目查看未完成需求（未开始 / 进行中 / 待审核）的聚合入口、数据拉取与列表展示
---

# 未完成需求聚合 - 任务规划

## 需求概述

当前手机端只能进入「项目」页后逐个点开项目查看需求，无法一眼看到多台桌面端、多个项目里还有哪些任务没做完。本需求在「项目」页增加「未完成」视图：按桌面端 `pending=1` 口径聚合展示未开始 / 进行中 / 待审核三类需求，标明所属电脑与项目，点开可进入现有需求详情。

- 口径：`todo + doing + review`（与桌面端 `UNFINISHED` 一致），不含候选池与终态。
- 范围：只读列表与跳转，不新增采纳 / 拒绝等操作入口；操作仍在需求详情页完成。
- 不做：不改桌面端接口、不新增底部 tab、不做按连接筛选与批量操作。

## 需求澄清记录

| 问题 | 用户答复 |
|---|---|
| 「未完成」的范围按哪个口径统计？ | 未开始 + 进行中 + 待审核（与桌面端未完成一致） |

## Requirements

### 1. 数据层：聚合未完成需求

| 属性 | 值 |
|------|-----|
| **目标** | 复用各台桌面端已有的 `/remote/project/:id/requirements?pending=1`，并发拉取每台电脑下各项目的未完成需求，汇总到 `RemoteState` |
| **涉及文件** | `ui/RemoteViewModel.kt` |
| **验收标准** | 新增 `unfinished` / `unfinishedLoading` / `unfinishedLoaded` / `unfinishedError`；每台电脑一个协程并发，项目内串行；单个项目失败只记录该电脑的错误，其他结果照常展示；连接移除后清掉其条目；提供可单测的纯排序函数（待审核 → 进行中 → 未开始，同档按项目名、编号倒序） |
| **依赖** | 无 |

### 2. 页面：项目页增加「项目 / 未完成」切换

| 属性 | 值 |
|------|-----|
| **目标** | 项目页标题下加一排 FilterChip「项目 N」「未完成 N」；切到未完成时按需拉取并展示跨项目需求列表，卡片显示状态标签、编号、标题、所属电脑与项目 |
| **涉及文件** | `ui/RemoteScreens.kt`、`ui/TodoScreen.kt`（复用 chip 配色） |
| **验收标准** | 切到未完成触发一次强制刷新；刷新按钮在未完成视图下刷新聚合数据；加载 / 失败 / 空三种状态齐全；点击条目进入现有需求详情路由 |
| **依赖** | 1 |

### 3. 导航接线与文档

| 属性 | 值 |
|------|-----|
| **目标** | `AppRoot` 的 `remote` 路由传入聚合加载与打开详情的回调；README 与设计文档补一句入口说明 |
| **涉及文件** | `ui/AppRoot.kt`、`README.md`、`doc/mobile-app-design.md` |
| **验收标准** | 从聚合列表进入详情后按 id 加载对应项目需求；文档能说明口径与入口 |
| **依赖** | 1、2 |

## Complements

### 1. 数据层 ✅

- 变更文件：`ui/RemoteViewModel.kt`
- 实现：`RemoteState` 增加 `unfinished` / `unfinishedLoading` / `unfinishedLoaded` / `unfinishedError`；`loadUnfinished()` 对每台已拿到接入点的电脑起一个协程，项目之间串行调用 `requirements(projectId, pendingOnly = true)`；按 `连接 + 需求 id` 去重；单项目失败只累计为「该电脑 N 个项目读取失败」，其余结果照常展示；连接移除时同步清掉其条目。`sortedUnfinished()` 负责待审核 → 进行中 → 未开始 → 其他，同档按项目名、编号倒序。
- 验证：`UnfinishedSortTest` 2 组（状态优先级、同档排序）。

### 2. 页面与导航 ✅

- 变更文件：`ui/RemoteScreens.kt`、`ui/TodoScreen.kt`、`ui/AppRoot.kt`
- 实现：项目页标题下加「项目 N」「未完成 N」两个 FilterChip（复用待办页 chip 配色）；切到未完成时 `LaunchedEffect` 强制拉取一次，刷新按钮在未完成视图下刷新聚合数据；列表卡片显示状态标签、编号、标题、提问数、来源「电脑 · 项目」；加载 / 部分失败 / 全部失败 / 空四种状态齐全；点条目进入现有 `remote/p/{connId}/{projectId}/r/{rid}` 详情路由。
- 验证：`:app:compileDebugKotlin`、`:app:testDebugUnitTest` 通过；无连接设备，真机点击与视觉未在本轮复测。