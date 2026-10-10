---
name: commit-convention
description: 提交信息规范——写 commit message 前查这里，保证历史可读、可检索。
---

# 提交信息规范（Conventional Commits）

## 格式

```
<type>(<scope>): <主题>

[可选正文：为什么改、怎么改的，72 字符换行]
```

- 主题用中文或英文均可，但同一仓库保持一致；祈使语气，结尾不加句号，≤50 字符
- scope 是模块名（如 `cli`、`permission`、`config`），单模块仓库可省略

## type 取值

| type | 含义 |
|---|---|
| feat | 新功能 |
| fix | 缺陷修复 |
| refactor | 既非新增功能也非修bug的改动 |
| test | 测试相关 |
| docs | 仅文档 |
| chore | 构建/工具链/杂务 |
| perf | 性能优化 |

## 例子

```
fix(permission): 危险命令正则漏匹配 rm -fr 组合

DENY/ASK 规则只覆盖了 -rf，换序的 -fr 与分开写的 -r -f 均未命中。
改为按 flag 集合匹配，并补三个用例。
```

## 何时拆分

一个提交只做一件事：功能修复与格式化重构绝不混在一个提交里。
