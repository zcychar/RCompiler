package frontend.semantic

import frontend.RLexer
import frontend.RParser
import frontend.RPreprocessor
import frontend.ast.*
import utils.CompileError
import kotlin.test.*

class SemanticPassesTest {

  // ---------- e2e helpers ----------
  private fun parse(src: String): CrateNode {
    val input = RPreprocessor(src).process()
    val tokens = RLexer(input).process()
    return RParser(tokens).process()
  }

  private fun runAll(src: String) {
    val crate = parse(src)
    val prelude = toPrelude()
    RSymbolCollector(prelude, crate).process()
    RSymbolResolver(prelude, crate).process()
    RImplInjector(prelude, crate).process()
    RSemanticChecker(prelude, crate).process()
  }

  private fun runAllExpectSuccess(src: String, title: String = "compile") {
    try {
      runAll(src)
    } catch (e: CompileError) {
      fail("$title failed with CompileError: ${e.message}\nSource:\n$src")
    }
  }

  // =========================================================
  // 成功用例（端到端）
  // =========================================================

  @Test
  fun e2e_minimal_main() {
    val src = """
      fn main() { exit(0); }
    """.trimIndent()
    runAllExpectSuccess(src, "e2e_minimal_main")
  }

  @Test
  fun e2e_struct_enum_const_impl_method_and_calls() {
    // 覆盖：结构体/枚举定义，常量求值（含数组/索引/布尔/算术/字符/字符串/单位结构体），
    // impl 注入（方法与关联常量），函数调用、方法调用、数组字面量与索引、字段访问、比较与位运算
    val src = """
      struct Point { x: i32, y: u32 }
      enum E { A, B }
      impl Point { fn show(self) -> &str { "" } const Z: i32 = 7; }

      const A1: i32 = 1 + 2 * 3; // 7
      const B1: bool = true && false || !false; // true
      const C1: char = 'c';
      const S1: str = "hi";

      fn add(x: i32, y: i32) -> i32 { return x + y; }

      fn main() {
        // 结构体字面量与字段访问
        let mut p: Point = Point { x: 1, y: 2u32 };
        let a: i32 = p.x; // 字段访问
        // 方法调用（impl 注入）与关联常量访问（通过类型路径）
        let shown: &str = p.show();
        // 数组字面量与索引
        let arr: [i32; 3] = [1, 2, 3];
        let i: i32 = arr[1];
        // 函数调用
        let s: i32 = add(1, 2);
        // 内置方法
        let len1: usize = "hello".len();
        let str: String = (1).to_string();
        // 比较与位运算
        let b: bool = (1i32 < 2i32) && (3i32 == 3i32) || (4i32 >= 4i32);
        let bit: i32 = (1i32 | 2i32) & 3i32;
        // 枚举使用（变体作为值被解析为枚举类型）
        let e: E = E::A;
        exit(0);
      }
    """.trimIndent()
    runAllExpectSuccess(src, "e2e_struct_enum_const_impl_method_and_calls")
  }

  @Test
  fun e2e_control_flow_if_while_loop_break_return_cast_ref_deref() {
    val src = """
      fn id(x: i32) -> i32 { return x; }
      fn main() {
        // if/else 类型合一
        let mut a: i32 = 1;
        if (true) { a += 1; } else { a -= 1; }
        // while 条件为 bool
        while (true) { break; }
        // loop / break 值
        let v: i32 = loop { break 10; };
        // return 与签名匹配
        let x: i32 = id(1);
        // 引用/解引用与赋值
        let mut b: i32 = 2;
        let pb: & mut i32 = & mut b;
        *pb = 3;
        // 类型转换（整数间）
        let c: i32 = (1u32 as i32);
        exit(0);
      }
    """.trimIndent()
    runAllExpectSuccess(src, "e2e_control_flow_if_while_loop_break_return_cast_ref_deref")
  }

  // =========================================================
  // 失败用例（端到端，带错误信息断言）
  // =========================================================

  @Test
  fun err_missing_exit_in_main() {
    val src = """
      fn main() { let x: i32 = 1; }
    """.trimIndent()
    val ex = assertFailsWith<CompileError> { runAll(src) }
    assertTrue(ex.message?.contains("missing exit() in main function") == true,
      "错误应包含 'missing exit() in main function'，实际: ${ex.message}")
  }

  @Test
  fun err_immutable_assignment() {
    val src = """
      fn main() { let a: i32 = 1; a = 2; exit(0); }
    """.trimIndent()
    val ex = assertFailsWith<CompileError> { runAll(src) }
    assertTrue(ex.message?.contains("cannot assign to immutable variable or field") == true,
      "错误应包含 'cannot assign to immutable variable or field'，实际: ${ex.message}")
  }

  @Test
  fun err_wrong_argument_count() {
    val src = """
      fn f(x: i32) -> i32 { return x; }
      fn main() { let k: i32 = 0; let m: i32 = f(k, k); exit(0); }
    """.trimIndent()
    val ex = assertFailsWith<CompileError> { runAll(src) }
    assertTrue(ex.message?.contains("wrong number of arguments") == true,
      "错误应包含 'wrong number of arguments'，实际: ${ex.message}")
  }

  @Test
  fun err_return_type_mismatch() {
    val src = """
      fn f() -> i32 { return true; }
      fn main() { exit(0); }
    """.trimIndent()
    val ex = assertFailsWith<CompileError> { runAll(src) }
    assertTrue(ex.message?.contains("mismatched types in return expression") == true,
      "错误应包含 'mismatched types in return expression'，实际: ${ex.message}")
  }

  @Test
  fun err_if_condition_not_bool() {
    val src = """
      fn main() { if (1) { } exit(0); }
    """.trimIndent()
    val ex = assertFailsWith<CompileError> { runAll(src) }
    assertTrue(ex.message?.contains("`if` condition must be a boolean") == true,
      "错误应包含 '`if` condition must be a boolean'，实际: ${ex.message}")
  }

  @Test
  fun err_index_non_array() {
    val src = """
      fn main() { let x: i32 = 1; let y: i32 = x[0]; exit(0); }
    """.trimIndent()
    val ex = assertFailsWith<CompileError> { runAll(src) }
    assertTrue(ex.message?.contains("cannot be indexed") == true,
      "错误应包含 'cannot be indexed'，实际: ${ex.message}")
  }

  @Test
  fun err_borrow_mut_from_immutable() {
    val src = """
      fn main() { let a: i32 = 1; let p: & mut i32 = & mut a; exit(0); }
    """.trimIndent()
    val ex = assertFailsWith<CompileError> { runAll(src) }
    assertTrue(ex.message?.contains("cannot create a mutable reference to an immutable variable") == true,
      "错误应包含 'cannot create a mutable reference to an immutable value'，实际: ${ex.message}")
  }

  @Test
  fun err_deref_non_ref() {
    val src = """
      fn main() { let x: i32 = 1; let y: i32 = *x; exit(0); }
    """.trimIndent()
    val ex = assertFailsWith<CompileError> { runAll(src) }
    assertTrue(ex.message?.contains("cannot be dereferenced") == true,
      "错误应包含 'cannot be dereferenced'，实际: ${ex.message}")
  }

  @Test
  fun err_field_access_on_non_struct() {
    val src = """
      fn main() { let x: i32 = 1; let y: i32 = x.foo; exit(0); }
    """.trimIndent()
    val ex = assertFailsWith<CompileError> { runAll(src) }
    assertTrue(ex.message?.contains("field access on a non-struct type") == true,
      "错误应包含 'field access on a non-struct type'，实际: ${ex.message}")
  }

  @Test
  fun err_no_such_field_on_struct() {
    val src = """
      struct P { x: i32 }
      fn main() { let p: P = P { x: 1 }; let y: i32 = p.y; exit(0); }
    """.trimIndent()
    val ex = assertFailsWith<CompileError> { runAll(src) }
    assertTrue(ex.message?.contains("no field `y` on type") == true,
      "错误应包含 'no field `y` on type'，实际: ${ex.message}")
  }

  @Test
  fun err_struct_literal_missing_field() {
    val src = """
      struct P { x: i32, y: i32 }
      fn main() { let p: P = P { x: 1 }; exit(0); }
    """.trimIndent()
    val ex = assertFailsWith<CompileError> { runAll(src) }
    assertTrue(ex.message?.contains("missing field(s) in struct literal") == true,
      "错误应包含 'missing field(s) in struct literal'，实际: ${ex.message}")
  }

  @Test
  fun err_enum_duplicate_variant() {
    val src = """
      enum Bad { A, A }
      fn main() { exit(0); }
    """.trimIndent()
    val ex = assertFailsWith<CompileError> { runAll(src) }
    assertTrue(ex.message?.contains("duplicated variant") == true,
      "错误应包含 'duplicated variant'，实际: ${ex.message}")
  }

  @Test
  fun err_recursive_struct_dependency() {
    val src = """
      struct A { x: A }
      fn main() { exit(0); }
    """.trimIndent()
    val ex = assertFailsWith<CompileError> { runAll(src) }
    assertTrue(ex.message?.contains("recursive dependency of struct A") == true,
      "错误应包含 'recursive dependency of struct A'，实际: ${ex.message}")
  }

  @Test
  fun err_array_type_negative_size() {
    val src = """
      struct S { a: [i32; -1] }
      fn main() { exit(0); }
    """.trimIndent()
    val ex = assertFailsWith<CompileError> { runAll(src) }
    assertTrue(ex.message?.contains("Array size cannot be negative") == true,
      "错误应包含 'Array size cannot be negative'，实际: ${ex.message}")
  }

  @Test
  fun err_non_unit_struct_as_const_path() {
    val src = """
      struct S { x: i32 }
      const C: S = S; // 非单位结构体作为常量路径
      fn main() { exit(0); }
    """.trimIndent()
    val ex = assertFailsWith<CompileError> { runAll(src) }
    assertTrue(ex.message?.contains("paths to non-unit struct is not allowed to be const") == true,
      "错误应包含 'paths to non-unit struct is not allowed to be const'，实际: ${ex.message}")
  }

  @Test
  fun err_use_self_param_outside_impl() {
    val src = """
      fn f(self) -> i32 { 0 }
      fn main() { exit(0); }
    """.trimIndent()
    val ex = assertFailsWith<CompileError> { runAll(src) }
    assertTrue(ex.message?.contains("'self' parameter used outside of an impl or trait block") == true,
      "错误应包含 ''self' parameter used outside of an impl or trait block'，实际: ${ex.message}")
  }

  @Test
  fun err_impl_invalid_type_or_trait() {
    val src1 = """
      impl T for i32 { }
      fn main() { exit(0); }
    """.trimIndent()
    val ex1 = assertFailsWith<CompileError> { runAll(src1) }
    assertTrue(ex1.message?.contains("implementing an invalid trait") == true ||
      ex1.message?.contains("implementing an invalid type") == true,
      "错误应包含 impl 的非法 trait 或类型，实际: ${ex1.message}")
  }


  @Test
  fun err_binary_op_type_mismatch_and_assign_ops() {
    val src1 = """
      fn main() { let b: bool = (1i32 == 1u32); exit(0); }
    """.trimIndent()
    val ex1 = assertFailsWith<CompileError> { runAll(src1) }
    assertTrue(ex1.message?.contains("cannot unify") == true,
      "错误应包含 'cannot compare两种不同类型'，实际: ${ex1.message}")

    val src2 = """
      fn main() { let mut c: char = 'a'; c += 'b'; exit(0); }
    """.trimIndent()
    val ex2 = assertFailsWith<CompileError> { runAll(src2) }
    assertTrue(ex2.message?.contains("cannot apply assignment operator") == true,
      "错误应包含 'cannot apply assignment operator'，实际: ${ex2.message}")
  }

  @Test
  fun err_method_receiver_must_be_variable() {
    val src = """
      struct S { }
      impl S { fn m(self) -> i32 { 0 } }
      fn main() {
        let x: i32 = S.m(); // 误把类型名当作方法接收者
        exit(0);
      }
    """.trimIndent()
    val ex = assertFailsWith<CompileError> { runAll(src) }
    assertTrue(ex.message?.contains("method receiver must be an instance") == true,
      "错误应包含 'method receiver must be an instance'，实际: ${ex.message}")
  }
}
