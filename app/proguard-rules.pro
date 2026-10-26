# GitDrop uses no reflection, no serialization libraries and no third-party
# code, so the default Android rules are all that R8 needs.

# Keep line numbers so crash reports from release builds stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
