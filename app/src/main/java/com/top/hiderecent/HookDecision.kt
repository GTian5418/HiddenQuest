package com.top.hiderecent

internal object HookDecision {
    inline fun <T> evaluateOrProceed(
        proceed: () -> T,
        onError: (Throwable) -> Unit = {},
        evaluate: () -> T?
    ): T {
        val decided = try {
            evaluate()
        } catch (t: Throwable) {
            onError(t)
            null
        }
        return decided ?: proceed()
    }
}
