package frontend

import frontend.AST.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import utils.CompileError


class RParserTest {

    private fun parse(src: String): CrateNode {
        val input = RPreprocessor(src).process()
        val tokens = RLexer(input).process()
        return RParser(tokens).process()
    }

    // --- Crate Parsing Tests ---

    @Test
    fun testEmptyCrate() {
        val src = ""
        val crate = parse(src)
        assertTrue(crate.items.isEmpty(), "An empty source should result in a crate with no items.")
    }

    @Test
    fun testSimpleFunctionItem() {
        val src = """
            fn first_function() {}
        """
        val crate = parse(src)
        assertEquals(1, crate.items.size, "Crate should contain one items.")
        assertTrue(crate.items[0] is FunctionItemNode)
    }

    @Test
    fun testCrateWithMultipleItems() {
        val src = """
            fn first_function() {}
            struct MyStruct;
            enum MyEnum { A, B }
            const MY_CONST: i32 = 1;
            fn second_function() {}
        """
        val crate = parse(src)
        assertEquals(5, crate.items.size, "Crate should contain five distinct items.")
        assertTrue(crate.items[0] is FunctionItemNode)
        assertTrue(crate.items[1] is StructItemNode)
        assertTrue(crate.items[2] is EnumItemNode)
        assertTrue(crate.items[3] is ConstItemNode)
        assertTrue(crate.items[4] is FunctionItemNode)
    }

    // --- Item Parsing Tests ---

    @Test
    fun testFunctionItem() {
        val src = "fn add(x: i32, y: i32) -> i32 { return x + y; }"
        val crate = parse(src)
        val func = crate.items[0] as FunctionItemNode
        assertEquals("add", func.name)
        assertEquals(2, func.funParams.size)
        assertEquals("x", (func.funParams[0].pattern as PathPatternNode).path.seg1.id)
        assertEquals("i32", (func.funParams[0].type as TypePathNode).id)
        assertNotNull(func.returnType)
        assertEquals("i32", (func.returnType as TypePathNode).id)
        assertEquals(1, func.body!!.stmts.size)
    }

    @Test
    fun testConstFunction() {
        val src = "const fn get_meaning_of_life() -> i32 { 42 }"
        val crate = parse(src)
        val func = crate.items[0] as FunctionItemNode
        assertTrue(func.isConst, "Function should be correctly marked as const.")
        assertEquals("get_meaning_of_life", func.name)
    }

    @Test
    fun testStructItem() {
        val src = "struct Point { x: f64, y: f64, }"
        val crate = parse(src)
        val struct = crate.items[0] as StructItemNode
        assertEquals("Point", struct.name)
        assertEquals(2, struct.fields.size, "Struct should have two fields.")
        assertEquals("x", struct.fields[0].name)
        assertEquals("f64", (struct.fields[0].type as TypePathNode).id)
    }

    @Test
    fun testUnitLikeStructItem() {
        val src = "struct Marker;"
        val crate = parse(src)
        val struct = crate.items[0] as StructItemNode
        assertEquals("Marker", struct.name)
        assertTrue(struct.fields.isEmpty(), "Unit-like struct should have no fields.")
    }

    @Test
    fun testEnumItemWithTrailingComma() {
        val src = "enum Direction { North, South, East, West, }"
        val crate = parse(src)
        val enumNode = crate.items[0] as EnumItemNode
        assertEquals("Direction", enumNode.name)
        assertEquals(listOf("North", "South", "East", "West"), enumNode.variants)
    }

    @Test
    fun testConstItem() {
        val src = "const MAX_POINTS: u32 = 100_000;"
        val crate = parse(src)
        val constItem = crate.items[0] as ConstItemNode
        assertEquals("MAX_POINTS", constItem.id)
        assertEquals("u32", (constItem.type as TypePathNode).id)
        assertEquals("100_000", (constItem.expr as LiteralExprNode).value)
    }

    // --- Statement Parsing Tests ---

    @Test
    fun testLetStatementVariations() {
        val src = """
            fn main() {
                let a;
                let b: i32;
                let c = 1;
                let d: i32 = 2;
                let mut e = 3;
            }
        """
        val stmts = (parse(src).items[0] as FunctionItemNode).body!!.stmts
        assertEquals(5, stmts.size)

        val a = stmts[0] as LetStmtNode
        assertNull(a.type)
        assertNull(a.expr)

        val b = stmts[1] as LetStmtNode
        assertNotNull(b.type)
        assertNull(b.expr)

        val c = stmts[2] as LetStmtNode
        assertNull(c.type)
        assertNotNull(c.expr)

        val d = stmts[3] as LetStmtNode
        assertNotNull(d.type)
        assertNotNull(d.expr)

        val e = stmts[4] as LetStmtNode
        assertTrue((e.pattern as IdentifierPatternNode).hasMut)
    }

    @Test
    fun testExpressionStatementWithAndWithoutSemicolon() {
        val src = "fn main() { 1; 2 }"
        val crate = parse(src)
        val block = (crate.items[0] as FunctionItemNode).body
        assertEquals(2, block!!.stmts.size)
        assertTrue(block.stmts[0] is ExprStmtNode)
        assertTrue(block.stmts[1] is ExprStmtNode)
    }

    // --- Pattern Parsing Tests ---

    @Test
    fun testIdentifierPatternVariations() {
        val src = """
            fn main() {
                let a = 1;
                let mut b = 2;
                let ref c = 3;
                let ref mut d = 4;
                let e @ _ = 5;
            }
        """
        val stmts = (parse(src).items[0] as FunctionItemNode).body!!.stmts

//        val a = (stmts[0] as LetStmtNode).pattern as IdentifierPatternNode
//        assertFalse(a.hasRef); assertFalse(a.hasMut); assertNull(a.subPattern)

        val b = (stmts[1] as LetStmtNode).pattern as IdentifierPatternNode
        assertFalse(b.hasRef); assertTrue(b.hasMut); assertNull(b.subPattern)

        val c = (stmts[2] as LetStmtNode).pattern as IdentifierPatternNode
        assertTrue(c.hasRef); assertFalse(c.hasMut); assertNull(c.subPattern)

        val d = (stmts[3] as LetStmtNode).pattern as IdentifierPatternNode
        assertTrue(d.hasRef); assertTrue(d.hasMut); assertNull(d.subPattern)

        val e = (stmts[4] as LetStmtNode).pattern as IdentifierPatternNode
        assertEquals("e", e.id); assertNotNull(e.subPattern)
    }


    // --- Expression Parsing Tests ---

    @Test
    fun testAssignmentAndCompoundAssignment() {
        val src = "fn main() { let mut x = 1; x = 5; x += 2; }"
        val stmts = (parse(src).items[0] as FunctionItemNode).body!!.stmts
        val assign = (stmts[1] as ExprStmtNode).expr as BinaryExprNode
        assertTrue(assign.op == Punctuation.EQUAL)
        val compoundAssign = (stmts[2] as ExprStmtNode).expr as BinaryExprNode
        assertTrue(compoundAssign.op == Punctuation.PLUS_EQUAL)
    }


    @Test
    fun testIfLetAndWhileLet() {
        val src = """
            fn main() {
                if let a = an_option { x };
                while let b = an_iterator.next { y };
            }
        """
        val stmts = (parse(src).items[0] as FunctionItemNode).body!!.stmts
        val ifLet = (stmts[0] as ExprStmtNode).expr as IfExprNode
        assertTrue(ifLet.conds[0].pattern is IdentifierPatternNode)
        val whileLet = (stmts[1] as ExprStmtNode).expr as WhileExprNode
        assertTrue(whileLet.conds[0].pattern is IdentifierPatternNode)
    }

    @Test
    fun testReturnBreakContinueWithValue() {
        val src = "fn main() { return 1; loop { break 2; continue; } }"
        val funcBlock = (parse(src).items[0] as FunctionItemNode).body
        val returnExpr = (funcBlock!!.stmts[0] as ExprStmtNode).expr as ReturnExprNode
        assertNotNull(returnExpr.expr)
        val loopBlock = ((funcBlock.stmts[1] as ExprStmtNode).expr as LoopExprNode).expr
        val breakExpr = (loopBlock.stmts[0] as ExprStmtNode).expr as BreakExprNode
        assertNotNull(breakExpr.expr)
        assertTrue((loopBlock.stmts[1] as ExprStmtNode).expr is ContinueExprNode)
    }

//    @Test
//    fun testRangeExpressions() {
//        val src = "fn main() { (..); (1..); (..2); (..=3); (4..5); (6..=7); }"
//        val stmts = (parse(src).items[0] as FunctionItemNode).body.stmts
//        assertTrue(((stmts[0] as ExprStmtNode).expr as GroupedExprNode).expr is RangeExprNode)
//        assertTrue(((stmts[1] as ExprStmtNode).expr as GroupedExprNode).expr is RangeExprNode)
//        assertTrue(((stmts[2] as ExprStmtNode).expr as GroupedExprNode).expr is RangeExprNode)
//        assertTrue(((stmts[3] as ExprStmtNode).expr as GroupedExprNode).expr is RangeExprNode)
//        assertTrue(((stmts[4] as ExprStmtNode).expr as GroupedExprNode).expr is RangeExprNode)
//        assertTrue(((stmts[5] as ExprStmtNode).expr as GroupedExprNode).expr is RangeExprNode)
//    }

    // --- Type Parsing Tests ---

    @Test
    fun testVariousTypes() {
        val src = """
           struct Me {
                 a : i32 , b : & a , c : [ i32 ; true ] , d : [ char ] , e : _
           }
        """
        val fields = (parse(src).items[0] as StructItemNode).fields
        assertEquals(fields.size, 5)
        assertTrue(fields[0].type is TypePathNode)
        assertTrue(fields[1].type is RefTypeNode)
        assertTrue(fields[2].type is ArrayTypeNode)
        assertTrue(fields[3].type is SliceTypeNode)
        assertTrue(fields[4].type is InferredTypeNode)
    }


    // --- Error Handling Tests ---

    @Test
    fun testMissingClosingParenInFunction() {
        assertThrows<CompileError> {
            parse("fn main( {}")
        }
    }

    @Test
    fun testMissingSemicolonAfterLet() {
        assertThrows<CompileError> {
            parse("fn main() { let x = 1 }")
        }
    }

    @Test
    fun testUnexpectedEof() {
        assertThrows<CompileError> {
            parse("fn main() { let x =")
        }
    }
}
