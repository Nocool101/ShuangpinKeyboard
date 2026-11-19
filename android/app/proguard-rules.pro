# 引擎层是纯数据 + 纯函数，无需混淆特殊处理。
# 保留入口类，避免 Release 包因反射找不到 IME 服务。
-keep class com.shuangpin.ime.ShuangpinImeService { *; }
