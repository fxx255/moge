# 编码图模板与使用范围

编码器由本地数学核心与固定模板绘制。语言模型只提供参数，抽头、寄存器和状态边均从同一规格推导。与原通信框图共用 diagrams / [[FIGURE:n]] 图槽、明暗主题、PNG 持久化及规格台账；渲染版本更新为 d6，旧结构化图会重新绘制。

## 参数协议

二进制单输入前馈卷积码使用 `convolutional_encoder`；卷积码状态图使用 `convolutional_state_graph`。示例：

```json
{"title":"K=3 卷积码编码器","profile":"convolutional_encoder","coding":{"version":1,"memory":2,"generators":[[0,1,2],[0,2]],"outputMode":"serial"}}
```

D⁰ 是当前输入，D¹ 是延迟一拍；状态位最近输入在前。输出依照 generators 数组顺序。编码器支持 1～8 级记忆、1～4 路输出；`outputMode` 可选 serial / parallel。d6 按用户提供的图 9.5.2 / 9.5.3 绘制：寄存器共用中间水平链，K=3 使用宽矩形、K=4 使用紧凑矩形；两路输出各自通过上方、下方的一个多输入模 2 加法圆。远端抽头水平进入左右端，近端抽头从内侧进入；串行模式采用右侧开放触点、拨杆与旋转箭头。保留 uⱼ、g⁽ⁱ⁾、cⱼ⁽ⁱ⁾ 等教材标注，生成多项式放在下方。单抽头直接输出，不添加多余加法器。多于两路时向上下外侧扩展，远路抽头避开内侧加法器，输出走线使用独立通道。

状态图最多 8 个状态。四状态按用户图 9.5.4 的扁菱形绘制：左 a/s₀=00，上 b/s₁=10，下 c/s₂=01，右 d/s₃=11；外围弧线、中心双向弧线、左右自环位置与样图对应。所有边均为单色实线箭头，边标注恢复为“输出(输入)”。状态 s 编号按最近位在低权序号排列。八状态沿用相同符号与标注，使用有向环 `000 → 100 → 010 → 101 → 110 → 111 → 011 → 001 → 000` 排列，其余边分别使用内弧或外弧。编码算法和参数协议不变。

系统循环码使用 `cyclic_encoder`：

```json
{"title":"(7,4) 系统循环码编码器","profile":"cyclic_encoder","coding":{"version":1,"n":7,"k":4,"generatorExponents":[0,1,3]}}
```

生成多项式最高次数必须等于 n−k、最高项和常数项必须为 1，并能整除 xⁿ+1。支持 n≤63、1～8 个校验位；信息高次位先输入，信息位在前，校验位在后。示例 1001 → 1001110。上方为最高位返回输入异或的反馈线，下方为反馈分配抽头。寄存器 Sᵢ 对应余式的 xⁱ 系数。K₁、K₂ 使用同套教材开放触点与拨杆，同步切换：信息阶段 K₁ 接 f、K₂ 输出原信息；校验阶段 K₁ 接 0、K₂ 移出余式。开关默认画在信息阶段，图下注明两阶段和各自拍数。

这三种 profile 不需要 nodes/edges。旧通信 profile 仍要求通用拓扑。参数缺失不会猜抽头，非法图保留失败位置。递归卷积码、多输入编码器、非系统循环码和超过 8 个状态的图尚未纳入首版模板。

## 样例和验证

样图在 `docs/coding-samples/`，包含 K=3、K=4 卷积码编码器、(7,4) 系统循环码、四状态图、八状态图与四路并行压力样例，另有二状态、八级循环码、一级循环码、单抽头、八状态四路输出及三路串行输出边界样例。共 12 组规格、24 张 PNG。每个样例均由生产 `DiagramParser → DiagramRenderer` 路径生成；JSON 一同保存，明暗 PNG 保持相同结构。`reference-comparison.jpg` 对照三张用户教材样图和当前生成结果。

`CodingSpecTest` 校验教材转移、串行输出、循环码长除法及参数拒收。`CodingReferenceTemplateTest` 校验参考 K=3 的加法器输入位置，以及 1～8 级、1～4 路输出的抽头避让和独立输出通道。`CodingDiagramAcceptanceTest` 验证各模板、主题、画布规模和画布边界裁切，并可重新导出：

```powershell
$env:JAVA_HOME = 'D:\tools\jdk-21.0.12+8'
$env:CODING_EXPORT_SAMPLES = '1'
.\gradlew.bat --no-configuration-cache --max-workers=2 :app:testDebugUnitTest --tests 'com.moge.app.domain.coding.*' --tests 'com.moge.app.domain.diagram.CodingDiagramAcceptanceTest' --tests 'com.moge.app.ui.figure.CodingReferenceTemplateTest'
```

输出位于 `app/build/coding-samples/`。测试不调用远程模型。
