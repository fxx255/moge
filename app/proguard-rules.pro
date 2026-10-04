# Room / Hilt 自带 consumer rules，这里补充渲染、序列化及持久化枚举。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# kotlinx.serialization 生成的 serializer
-keepclassmembers class com.moge.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.moge.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.moge.app.**$$serializer { *; }

# 枚举在 Room / 序列化中按名字使用
-keepclassmembers enum com.moge.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# jlatexmath 用 Class.forName(类名).newInstance() 反射实例化 XML 里预定义的命令
# （\operatorname、\dfrac、cases 环境等）。R8 改名后按原名查找失败会 NPE，
# 真机表现为「公式无法渲染」，而 debug 不混淆所以测试全绿。整包保留原名。
-keep class org.scilab.forge.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**

# Markwon 插件和它们的 span / drawable 桥接代码参与本地公式、表格渲染。
-keep,allowoptimization class io.noties.markwon.** { *; }

# Room、DataStore、请求快照存枚举 name；release 必须继续读取 debug 已有的数据。
-keep enum com.moge.app.** { *; }
# PdfBox's optional JPEG 2000 decoder is only used for bitmap extraction.
# DocumentReader uses PdfBox for text and Android PdfRenderer for page images.
-dontwarn com.gemalto.jp2.JP2Decoder
