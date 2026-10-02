# 墨格 0.2.2 · 纯图片首问追问修复

日期：2026-10-03。版本：0.2.2，versionCode 4。

## 已确认的问题

0.2.1 中确实存在“纯图片首问在追问历史中带入空文本块”的缺陷。

首次纯图片提交通过 direct 路线发送时，GenerationPreparer.prepare 会补上 DEFAULT_VISION_PROMPT；数据库仍保存用户原始空正文。后续提问及重试根据快照重新组装历史，旧 historyFromSnapshot 在成功读取历史图片后原样使用空正文。ModelClient 的 Chat Completions 和 Responses 序列化随后无条件添加 text/input_text，最终生成含空文本块的多模态消息。

这条历史消息仍位于上一轮助手回答之前，当前追问也仍在消息末尾。对拒绝空文本块的后端，整个追问请求会在生成前遭拒。上一轮助手回答并未从历史中丢失。

触发范围：此前用户只发图片、当前主模型支持看图，并且原图成功重新附到历史中。带文字的图片消息通常不触发；历史图片未附送时原有说明文字也避免了这个空块。

## 修复内容

- 成功带回历史图片且用户原正文为空或全为空白时，补回与首次提交一致的默认看图提示词。历史重组由新请求和准备完成后的重试共用，已保存的空正文历史也能处理。
- Chat Completions 和 Responses 在历史及当前多模态消息中仅添加非空白文本块，保留图片和问答顺序；指定识题模型的非流式多模态调用也同步过滤空白文本。
- 保留用户填写的正文及数据库原始内容。没有修改数据库结构，Room 仍为 v2。

## 复现与回归

修复前新增回归执行了 34 项相关测试，出现 3 个预期失败：

1. HTTP 请求体测试捕获到空文本块。
2. 严格 MockWebServer 在含空文本块时返回 HTTP 400：纯图片首问完成，随后追问落入 INTERRUPTED。
3. 真正的提交和 Room 历史重组测试发现，追问里的原图片消息正文为空，而不是默认看图提示词。

修复后的测试同时检查两种协议、空串和仅含空白的正文、历史/当前图片归属、上一轮助手回答保留、当前追问最后、准备快照的重试恢复。严格后端场景走真实 GenerationManager、GenerationPreparer、ModelClient、Room 和 HTTP/SSE；未调用外部模型。

最终完整检查通过：

- Android 单元/Compose/Robolectric 测试 733 项，失败、错误、跳过均为 0。
- testDebugUnitTest、assembleDebug、assembleRelease、lintDebug、lintRelease 全部成功。最终 Gradle 检查用时 2 分 16 秒，日志为 build/photo-only-followup-complete.log。
- 两种 Lint 均无错误，各有 37 条 Warning 和 2 条 Hint。
- 完整检查同时发现并修正了一项原有测试的时序问题：转写失败的模拟行为需在提交前设置，避免请求提前成功。
- aapt 验证 release 为 com.moge.app、0.2.2、versionCode 4、arm64-v8a；apksigner 验证通过，延用同一开发签名。
- git diff --check 通过。

此轮验证使用真实客户端和本地严格模拟后端，没有调用用户的外部模型或修改设备中的数据。

## 安装产物

- release：app/build/outputs/apk/release/app-release.apk，3,652,416 字节。
- debug：app/build/outputs/apk/debug/app-debug.apk，26,465,012 字节，包名 com.moge.app.debug。
- release SHA-256：B236D2AD13BBB66651A438EFBFC9A8C8EB6066ACF4DD030037FA9FDCF9F88D85。

本次沿用现有开发签名，可覆盖同签名的 0.2.0/0.2.1，保留历史和配置。没有上传 GitHub、发布 Release 或改动暂存状态。

0.2.1 的图像显示修复记录见 figure-display-fix-report.md；上述通用 APK 输出路径现在对应本轮 0.2.2 构建。
