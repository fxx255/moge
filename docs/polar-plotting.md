# 极坐标绘图

沿用 `plots` / `[[FIGURE:n]]` 协议，单张图增加 `polar` 后，表达式解释为 `r=f(theta)`，由客户端安全表达式引擎采样。普通图仍使用 `y=f(x)`。

```json
{
  "title": "心形线 r=1−cos θ",
  "polar": {"radiusMax": 2.2, "angleStepDegrees": 30},
  "series": [{"expr": "1-cos(theta)", "thetaMin": 0, "thetaMax": 6.283185307179586}],
  "legend": false
}
```

- `polar: true` 使用默认设置；省略或 `false` 保留直角坐标。
- 每条序列的 `thetaMin` / `thetaMax` 均为弧度，默认 `0..2π`。表达式支持 `theta` / `θ`，常量支持 `pi` / `π`。
- 负半径按原数学含义转换；无定义或无穷取值断开曲线。含极点、无界曲线须指定有限的 `radiusMax`，极远点与跨极点跳跃不连接。
- `radiusMax` 为正视窗半径，省略时按采样数据留 8% 余量；`radialTicks` 控制正半径刻度，`angleStepDegrees` 支持 15 / 30 / 45 / 60 / 90，默认 30；`grid: false` 隐藏网格，保留极轴、外圆和刻度。
- 圆形视窗自动保持等比例，忽略 `x` / `y` 范围和刻度。`points` 仍为直角坐标 `[x,y]`，可叠加标记点，不能与 `voronoi` 共用。
- 半径标签沿用邻近避让；颜色、图例、曲线线型、点标记与透明标注共用现有渲染器。`fill: true` 在极坐标图中填充至极点。`shades` / `markLines` 保留原有直角坐标语义，未增加极角区域协议。

示例：玫瑰线 `cos(2*theta)`、双纽线 `sqrt(cos(2*theta))`、螺线 `theta`（可增加角度区间）。
