package backend.ir.opt

import backend.ir.IrFunction
import backend.ir.IrModule

interface FunctionPass {
  val name: String
  fun run(module: IrModule, function: IrFunction)
}
