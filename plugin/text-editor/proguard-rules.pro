# Sora editor and tm4e use reflection for parser/registry classes; keep them safe.
-keep class io.github.rosemoe.sora.** { *; }
-keep class org.eclipse.tm4e.** { *; }
-dontwarn org.eclipse.tm4e.**
-dontwarn io.github.rosemoe.sora.**
