# IR refactor plan (address-only aggregates, sret, destination passing)

We do **not** model lifetime/ownership (unlike Rust). The plan below is a staging guide for refactoring codegen; no code has been changed yet. Each step lists what to add/change/remove.

## Stage 0: groundwork and invariants
- Add helper `isAggregate(IrType)` (struct/array) and keep existing primitive handling.
- Introduce an emitter result wrapper (e.g., `ExprResult { kind: Scalar|Addr, type, scalar?, addr?, isLvalue }`) so aggregate expressions can return addresses instead of SSA values.
- Representation rules:
  - Scalars (ints/bools/pointer-as-scalar): `kind=Scalar`, value in `scalar`.
  - Aggregates (struct/array): `kind=Addr`, address of storage in `addr` (address itself is a scalar pointer), `isLvalue` marks existing storage vs temp.
  - References/borrows to aggregates: `kind=Scalar`, value is the pointer to the aggregate (no extra wrapping); use directly as a base for GEP/field/index/method.
- Clarify invariant: aggregates are **address-only**; scalars remain SSA.
- Non-parameter references/borrows (e.g., `let r = &arr;` or a field yielding `&T`) follow the same pointer-as-scalar rule as above; caller/callee rules in Stage 6/7 apply symmetrically when such values are passed around.

## Stage 1: entry-block allocations
- Change allocas for locals/temps to be inserted in the function entry block (or add a hoisting pass).
- Remove block-local allocas that currently sit mid-block in `StructExpr`/`ArrayExpr`/other helpers.

## Stage 2: aggregate literals as addresses
- Rework `emitStructLiteral`/`emitArrayLiteral` to return an address (no trailing load). For aggregates, `emitExpr` should yield `ExprResult(kind=Addr, isLvalue=false)`.
- Delete the final `load` of struct/array literals; keep initialization-by-store.

## Stage 3: destination passing
- Add `emitExprInto(destPtr, expr)` for aggregates; it writes directly into `destPtr`.
- Use destination passing in:
  - `let` initialization of aggregates (write directly into the bound slot).
  - Assignment of aggregates (via a unified copy helper; see Stage 4).
  - `return` of aggregates (write into sret pointer once added).
  - Call argument marshalling for by-value aggregate params (write into the caller’s temp slot).
- When the consumer already has a final slot (e.g., `let x = if ...`), pass that slot down to both branches to avoid extra temps.

## Stage 4: unified aggregate copy
- Add `emitCopyAggregate(destPtr, srcPtr, type)` handling struct (per-field), array (per-element or loop/memcpy), and scalar (load+store).
- Replace scattered deep-copy logic with calls to this helper:
  - Aggregate assignment (`x = y`).
  - By-value aggregate argument setup (if ABI requires a copy).
  - Any remaining places that copy aggregates today.
- Remove ad hoc aggregate load/store sequences once the helper is in place.
- Mirror ref’s behavior: array copy uses an entry-block loop index; struct copy recurses field-by-field; scalar copy is load+store.
- Add an array literal initializer helper (like ref’s `StoreArrayLiteral`) that fills a provided destination address in-place, recursing for nested arrays/repeats and placing loop indices in the entry block.
- When copying/storing aggregates, use `isLvalue` from `ExprResult` to avoid unnecessary loads: `isLvalue=true` means you already have an addressable slot, `isLvalue=false` may require loading/copying into the destination.

## Stage 5: control-flow joins
- For aggregate-valued `if`/loops, merge using “phi of pointer” (or avoid phi by constructing into a known destination when possible).
- Add IR support for pointer phis if missing; render them and ensure builder supports insertion.
- Eliminate SSA-valued aggregate phis.

## Stage 6: ABI and sret
- Extend `IrFunctionSignature` to record when a function returns an aggregate; add an implicit first parameter `*retType` (sret).
- In function bodies, bind the sret parameter and make `return <agg>` store into it, then `ret void/unit`.
- In call sites, when callee is sret:
  - Allocate a result slot.
  - Pass its address as the first argument.
  - Use that slot as the call “result” (no SSA aggregate return).
- Keep primitive/bool/unit returning as direct values.
- Parameter policy (ABI):
  1. Scalar by value (`i32`, `bool`, pointer-as-scalar)
     - Caller: pass the SSA value directly; no slot unless the caller itself needs an address for other reasons.
     - Callee: bind as SSA. If an address is later required (e.g., mutable write), spill once to an entry-block slot and use that slot thereafter.
  2. Scalar by reference (`&T` / `&mut T`)
     - Caller: ensure the argument has a storage address. If it’s already in a slot, pass that address. If it’s currently SSA, allocate an entry-block slot, store the SSA into it, and pass that slot’s address.
     - Callee: bind as an addressable pointer to the scalar; use it directly (loads/stores). No pointer-to-pointer wrapping.
  3. Aggregate by value (struct/array)
     - Caller: allocate a temp slot in the entry block, deep-copy the aggregate argument into it (using the copy helper), and pass that address.
     - Callee: bind the parameter as an addressable aggregate pointer and read/write fields/elements directly.
  4. Aggregate by reference (`&T` / `&mut T` for struct/array)
     - Caller: pass the original aggregate address (no copy, no extra wrapping).
     - Callee: bind as an addressable pointer to the aggregate and use directly.
  Special cases:
     - `self` follows the same rules: always pass a pointer; by-value `self` uses the caller-side copy slot; `&self`/`&mut self` pass the original address.
     - Aggregate returns use sret (caller allocates result slot, passes its address; callee writes into it and returns void).

## Stage 7: argument passing cleanup
- Simplify `emitArgument` to avoid pointer-to-pointer for aggregates: if the expected type is a pointer to aggregate, pass the address directly.
- Remove retarget/coerce paths that were compensating for aggregate-by-value SSA.
- Ensure autoref/autoderef rules align with address-only aggregates (still no lifetime tracking).
- Method receiver (`self`) handling: always pass a pointer. For by-value `self`, the caller copies into a temp slot and passes its address; for `&self`/`&mut self`, pass the original address. Callee binds `self` as an addressable parameter and accesses fields directly without introducing pointer-to-pointer.
- Autoref/autoderef with references (pointer-as-scalar model):
  - References/borrows are scalar pointers; aggregates are addressed. Do not wrap a pointer again.
  - Field access on a reference to a struct: use the pointer as the GEP base with indices `[0, fieldIndex]` (no load of the aggregate, no pointer-to-pointer).
  - Method call on a reference receiver: pass that pointer directly for `&self`/`&mut self`; if the method expects by-value `self`, make a temp copy and pass the temp’s address.
  - Array indexing on a reference to an array: use the pointer-to-array as the GEP base (`[0, elementIndex]`), then load/store the element. No extra indirection.

## Stage 8: docs and tests
- Update backend design docs to describe address-only aggregates, destination passing, sret ABI, and pointer-phi merging.
- Add/adjust tests:
  - Functions returning/accepting structs/arrays.
  - Control-flow producing aggregates.
  - Assignment/copy semantics for aggregates.

## Stage 9: cleanup and follow-ups
- Remove now-unused helpers/paths (e.g., aggregate loads after literal init, redundant temporaries).
- Consider optional optimizations: small-struct by-value special cases, array memcpy lowering, but only after the core refactor is stable.
