## | ` NametagAPI`
** It's a rewrite of [NametagEdit](https://github.com/sgtcaze/NametagEdit)

To use you need to download plugin (from realises) to your server. Add to plugin depends in `plugin.yml` :: `NametagAPI`, and this is all.
### | `Add as depend`
```groovy
repositories {
    // other repositories
    maven {
        name = "clojars.org"
        url = uri("https://repo.clojars.org")
    }
}

dependencies {
    // other depends
    implementation 'io.github.dynomake:nametagapi:1.0'
}
```