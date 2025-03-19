package org.nous

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import llms.RetryPolicy
import llms.asString
import llms.configs.AuthenticationDefinition
import llms.configs.ModelRegistry
import pipelinedag.FilesystemDB
import pipelinedag.PipelineContextSerializer
import pipelinedag.PipelineContextWithVars
import pipelinedag.PipelineVariables
import pipelinedag.SimplePipeline
import pipelinedag.executeAndSave
import pipelinedag.simplePipeline
import java.time.Duration
import java.util.UUID
import kotlin.collections.groupBy
import kotlin.io.path.Path

internal val defaultRetryPolicy = RetryPolicy(
	initialDelay = Duration.ofSeconds(1),
	shouldRetryForException = { rp, state, ex ->
		ex !is ExceptionInInitializerError
	}
)

class CardGenVariables : PipelineVariables() {
	val model by string()
	val words by list<String>()
	val output by list<String>()

	override val inputs = inputs {
		option(model, words)
	}

	override val outputs = outputs {
		option(output)
	}
}

internal val cardgen = simplePipeline<CardGenVariables>("cardgen") {
	retryPolicy(defaultRetryPolicy)

	step("Generate content") {
		consumes(vars.model, vars.words)
		produces(vars.output)
		execute {
			val generator = Templates.content_generators[vars.model.value()]!!

			set(vars.output, generator(vars.words.value()))
		}
	}
}

class CriticVariables : PipelineVariables() {
	val model by string()
	val items by list<String>()
	val thoughts by string()
	val ranking by list<Int>()

	override val inputs = inputs {
		option(model, items)
	}

	override val outputs = outputs {
		option(ranking)
	}
}

internal val critic = simplePipeline<CriticVariables>("critic") {
	retryPolicy(defaultRetryPolicy)

	val ranking = Regex("\\**ITEM RANKING\\**: \\[(\\d(?:, \\d)*)\\]", RegexOption.IGNORE_CASE)

	step("Criticize content") {
		consumes(vars.model, vars.items)
		produces(vars.thoughts, vars.ranking)
		execute {
			val result = Templates.content_judges[vars.model.value()]!! {
				val items = vars.items.value()
					.mapIndexed { i, text ->
						"<item${i+1}>\n$text\n</item${i+1}>"
					}
					.joinToString("\n\n")
				variable("ITEMS", items)
			}.asString()

			val parsed = ranking.find(result) ?:
				error("couldn't find rankings in LLM output")

			val rankings = parsed.groups[1]?.value
				?.replace(" ", "")
				?.split(",")
				?.mapNotNull { it.toIntOrNull() }
				?: error("got bad ranking value: ${parsed.value}")

			val length = vars.items.value().size
			require(rankings.size == length) {
				"rankings wasn't the same length as inputs provided: $rankings"
			}

			val validRankings = setOf(
				(0 until length).toSet(),
				(1 until length+1).toSet()
			)

			require(validRankings.contains(rankings.toSet())) {
				"invalid ranking set: $rankings"
			}

			set(vars.thoughts, result)
			set(vars.ranking, rankings)
		}
	}
}

typealias ModelID = String
data class Task<T>(val model: ModelID, val data: T)
typealias TaskSet<T> = Map<AuthenticationDefinition, List<Task<suspend () -> T>>>
data class ConcurrentTaskResult<T>(val auth: String, val model: ModelID, val result: T)

/**
 * executes a set of tasks concurrently, with no more than [global_concurrency_limit] requests simultaneously,
 * and no more than [per_provider_limit] simultaenously for a given authentication source.
 */
private suspend fun <T> TaskSet<T>.executeConcurrentTasks(
	global_concurrency_limit: Int = 8,
	per_provider_limit: Int = 3,
): List<ConcurrentTaskResult<T>> = coroutineScope {
	val global_semaphore = Semaphore(global_concurrency_limit)

	this@executeConcurrentTasks.flatMap { (auth, tasks) ->
		val providerSemaphore = Semaphore(per_provider_limit)
		tasks.map { task ->
			async {
				global_semaphore.withPermit {
					providerSemaphore.withPermit {
						val data = try {
							task.data()
						} catch(ex: FailedTaskException) {
							return@async null
						}

						ConcurrentTaskResult(auth.name, task.model, data)
					}
				}
			}
		}
	}.awaitAll().filterNotNull()
}

/**
 * group a list of tasks by their model's authentication source
 */
private fun <T> List<Task<suspend () -> T>>.grouped(): TaskSet<T> = this.groupBy(
	{ ModelRegistry.getAuth(it.model) },
	{ it }
)

private class FailedTaskException() : Exception()

/**
 * if a pipeline fails its retry policy we still want to continue with all the other invocations
 */
private fun <T> errorCaught(modelName: String, func: suspend () -> T): suspend () -> T {
	return suspend {
		try {
			func()
		} catch(ex: Throwable) {
			System.err.println("[!!!] pipeline failed, skipping execution for $modelName - $ex")
			throw FailedTaskException()
		}
	}
}

/**
 * create a wrapped suspend function for every model in a set
 */
private fun <T> Collection<String>.forEachModel(block: suspend (model: ModelID) -> T): TaskSet<T> =
	this
		.map { Task(it, errorCaught(it) {
			block(it)
		}) }
		.grouped()

/**
 * returns no more than one Task<T> per model up to [maximum] items per iteration until the list is consumed
 *
 * may yield less than 5 items if there are fewer left
 */
fun <T> List<ConcurrentTaskResult<List<T>>>.batchedRoundRobin(maximum: Int = 5): Sequence<List<Task<T>>> {
	val iterators = this.map { crt ->
		crt.model to crt.result.shuffled().iterator()
	}

	return sequence {
		while (iterators.any { it.second.hasNext() }) {
			val ret = sequence {
				iterators.shuffled().take(maximum).forEach { (model, iterator) ->
					if (iterator.hasNext())
						yield(Task(model, iterator.next()))
				}
			}.toList()

			// TODO: we can't judge only one item. there's a variety of possible solutions here, but as this is
			//       an example project we leave this as an exercise for the reader and discard single elements
			if(ret.size > 1)
				yield(ret)
		}
	}
}

/**
 * convenience function which prepares a pipeline with our logging hook
 */
private suspend fun <T : PipelineVariables> SimplePipeline<T>._quickExecute(
	run: String,
	db: PipelineContextSerializer,
	model: ModelID,
	context: PipelineContextWithVars<T>.() -> Unit
) = this
	.prepare()
	.hooks {
		val discriminator = listOf(
			this@_quickExecute.pipeline.id,
			model,
		).joinToString("/")

		pipeline_logger(
			"[$discriminator] ".padEnd(40, ' '),
			true
		)
	}
	.context {
		context()
	}
	.executeAndSave(run, db)

/*
note: to get proper coroutine stack traces during development use the
environment variable -Dkotlinx.coroutines.debug=on
*/
suspend fun main() {
	val db = FilesystemDB.fromPath(Path("output", "output.db"))
	val run = UUID.randomUUID().toString()
	val words = (1 .. 5).map {ENGLISH_WORDS.random()}

	/**
	 * wraps _quickExecute so we don't have to duplicate the run and db parameters
	 */
	suspend fun <T : PipelineVariables> SimplePipeline<T>.quickExecute(model: ModelID, context: PipelineContextWithVars<T>.() -> Unit) =
		_quickExecute(run, db, model, context)

	try {
		val results = Templates.content_generators.keys
			.shuffled()
			.forEachModel { model ->
				cardgen.quickExecute(model) {
					ctx.set(vars.model, model)
					ctx.set(vars.words, words)
				}.get { vars.output }
			}
			.executeConcurrentTasks()

		val judges = sequence {
			while(true) {
				Templates.content_judges.keys.shuffled().forEach { yield(it) }
			}
		}.iterator()

		val finalStats = mutableMapOf<ModelID, MutableMap<ModelID, Int>>()
		results
			.batchedRoundRobin()
			.map { items ->
				val model: ModelID = judges.next()
				Task(model, errorCaught(model) {
					val ranking = critic.quickExecute(model) {
						ctx.set(vars.model, model)
						ctx.set(vars.items, items.map { it.data })
					}.get { vars.ranking }

					val us = finalStats.getOrPut(model) {mutableMapOf()}

					val adjusted = run {
						if(ranking.min() == 0)
							ranking
						else
							ranking.map { it - 1 }
					}

					for (item in adjusted.map { items[it] }) {
						us[item.model] = us.getOrDefault(item.model, 0) + 1
						// let's only record the winner for now
						break
					}
				})
			}
			.toList()
			.grouped()
			.executeConcurrentTasks()

		/*
		this finalStats map tells us which generative models each critic model liked most!
		 */
		finalStats.forEach { key, items ->
			println("$key:")
			items.forEach { key, value ->
				println("\t- $key: $value")
			}
		}
		println(finalStats)
	} finally {
		db.close()
	}
}