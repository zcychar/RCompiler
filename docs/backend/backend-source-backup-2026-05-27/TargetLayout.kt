package backend

/**
 * Fixed target layout for the native backend.
 *
 * The compiler now targets RV64IM/LP64 only. Source-level isize/usize are still
 * represented as 32-bit scalar values in this compiler; pointers and ABI stack
 * slots are 64-bit.
 */
object TargetLayout {
    const val INT_BYTES: Int = 4
    const val POINTER_BYTES: Int = 8
    const val REGISTER_BYTES: Int = 8
    const val ABI_STACK_SLOT_BYTES: Int = 8
}
