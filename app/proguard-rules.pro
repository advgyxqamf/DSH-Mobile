# 本骨架未开启 minify；保留默认规则即可。
# 若日后开启 R8，注意：node 通过 ProcessBuilder 启动，无 JNI 反射，无需额外 keep。
-dontwarn
-keepattributes *Annotation*
