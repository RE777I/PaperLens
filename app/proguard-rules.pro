# PdfBox-Android loads some classes reflectively and references optional JP2/Gemalto codecs.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.filter.JPXFilter

# ONNX Runtime's native code finds its Java classes and constructors by name (JNI); renaming or removing them
# crashes the page-corner model in release builds ("JNI DETECTED ERROR: java_class == null").
-keep class ai.onnxruntime.** { *; }

# ML Kit starts its components by reading the registrar class names from the manifest and calling their no-argument
# constructors by reflection. Nothing in the code calls those constructors, so R8 removes them and every ML Kit
# call (text recognition, translation, barcodes) then fails in release builds with NoSuchMethodException.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
