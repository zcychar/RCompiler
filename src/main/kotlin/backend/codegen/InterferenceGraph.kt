package backend.codegen

import backend.codegen.riscv.*

// ============================================================================
//  MoveEdge — represents a move instruction between two registers
// ============================================================================

/**
 * A move edge connecting [dst] and [src], corresponding to a `mv dst, src`
 * instruction.  The register allocator's coalescer attempts to assign the
 * same color to both endpoints so the move can be eliminated.
 */
data class MoveEdge(val dst: RegKey, val src: RegKey)

// ============================================================================
//  InterferenceGraph — the core data structure for Chaitin-Briggs allocation
// ============================================================================

/**
 * An undirected interference graph used by the Chaitin-Briggs graph-coloring
 * register allocator.
 *
 * Nodes are [RegKey]s (virtual registers or allocatable physical registers).
 * An edge between two nodes means the corresponding registers are
 * simultaneously live at some program point and therefore cannot be assigned
 * the same physical register (color).
 *
 * Physical register nodes are **pre-colored** — their color is fixed and their
 * degree is set to a sentinel value ([Int.MAX_VALUE] / 2) so that they are
 * never selected for simplification or spilling.
 *
 * The graph also tracks **move edges** so the coalescer can identify
 * opportunities to merge nodes and eliminate unnecessary moves.
 */
class InterferenceGraph() {

    // ------------------------------------------------------------------
    //  Core data structures
    // ------------------------------------------------------------------

    /** All nodes currently in the graph. */
    val nodes: MutableSet<RegKey> = mutableSetOf()

    /**
     * Set of interference edges stored as canonical ordered pairs.
     * Uses [LivenessResult.orderedPair] to ensure `(a, b)` and `(b, a)`
     * are represented identically, preventing duplicates.
     */
    val adjSet: MutableSet<Pair<RegKey, RegKey>> = mutableSetOf()

    /** Adjacency list: maps each node to the set of its neighbors. */
    val adjList: MutableMap<RegKey, MutableSet<RegKey>> = mutableMapOf()

    /**
     * Degree of each node.  For virtual registers this is the actual number
     * of neighbors; for pre-colored physical registers it is set to
     * [Int.MAX_VALUE] / 2 (effectively infinite) so they are never
     * simplified or spilled.
     */
    val degree: MutableMap<RegKey, Int> = mutableMapOf()

    /**
     * Move list: maps each node to the set of [MoveEdge]s it participates in.
     * Used by the coalescer to find move-related nodes.
     */
    val moveList: MutableMap<RegKey, MutableSet<MoveEdge>> = mutableMapOf()

    /**
     * Color assignment: maps a node to the physical register it has been
     * assigned.  Pre-colored physical register nodes are entered here
     * during graph construction; virtual register nodes are colored
     * during the Select phase of the allocator.
     */
    val color: MutableMap<RegKey, RvPhysReg> = mutableMapOf()

    /**
     * Coalescing alias map.  When two nodes are coalesced, one becomes the
     * representative and the other points to it via this map.  Use
     * [getAlias] to follow the chain to the current representative.
     */
    val alias: MutableMap<RegKey, RegKey> = mutableMapOf()

    // ------------------------------------------------------------------
    //  Node operations
    // ------------------------------------------------------------------

    /**
     * Add a node to the graph if it is not already present.
     *
     * - For [RegKey.Vreg] nodes the initial degree is 0.
     * - For [RegKey.Phys] nodes the degree is set to [Int.MAX_VALUE] / 2
     *   (pseudo-infinite) because physical register nodes must never be
     *   simplified or spilled.
     */
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

    /**
     * Add an undirected interference edge between [u] and [v].
     *
     * Self-loops (`u == v`) and duplicate edges are silently ignored.
     * The edge is stored in [adjSet] as a canonical ordered pair and
     * both adjacency lists are updated.
     *
     * Degree is incremented only for virtual register endpoints;
     * physical register nodes retain their pseudo-infinite degree.
     */
    fun addEdge(u: RegKey, v: RegKey) {
        if (u == v) return
        val pair = LivenessResult.orderedPair(u, v)
        if (!adjSet.add(pair)) return  // edge already exists

        // Update adjacency lists.
        adjList.getOrPut(u) { mutableSetOf() }.add(v)
        adjList.getOrPut(v) { mutableSetOf() }.add(u)

        // Increment degree only for virtual registers.
        if (u is RegKey.Vreg) {
            degree[u] = (degree[u] ?: 0) + 1
        }
        if (v is RegKey.Vreg) {
            degree[v] = (degree[v] ?: 0) + 1
        }
    }

    /**
     * Register a [MoveEdge] with both of its endpoint nodes.
     *
     * This information is used by the coalescer to find move-related nodes
     * and attempt to merge them.
     */
    fun addMove(move: MoveEdge) {
        moveList.getOrPut(move.dst) { mutableSetOf() }.add(move)
        moveList.getOrPut(move.src) { mutableSetOf() }.add(move)
    }

    // ------------------------------------------------------------------
    //  Query operations
    // ------------------------------------------------------------------

    /**
     * Return the set of neighbors of [n] in the interference graph.
     * Returns an empty set if [n] has no recorded neighbors.
     */
    fun neighbors(n: RegKey): Set<RegKey> = adjList[n] ?: emptySet()

    /**
     * Return the current degree of [n], or 0 if the node is unknown.
     */
    fun degree(n: RegKey): Int = degree[n] ?: 0

    /**
     * Follow the [alias] chain from [n] to find its current representative.
     *
     * After coalescing, multiple nodes may share a single representative.
     * This method performs path compression implicitly by following the
     * chain until a node that is not aliased is found.
     */
    fun getAlias(n: RegKey): RegKey {
        var cur = n
        while (alias.containsKey(cur)) {
            cur = alias[cur]!!
        }
        return cur
    }

    // ------------------------------------------------------------------
    //  Pre-coloring
    // ------------------------------------------------------------------

    /**
     * Pre-color a physical register node: set its [color] to its own
     * physical register and ensure its degree is pseudo-infinite.
     */
    fun preColor(key: RegKey.Phys) {
        color[key] = key.reg
        degree[key] = Int.MAX_VALUE / 2
    }

    // ------------------------------------------------------------------
    //  Companion — graph construction
    // ------------------------------------------------------------------

    companion object {

        /**
         * Build an [InterferenceGraph] from a machine function and its
         * liveness analysis result.
         *
         * Construction proceeds in five phases:
         *
         * 1. **Node creation** — Every virtual register and every allocatable
         *    physical register that appears in any instruction's defs or uses
         *    is added as a node.
         *
         * 2. **Pre-coloring** — All [RegKey.Phys] nodes are pre-colored to
         *    their corresponding physical register.
         *
         * 3. **Interference edges** — All pairs from
         *    [LivenessResult.interferenceEdges] are added.
         *
         * 4. **Move discovery** — Every `mv` instruction is walked to record
         *    [MoveEdge]s, enabling the coalescer.
         *
         * 5. **Physical–physical interference** — All pairs of physical
         *    register nodes are connected (they trivially interfere because
         *    each occupies a distinct architectural register).
         *
         * @param mf       The machine function whose registers are being allocated.
         * @param liveness The liveness analysis result for [mf].
         * @return A fully constructed interference graph ready for coloring.
         */
        fun build(mf: RvMachineFunction, liveness: LivenessResult): InterferenceGraph {
            val graph = InterferenceGraph()

            // ── Phase 1: Create nodes ────────────────────────────────────
            // Collect all RegKeys that appear in defs or uses across every
            // instruction in the function.
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

            // Also ensure every allocatable physical register that appears
            // in the liveness information is present as a node.
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

            // ── Phase 2: Pre-color physical register nodes ───────────────
            for (phys in physNodes) {
                graph.preColor(phys)
            }

            // ── Phase 3: Add interference edges from liveness ────────────
            for ((u, v) in liveness.interferenceEdges()) {
                graph.addEdge(u, v)
            }

            // ── Phase 4: Discover moves for the coalescer ────────────────
            for (block in mf.blocks) {
                for (inst in block.instructions) {
                    if (!inst.isMove()) continue

                    val dst = inst.defs().firstOrNull()?.toRegKey() ?: continue
                    val src = inst.uses().firstOrNull()?.toRegKey() ?: continue

                    val move = MoveEdge(dst = dst, src = src)
                    graph.addMove(move)
                }
            }

            // ── Phase 5: Physical–physical interference ──────────────────
            // All distinct physical register nodes interfere with each other
            // because they occupy different architectural registers and can
            // never be "coalesced" to share a single register.
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
