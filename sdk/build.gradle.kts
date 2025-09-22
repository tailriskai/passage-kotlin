plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

group = project.findProperty("PUBLISHING_GROUP") as? String ?: "com.passage"
version = project.findProperty("PUBLISHING_VERSION") as? String ?: "0.0.1"

val publishingGroup = project.findProperty("PUBLISHING_GROUP") as? String ?: "com.passage"
val publishingArtifact = project.findProperty("PUBLISHING_ARTIFACT") as? String ?: "sdk"
val publishingVersion = project.findProperty("PUBLISHING_VERSION") as? String ?: "0.0.1"
val pomName = project.findProperty("POM_NAME") as? String ?: "Passage Kotlin SDK"
val pomDescription = project.findProperty("POM_DESCRIPTION") as? String ?: "Native Android SDK for Passage"
val pomUrl = project.findProperty("POM_URL") as? String ?: "https://github.com/tailriskai/passage-kotlin"
val pomLicenseName = project.findProperty("POM_LICENSE_NAME") as? String ?: "MIT License"
val pomLicenseUrl = project.findProperty("POM_LICENSE_URL") as? String ?: "https://opensource.org/licenses/MIT"
val pomDeveloperName = project.findProperty("POM_DEVELOPER_NAME") as? String ?: "Passage"
val pomDeveloperEmail = project.findProperty("POM_DEVELOPER_EMAIL") as? String ?: "support@trypassage.ai"
val pomScmUrl = project.findProperty("POM_SCM_URL") as? String ?: pomUrl
val pomScmConnection = project.findProperty("POM_SCM_CONNECTION") as? String ?: "scm:git:$pomUrl.git"
val pomScmDevConnection = project.findProperty("POM_SCM_DEV_CONNECTION") as? String ?: pomScmConnection

android {
    namespace = "com.passage.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        targetSdk = 34
    }

    lint {
        targetSdk = 34
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("io.socket:socket.io-client:2.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.auth0.android:jwtdecode:2.0.2")
    implementation("com.google.android.material:material:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = publishingGroup
            artifactId = publishingArtifact
            version = publishingVersion

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set(pomName)
                description.set(pomDescription)
                url.set(pomUrl)

                licenses {
                    license {
                        name.set(pomLicenseName)
                        url.set(pomLicenseUrl)
                    }
                }

                developers {
                    developer {
                        name.set(pomDeveloperName)
                        email.set(pomDeveloperEmail)
                    }
                }

                scm {
                    url.set(pomScmUrl)
                    connection.set(pomScmConnection)
                    developerConnection.set(pomScmDevConnection)
                }
            }
        }
    }

    repositories {
        maven {
            name = "Passage"
            val configuredUrl = (project.findProperty("passageMavenUrl") as? String)
                ?: System.getenv("PASSAGE_MAVEN_URL")
            val repoUri = configuredUrl?.takeIf { it.isNotBlank() }?.let { uri(it) }
                ?: uri("${project.buildDir}/repo")
            url = repoUri

            credentials {
                val configuredUsername = (project.findProperty("passageMavenUsername") as? String)
                    ?: System.getenv("PASSAGE_MAVEN_USERNAME")
                val configuredPassword = (project.findProperty("passageMavenPassword") as? String)
                    ?: System.getenv("PASSAGE_MAVEN_PASSWORD")
                username = configuredUsername
                password = configuredPassword
            }

            isAllowInsecureProtocol = repoUri.scheme == "http"
        }
    }
}
