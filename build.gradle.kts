buildscript {
    dependencies {
        // AGP still declares vulnerable transitive versions on its build classpath.
        // These constraints affect build tooling, not the app's runtime dependencies.
        constraints {
            add("classpath", "org.bouncycastle:bcprov-jdk18on:1.85")
            add("classpath", "org.bouncycastle:bcpkix-jdk18on:1.85")
            add("classpath", "com.google.guava:guava:33.7.2-jre")
            add("classpath", "org.bitbucket.b_c:jose4j:0.9.6")
            add("classpath", "com.squareup.okhttp3:okhttp:4.12.0")
            add("classpath", "com.squareup.okio:okio:3.9.0")
            add("classpath", "org.jdom:jdom2:2.0.6.1")
            add("classpath", "org.apache.commons:commons-lang3:3.18.0")
            add("classpath", "org.apache.httpcomponents:httpclient:4.5.14")
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
