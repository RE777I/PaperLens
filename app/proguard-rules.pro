# PdfBox-Android loads some classes reflectively and references optional JP2/Gemalto codecs.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.filter.JPXFilter

# ONNX Runtime's native code finds its Java classes and constructors by name (JNI); renaming or removing them
# crashes the page-corner model in release builds ("JNI DETECTED ERROR: java_class == null").
-keep class ai.onnxruntime.** { *; }
