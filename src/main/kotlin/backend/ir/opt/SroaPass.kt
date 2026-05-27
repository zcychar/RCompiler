package backend.ir.opt

// Splits conservative aggregate allocas into scalar slots.

import backend.ir.*

class SroaPass(
    private val maxSplitSlots: Int = 32,
) : FunctionPass {
    override val name: String = "sroa"

    override fun run(module: IrModule, function: IrFunction) {
        if (function.blocks.isEmpty()) return

        expandWholeAggregateCopies(function)

        var changed = true
        var rounds = 0
        while (changed && rounds < MAX_ROUNDS) {
            rounds++
            changed = false

            val entry = function.blocks.first()
            val candidates = entry.instructions
                .filterIsInstance<IrAlloca>()
                .filter { isAggregate(it.allocatedType) }
                .map { it.name }

            for (candidate in candidates) {
                val result = analyzeCandidate(function, candidate) ?: continue
                if (result.leafPaths.isEmpty()) continue
                if (result.leafPaths.size > maxSplitSlots) continue
                applySplit(function, result)
                changed = true
                break
            }
        }
    }

    private fun expandWholeAggregateCopies(function: IrFunction) {
        val nameAllocator = SroaNameAllocator(function)
        val plans = buildAggregateCopyPlans(function, nameAllocator)
        if (plans.isEmpty()) return

        function.blocks.forEach { block ->
            val rewritten = mutableListOf<IrInstruction>()
            block.instructions.forEach { instruction ->
                when {
                    instruction is IrLoad && instruction.name in plans -> {
                        rewritten.addAll(plans.getValue(instruction.name).loadInstructions)
                    }
                    instruction is IrStore -> {
                        val sourceName = localName(instruction.value)
                        val plan = sourceName?.let(plans::get)
                        if (plan == null) {
                            rewritten.add(instruction)
                        } else {
                            rewritten.addAll(expandAggregateStore(instruction, plan, nameAllocator))
                        }
                    }
                    else -> rewritten.add(instruction)
                }
            }
            block.instructions.clear()
            block.instructions.addAll(rewritten)
        }
    }

    private fun buildAggregateCopyPlans(
        function: IrFunction,
        nameAllocator: SroaNameAllocator,
    ): Map<String, AggregateCopyPlan> {
        val aggregateLoads = linkedMapOf<String, IrLoad>()
        function.blocks.forEach { block ->
            block.instructions.forEach { instruction ->
                if (instruction is IrLoad &&
                    instruction.name.isNotBlank() &&
                    isAggregate(instruction.type) &&
                    pointsTo(instruction.address, instruction.type)
                ) {
                    aggregateLoads[instruction.name] = instruction
                }
            }
        }
        if (aggregateLoads.isEmpty()) return emptyMap()

        val storesByLoad = linkedMapOf<String, MutableList<IrStore>>()
        val unsafeLoads = linkedSetOf<String>()

        fun rejectAggregateLoadUse(value: IrValue?) {
            val name = value?.let(::localName) ?: return
            if (name in aggregateLoads) unsafeLoads.add(name)
        }

        function.blocks.forEach { block ->
            block.instructions.forEach { instruction ->
                when (instruction) {
                    is IrStore -> {
                        val valueName = localName(instruction.value)
                        if (valueName != null && valueName in aggregateLoads) {
                            val load = aggregateLoads.getValue(valueName)
                            if (instruction.value.type == load.type &&
                                pointsTo(instruction.address, load.type)
                            ) {
                                storesByLoad.getOrPut(valueName) { mutableListOf() }.add(instruction)
                            } else {
                                unsafeLoads.add(valueName)
                            }
                            rejectAggregateLoadUse(instruction.address)
                        } else {
                            instructionUses(instruction).forEach(::rejectAggregateLoadUse)
                        }
                    }
                    else -> instructionUses(instruction).forEach(::rejectAggregateLoadUse)
                }
            }
            block.terminator?.let { terminator ->
                terminatorUses(terminator).forEach(::rejectAggregateLoadUse)
            }
        }

        return aggregateLoads.mapNotNull { (name, load) ->
            if (name in unsafeLoads) return@mapNotNull null
            if (storesByLoad[name].isNullOrEmpty()) return@mapNotNull null
            val leafPaths = scalarLeafPaths(load.type) ?: return@mapNotNull null
            if (leafPaths.size > maxSplitSlots) return@mapNotNull null
            name to buildAggregateLoadPlan(load, leafPaths, nameAllocator)
        }.toMap()
    }

    private fun buildAggregateLoadPlan(
        load: IrLoad,
        leafPaths: List<AccessPath>,
        nameAllocator: SroaNameAllocator,
    ): AggregateCopyPlan {
        val instructions = mutableListOf<IrInstruction>()
        val leaves = leafPaths.map { path ->
            val leafType = typeAtPath(load.type, path)
                ?: error("SROA lost aggregate-copy leaf type for ${load.name}.${path.indices}")
            val pointerName = nameAllocator.fresh("${load.name}.sroa.load.${path.suffix()}.ptr")
            val pointer = IrLocal(pointerName, IrPointer(leafType))
            instructions.add(IrGep(pointerName, pointer.type, load.address, gepIndices(path)))

            val valueName = nameAllocator.fresh("${load.name}.sroa.load.${path.suffix()}")
            val value = IrLocal(valueName, leafType)
            instructions.add(IrLoad(valueName, leafType, pointer))

            AggregateCopyLeaf(path, leafType, value)
        }
        return AggregateCopyPlan(load, leaves, instructions)
    }

    private fun expandAggregateStore(
        store: IrStore,
        plan: AggregateCopyPlan,
        nameAllocator: SroaNameAllocator,
    ): List<IrInstruction> {
        val stem = store.name.takeIf { it.isNotBlank() } ?: "${plan.load.name}.sroa.store"
        return plan.leaves.flatMap { leaf ->
            val pointerName = nameAllocator.fresh("$stem.${leaf.path.suffix()}.ptr")
            val pointer = IrLocal(pointerName, IrPointer(leaf.type))
            listOf(
                IrGep(pointerName, pointer.type, store.address, gepIndices(leaf.path)),
                IrStore("", UNIT_TYPE, pointer, leaf.value),
            )
        }
    }

    private fun scalarLeafPaths(type: IrType): List<AccessPath>? {
        val paths = mutableListOf<AccessPath>()

        fun visit(current: IrType, path: List<Int>): Boolean = when {
            isScalarLeaf(current) -> {
                paths.add(AccessPath(path))
                true
            }
            current is IrStruct -> current.fields.withIndex().all { (index, fieldType) ->
                visit(fieldType, path + index)
            }
            current is IrArray -> (0 until current.length).all { index ->
                visit(current.element, path + index)
            }
            else -> false
        }

        return if (visit(type, emptyList())) paths else null
    }

    private fun gepIndices(path: AccessPath): List<IrValue> =
        listOf(IrConstant(0, INDEX_TYPE)) +
            path.indices.map { index -> IrConstant(index.toLong(), INDEX_TYPE) }

    private fun pointsTo(value: IrValue, pointee: IrType): Boolean =
        (value.type as? IrPointer)?.pointee == pointee

    private fun analyzeCandidate(function: IrFunction, root: String): SplitAnalysis? {
        val definitions = linkedMapOf<String, IrInstruction>()
        function.blocks.forEach { block ->
            block.instructions.forEach { instruction ->
                instruction.definedName()?.let { definitions[it] = instruction }
            }
        }

        val rootAlloca = definitions[root] as? IrAlloca ?: return null
        val rootType = rootAlloca.allocatedType
        if (!isAggregate(rootType)) return null

        val memo = mutableMapOf<String, AccessPath?>()
        val failed = mutableSetOf<String>()

        fun resolve(value: IrValue): AccessPath? {
            val local = value as? IrLocal ?: return null
            if (local.name == root) return AccessPath(emptyList())
            if (local.name in failed) return null
            if (memo.containsKey(local.name)) return memo[local.name]

            val gep = definitions[local.name] as? IrGep ?: return null
            val basePath = resolve(gep.base) ?: return null
            val extension = gepPath(rootType, basePath, gep.indices)
            if (extension == null) {
                failed.add(local.name)
                memo[local.name] = null
                return null
            }

            val path = AccessPath(basePath.indices + extension)
            memo[local.name] = path
            return path
        }

        val derivedPointers = linkedMapOf<String, AccessPath>()
        val leafPaths = linkedSetOf<AccessPath>()
        var unsafe = false

        fun rejectIfDerived(value: IrValue?) {
            if (value != null && resolve(value) != null) unsafe = true
        }

        fun requireScalarAddress(value: IrValue?) {
            val path = value?.let(::resolve) ?: return
            val type = typeAtPath(rootType, path) ?: run {
                unsafe = true
                return
            }
            if (!isScalarLeaf(type)) {
                unsafe = true
                return
            }
            leafPaths.add(path)
        }

        function.blocks.forEach { block ->
            block.instructions.forEach { instruction ->
                if (unsafe) return@forEach
                when (instruction) {
                    is IrAlloca -> Unit
                    is IrGep -> {
                        val path = resolve(instruction.base)
                        if (path != null) {
                            val resultPath = resolve(IrLocal(instruction.name, instruction.type))
                            if (resultPath == null) {
                                unsafe = true
                            } else {
                                derivedPointers[instruction.name] = resultPath
                            }
                        } else {
                            instruction.indices.forEach(::rejectIfDerived)
                        }
                    }
                    is IrLoad -> requireScalarAddress(instruction.address)
                    is IrStore -> {
                        requireScalarAddress(instruction.address)
                        rejectIfDerived(instruction.value)
                    }
                    is IrBinary -> {
                        rejectIfDerived(instruction.lhs)
                        rejectIfDerived(instruction.rhs)
                    }
                    is IrUnary -> rejectIfDerived(instruction.operand)
                    is IrCmp -> {
                        rejectIfDerived(instruction.lhs)
                        rejectIfDerived(instruction.rhs)
                    }
                    is IrCall -> instruction.arguments.forEach(::rejectIfDerived)
                    is IrPhi -> instruction.incoming.forEach { incoming -> rejectIfDerived(incoming.value) }
                    is IrCast -> rejectIfDerived(instruction.value)
                    is IrConst -> Unit
                    is IrReturn -> rejectIfDerived(instruction.value)
                    is IrBranch -> rejectIfDerived(instruction.condition)
                    is IrJump -> Unit
                }
            }
            when (val terminator = block.terminator) {
                is IrReturn -> rejectIfDerived(terminator.value)
                is IrBranch -> rejectIfDerived(terminator.condition)
                is IrJump, null -> Unit
            }
        }

        if (unsafe) return null

        leafPaths.forEach { path ->
            val type = typeAtPath(rootType, path) ?: return null
            if (!isScalarLeaf(type)) return null
        }

        return SplitAnalysis(
            root = root,
            rootAlloca = rootAlloca,
            rootType = rootType,
            derivedPointers = derivedPointers,
            leafPaths = leafPaths,
        )
    }

    private fun applySplit(function: IrFunction, analysis: SplitAnalysis) {
        val nameAllocator = SroaNameAllocator(function)
        val replacementByPath = linkedMapOf<AccessPath, IrLocal>()
        val newAllocas = analysis.leafPaths.map { path ->
            val type = typeAtPath(analysis.rootType, path)
                ?: error("SROA lost type for ${analysis.root}.${path.indices}")
            val name = nameAllocator.fresh("${analysis.root}.sroa.${path.suffix()}")
            val local = IrLocal(name, IrPointer(type))
            replacementByPath[path] = local
            IrAlloca(name, IrPointer(type), type)
        }

        val derivedNames = analysis.derivedPointers.keys
        val leafReplacementByName = analysis.derivedPointers.mapNotNull { (name, path) ->
            val local = replacementByPath[path] ?: return@mapNotNull null
            name to local
        }.toMap()

        function.blocks.forEach { block ->
            val rewritten = mutableListOf<IrInstruction>()
            block.instructions.forEach { instruction ->
                when {
                    instruction is IrAlloca && instruction.name == analysis.root -> {
                        rewritten.addAll(newAllocas)
                    }
                    instruction is IrGep && instruction.name in derivedNames -> Unit
                    else -> rewritten.add(rewriteInstructionValues(instruction) { value ->
                        replacePointerValue(value, leafReplacementByName)
                    })
                }
            }
            block.instructions.clear()
            block.instructions.addAll(rewritten)

            block.terminator?.let { terminator ->
                block.replaceTerminator(
                    rewriteTerminatorValues(terminator) { value ->
                        replacePointerValue(value, leafReplacementByName)
                    }
                )
            }
        }
    }

    private fun replacePointerValue(
        value: IrValue,
        replacements: Map<String, IrLocal>,
    ): IrValue =
        if (value is IrLocal) replacements[value.name] ?: value else value

    private fun gepPath(rootType: IrType, basePath: AccessPath, indices: List<IrValue>): List<Int>? {
        var currentType = typeAtPath(rootType, basePath) ?: return null
        val constants = indices.map { (it as? IrConstant)?.value?.toInt() ?: return null }
        if (constants.isEmpty()) return emptyList()

        var start = 0
        if (isAggregate(currentType)) {
            if (constants.first() != 0) return null
            start = 1
        }

        val extension = mutableListOf<Int>()
        for (index in constants.drop(start)) {
            currentType = when (currentType) {
                is IrStruct -> {
                    if (index !in currentType.fields.indices) return null
                    extension.add(index)
                    currentType.fields[index]
                }
                is IrArray -> {
                    if (index < 0 || index >= currentType.length) return null
                    extension.add(index)
                    currentType.element
                }
                else -> return null
            }
        }
        return extension
    }

    private fun typeAtPath(rootType: IrType, path: AccessPath): IrType? {
        var current = rootType
        path.indices.forEach { index ->
            current = when (current) {
                is IrStruct -> current.fields.getOrNull(index) ?: return null
                is IrArray -> {
                    if (index < 0 || index >= current.length) return null
                    current.element
                }
                else -> return null
            }
        }
        return current
    }

    private fun isScalarLeaf(type: IrType): Boolean = type is IrPrimitive || type is IrPointer

    private fun isAggregate(type: IrType): Boolean = type is IrStruct || type is IrArray

    private data class AccessPath(val indices: List<Int>) {
        fun suffix(): String = indices.joinToString(".").ifBlank { "root" }
    }

    private data class SplitAnalysis(
        val root: String,
        val rootAlloca: IrAlloca,
        val rootType: IrType,
        val derivedPointers: Map<String, AccessPath>,
        val leafPaths: Set<AccessPath>,
    )

    private data class AggregateCopyPlan(
        val load: IrLoad,
        val leaves: List<AggregateCopyLeaf>,
        val loadInstructions: List<IrInstruction>,
    )

    private data class AggregateCopyLeaf(
        val path: AccessPath,
        val type: IrType,
        val value: IrLocal,
    )

    private companion object {
        val INDEX_TYPE = IrPrimitive(PrimitiveKind.I32)
        val UNIT_TYPE = IrPrimitive(PrimitiveKind.UNIT)
        const val MAX_ROUNDS = 20
    }
}

private class SroaNameAllocator(
    function: IrFunction,
) {
    private val usedNames = mutableSetOf<String>()

    init {
        function.parameterNames.filterTo(usedNames) { it.isNotBlank() }
        function.blocks.forEach { block ->
            block.instructions.forEach { instruction ->
                if (instruction.name.isNotBlank()) usedNames.add(instruction.name)
            }
        }
    }

    fun fresh(stem: String): String {
        val base = stem.ifBlank { "sroa" }
        if (usedNames.add(base)) return base
        var index = 2
        while (true) {
            val candidate = "$base.$index"
            if (usedNames.add(candidate)) return candidate
            index++
        }
    }
}
