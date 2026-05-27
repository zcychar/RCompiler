package backend.ir.opt

// Runs ordered function-pass pipelines over an IR module.

import backend.ir.IrModule

class PassPipeline(
  private val functionPasses: List<FunctionPass> = emptyList(),
) {
  fun run(module: IrModule) {
    if (functionPasses.isEmpty()) return
    module.declaredFunctions().forEach { function ->
      functionPasses.forEach { pass ->
        pass.run(module, function)
      }
    }
  }
}
