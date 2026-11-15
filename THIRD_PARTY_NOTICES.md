# Third-party notices

Reality Office's original source and documentation are available under the [MIT License](LICENSE). The following components retain their upstream licenses.

| Component | Source and license |
| --- | --- |
| Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) | The Gradle authors, [Apache License 2.0](LICENSES/Apache-2.0.txt). The checked-in wrapper downloads the Gradle 8.13 distribution; Gradle publishes [checksums for the bootstrap JAR and distribution](https://gradle.org/release-checksums/). |
| PICO ICON 7.0 paths (`tools/icons/pico-icon-7.0.paths.json` and the drawables generated from them) | PICO Immersive Pte. Ltd. The `pico-spatial-agentic-tools` 0.6.1 distribution declares [Apache License 2.0](LICENSES/Apache-2.0.txt) for the plugin, its skills, examples and bundled references unless separately noted. The paths used by Reality Office are selected from `pico-spatial-app-designer/assets/icon-7.0`, stored as JSON, and converted to Android drawables. Some are scaled or mirrored. The generator records each source icon name. [PICO developer resources](https://developer.picoxr.com/resources/). |

Gradle resolves the PICO Spatial SDK, AndroidX and other dependencies from the repositories in `settings.gradle.kts`. These dependencies retain their own terms; the PICO SDK and its offline project templates are not bundled in this source repository.
