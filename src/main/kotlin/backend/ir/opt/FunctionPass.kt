package backend.ir.opt

// Defines the common interface for function-level IR passes.

import backend.ir.IrFunction
import backend.ir.IrModule

interface FunctionPass {
  val name: String
  fun run(module: IrModule, function: IrFunction)
}
