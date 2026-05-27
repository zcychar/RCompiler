package backend.codegen

// Builds and stores register interference data for allocation.

import backend.codegen.riscv.*

data class MoveEdge(val dst: RegKey, val src: RegKey)

class InterferenceGraph() {

    val nodes: MutableSet<RegKey> = mutableSetOf()

    val adjSet: MutableSet<Pair<RegKey, RegKey>> = mutableSetOf()

    val adjList: MutableMap<RegKey, MutableSet<RegKey>> = mutableMapOf()

    val degree: MutableMap<RegKey, Int> = mutableMapOf()

    val moveList: MutableMap<RegKey, MutableSet<MoveEdge>> = mutableMapOf()

    val color: MutableMap<RegKey, RvPhysReg> = mutableMapOf()

    val alias: MutableMap<RegKey, RegKey> = mutableMapOf()

    fun addNode(key: RegKey) {
        if (nodes.add(key)) {
            adjList.getOrPut(key) { mutableSetOf() }
            degree.putIfAbsent(key, when (key) {
                is RegKey.Phys -> Int.MAX_VALUE / 2
                is RegKey.Vreg -> 0
            })
            moveList.getOrPut(key) { mutableSetOf() }
        }
    }

    fun addEdge(u: RegKey, v: RegKey) {
        if (u == v) return
        val pair = LivenessResult.orderedPair(u, v)
        if (!adjSet.add(pair)) return

        adjList.getOrPut(u) { mutableSetOf() }.add(v)
        adjList.getOrPut(v) { mutableSetOf() }.add(u)

        if (u is RegKey.Vreg) {
            degree[u] = (degree[u] ?: 0) + 1
        }
        if (v is RegKey.Vreg) {
            degree[v] = (degree[v] ?: 0) + 1
        }
    }

    fun addMove(move: MoveEdge) {
        moveList.getOrPut(move.dst) { mutableSetOf() }.add(move)
        moveList.getOrPut(move.src) { mutableSetOf() }.add(move)
    }

    fun neighbors(n: RegKey): Set<RegKey> = adjList[n] ?: emptySet()

    fun degree(n: RegKey): Int = degree[n] ?: 0

    fun getAlias(n: RegKey): RegKey {
        var cur = n
        while (alias.containsKey(cur)) {
            cur = alias[cur]!!
        }
        return cur
    }

    fun preColor(key: RegKey.Phys) {
        color[key] = key.reg
        degree[key] = Int.MAX_VALUE / 2
    }

    companion object {

        fun build(mf: RvMachineFunction, liveness: LivenessResult): InterferenceGraph {
            val graph = InterferenceGraph()

            val physNodes = mutableSetOf<RegKey.Phys>()

            for (block in mf.blocks) {
                for (inst in block.instructions) {
                    for (operand in inst.defs() + inst.uses()) {
                        val key = operand.toRegKey() ?: continue
                        graph.addNode(key)
                        if (key is RegKey.Phys) {
                            physNodes.add(key)
                        }
                    }
                }
            }

            for (block in mf.blocks) {
                liveness.forEachInstruction(block) { _, liveAfter ->
                    for (key in liveAfter) {
                        graph.addNode(key)
                        if (key is RegKey.Phys) {
                            physNodes.add(key)
                        }
                    }
                }
            }

            for (phys in physNodes) {
                graph.preColor(phys)
            }

            for ((u, v) in liveness.interferenceEdges()) {
                graph.addEdge(u, v)
            }

            for (block in mf.blocks) {
                for (inst in block.instructions) {
                    if (!inst.isMove()) continue

                    val dst = inst.defs().firstOrNull()?.toRegKey() ?: continue
                    val src = inst.uses().firstOrNull()?.toRegKey() ?: continue

                    val move = MoveEdge(dst = dst, src = src)
                    graph.addMove(move)
                }
            }

            val physList = physNodes.toList()
            for (i in physList.indices) {
                for (j in i + 1 until physList.size) {
                    graph.addEdge(physList[i], physList[j])
                }
            }

            return graph
        }
    }
}
