package backend.ir.opt

import backend.ir.*

/**
 * Function inlining pass.
 *
 * Uses Tarjan's SCC on the call graph to detect recursive/mutually-recursive
 * functions (skipped). Processes functions bottom-up in reverse topological
 * order so that callees are already optimised when inlined into callers,
 * giving full transitive inlining in a single pass.
 */
class InlinePass(
  private val instructionThreshold: Int = 100,
) {
  private var inlineCounter = 0

  fun run(module: IrModule) {
    val functions = module.declaredFunctions()
    if (functions.isEmpty()) return

    // Phase 1: call-graph analysis
    val callGraph = buildCallGraph(functions)
    val allNames = functions.map { it.name }.toSet()
    val sccs = tarjanSCC(callGraph, allNames)

    val recursive = mutableSetOf<String>()
    for (scc in sccs) {
      if (scc.size > 1) {
        recursive.addAll(scc)
      } else {
        val name = scc.single()
        if (name in callGraph[name].orEmpty()) recursive.add(name)
      }
    }

    val fnByName = functions.associateBy { it.name }

    // Phase 2: bottom-up inlining (Tarjan outputs reverse-topo order)
    for (scc in sccs) {
      if (scc.size > 1) continue
      val fnName = scc.single()
      if (fnName in recursive) continue
      val fn = fnByName[fnName] ?: continue
      if (fn.blocks.isEmpty()) continue
      inlineCallsInFunction(fn, fnByName, recursive)
    }
  }

  // ── call graph ──────────────────────────────────────────────────────

  private fun buildCallGraph(functions: List<IrFunction>): Map<String, Set<String>> {
    val graph = mutableMapOf<String, Set<String>>()
    for (fn in functions) {
      val callees = mutableSetOf<String>()
      for (block in fn.blocks) {
        for (inst in block.instructions) {
          if (inst is IrCall) callees.add(inst.callee.name)
        }
      }
      graph[fn.name] = callees
    }
    return graph
  }

  private fun tarjanSCC(
    graph: Map<String, Set<String>>,
    nodes: Set<String>,
  ): List<Set<String>> {
    var idx = 0
    val nodeIndex = mutableMapOf<String, Int>()
    val lowlink = mutableMapOf<String, Int>()
    val onStack = mutableSetOf<String>()
    val stack = ArrayDeque<String>()
    val result = mutableListOf<Set<String>>()

    fun strongConnect(v: String) {
      nodeIndex[v] = idx
      lowlink[v] = idx
      idx++
      stack.addLast(v)
      onStack.add(v)

      for (w in graph[v].orEmpty()) {
        if (w !in nodes) continue
        if (w !in nodeIndex) {
          strongConnect(w)
          lowlink[v] = minOf(lowlink[v]!!, lowlink[w]!!)
        } else if (w in onStack) {
          lowlink[v] = minOf(lowlink[v]!!, nodeIndex[w]!!)
        }
      }

      if (lowlink[v] == nodeIndex[v]) {
        val scc = linkedSetOf<String>()
        while (true) {
          val w = stack.removeLast()
          onStack.remove(w)
          scc.add(w)
          if (w == v) break
        }
        result.add(scc)
      }
    }

    for (node in nodes) {
      if (node !in nodeIndex) strongConnect(node)
    }
    return result // reverse topological order
  }

  // ── per-function inlining ───────────────────────────────────────────

  private fun inlineCallsInFunction(
    fn: IrFunction,
    fnByName: Map<String, IrFunction>,
    recursive: Set<String>,
  ) {
    val blockSnapshot = fn.blocks.toList()
    for (block in blockSnapshot) {
      val calls = block.instructions
        .filterIsInstance<IrCall>()
        .filter { isEligible(it.callee.name, fnByName, recursive) }
      if (calls.isEmpty()) continue
      for (call in calls.reversed()) {
        val callee = fnByName[call.callee.name] ?: continue
        inlineCallSite(fn, block, call, callee)
      }
    }
  }

  private fun isEligible(
    name: String,
    fnByName: Map<String, IrFunction>,
    recursive: Set<String>,
  ): Boolean {
    if (name == "main") return false
    if (name in recursive) return false
    val callee = fnByName[name] ?: return false
    if (callee.blocks.isEmpty()) return false
    if (instructionCount(callee) > instructionThreshold) return false
    return true
  }

  private fun instructionCount(fn: IrFunction): Int =
    fn.blocks.sumOf { it.instructions.size + (if (it.terminator != null) 1 else 0) }

  // ── call-site splicing ──────────────────────────────────────────────

  private fun inlineCallSite(
    caller: IrFunction,
    block: IrBasicBlock,
    call: IrCall,
    callee: IrFunction,
  ) {
    val callIndex = block.instructions.indexOfFirst { it === call }
    if (callIndex < 0) return

    val prefix = "inline.${callee.name.trimEnd('.')}.${inlineCounter++}"

    // label remap
    val labelMap = callee.blocks.associate { it.label to "$prefix.${it.label}" }
    fun remapLabel(label: String): String = labelMap[label] ?: label

    // parameter → argument map (by index)
    val argMap = mutableMapOf<Int, IrValue>()
    call.arguments.forEachIndexed { i, arg -> argMap[i] = arg }

    // value remap
    fun remapName(name: String): String = if (name.isBlank()) "" else "$prefix.$name"

    fun remapValue(value: IrValue): IrValue = when (value) {
      is IrParameter -> argMap[value.index] ?: value
      is IrLocal -> IrLocal(remapName(value.name), value.type)
      else -> value
    }

    // ── 1. split caller block ──

    val contLabel = "$prefix.cont"
    val preCall = block.instructions.subList(0, callIndex).toList()
    val postCall = block.instructions.subList(callIndex + 1, block.instructions.size).toList()
    val origTerminator = block.terminator

    block.instructions.clear()
    block.instructions.addAll(preCall)

    val clonedEntryLabel = remapLabel(callee.blocks.first().label)
    block.replaceTerminator(
      IrJump(name = "", type = IrPrimitive(PrimitiveKind.UNIT), target = clonedEntryLabel)
    )

    val contBlock = IrBasicBlock(contLabel)
    contBlock.instructions.addAll(postCall)
    if (origTerminator != null) {
      contBlock.setTerminator(origTerminator)
      // update phi predecessors in successors: old block label → contLabel
      val succs = terminatorSuccessors(origTerminator)
      updatePhiPredecessors(caller, block.label, contLabel, succs)
    }

    // ── 2. clone callee body ──

    val clonedBlocks = mutableListOf<IrBasicBlock>()
    val returnSites = mutableListOf<Pair<String, IrValue?>>()

    for (calleeBlock in callee.blocks) {
      val clonedLabel = remapLabel(calleeBlock.label)
      val cloned = IrBasicBlock(clonedLabel)

      for (inst in calleeBlock.instructions) {
        cloned.instructions.add(
          cloneInstruction(inst, ::remapValue, ::remapName, ::remapLabel)
        )
      }

      when (val term = calleeBlock.terminator) {
        is IrReturn -> {
          val retVal = term.value?.let { remapValue(it) }
          returnSites.add(clonedLabel to retVal)
          cloned.setTerminator(
            IrJump(name = "", type = IrPrimitive(PrimitiveKind.UNIT), target = contLabel)
          )
        }
        is IrBranch -> cloned.setTerminator(
          term.copy(
            condition = remapValue(term.condition),
            trueTarget = remapLabel(term.trueTarget),
            falseTarget = remapLabel(term.falseTarget),
          )
        )
        is IrJump -> cloned.setTerminator(
          term.copy(target = remapLabel(term.target))
        )
        null -> {}
      }

      clonedBlocks.add(cloned)
    }

    // ── 3. insert blocks into caller (must happen before value replacement) ──

    val insertPos = caller.blocks.indexOf(block) + 1
    for ((i, clonedBlock) in clonedBlocks.withIndex()) {
      caller.blocks.add(insertPos + i, clonedBlock)
    }
    caller.blocks.add(insertPos + clonedBlocks.size, contBlock)

    // ── 4. wire return values ──

    val callReturnsVoid =
      (call.type as? IrPrimitive)?.kind == PrimitiveKind.UNIT

    if (!callReturnsVoid && call.name.isNotBlank()) {
      if (returnSites.size == 1) {
        val (_, retVal) = returnSites.single()
        if (retVal != null) {
          replaceUsesOfValue(caller, call.name, retVal)
        }
      } else if (returnSites.size > 1) {
        val phiName = "$prefix.retval"
        val incoming = returnSites.mapNotNull { (label, value) ->
          value?.let { PhiBranch(it, label) }
        }
        if (incoming.isNotEmpty()) {
          val phi = IrPhi(name = phiName, type = call.type, incoming = incoming)
          contBlock.instructions.add(0, phi)
          replaceUsesOfValue(caller, call.name, IrLocal(phiName, call.type))
        }
      }
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private fun cloneInstruction(
    inst: IrInstruction,
    remap: (IrValue) -> IrValue,
    remapName: (String) -> String,
    remapLabel: (String) -> String,
  ): IrInstruction {
    val n = remapName(inst.name)
    return when (inst) {
      is IrConst -> inst.copy(name = n)
      is IrAlloca -> inst.copy(name = n)
      is IrLoad -> inst.copy(name = n, address = remap(inst.address))
      is IrStore -> inst.copy(name = n, address = remap(inst.address), value = remap(inst.value))
      is IrBinary -> inst.copy(name = n, lhs = remap(inst.lhs), rhs = remap(inst.rhs))
      is IrUnary -> inst.copy(name = n, operand = remap(inst.operand))
      is IrCmp -> inst.copy(name = n, lhs = remap(inst.lhs), rhs = remap(inst.rhs))
      is IrCall -> inst.copy(name = n, arguments = inst.arguments.map(remap))
      is IrGep -> inst.copy(
        name = n, base = remap(inst.base),
        indices = inst.indices.map(remap),
      )
      is IrPhi -> inst.copy(
        name = n,
        incoming = inst.incoming.map {
          it.copy(value = remap(it.value), predecessor = remapLabel(it.predecessor))
        },
      )
      is IrCast -> inst.copy(name = n, value = remap(inst.value))
      // terminators are handled separately
      is IrReturn -> error("return handled separately")
      is IrBranch -> error("branch handled separately")
      is IrJump -> error("jump handled separately")
    }
  }

  private fun terminatorSuccessors(term: IrTerminator?): Set<String> = when (term) {
    is IrBranch -> setOf(term.trueTarget, term.falseTarget)
    is IrJump -> setOf(term.target)
    else -> emptySet()
  }

  private fun updatePhiPredecessors(
    fn: IrFunction,
    oldLabel: String,
    newLabel: String,
    successors: Set<String>,
  ) {
    for (block in fn.blocks) {
      if (block.label !in successors) continue
      val rewritten = block.instructions.map { inst ->
        if (inst is IrPhi) {
          inst.copy(
            incoming = inst.incoming.map { br ->
              if (br.predecessor == oldLabel) br.copy(predecessor = newLabel) else br
            }
          )
        } else inst
      }
      block.instructions.clear()
      block.instructions.addAll(rewritten)
    }
  }

  /**
   * Replace all uses of `IrLocal(oldName, _)` with [newValue] throughout the
   * function's instructions and terminators.
   */
  private fun replaceUsesOfValue(fn: IrFunction, oldName: String, newValue: IrValue) {
    val replace: (IrValue) -> IrValue = { v ->
      if (v is IrLocal && v.name == oldName) newValue else v
    }
    for (block in fn.blocks) {
      val rewritten = block.instructions.map { inst ->
        rewriteInstructionValues(inst, replace)
      }
      block.instructions.clear()
      block.instructions.addAll(rewritten)

      block.terminator?.let { term ->
        block.replaceTerminator(rewriteTerminatorValues(term, replace))
      }
    }
  }

  private fun rewriteInstructionValues(
    inst: IrInstruction,
    remap: (IrValue) -> IrValue,
  ): IrInstruction = when (inst) {
    is IrConst -> inst
    is IrAlloca -> inst
    is IrLoad -> inst.copy(address = remap(inst.address))
    is IrStore -> inst.copy(address = remap(inst.address), value = remap(inst.value))
    is IrBinary -> inst.copy(lhs = remap(inst.lhs), rhs = remap(inst.rhs))
    is IrUnary -> inst.copy(operand = remap(inst.operand))
    is IrCmp -> inst.copy(lhs = remap(inst.lhs), rhs = remap(inst.rhs))
    is IrCall -> inst.copy(arguments = inst.arguments.map(remap))
    is IrGep -> inst.copy(base = remap(inst.base), indices = inst.indices.map(remap))
    is IrPhi -> inst.copy(incoming = inst.incoming.map { it.copy(value = remap(it.value)) })
    is IrCast -> inst.copy(value = remap(inst.value))
    is IrReturn -> inst.copy(value = inst.value?.let(remap))
    is IrBranch -> inst.copy(condition = remap(inst.condition))
    is IrJump -> inst
  }

  private fun rewriteTerminatorValues(
    term: IrTerminator,
    remap: (IrValue) -> IrValue,
  ): IrTerminator = when (term) {
    is IrReturn -> term.copy(value = term.value?.let(remap))
    is IrBranch -> term.copy(condition = remap(term.condition))
    is IrJump -> term
  }
}
