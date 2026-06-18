# Gson reads these models by field name. Keep their names stable in release builds so
# versioned backup files and JSON response formats remain compatible.
-keep class com.houzhengbo.interview.data.entity.** { *; }
-keep class com.houzhengbo.interview.data.dto.** { *; }

-keepattributes Signature
-keepattributes *Annotation*
