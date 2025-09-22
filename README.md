# Passage Kotlin SDK

Native Android SDK for integrating Passage authentication and data capture into your Android applications.

## Documentation

For installation instructions, complete integration guides, API reference, and examples, visit:

**📚 [https://docs.trypassage.ai/docs/kotlin/integration](https://docs.trypassage.ai/docs/kotlin/integration)**

## Support

- **Documentation**: https://docs.trypassage.ai/docs/kotlin
- **Issues**: [GitHub Issues](https://github.com/tailriskai/passage-kotlin/issues)
- **Email**: support@trypassage.ai

## Distribution

- **Current version**: `0.0.1`

To publish the SDK to your local Maven repository for testing:

```bash
./scripts/publish_local.sh
```

This generates the release AAR and installs it into `~/.m2/repository`, allowing other Android or Capacitor projects to depend on `mavenLocal()` and pull in `ai.trypassage:sdk:0.0.1`.

To publish to a remote Maven repository, export the following environment variables (or set matching Gradle properties) and run:

```bash
export PASSAGE_MAVEN_URL="https://your.maven.repo/releases"
export PASSAGE_MAVEN_USERNAME="your-username"
export PASSAGE_MAVEN_PASSWORD="your-password"
./scripts/publish_remote.sh
```

After publishing, add the dependency to your project:

```kotlin
repositories {
    mavenLocal()
    maven { url = uri("https://your.maven.repo/releases") }
}

dependencies {
    implementation("ai.trypassage:sdk:0.0.1")
}
```

## License

See `LICENSE`.
