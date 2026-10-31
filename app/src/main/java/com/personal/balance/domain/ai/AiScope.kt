package com.personal.balance.domain.ai

/**
 * The ONE declared scope of the AI Financial Assistant and its ONE
 * out-of-scope reply.
 *
 * The assistant answers questions about the user's own money only. Anything
 * else (free-standing arithmetic such as "5 plus 8", general knowledge,
 * coding, translation, creative writing, medical or legal advice, ...) is not
 * something the assistant is built for, and it says so plainly with [REPLY]
 * instead of answering or pretending the request was handled.
 *
 * The wording lives here — not in the prompts and not in the UI — so the
 * deterministic gate ([com.personal.balance.domain.ai.orchestration.OutOfScopeGuard]),
 * the system prompt ([DefaultAiConfig]) and every composer instruction block
 * can quote exactly the same sentence and never drift apart.
 */
object AiScope {

    const val REPLY: String =
        "I'm not designed for that. I'm the Balance financial assistant, so I can only " +
            "help with your own money — your spending, income, balance, categories, " +
            "transaction patterns and savings goals."
}
