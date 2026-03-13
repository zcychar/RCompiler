package backend.ir.opt

import backend.ir.*
import backend.ir.analysis.buildCfg
import backend.ir.analysis.computeDominanceFrontier
import backend.ir.analysis.computeDominators

class Mem2RegPass : FunctionPass {
  override val name: String = "mem2reg"

  override fun run(module: IrModule, function: IrFunction) {
    if (function.blocks.isEmpty()) return
    val cfg = buildCfg(function)
    if (cfg.entry.isEmpty()) return

    val entryBlock = function.blocks.first()
    val entryAllocas = entryBlock.instructions.filterIsInstance<IrAlloca>()
    if (entryAllocas.isEmpty()) return

    val promotable = linkedMapOf<String, IrAlloca>()
    entryAllocas.forEach { alloca ->
      if (isPromotableType(alloca.allocatedType)) {
        promotable.putIfAbsent(alloca.name, alloca)
      }
    }
    if (promotable.isEmpty()) return

    val rejected = collectRejectedSlots(function, promotable.keys)
    rejected.forEach { promotable.remove(it) }
    if (promotable.isEmpty()) return

    val dominators = computeDominators(cfg)
    val frontier = computeDominanceFrontier(cfg, dominators)
    val blockIndex = cfg.blockOrder.withIndex().associate { (idx, label) -> label to idx }
    val slotOrder = promotable.keys.toList()
    val slotIndex = slotOrder.withIndex().associate { (idx, slot) -> slot to idx }

    val defBlocks = collectDefinitionBlocks(function, promotable.keys)
    val phiSlotsByBlock = placePhiSlots(
      defBlocks = defBlocks,
      frontier = frontier,
      slotOrder = slotOrder,
      blockIndex = blockIndex,
    )

    val nameAllocator = NameAllocator(function)
    val phiNameByBlock = mutableMapOf<String, MutableMap<String, String>>()
    val phiSlotByName = mutableMapOf<String, String>()
    val phiIncoming = mutableMapOf<String, MutableList<PhiBranch>>()
    insertPhiNodes(
      function = function,
      promotable = promotable,
      phiSlotsByBlock = phiSlotsByBlock,
      phiNameByBlock = phiNameByBlock,
      phiSlotByName = phiSlotByName,
      phiIncoming = phiIncoming,
      nameAllocator = nameAllocator,
      slotIndex = slotIndex,
    )

    val stacks = mutableMapOf<String, ArrayDeque<IrValue>>()
    promotable.forEach { (slot, alloca) ->
      stacks[slot] = ArrayDeque<IrValue>().also { it.addLast(IrUndef(alloca.allocatedType)) }
    }
    val alias = mutableMapOf<String, IrValue>()
    val blockByLabel = function.blocks.associateBy { it.label }
    val visited = mutableSetOf<String>()

    fun dfs(blockLabel: String) {
      if (!visited.add(blockLabel)) return
      val block = blockByLabel[blockLabel] ?: return

      val pushCount = mutableMapOf<String, Int>()
      val original = block.instructions.toList()
      val rewritten = mutableListOf<IrInstruction>()

      original.forEach { instruction ->
        if (instruction is IrPhi) {
          val slot = phiSlotByName[instruction.name]
          if (slot != null) {
            val phiValue = IrLocal(instruction.name, instruction.type)
            stacks.getValue(slot).addLast(phiValue)
            pushCount[slot] = (pushCount[slot] ?: 0) + 1
            rewritten.add(instruction)
            return@forEach
          }
        }

        val normalized = rewriteInstruction(instruction, alias)
        when (normalized) {
          is IrLoad -> {
            val slot = slotName(normalized.address, promotable.keys)
            if (slot != null) {
              alias[normalized.name] = currentSlotValue(slot, stacks, promotable)
            } else {
              rewritten.add(normalized)
            }
          }

          is IrStore -> {
            val slot = slotName(normalized.address, promotable.keys)
            if (slot != null) {
              val storedValue = resolveAlias(normalized.value, alias)
              stacks.getValue(slot).addLast(storedValue)
              pushCount[slot] = (pushCount[slot] ?: 0) + 1
            } else {
              rewritten.add(normalized)
            }
          }

          else -> rewritten.add(normalized)
        }
      }

      block.instructions.clear()
      block.instructions.addAll(rewritten)

      block.terminator?.let { term ->
        block.replaceTerminator(rewriteTerminator(term, alias))
      }

      cfg.successors[blockLabel].orEmpty()
        .sortedBy { label -> blockIndex[label] ?: Int.MAX_VALUE }
        .forEach { succ ->
          val phis = phiNameByBlock[succ] ?: return@forEach
          phis.entries.sortedBy { (slot, _) -> slotIndex[slot] ?: Int.MAX_VALUE }.forEach { (slot, phiName) ->
            val incomingValue = resolveAlias(currentSlotValue(slot, stacks, promotable), alias)
            phiIncoming.getValue(phiName).add(PhiBranch(incomingValue, blockLabel))
          }
        }

      dominators.treeChildren[blockLabel].orEmpty()
        .sortedBy { label -> blockIndex[label] ?: Int.MAX_VALUE }
        .forEach { child ->
          dfs(child)
        }

      pushCount.forEach { (slot, count) ->
        repeat(count) {
          stacks.getValue(slot).removeLast()
        }
      }
    }

    cfg.blockOrder.filter { label -> dominators.idom[label] == null }.forEach(::dfs)
    cfg.blockOrder.forEach { label -> if (label !in visited) dfs(label) }

    rewriteAllRemainingValues(function, alias, phiIncoming)
    removePromotedStackOps(function, promotable.keys)
  }

  private fun isPromotableType(type: IrType): Boolean = when (type) {
    is IrPrimitive, is IrPointer -> true
    else -> false
  }

  private fun collectRejectedSlots(function: IrFunction, candidates: Set<String>): Set<String> {
    val rejected = mutableSetOf<String>()
    fun rejectIfCandidate(value: IrValue?) {
      val slot = value?.let { slotName(it, candidates) }
      if (slot != null) rejected.add(slot)
    }

    function.blocks.forEach { block ->
      block.instructions.forEach { instruction ->
        when (instruction) {
          is IrAlloca -> Unit
          is IrLoad -> Unit // Address-use is allowed for candidates.
          is IrStore -> {
            rejectIfCandidate(instruction.value) // Candidate used as value means escape.
          }

          is IrBinary -> {
            rejectIfCandidate(instruction.lhs)
            rejectIfCandidate(instruction.rhs)
          }

          is IrUnary -> rejectIfCandidate(instruction.operand)
          is IrCmp -> {
            rejectIfCandidate(instruction.lhs)
            rejectIfCandidate(instruction.rhs)
          }

          is IrCall -> instruction.arguments.forEach(::rejectIfCandidate)
          is IrGep -> {
            rejectIfCandidate(instruction.base)
            instruction.indices.forEach(::rejectIfCandidate)
          }

          is IrPhi -> instruction.incoming.forEach { incoming ->
            rejectIfCandidate(incoming.value)
          }

          is IrCast -> rejectIfCandidate(instruction.value)
          else -> Unit
        }
      }
      when (val term = block.terminator) {
        is IrReturn -> rejectIfCandidate(term.value)
        is IrBranch -> rejectIfCandidate(term.condition)
        is IrJump -> Unit
        null -> Unit
      }
    }

    return rejected
  }

  private fun collectDefinitionBlocks(
    function: IrFunction,
    promotableSlots: Set<String>,
  ): Map<String, Set<String>> {
    val defs = mutableMapOf<String, MutableSet<String>>()
    promotableSlots.forEach { slot -> defs[slot] = linkedSetOf() }
    function.blocks.forEach { block ->
      block.instructions.forEach { instruction ->
        if (instruction is IrStore) {
          val slot = slotName(instruction.address, promotableSlots) ?: return@forEach
          defs.getValue(slot).add(block.label)
        }
      }
    }
    return defs.mapValues { (_, blocks) -> blocks.toSet() }
  }

  private fun placePhiSlots(
    defBlocks: Map<String, Set<String>>,
    frontier: Map<String, Set<String>>,
    slotOrder: List<String>,
    blockIndex: Map<String, Int>,
  ): Map<String, Set<String>> {
    val placed = mutableMapOf<String, MutableSet<String>>()
    slotOrder.forEach { slot ->
      val defs = defBlocks[slot].orEmpty()
      if (defs.isEmpty()) return@forEach

      val hasPhi = mutableSetOf<String>()
      val work = ArrayDeque<String>()
      defs.forEach(work::addLast)

      while (work.isNotEmpty()) {
        val block = work.removeFirst()
        frontier[block].orEmpty()
          .sortedBy { label -> blockIndex[label] ?: Int.MAX_VALUE }
          .forEach { frontierBlock ->
            if (hasPhi.add(frontierBlock)) {
              placed.getOrPut(frontierBlock) { linkedSetOf() }.add(slot)
              if (frontierBlock !in defs) {
                work.addLast(frontierBlock)
              }
            }
          }
      }
    }

    return placed.mapValues { (_, slots) -> slots.toSet() }
  }

  private fun insertPhiNodes(
    function: IrFunction,
    promotable: Map<String, IrAlloca>,
    phiSlotsByBlock: Map<String, Set<String>>,
    phiNameByBlock: MutableMap<String, MutableMap<String, String>>,
    phiSlotByName: MutableMap<String, String>,
    phiIncoming: MutableMap<String, MutableList<PhiBranch>>,
    nameAllocator: NameAllocator,
    slotIndex: Map<String, Int>,
  ) {
    function.blocks.forEach { block ->
      val slots = phiSlotsByBlock[block.label].orEmpty()
        .sortedBy { slot -> slotIndex[slot] ?: Int.MAX_VALUE }
      if (slots.isEmpty()) return@forEach

      val prefix = mutableListOf<IrInstruction>()
      val bySlot = linkedMapOf<String, String>()
      slots.forEach { slot ->
        val alloca = promotable.getValue(slot)
        val phiName = nameAllocator.fresh("m2r.phi.$slot")
        val phi = IrPhi(
          name = phiName,
          type = alloca.allocatedType,
          incoming = emptyList(),
        )
        prefix.add(phi)
        bySlot[slot] = phiName
        phiSlotByName[phiName] = slot
        phiIncoming[phiName] = mutableListOf()
      }
      phiNameByBlock[block.label] = bySlot

      val old = block.instructions.toList()
      block.instructions.clear()
      block.instructions.addAll(prefix)
      block.instructions.addAll(old)
    }
  }

  private fun currentSlotValue(
    slot: String,
    stacks: Map<String, ArrayDeque<IrValue>>,
    promotable: Map<String, IrAlloca>,
  ): IrValue {
    val stack = stacks[slot]
    if (stack != null && stack.isNotEmpty()) return stack.last()
    return IrUndef(promotable.getValue(slot).allocatedType)
  }

  private fun slotName(value: IrValue, slots: Set<String>): String? {
    val local = value as? IrLocal ?: return null
    return local.name.takeIf { it in slots }
  }

  private fun resolveAlias(value: IrValue, alias: Map<String, IrValue>): IrValue {
    var current = value
    val seen = mutableSetOf<String>()
    while (current is IrLocal) {
      val next = alias[current.name] ?: break
      if (!seen.add(current.name)) break
      current = next
    }
    return current
  }

  private fun rewriteInstruction(
    instruction: IrInstruction,
    alias: Map<String, IrValue>,
  ): IrInstruction = when (instruction) {
    is IrAlloca -> instruction
    is IrLoad -> instruction.copy(address = resolveAlias(instruction.address, alias))
    is IrStore -> instruction.copy(
      address = resolveAlias(instruction.address, alias),
      value = resolveAlias(instruction.value, alias),
    )

    is IrBinary -> instruction.copy(
      lhs = resolveAlias(instruction.lhs, alias),
      rhs = resolveAlias(instruction.rhs, alias),
    )

    is IrUnary -> instruction.copy(
      operand = resolveAlias(instruction.operand, alias)
    )

    is IrCmp -> instruction.copy(
      lhs = resolveAlias(instruction.lhs, alias),
      rhs = resolveAlias(instruction.rhs, alias),
    )

    is IrCall -> instruction.copy(
      arguments = instruction.arguments.map { arg -> resolveAlias(arg, alias) }
    )

    is IrGep -> instruction.copy(
      base = resolveAlias(instruction.base, alias),
      indices = instruction.indices.map { index -> resolveAlias(index, alias) },
    )

    is IrPhi -> instruction.copy(
      incoming = instruction.incoming.map { incoming ->
        incoming.copy(value = resolveAlias(incoming.value, alias))
      }
    )

    is IrCast -> instruction.copy(
      value = resolveAlias(instruction.value, alias)
    )

    else -> instruction
  }

  private fun rewriteTerminator(
    terminator: backend.ir.IrTerminator,
    alias: Map<String, IrValue>,
  ): backend.ir.IrTerminator = when (terminator) {
    is IrReturn -> terminator.copy(
      value = terminator.value?.let { value -> resolveAlias(value, alias) }
    )

    is IrBranch -> terminator.copy(
      condition = resolveAlias(terminator.condition, alias)
    )

    is IrJump -> terminator
  }

  private fun rewriteAllRemainingValues(
    function: IrFunction,
    alias: Map<String, IrValue>,
    phiIncoming: Map<String, List<PhiBranch>>,
  ) {
    function.blocks.forEach { block ->
      val rewrittenInstructions = block.instructions.map { instruction ->
        val normalized = rewriteInstruction(instruction, alias)
        if (normalized is IrPhi && phiIncoming.containsKey(normalized.name)) {
          val incoming = phiIncoming.getValue(normalized.name).map { entry ->
            entry.copy(value = resolveAlias(entry.value, alias))
          }
          normalized.copy(incoming = incoming)
        } else {
          normalized
        }
      }
      block.instructions.clear()
      block.instructions.addAll(rewrittenInstructions)

      block.terminator?.let { term ->
        block.replaceTerminator(rewriteTerminator(term, alias))
      }
    }
  }

  private fun removePromotedStackOps(
    function: IrFunction,
    promotedSlots: Set<String>,
  ) {
    function.blocks.forEach { block ->
      block.instructions.removeAll { instruction ->
        when (instruction) {
          is IrAlloca -> instruction.name in promotedSlots
          is IrLoad -> slotName(instruction.address, promotedSlots) != null
          is IrStore -> slotName(instruction.address, promotedSlots) != null
          else -> false
        }
      }
    }
  }
}

private class NameAllocator(
  function: IrFunction,
) {
  private val usedNames = mutableSetOf<String>()

  init {
    function.signature.parameters.indices.forEach { index ->
      val parameterName = function.parameterNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "arg$index"
      usedNames.add(parameterName)
    }
    function.blocks.forEach { block ->
      block.instructions.forEach { instruction ->
        if (instruction.name.isNotBlank()) {
          usedNames.add(instruction.name)
        }
      }
    }
  }

  fun fresh(stem: String): String {
    val base = stem.ifBlank { "m2r.tmp" }
    if (usedNames.add(base)) return base
    var id = 2
    while (true) {
      val candidate = "$base.$id"
      if (usedNames.add(candidate)) return candidate
      id++
    }
  }
}
