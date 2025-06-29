pluginManagement {
    repositories {
        maven{ url=uri("https://maven.aliyun.com/repository/public")}
        maven{ url=uri("https://maven.aliyun.com/repository/google")}
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven{ url=uri("https://maven.aliyun.com/repository/public")}
        maven{ url=uri("https://maven.aliyun.com/repository/google")}
        google()
        mavenCentral()
    }

}

rootProject.name = "VolumeCounter"
include(":app")
