package backend.ir

import frontend.semantic.Scope

class ValueEnv {
    private val scopes: ArrayDeque<MutableMap<String, ValueBinding>> = ArrayDeque()
    private val functionStack: ArrayDeque<FunctionFrame> = ArrayDeque()

    fun enterScope() {
        scopes.addLast(mutableMapOf())
    }

    fun leaveScope() {
        scopes.removeLastOrNull()
    }

    fun bind(name: String, binding: ValueBinding) {
        val current = scopes.lastOrNull() ?: error("No scope to bind $name")
        current[name] = binding
    }

    fun resolve(name: String): ValueBinding? {
        for (i in scopes.indices.reversed()) {
            val scope = scopes.elementAt(i)
            scope[name]?.let { return it }
        }
        return null
    }

    fun pushFunction(returnType: IrType) {
        functionStack.addLast(FunctionFrame(returnType))
    }

    fun popFunction() {
        functionStack.removeLastOrNull()
    }

    fun currentReturnType(): IrType =
        functionStack.lastOrNull()?.returnType ?: error("No active function")

    fun pushLoop(breakTarget: String, continueTarget: String) {
        val frame = functionStack.lastOrNull() ?: error("No active function")
        frame.breakTargets.add(breakTarget)
        frame.continueTargets.add(continueTarget)
    }

    fun popLoop() {
        val frame = functionStack.lastOrNull() ?: error("No active function")
        frame.breakTargets.removeLastOrNull()
        frame.continueTargets.removeLastOrNull()
    }

    fun currentBreakTarget(): String? = functionStack.lastOrNull()?.breakTargets?.lastOrNull()
    fun currentContinueTarget(): String? = functionStack.lastOrNull()?.continueTargets?.lastOrNull()
}

sealed interface ValueBinding
data class SsaValue(val value: IrValue) : ValueBinding
data class StackSlot(val address: IrValue, val type: IrType) : ValueBinding

private data class FunctionFrame(
    val returnType: IrType,
    val breakTargets: ArrayDeque<String> = ArrayDeque(),
    val continueTargets: ArrayDeque<String> = ArrayDeque(),
)
