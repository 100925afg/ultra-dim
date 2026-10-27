# 极暗 (UltraDim) ProGuard 规则
#
# 本项目 release 默认不开启混淆（isMinifyEnabled = false），此文件保证
# build.gradle 中 proguardFiles 引用的文件存在，避免 Android Studio 同步/构建时报缺失。
#
# 若以后开启混淆，需保留被清单直接引用的组件与自定义 View 的构造函数：
-keep class com.ultradim.MainActivity { *; }
-keep class com.ultradim.DimService { *; }
-keep class com.ultradim.DimTileService { *; }
-keep class com.ultradim.DeepDimService { *; }
-keep class com.ultradim.BootReceiver { *; }
-keep class com.ultradim.KnobView { *; }
-keep class com.ultradim.State { *; }
