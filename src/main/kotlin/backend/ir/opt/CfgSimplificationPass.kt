package backend.ir.opt

// Simplifies IR control flow and bypasses empty jump blocks.

import backend.ir.*
import backend.ir.analysis.buildCfg

class CfgSimplificationPass : FunctionPass {
    override val name: String = "cfg-simplify"

    override fun run(module: IrModule, function: IrFunction) {
        var changed = true
        var rounds = 0
        while (changed && rounds < MAX_ROUNDS) {
            rounds++
            changed = false
            changed = simplifySameTargetBranches(function) || changed
            changed = removeUnreachableBlocks(function) || changed
            changed = bypassEmptyJumpBlocks(function) || changed
        }
    }

    private fun simplifySameTargetBranches(function: IrFunction): Boolean {
        var changed = false
        function.blocks.forEach { block ->
            val branch = block.terminator as? IrBranch ?: return@forEach
            if (branch.trueTarget == branch.falseTarget) {
                block.replaceTerminator(IrJump(branch.name, branch.type, branch.trueTarget))
                changed = true
            }
        }
        return changed
    }

    private fun bypassEmptyJumpBlocks(function: IrFunction): Boolean {
        if (function.blocks.size <= 1) return false
        val cfg = buildCfg(function)
        val existingLabels = function.blocks.map { it.label }.toSet()
        val entryLabel = function.blocks.first().label

        for (block in function.blocks.toList()) {
            if (block.label == entryLabel) continue
            if (block.instructions.isNotEmpty()) continue
            val jump = block.terminator as? IrJump ?: continue
            val target = jump.target
            if (target == block.label || target !in existingLabels) continue

            val predecessors = cfg.predecessors[block.label].orEmpty()
            if (predecessors.isEmpty()) continue
            if (predecessors.any { pred -> target in cfg.successors[pred].orEmpty() }) {
                continue
            }

            redirectTerminatorTargets(function, block.label, target)
            rewritePhiPredecessor(function, target, block.label, predecessors)
            function.blocks.remove(block)
            return true
        }

        return false
    }

    private fun redirectTerminatorTargets(function: IrFunction, oldTarget: String, newTarget: String) {
        function.blocks.forEach { block ->
            val terminator = block.terminator ?: return@forEach
            val rewritten = when (terminator) {
                is IrBranch -> terminator.copy(
                    trueTarget = if (terminator.trueTarget == oldTarget) newTarget else terminator.trueTarget,
                    falseTarget = if (terminator.falseTarget == oldTarget) newTarget else terminator.falseTarget,
                )
                is IrJump -> terminator.copy(
                    target = if (terminator.target == oldTarget) newTarget else terminator.target,
                )
                is IrReturn -> terminator
            }
            if (rewritten != terminator) {
                block.replaceTerminator(rewritten)
            }
        }
    }

    private fun rewritePhiPredecessor(
        function: IrFunction,
        targetLabel: String,
        oldPredecessor: String,
        newPredecessors: Set<String>,
    ) {
        val targetBlock = function.blocks.find { it.label == targetLabel } ?: return
        val orderedPreds = function.blocks.map { it.label }.filter { it in newPredecessors }

        val rewritten = targetBlock.instructions.map { instruction ->
            if (instruction is IrPhi) {
                instruction.copy(
                    incoming = instruction.incoming.flatMap { incoming ->
                        if (incoming.predecessor == oldPredecessor) {
                            orderedPreds.map { pred -> incoming.copy(predecessor = pred) }
                        } else {
                            listOf(incoming)
                        }
                    }
                )
            } else {
                instruction
            }
        }
        targetBlock.instructions.clear()
        targetBlock.instructions.addAll(rewritten)
    }

    private companion object {
        const val MAX_ROUNDS = 50
    }
}
