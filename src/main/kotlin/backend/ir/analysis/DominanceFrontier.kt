package backend.ir.analysis

fun computeDominanceFrontier(
  cfg: Cfg,
  dominators: DominatorInfo,
): Map<String, Set<String>> {
  if (cfg.entry.isEmpty() || cfg.blockOrder.isEmpty()) return emptyMap()

  val frontier = linkedMapOf<String, MutableSet<String>>()
  cfg.blockOrder.forEach { block ->
    frontier[block] = linkedSetOf()
  }

  cfg.blockOrder.forEach { block ->
    val preds = cfg.predecessors[block].orEmpty()
    if (preds.size < 2) return@forEach

    preds.forEach { pred ->
      var runner: String? = pred
      val stop = dominators.idom[block]
      while (runner != null && runner != stop) {
        frontier.getValue(runner).add(block)
        runner = dominators.idom[runner]
      }
    }
  }

  return frontier.mapValues { it.value.toSet() }
}
