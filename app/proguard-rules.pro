# R8 / ProGuard rules for :app

# NativeBridge is looked up dynamically by name in JNI_OnLoad via FindClass
# ("com/bitlockerdroid/util/NativeBridge") and bound via RegisterNatives.
-keep class com.bitlockerdroid.util.NativeBridge {
    native <methods>;
}

-keep class com.bitlockerdroid.usb.PipelinedUsbCommunication {
    native <methods>;
}

-keep class me.jahnen.libaums.** { *; }

# Preserve source file names and line numbers for readable crash stack traces
# when deobfuscated with mapping.txt.
-keepattributes SourceFile,LineNumberTable
