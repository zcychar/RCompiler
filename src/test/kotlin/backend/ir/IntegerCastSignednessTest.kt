package backend.ir

import frontend.RLexer
import frontend.RParser
import frontend.RPreprocessor
import frontend.ast.CrateNode
import frontend.semantic.RImplInjector
import frontend.semantic.RSemanticChecker
import frontend.semantic.RSymbolCollector
import frontend.semantic.RSymbolResolver
import frontend.semantic.Scope
import frontend.semantic.toPrelude
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntegerCastSignednessTest {
  @Test
  fun `same-width cast from unsigned to signed makes remainder signed`() {
    val ir = compileToUnoptimizedIr(
      """
        fn main() {
          let u: usize = 1 as usize;
          printlnInt(((u as i32) - 5) % 3);
          exit(0);
        }
      """.trimIndent()
    )

    assertTrue(ir.contains("srem i32"), "usize as i32 must use signed remainder:\n$ir")
    assertFalse(ir.contains("urem i32"), "usize as i32 leaked unsigned signedness:\n$ir")
  }

  @Test
  fun `same-width cast from signed to unsigned makes remainder unsigned`() {
    val ir = compileToUnoptimizedIr(
      """
        fn main() {
          let x: i32 = 1 - 5;
          printlnInt(((x as usize) % 3) as i32);
          exit(0);
        }
      """.trimIndent()
    )

    assertTrue(ir.contains("urem i32"), "i32 as usize must use unsigned remainder:\n$ir")
  }

  private fun compileToUnoptimizedIr(source: String): String {
    val crate = parse(source)
    val prelude = toPrelude()
    runSemanticPasses(crate, prelude)
    return IrBackend(enableOptimization = false).generate(crate, prelude)
  }

  private fun parse(source: String): CrateNode {
    val input = RPreprocessor(source).process()
    val tokens = RLexer(input).process()
    return RParser(tokens).process()
  }

  private fun runSemanticPasses(crate: CrateNode, prelude: Scope) {
    RSymbolCollector(prelude, crate).process()
    RSymbolResolver(prelude, crate).process()
    RImplInjector(prelude, crate).process()
    RSemanticChecker(prelude, crate).process()
  }
}
