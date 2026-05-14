# androidx benchmark requires these classes intact
-keep class androidx.benchmark.** { *; }
# JUnit @RunWith-annotated test classes
-keep @org.junit.runner.RunWith class * { *; }
# All classes in the benchmark test package
-keep class com.imageshare.benchmark.micro.** { *; }
# Don't obfuscate anything in the benchmark module — readable names help debug
-dontobfuscate
