import java.util.Properties

rootProject.name = "kaida-gencritique"

val props = rootDir.resolve("local.properties")
if (props.exists()) {
	val props = try {
		Properties().apply { load(props.inputStream()) }
	} catch(ex: Throwable) {
		throw Error("failed while loading local.properties", ex)
	}
	val path = props.getProperty("KAIDA_PATH")

	if (path != null) {
		println("sourcing kaida from: $path")
		includeBuild(File(path)) {
			dependencySubstitution {
				substitute(module("org.nous:kaida")).using(project(":"))
			}
		}
	}
}