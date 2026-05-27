package backend

// Backend target-size constants for RV64IM/LP64.

object TargetLayout {
    const val INT_BYTES: Int = 4
    const val POINTER_BYTES: Int = 8
    const val REGISTER_BYTES: Int = 8
    const val ABI_STACK_SLOT_BYTES: Int = 8
}
