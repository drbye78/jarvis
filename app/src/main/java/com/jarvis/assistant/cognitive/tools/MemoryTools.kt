package com.jarvis.assistant.cognitive.tools

import com.jarvis.assistant.cognitive.CognitiveCoordinator
import com.jarvis.assistant.tools.ToolArgs
import com.jarvis.assistant.tools.ToolContract
import com.jarvis.assistant.tools.bool
import com.jarvis.assistant.tools.schema
import com.jarvis.assistant.tools.string

/**
 * COGNITIVE_PLAN §6.4: the LLM-callable memory tool surface. All three route
 * through the coordinator (the single writer/reader of `user_facts`) and
 * return [MemoryOutcome] JSON — honest outcomes with a pre-rendered
 * `spoken` line (ToolStrings seam), never a bare "ok".
 *
 * Explicit writes bypass the extraction LLM entirely (plan §6.4): the tool
 * arguments ARE the fact, so there is nothing to hallucinate.
 */

/** `remember_fact(value, category?, subject?)` — deterministic local write. */
class RememberFactTool(
    private val coordinator: CognitiveCoordinator,
) : ToolContract {
    override val name = "remember_fact"
    override val description =
        "Запомнить факт о пользователе НАДОЛГО (сохраняется на устройстве). " +
            "value — сам факт коротко (например «зовут Алексей», «жена Маша», «любит фильмы Тарковского»). " +
            "category — необязательная подсказка: name, birthday, likes, dislikes, works_at, spouse, child, boss, goal, health, other. " +
            "НЕ вызывай для команд, погоды, музыки — только для устойчивых сведений."
    override val parametersJson = schema(
        mapOf(
            "value" to """{"type":"string","description":"Сам факт, коротко и дословно"}""",
            "category" to """{"type":"string","description":"Тип факта: name|birthday|likes|dislikes|works_at|spouse|child|boss|other"}""",
            "subject" to """{"type":"string","description":"О ком факт; по умолчанию — пользователь"}""",
        ),
        required = listOf("value"),
    )

    override suspend fun execute(arguments: String): String {
        val args = ToolArgs.parse(arguments)
            ?: return MemoryOutcome.Failed("bad arguments").toJson()
        val value = args.string("value").orEmpty()
        val outcome = coordinator.rememberFact(
            value = value,
            category = args.string("category"),
            subject = args.string("subject"),
        )
        return outcome.toJson()
    }
}

/** `recall_facts(query?)` — ranked recall with honest confidence marks. */
class RecallFactsTool(
    private val coordinator: CognitiveCoordinator,
) : ToolContract {
    override val name = "recall_facts"
    override val description =
        "Проверить долговременную память о пользователе. query — необязательный поиск " +
            "(например «имя», «начальник», «музыка»); без query — самые важные факты. " +
            "Используй, когда вопрос может опираться на прошлые разговоры."
    override val parametersJson = schema(
        mapOf(
            "query" to """{"type":"string","description":"Что искать в памяти; пусто — все главные факты"}""",
        ),
    )

    override suspend fun execute(arguments: String): String {
        val args = ToolArgs.parse(arguments) ?: kotlinx.serialization.json.JsonObject(emptyMap())
        val outcome = coordinator.recallFacts(args.string("query"))
        return outcome.toJson()
    }
}

/**
 * `forget_fact(query, confirmed=false, token?)` — two-step forget (plan
 * §6.4): step 1 lists candidates + hands out a confirmation token; step 2
 * (confirmed=true, token) marks FORGOTTEN. The tool refuses `confirmed=true`
 * without a valid token — the model cannot skip the confirmation.
 */
class ForgetFactTool(
    private val coordinator: CognitiveCoordinator,
) : ToolContract {
    override val name = "forget_fact"
    override val description =
        "Забыть факт о пользователе. СНАЧАЛА вызови с confirmed=false — получишь список " +
            "кандидатов и token; покажи кандидаты пользователю и подтверди. Потом вызови ещё раз " +
            "с confirmed=true и тем же token. НЕ вызывай confirmed=true без token."
    override val parametersJson = schema(
        mapOf(
            "query" to """{"type":"string","description":"Что забыть (поиск по фактам)"}""",
            "confirmed" to """{"type":"boolean","description":"true только с token из первого вызова"}""",
            "token" to """{"type":"string","description":"token из первого вызова forget_fact"}""",
        ),
        required = listOf("query"),
    )

    override suspend fun execute(arguments: String): String {
        val args = ToolArgs.parse(arguments)
            ?: return MemoryOutcome.Failed("bad arguments").toJson()
        val query = args.string("query").orEmpty()
        val confirmed = args.bool("confirmed") ?: false
        val token = args.string("token")
        val outcome = coordinator.forgetFact(query, confirmed, token)
        return outcome.toJson()
    }
}

/** Bundle for the FunctionRouter registration (plan §6.4 / §11 task 1.5). */
class MemoryToolsFactory(
    coordinator: CognitiveCoordinator,
) {
    private val tools = listOf(
        RememberFactTool(coordinator),
        RecallFactsTool(coordinator),
        ForgetFactTool(coordinator),
    )

    fun all(): List<ToolContract> = tools
}
