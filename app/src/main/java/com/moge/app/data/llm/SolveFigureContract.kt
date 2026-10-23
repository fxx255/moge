package com.moge.app.data.llm

/** Shared drawing contract: providers only return data; the app creates the images. */
internal const val SOLVE_FIGURE_CONTRACT = """应用绘图能力：
墨格可以把回答 JSON 中的绘图数据生成图片。需要函数图、频谱、功率谱、波形、眼图、星座图等时，必须提供顶层 plots 数组；需要框图、流程图时，必须提供顶层 diagrams 数组。输出这些字段就是请求应用画图，不依赖接口是否提供绘图工具，也不需要服务商的图片生成功能。
reply 中只写讲解，并在对应位置单独一行写 [[FIGURE:n]]。绘图数据是与 reply 并列的 JSON 字段，不能作为文字或代码块塞进 reply；不能只有图号而没有数据。
除非用户明确索要代码或字符画，不得用 ASCII / Unicode 字符拼图、代码块、Mermaid、SVG、Markdown 表格或图片链接代替图像，也不要以“无法画图”代替应用已经支持的绘图输出。历史回答即使用了字符画，本轮仍按此协议给出完整绘图数据。
没有画图需求时省略 plots 和 diagrams。以下字段示例只演示协议，不是当前题目的条件、图形或答案。"""

/** A complete, parseable envelope also demonstrates mixed figure numbering. */
internal const val SOLVE_FIGURE_RESPONSE_EXAMPLE = """{"conversation_title":"绘图协议示例","answer":"示例结论","reply":"曲线说明。\n[[FIGURE:1]]\n框图说明。\n[[FIGURE:2]]","plots":[{"title":"曲线示例","x":{"label":"x","min":-2,"max":2},"y":{"label":"y","min":0,"max":5},"series":[{"expr":"x^2"}],"legend":false}],"diagrams":[{"title":"框图示例","profile":"generic","nodes":[{"id":"in","label":"输入","shape":"io"},{"id":"f","label":"处理","shape":"block"}],"edges":[{"from":"in","to":"f"}]}]}"""
