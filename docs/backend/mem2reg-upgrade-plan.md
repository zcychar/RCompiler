IR Mem2Reg Upgrade Plan
=======================

Date: 2026-03-10

Goal
----

Prepare the current IR backend for a correct, testable `mem2reg` pass, while keeping current ABI decisions (`sret` for aggregate returns).


Current State Snapshot
----------------------

- IR generation is integrated into the main pipeline and emits LLVM-like IR.
- Local variables and parameters are mostly represented as stack slots (`alloca` + `store` + `load`).
- The IR already supports `phi` instructions and multi-block control flow.
- Aggregate values are handled with:
  - hidden `sret` pointer for aggregate returns,
  - explicit aggregate memory copy helpers (`llvm.memcpy` path).
- There is an explicit function-pass pipeline in place.
- The current optimized pipeline is inline -> conservative aggregate SROA -> mem2reg -> constant propagation -> CFG simplification -> DCE -> conservative ADCE -> CFG simplification -> final DCE. φ nodes are preserved in SSA form and lowered to register moves by the instruction selector during codegen (the former `phi-lowering` pass that demoted φ to alloca/store/load has been removed to avoid expensive memory traffic on the REIMU target).


Scope Boundary for Mem2Reg v1
-----------------------------

- Keep `sret` unchanged. `mem2reg` does not replace ABI return-lowering policy.
- Only promote allocas that are safe:
  - scalar/pointer element type,
  - address does not escape,
  - users are only direct `load`/`store` to the same slot.
- Mem2Reg itself still does not decompose aggregates; conservative SROA now runs
  immediately before Mem2Reg and exposes scalar/pointer leaf slots for promotion.


Upgrade Plan
------------

Phase 1: Introduce Pass Infrastructure

1. Add a backend optimization pipeline entry before final IR render.
2. Define a small pass interface (function pass is enough for now).
3. Run passes per function in deterministic order.

Expected file changes:
- `src/main/kotlin/backend/ir/IrBackend.kt`
- New files under `src/main/kotlin/backend/ir/opt/`


Phase 2: IR Analysis Utilities

1. CFG builder:
  - successors from terminators,
  - predecessor map for each basic block.
2. Dominator analysis:
  - dominator sets,
  - immediate dominator tree.
3. Dominance frontier computation.

Expected file changes:
- New files under `src/main/kotlin/backend/ir/analysis/`


Phase 3: Mem2Reg Pass (v1)

Algorithm target: classic SSA construction for promotable allocas.

1. Collect promotable allocas from entry block.
2. For each promotable slot:
  - collect definition blocks (`store slot, value`),
  - place phi nodes at dominance frontiers.
3. Rename pass over dominator tree:
  - maintain value stack per promoted slot,
  - replace loads with current SSA value,
  - remove stores to promoted slot,
  - fill phi incoming per predecessor edge.
4. Remove now-dead allocas.

Expected file changes:
- New `mem2reg` pass under `src/main/kotlin/backend/ir/opt/`
- Minor IR mutation helpers where needed (block instruction replacement/removal helpers)


Phase 4: Cleanup and Canonicalization
-------------------------------------

1. Add lightweight DCE after mem2reg. **Done**:
  - remove unused instructions created by promotion/rewrite.
2. Keep `sret` path unchanged.
3. Use the separate SROA pass for aggregate scalarization before Mem2Reg.


Readiness Fixes Recommended Before/Alongside Phase 3
----------------------------------------------------

1. Ensure backend failures are not silently swallowed in the main driver, so pass bugs surface clearly during bring-up.
2. Keep IR docs aligned with implemented optimization behavior.
3. Add stable naming helper for generated phi/load-rewrite temporaries to avoid collisions.


Testing Plan
------------

1. Analysis unit tests:
  - CFG for branch/jump/return combinations,
  - dominator tree and dominance frontier for diamond + loop graphs.
2. Mem2reg unit tests:
  - straight-line variable promotion,
  - if/else merge requiring phi,
  - loop-carried value requiring phi,
  - non-promotable slot (address escape) left unchanged.
3. Integration checks:
  - compare IR text before/after: fewer `alloca/load/store`, preserved behavior.
  - run local and IR-1 stage tests after implementation.


Follow-up Roadmap (After Mem2Reg v1)
------------------------------------

1. Conservative SROA for aggregates (struct/array locals). **Done**.
2. Run Mem2Reg after SROA. **Done**.
3. Optional control-dependence ADCE and additional target-aware codegen cleanup.
