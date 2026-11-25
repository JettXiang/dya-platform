# 计划：抖音搜索页 502 取证报告

`Color preset: Business Blue — cue: 服务端故障取证的工程分析口吻`

`Intro mode: contained — cue: 单页取证报告，结论一句话可概括，无可用配图素材`

## 交付物

单文件 `douyin-502-forensics.html`，自包含、无外部依赖、无图片。图表依赖本地 `_shared/js/echarts.min.js`（离线副本，不走 CDN），初始化脚本为 `assets/charts.js`。

## 章节

1. 结论 — 根因与影响范围（callout）
2. 现场证据 — 恢复验证表、502 响应头原文表、链路图、两次 502 差异
3. 候选原因排除 — 12 项排除表 + 七轮实验明细表
4. 为什么本地复现不出来 — 一跳与两跳路由差异
5. 时间线 — 15:11 至恢复
6. 附录 — 取证脚本清单、取证边界与影响

## 视觉

- 页面中性底，品牌蓝仅用于 Intro 面、callout 与链接；表格与正文不染品牌色。
- 图 1：CSS 链路图（4 节点），放在「现场证据」。
- 图 2：ECharts 横向条形图「响应体量级对照」（对数刻度），放在「现场证据」，颜色取 `--chart-negative`（502 错误页）与 `--chart-series-1`（200 校验壳页）。
- 图 3：ECharts 横向条形图「七轮实验样本量对照」，放在「候选原因排除」表 4 之后，颜色取 `--chart-series-1`，零样本轮次用 `--chart-other`。
- 图 4：ECharts 横向堆叠条形图「链路类型与结果构成对照」，放在「为什么本地复现不出来」，颜色取 `--chart-positive` / `--chart-negative`。
- 图 5：ECharts 甘特/时序图「故障时间窗与访问时序」，放在「时间线」；故障窗用 `--chart-negative`，访问散点用 `--chart-positive` / `--chart-negative` / `--chart-warning`。
- 四张 ECharts 图统一容器高 320px（窄屏 360px），初始化集中在 `assets/charts.js`，共用一次 resize 监听。
- 表格在窄屏转为标签值块。
