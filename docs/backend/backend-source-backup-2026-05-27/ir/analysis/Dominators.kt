package backend.ir.analysis

data class DominatorInfo(
  val dom: Map<String, Set<String>>,
  val idom: Map<String, String?>,
  val treeChildren: Map<String, Set<String>>,
)

fun computeDominators(cfg: Cfg): DominatorInfo {
  if (cfg.entry.isEmpty() || cfg.blockOrder.isEmpty()) {
    return DominatorInfo(
      dom = emptyMap(),
      idom = emptyMap(),
      treeChildren = emptyMap(),
    )
  }

  val all = cfg.blockOrder.toSet()
  val dom = linkedMapOf<String, Set<String>>()
  cfg.blockOrder.forEach { block ->
    dom[block] = if (block == cfg.entry) setOf(block) else all
  }

  var changed = true
  while (changed) {
    changed = false
    cfg.blockOrder.forEach { block ->
      if (block == cfg.entry) return@forEach
      val preds = cfg.predecessors[block].orEmpty()
      val predDomIntersection = preds
        .map { pred -> dom.getValue(pred) }
        .reduceOrNull { acc, set -> acc.intersect(set) }
        ?: emptySet()
      val newDom = predDomIntersection + block
      if (newDom != dom.getValue(block)) {
        dom[block] = newDom
        changed = true
      }
    }
  }

  val idom = linkedMapOf<String, String?>()
  cfg.blockOrder.forEach { block ->
    if (block == cfg.entry) {
      idom[block] = null
      return@forEach
    }
    val strictDominators = dom.getValue(block) - block
    val immediate = cfg.blockOrder.firstOrNull { candidate ->
      candidate in strictDominators &&
        strictDominators.all { other ->
          other == candidate || other in dom.getValue(candidate)
        }
    }
    idom[block] = immediate
  }

  val treeChildren = linkedMapOf<String, MutableSet<String>>()
  cfg.blockOrder.forEach { block -> treeChildren[block] = linkedSetOf() }
  idom.forEach { (block, parent) ->
    if (parent != null) {
      treeChildren.getValue(parent).add(block)
    }
  }

  return DominatorInfo(
    dom = dom,
    idom = idom,
    treeChildren = treeChildren.mapValues { it.value.toSet() },
  )
}
