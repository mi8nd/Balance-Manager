# Firestore deserializes these model classes reflectively via
# DocumentSnapshot.toObject(...) / toObjects(...), which requires the synthetic
# no-argument constructor that Kotlin generates for all-default data classes.
# R8 strips that constructor (and may rename the class) when no keep rules are
# present, causing at runtime:
#   "Could not deserialize object. Class d3.c does not define a no-argument
#    constructor. If you are using ProGuard, make sure these constructors are
#    not stripped"
-keep class com.personal.balance.model.** { *; }
-keep class * extends java.lang.Enum { *; }
-keepclassmembers class com.personal.balance.model.** {
    <init>();
    void set*();
    *** get*();
}
