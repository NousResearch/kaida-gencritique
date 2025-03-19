package org.nous

import pipelinedag.PipelineHooksBuilder

fun PipelineHooksBuilder.pipeline_logger(
	discriminator: String? = null,
	log_outputs: Boolean = false,
) {
	fun println(str: String) = kotlin.io.println(buildString {
		if(discriminator != null)
			append("$discriminator$str")
		else
			append(str)
	})

	fun printerr(str: String) = System.err.println(buildString {
		if(discriminator != null)
			append("$discriminator$str")
		else
			append(str)
	})

	beforeEachStep {
		if(skipped) {
			println("Skipping step (fully satisfied): ${step.name}...")
		} else {
			println("Executing step: ${step.name}...")
		}
	}

	onStepFailure {
		printerr("Skipping exception ${currentRetryState.currentAttempt}/${rp.maxAttempts}: $exception\n${exception.stackTraceToString().prependIndent()}\n")
	}

	if(log_outputs) {
		afterEachStep {
			val msg = step.produces
				.map { it to ctx.getOrNull(it) }
				.joinToString("\n\n") {
					val str = it.second.toString()
					val name = it.first.name

					if(str.contains("\n")) {
						"<$name>\n$str\n</$name>"
					} else {
						"<$name>$str</$name>"
					}
				}
				.prependIndent()

			println("Outputs:\n$msg")
		}
	}
}