package org.nous

import llms.ChatModelAPI
import llms.Completion
import llms.PromptTemplateBuilder
import llms.configs.ModelRegistry
import llms.promptTemplate
import llms.templateStream
import kotlin.io.path.Path
import kotlinx.coroutines.flow.Flow
import llms.asString
import org.nous.Models.Instruct
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

abstract class ModelContainer {
	private val _models: MutableMap<String, ChatModelAPI> = mutableMapOf()
	val models: Map<String, ChatModelAPI> = _models

	fun getModelKey(model: ChatModelAPI) = models.entries.firstOrNull {it.value == model}?.key

	protected fun model(key: String): ReadOnlyProperty<ModelContainer, ChatModelAPI> =
		object : ReadOnlyProperty<ModelContainer, ChatModelAPI> {
			override fun getValue(thisRef: ModelContainer, property: KProperty<*>): ChatModelAPI {
				return _models.getOrPut(key) { ModelRegistry.get(key) }
			}
		}
}

object Models {
	object Completions : ModelContainer() {
		val deepseek_r1 by model("deepseek-r1-completion")
		val llama_405b_base by model("llama-3.1-405b-base")
	}

	object Instruct : ModelContainer() {
		val claude_3p7_sonnet by model("claude_3.7-sonnet")
		val claude_3p5_haiku by model("claude_3.5-haiku")
		val claude_3p5_sonnet_v2 by model("claude_3.5-sonnet-v2")
		val claude_3p5_sonnet by model("claude_3.5-sonnet")
		val claude_3_opus by model("claude_3-opus")
		val claude_3_sonnet by model("claude_3-sonnet")
		val claude_3_haiku by model("claude_3-haiku")
		val deepseek_r1 by model("deepseek-r1")
		val llama_3p1_405b_instruct by model("llama-3.1-405b-instruct")
		val yi_large by model("yi-large")
		val llama_v3p2_90b_vision_instruct by model("llama-v3p2-90b-vision-instruct")
		val dobby_unhinged_llama_3p3_70b by model("dobby-unhinged-llama-3.3-70b")
		val openai_gpt4o by model("openai_gpt4o")
		val openai_gpt4o_mini by model("openai_gpt4o-mini")
		val openai_o3_mini by model("openai_o3-mini")
		val openai_gpt_4p5 by model("openai_gpt-4.5")
		val openai_gpt4 by model("openai_gpt4")
		val openai_chatgpt_4o by model("openai_chatgpt-4o")
	}
}

typealias BoundTemplate = suspend (block: (PromptTemplateBuilder.() -> Unit)?) -> Flow<Completion>
private fun ChatModelAPI.forTemplate(first: String, vararg rest: String): BoundTemplate {
	val name =
		if(rest.isEmpty())
			Path(first)
		else
			Path(first, *rest.toList().toTypedArray())

	fun wrappedTemplateCall(block: (PromptTemplateBuilder.() -> Unit)? = null): Flow<Completion> {
		val pd = promptTemplate(name.toString(), block)

		// we could do token counting, etc here
		return this.templateStream(pd)
	}

	return ::wrappedTemplateCall
}

object Templates {
	private val instruct_models = Instruct.run {
		listOf(
			claude_3p7_sonnet,
			claude_3p5_haiku,
			claude_3p5_sonnet_v2,
			claude_3p5_sonnet,
			claude_3_opus,
			claude_3_sonnet,
			claude_3_haiku,
			deepseek_r1,
			llama_3p1_405b_instruct,
			yi_large,
			llama_v3p2_90b_vision_instruct,
			dobby_unhinged_llama_3p3_70b,
			openai_gpt4o,
			openai_gpt4o_mini,
			openai_o3_mini,
			// too expensive
//			openai_gpt_4p5,
//			openai_gpt4,
			openai_chatgpt_4o,
		)
	}

	val content_generators = instruct_models.map {
		val template = it.forTemplate("instruct", "generate")

		suspend fun generateCard(words: List<String>): List<String> {
			val raw = template {
				variable("WORDS", words.joinToString(", "))
			}.asString()

			val results = findAllTags(raw) {it.tagName.startsWith("item")}

			require(results.size == 5) { "got ${results.size} results in cardgen instead of 5" }
			// we could parse the results into JSON or similar here if we wanted

			return results
		}

		Instruct.getModelKey(it)!! to ::generateCard
	}.toMap()

	val content_judges = listOf(
		Instruct.deepseek_r1,
		Instruct.claude_3p7_sonnet,
		Instruct.claude_3_opus,
		Instruct.openai_chatgpt_4o,
		Instruct.openai_gpt4o,
		Instruct.openai_gpt4o_mini,
		Instruct.openai_o3_mini,
	).associate { Instruct.getModelKey(it)!! to it.forTemplate("instruct", "judge") }
}