package com.jarvis.assistant.cognitive.tools

import com.jarvis.assistant.cognitive.CognitiveCoordinator
import com.jarvis.assistant.tools.ToolArgs
import com.jarvis.assistant.tools.ToolContract
import com.jarvis.assistant.tools.ToolRisk
import com.jarvis.assistant.tools.bool
import com.jarvis.assistant.tools.schema
import com.jarvis.assistant.tools.string

/**
 * COGNITIVE_PLAN §6.4: the LLM-callable memory tool surface. All three route
 * through the coordinator (the single writer/reader of `user_facts`) and
 * return [MemoryOutcome] JSON — honest structured outcomes, never a bare
 * "ok". The JSON carries NO pre-rendered spoken line (P1-C): the LLM composes
 * the reply in the conversation's language; locale-correct user-facing
 * wording, where needed, goes through [spoken] + [com.jarvis.assistant.tools.ToolStrings].
 *
 * Explicit writes bypass the extraction LLM entirely (plan §6.4): the tool
 * arguments ARE the fact, so there is nothing to hallucinate.
 */

/**
 * `remember_fact(value, category?)` — deterministic local write. The subject
 * is ALWAYS `user` (owner decision): there is deliberately NO free-text
 * `subject` in the schema. Facts about named people use a RELATION category
 * with the name in `value`; a subject smuggled into the raw arguments is
 * refused by the coordinator's `sanitizeSubject` gate, never silently filed.
 */
class RememberFactTool(
    private val coordinator: CognitiveCoordinator,
) : ToolContract {
    override val name = "remember_fact"
    override val risk = ToolRisk.STATEFUL
    override val description =
        "Запомнить факт о пользователе НАДОЛГО (сохраняется на устройстве). " +
            "value — сам факт коротко (например «зовут Алексей», «любит фильмы Тарковского»). " +
            "О других людях пиши как об отношении: category spouse/child/parent/friend/colleague/boss/pet, " +
            "а имя — в value (например value «жена Маша», category spouse). " +
            "category — необязательная подсказка: name, birthday, likes, dislikes, works_at, spouse, child, boss, goal, health, other. " +
            "НЕ вызывай для команд, погоды, музыки — только для устойчивых сведений."
    override val parametersJson = schema(
        mapOf(
            "value" to """{"type":"string","description":"Сам факт, коротко и дословно"}""",
            "category" to """{"type":"string","description":"Тип факта: name|birthday|likes|dislikes|works_at|spouse|child|boss|other"}""",
        ),
        required = listOf("value"),
    )

    override suspend fun execute(arguments: String): String {
        val args = ToolArgs.parse(arguments)
            ?: return MemoryOutcome.Failed("bad arguments").toJson()
        val value = args.string("value").orEmpty()
        // `subject` is intentionally absent from the schema; if a model still
        // smuggles it, the coordinator's sanitizeSubject gate refuses it.
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
    override val risk = ToolRisk.READ_ONLY
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
 * `forget_fact(query, confirmed=false)` — two-step forget (plan §6.4):
 * step 1 lists the candidates; step 2 (`confirmed=true`) marks them
 * FORGOTTEN. Confirmation is bound to TURN PROVENANCE AND AN EXPLICIT
 * AFFIRMATION in the coordinator ([CognitiveCoordinator.noteTurnStart] /
 * [CognitiveCoordinator.noteUserUtterance]): a listing issued in one turn can
 * only be confirmed by the IMMEDIATELY-NEXT user turn, and that turn's
 * utterance must be a genuine affirmative («да» / «подтверждаю» / "yes" /
 * «удали») — «нет», a question or an unrelated remark is refused and the
 * candidates are re-listed. There is no token on the wire — the model must
 * show the candidates to the user and wait for their reply.
 */
class ForgetFactTool(
    private val coordinator: CognitiveCoordinator,
) : ToolContract {
    override val name = "forget_fact"
    override val risk = ToolRisk.IRREVERSIBLE
    override val description =
        "Забыть факт о пользователе. СНАЧАЛА вызови с confirmed=false — получишь список " +
            "кандидатов; покажи их пользователю и дождись его подтверждения. Затем, уже в СЛЕДУЮЩЕМ " +
            "ответе пользователя, вызови ещё раз С ТЕМ ЖЕ query и с confirmed=true. Подтверждением " +
            "считается только явное согласие пользователя («да», «подтверждаю», «удали»); вопрос, «нет» " +
            "или посторонняя фраза не сработают."
    override val parametersJson = schema(
        mapOf(
            "query" to """{"type":"string","description":"Что забыть (поиск по фактам), тот же запрос, что и при confirmed=false"}""",
            "confirmed" to """{"type":"boolean","description":"true только после явного «да»/«подтверждаю» в СЛЕДУЮЩЕМ ответе"}""",
        ),
        required = listOf("query"),
    )

    override suspend fun execute(arguments: String): String {
        val args = ToolArgs.parse(arguments)
            ?: return MemoryOutcome.Failed("bad arguments").toJson()
        val query = args.string("query").orEmpty()
        val confirmed = args.bool("confirmed") ?: false
        val outcome = coordinator.forgetFact(query, confirmed)
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
