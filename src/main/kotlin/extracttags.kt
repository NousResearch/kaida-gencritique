package org.nous

data class TagMatch(val isOpening: Boolean, val tagName: String, val start: Int, val end: Int)

/**
 * Finds and pairs all matching tags in a given string.
 *
 * @param str The input string containing tags.
 * @param isValidTag Optional filtering function which decides whether a tag string will be considered. This
 *                   may be used to filter by tag, by default all tags are considered.
 * @return A list of the inner texts for each valid tag pair, in order.
 * @throws IllegalArgumentException if tags are mismatched, interleaved, or missing.
 */
fun findAllTags(
	str: String,
	isValidTag: (tag: TagMatch) -> Boolean = { true }
): List<String> {
	val tagRegex = Regex("<(/?)([^\r\n>]+)>")

	val allTags = tagRegex.findAll(str)
		.map { match ->
			val isOpening = match.groupValues[1].isEmpty()
			val tagName = match.groupValues[2]
			TagMatch(isOpening, tagName, match.range.first, match.range.last + 1)
		}
		.filter(isValidTag)
		.sortedBy { it.start }
		.toList()

	val stack = mutableListOf<TagMatch>()
	val results = mutableListOf<Pair<Int, String>>()

	for (tag in allTags) {
		if (tag.isOpening) {
			stack.add(tag)
			continue
		}

		if (stack.isEmpty())
			throw IllegalArgumentException("Closing tag '${tag.tagName}' at position ${tag.start} has no matching opening tag")

		val lastOpen = stack.removeAt(stack.lastIndex)
		if (lastOpen.tagName != tag.tagName)
			throw IllegalArgumentException(
				"Mismatched tags: expected closing tag for '${lastOpen.tagName}' (opened at ${lastOpen.start}) " +
						"but found closing tag '${tag.tagName}' at position ${tag.start}"
			)

		val innerText = str.substring(lastOpen.end, tag.start)
		results.add(lastOpen.start to innerText)
	}

	if (stack.isNotEmpty()) {
		val unmatched = stack.joinToString(", ") { "'${it.tagName}' at ${it.start}" }
		throw IllegalArgumentException("Unmatched opening tag(s): $unmatched")
	}

	return results.sortedBy { it.first }.map { it.second }
}