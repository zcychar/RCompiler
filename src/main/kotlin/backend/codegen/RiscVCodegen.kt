package backend.codegen

// Coordinates instruction selection, register allocation, frame layout, and assembly emission.

import backend.codegen.riscv.*
import backend.ir.*
import backend.codegen.BranchRelaxation

object RiscVCodegen {

    fun compile(irModule: IrModule, debugDump: Boolean = false): String {

        val isel = InstructionSelector(irModule)
        val machineFunctions = isel.selectAll()

        if (debugDump) {
            System.err.println("===== After Instruction Selection =====")
            for (mf in machineFunctions) {
                System.err.println(mf.debugRender())
            }
        }

        val allocator = GraphColorRegAlloc()
        for (mf in machineFunctions) {
            allocator.allocate(mf)
        }

        if (debugDump) {
            System.err.println("===== After Register Allocation =====")
            for (mf in machineFunctions) {
                System.err.println(mf.debugRender())
            }
        }

        for (mf in machineFunctions) {
            FrameLayout.run(mf)
        }

        if (debugDump) {
            System.err.println("===== After Frame Layout =====")
            for (mf in machineFunctions) {
                System.err.println(mf.debugRender())
            }
        }

        for (mf in machineFunctions) {
            BranchRelaxation.relax(mf)
            FallthroughJumpElimination.run(mf)
        }

        if (debugDump) {
            System.err.println("===== After Branch Relaxation / Fallthrough Cleanup =====")
            for (mf in machineFunctions) {
                System.err.println(mf.debugRender())
            }
        }

        return AsmEmitter.emit(machineFunctions, irModule)
    }
}
