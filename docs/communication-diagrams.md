# 通信框图生成与样图

布局入口仍为 `DiagramLayout.layout`，绘制入口仍为 `DiagramRenderer.render`。新增的 `communication` profile 根据有向信号拓扑计算处理阶段，根据支路角色排列平行信号；不使用参考图片坐标或单独绘制样图的代码。

## 结构化约定

- `shape: sampler` 绘制抽样开关，`label` 写“抽样”，`subLabel` 写抽样时刻。
- `shape: bus` 表示串并转换、并串转换、分路或复用竖框；名称可用换行竖排。以 `role: combiner`、`label: Σ` 也可画多路求和竖框。
- `role: branch_0_filter` 等表示支路及功能。同一支路保持同一数字，支持 0～3；I/Q 也可使用 `i_` / `q_`。阶段由连线推导，JSON 数组顺序不影响布局。
- `role: transform` 的矩形框跨越相连信号，适用于 FFT/IFFT。`real_imag` 表示取实部和虚部的公共框。
- `edge.channel: 0` 等表示两个多端口框之间独立的并行信号，不是坐标。每条边保留自己的信号名与箭头。
- `repeatLastLane: true` 表示最后一条代表更大并行系统的末路，自动补省略号；实际信号标签应注明 N−1 等下标。
- `noise` / `carrier` / `constant` / `threshold` / `clock` 是辅助源，连接到真实作用节点，以 top/bottom 指定输入侧；不会增加主信号链的阶段。求和输入使用显式 `polarity`。

SSB 的 `textbook_dual_branch`、相干接收机的 `iq_demodulator` 和旧 `generic` profile 保留。新形状或 `branch_数字_` 角色在未指定 profile 时也会选择通信布局。不能分层的循环拓扑回退到原通用布局；反馈边应明确标成虚线。仍限制单图 24 个节点、40 条连线和最多 4 条代表支路。

三路到两路等转换会按每个阶段的有效支路数展开，保证公共前后级居中。跨越节点的走线由正交路由器避让。保存图片的渲染版本为 d6，已存结构化框图会按既有版本更新机制重新生成图片。

## 参考覆盖

测试资源位于 `app/src/test/resources/diagrams/communication/`；`manifest.json` 记录参考图编号与样图对应关系。参考图按原文件名排序编号，共 17 张；第 13、14 张为重复样式，第 12 张的发送和接收分别导出，最终生成 16 个样例：

1. 相干检测与抽样
2. 带通噪声相干解调及测试点
3. 双路匹配滤波接收机
4. 基带脉冲传输系统
5. 带通噪声复包络分解与旁路
6. 相关接收机
7. 8PSK 正交调制
8. 正交基函数合成与系数恢复
9. MASK 幅度键控
10. 多载波 OFDM 调制
11. OFDM IFFT 发送处理
12. OFDM FFT 接收处理
13. OFDM 复包络与正交调制
14. 分路编码、并行信道与合路
15. PCM 编码与时分复用
16. 积分、抽样与判决接收机

## 验证和重新导出

`CommunicationDiagramAcceptanceTest` 从 JSON 解析全部样例，检查节点不重叠、连线正交且不穿过其他节点、画布边界、顺序稳定、多端口分离和公式有效性，并使用 Robolectric Native Graphics 渲染明暗两套 PNG。它验证的是客户端生成能力；没有远程调用语言模型，因此模型是否给出正确电路拓扑仍取决于题意和回答。

Windows PowerShell（使用项目要求的 JDK 21）：

```powershell
$env:DIAGRAM_EXPORT_SAMPLES = '1'
.\gradlew.bat --no-configuration-cache --max-workers=2 :app:testDebugUnitTest --tests 'com.moge.app.domain.diagram.*'
.\scripts\diagrams\export-gallery.ps1
```

输出为 `app/build/diagram-samples/index.html`，可在浏览器切换白纸/黑板主题，查看原图及生成规格。四张 `overview-*.jpg` 用于小批量视觉检查。脚本可通过 `-Destination` 指定额外导出目录；不改动参考图。
