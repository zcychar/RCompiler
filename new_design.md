先给一句结论版设计建议：

> **对所有“大对象类型”（struct / array / 大 tuple），在 IR 里统一按“地址值”（pointer）来表示值本身，表达式结果也是地址；然后通过「目标传递（destination passing）」和一个统一的 `emitCopyAggregate` 把真正的拷贝点收束到少数几个地方。**

你参考的那份 `IRBuilder.cpp` 其实已经在做这件事，只是略微粗糙，比如 `StructExpr` / `ArrayExpr` 会先 `alloca` 临时，再在 `let` 里再拷贝一遍。
下面我按“设计自己的 IR 阶段”来讲一套比较干净的方案。

---

## 0. 先定义：什么是“大型临时变量”

在设计里，你先逻辑上把类型分三类：

1. **标量（scalar）**：`iN`、`fN`、`bool`、指针、（可能还有小的 enum）。
2. **聚合（aggregate）**：`struct`、`array`、大 tuple、闭包环境、胖指针之类。
3. **函数/never/opaque**：不直接以值形式出现的可以另说。

这里你关心的是 2，典型场景：

* `let s = BigStruct { ... };`
* `if cond { BigStruct{..} } else { BigStruct{..} }`
* `foo(BigStruct { ... })`
* `return BigStruct { ... }`
* 大数组字面量 `[x; 1000]` / `[x0, x1, ..., x999]`

---

## 1. 核心设计：地址表示 (address-only) + L/RValue 区分

### 1.1 表达式结果的内部表示

在 AST → IR 的阶段，给每个表达式维护这样一套信息：

```text
struct ExprResult {
    enum class Kind { Scalar, Addr } kind;
    IRType *ty;                // 语义层算出来再映射到 IRType
    IRValue *scalar;           // kind == Scalar 时有效
    IRValue *addr;             // kind == Addr 时有效（其实是指向 ty 的指针）
    bool is_lvalue;            // 是否代表“已有存储的左值”
}
```

然后定义一个简单规则：

* 如果类型是 **标量** ⇒ `Kind::Scalar`，表达式结果就是 SSA 值。
* 如果类型是 **聚合** ⇒ `Kind::Addr`，表达式结果就是“某块内存的地址”。

这样，“临时**大对象**”在 IR 层就天然是“在哪块内存里”的概念，而不是“一个超大 SSA 值”。

### 1.2 LValue vs RValue 对大对象的区别

* `is_lvalue = true`：这个地址是 **已有存储**（变量、参数 slot、struct 字段、数组元素等）。
* `is_lvalue = false`：这个地址是 **临时存储**，通常是某个表达式为自己 `alloca` 出来的一块栈上的存储。

IR 层不需要知道“大小是不是很大”，只要是 aggregate 就统一走 address-only，这样所有政策统一，就不会出现“某些 struct 直接当值传，某些当指针传”这种容易踩坑的情况。

---

## 2. 临时大型变量怎么存：stack alloca + 尽量重用目标

### 2.1 最简单的 baseline：所有临时 aggregate 都在栈上 `alloca`

这是你参考代码在做的事情：

* `StructExpressionNode`：`alloca struct`，然后按字段写进去。
* `ArrayLiteralNode`：`alloca array`，然后循环填元素。
* `IfExpressionNode` 对 struct/array 结果用 **phi of 指针** 合并。
* 函数返回 struct：调用点先 `alloca` 返回值，再把指针塞进 `.ret`。

你自己的设计可以直接沿用这个大方向，不过要注意两个点：

1. **统一把所有 `alloca` 往 function entry block 插**（或者之后用 pass 统一挪过去）。
   这有利于 LLVM 的 `mem2reg` / SROA 之类做优化。

2. **不要疯狂多次拷贝**：如果最终目标已经是一个 local slot，就尽量让表达式直接初始化那块 slot，而不是“先写进临时，再 EmitStructCopy 一遍”。

这一点下面用「目标传递」专门讲。

---

## 3. 关键优化：目标传递 (destination passing style)

### 3.1 思路

很多创建大对象的表达式，其实在语义上都知道“结果最后要存到哪”：

* `let s = BigStruct { ... };` ⇒ 其实一开始就知道目的地是局部变量 `s` 那个 slot；
* `return BigStruct { ... }` ⇒ 最终目的地是 callee 的 sret 参数；
* `foo(BigStruct { ... })` ⇒ 参数位置是 by-value 的话，会在 caller 为这个参数做一个临时 slot，目的地是那个 slot；
* `if cond { BigStruct{...} } else { BigStruct{...} }`，如果它的值马上被 `let s = ...` 使用，最终目的地仍然是 `s`。

与其“表达式自己 `alloca` 一块临时，再由上层复制到目的地”，不如直接让表达式 **向下传一个“目标地址”**，自己往里面写，这样就避免了多一次 `memcpy`。

伪代码接口可以像（只针对 aggregate 型）：

```text
// 要求把 Expr 的结果写入 dest_addr 所指的存储中
void genExprInto(Expr *node, IRValue *dest_addr);
```

然后：

* 如果上层 **已经知道最终目的地**（比如 `let` 左边的变量 slot，或者函数 sret 参数），就直接用这个地址。
* 如果上层不知道（例如 `foo(BigStruct{...})` 中 `BigStruct{...}` 既不是 let 初始化，又不是 return，而是某个参数），那就：

    1. 在 caller `alloca` 一块临时；
    2. 调 `genExprInto` 把 struct 写进去；
    3. 把这块地址当作 by-value 参数传（或者如果你的 ABI 就是按指针传值，那直接传指针）。

### 3.2 举个完整流程例子

#### `let s: S = S { a: e1, b: e2 };`

* 语义阶段已经知道 `S` 是 struct、`s` 是局部变量。
* IRBuilder：

    1. 在 entry block：`alloca %s_slot : *S`
    2. 调用 `genStructExprInto(S{...}, %s_slot)`

`genStructExprInto` 做的事：

```text
for each field i:
    let field_ptr = gep %s_slot, [0, i]
    genExprInto(field_expr_i, field_ptr) or:
        - 如果 field 类型是标量：生成 expr 到 SSA，然后一条 store 到 field_ptr
        - 如果 field 类型仍是 aggregate：递归 genExprInto
```

**不再需要多出来的“临时 struct alloca + EmitStructCopy”。**

#### `return S { ... }` + sret 模式

* 函数 IR 签名多出一个 `.ret: *S` 参数。
* 在处理 `return expr` 时，如果 `expr` 是 aggregate：

  ```text
  genExprInto(expr, %ret_param);
  ret void;
  ```

#### `foo(S { ... })`

* 根据 ABI 确定 `foo` 对这个参数是 by-value 还是 by-ref：

    * 如果 by-value 且函数签名内部是 `S` 本身：caller 需要临时 slot `%arg0_tmp : *S`。
    * 如果你直接把参数类型定成 `*S`，那压根不用拷贝，只传地址。

* 假设 by-value + 临时 slot：

  ```text
  %arg0_tmp = alloca S
  genExprInto(S{...}, %arg0_tmp)
  call foo(%arg0_tmp)   // 或 load 再传，取决于你的 IR 约定
  ```

---

## 4. 拷贝策略：所有大对象复制集中到一个 helper

你参考代码里已经有一个 `EmitStructCopy`，递归拷贝 struct / array，很像一个高层版 memcpy。

建议在自己的设计里直接抽象成：

```text
void emitCopyAggregate(IRValue *dest_addr, IRValue *src_addr, Type *ty);
```

内部根据类型分类：

1. **struct**：对每个字段 `i`：

    * 通过 GEP 算 `dest_field_ptr` / `src_field_ptr`；
    * 对字段类型递归 `emitCopyAggregate`（字段本身是 struct/array 继续递归，标量直接 load/store）。

2. **array**：可以用两种策略：

    * 小数组（长度 ≤ N）：直接 unroll，按元素递归 `emitCopyAggregate`；
    * 大数组：生成一个循环，或者干脆发一个 LLVM 的 `llvm.memcpy` intrinsic。

3. **标量**：一条 load + 一条 store。

使用点：

* 赋值 `x = y`，当 `x` 的类型是 aggregate 且要做“按值语义”的赋值时；
* `let` 初始化中，当右边不是“直接写入目标地址”的场景时（比如 `let s = t;`，这里要 copy，而不是 move）；
* 函数参数如果语义层是“按值传递大对象”，则 call 之前从源地址拷贝一份到参数 slot。

这样你所有“大对象拷贝行为”只在几个明显的地方调用 `emitCopyAggregate`，不会散落在各个表达式逻辑里，将来要搞 move 语义 / copy-on-write / drop 语义都好改。

---

## 5. 临时大对象 + 控制流：phi of pointer，而不是 phi of struct

你参考代码里对 `if` 已经是这个思路：如果 if 表达式的类型是 struct/array，会把 phi 的类型变成“指向 struct/array 的指针”，而不是 struct 值本身。

你可以把这个规则推广为：

> **所有 aggregate 类型表达式在控制流合流点一律做 “phi of addr”，不会在 phi 上拼装新 struct。**

例子：

```rust
let s = if cond {
    S { a: ... }
} else {
    S { a: ... }
};
```

实现的一个自然方式：

1. 事先知道最终目的地 `%s_slot`，所以其实根本不需要 phi：

    * 在 `then` 分支直接用 `%s_slot` 做 `genStructExprInto`；
    * 在 `else` 分支同理；
    * 到合流块 `combine` 时，`s_slot` 已经被两条路径之一初始化完了。

**更通用的情况**是 if 本身是一个表达式，被别的表达式使用（而不是直接绑定到某个变量），这时你可以：

* 为整个 if 表达式 `alloca` 一个临时 `%tmp_if : *S`；
* 在两个分支都往 `%tmp_if` 写；
* 合流处 if 表达式的结果就是 `%tmp_if` 这个地址（`ExprResult{kind=Addr, addr=%tmp_if, is_lvalue=false}`）；
* 后续如果要再复制到别处，就靠 `emitCopyAggregate`。

这样整个控制流中，**不出现 struct SSA 值，只出现 struct 地址和拷贝函数**。

---

## 6. 和 `&` / `&mut` 的关系：统一“指向存储的指针”

在这种 address-only 设计下，`&T` / `&mut T` 其实很好统一：

* 语义层：`ReferenceType(T, mut)`。
* IR 层：把 `&T` 映射成 `IRPointerType(IRType(T))`。
* 取引用表达式 `&expr` 的 codegen：

    * 如果 `expr` 是 lvalue（`is_lvalue = true`）且 `kind = Addr`：直接返回这块地址作为引用的值（不再额外 alloca）。
    * 如果 `expr` 是 rvalue（比如临时 struct expr），而你的语言允许对临时取引用（Rust 基本禁止，C++ 允许并延长生命周期），那你就：

        * 为 expr 结果分配一个 slot；
        * 把 expr 写进去；
        * 返回那块 slot 的地址作为引用的值。

这里也能看出：**临时大对象的“放在哪里”本质上是“引用的生命周期可不可用”的问题**。
如果你语言规约里禁止“取对临时 aggregate 的长活引用”，那么只要按上面的栈上临时 + 作用域结束统一 drop 就够了，不用做额外 escape 分析。

---

## 7. 实际工程上的简化建议

如果你现在是第一版 IR 翻译项目，我会建议你按复杂度分三步：

### Step 1：简单版（和参考代码差不多）

* 规则：

    * 所有 struct/array 都是 address-only；
    * 表达式结果遇到 struct/array 一律自己 `alloca` 临时；
    * `let` / 赋值 / 返回 / 参数传递时，通过 `emitCopyAggregate` 深拷贝；
    * `if` / `while` 的表达式值，使用 “phi of pointer” 或直接使用一个 shared alloca。
* 优点：实现简单；容易 debug。
* 缺点：大量不必要拷贝，但 LLVM 优化掉一部分，你可以先不管。

### Step 2：加上“目标传递”优化

* 为 `StructExpr` / `ArrayExpr` / `if` 表达式增加「带目标地址版本的 codegen」：

    * 若上层已经有最终目的地，就直接“原地构造”，不再在表达式内部 `alloca`。
* 这一步能显著减少大对象拷贝。

### Step 3：进一步优化参数/返回 ABI

* 对“大类型参数”，直接约定 IR 层就是 `*T`（by-ref），由语义层的所有权/借用规则控制是否允许修改、能否 alias；
* 对 struct 返回统一使用 sret；
* 可视情况为“小 struct”允许按值传递，交给 LLVM 做 SROA。

---

## 8. 你可以直接拿走的“设计 checklist”

最后整理成一份“设计 checklist”，方便你对着自己的实现对照：

1. **类型分类**

    * 有一个 `isAggregateType(T)`：struct / array / 大 tuple / closure env → true。
    * `GetIRType` 把 `&T` / `&mut T` 映射为 `IRPointerType(GetIRType(T))`。

2. **表达式结果表示**

    * 对标量：`Kind::Scalar` + SSA 值；
    * 对 aggregate：`Kind::Addr` + 地址；lvalue / rvalue 用 `is_lvalue` 区分。

3. **局部变量 & 参数**

    * `let x: T`：如果 `isAggregate(T)` ⇒ `alloca *T`；否则也可以 alloca 再 mem2reg，或直接用 SSA；
    * 参数：大对象尽量用 `*T` 形式；如果语义上是按值，call 前按需 `emitCopyAggregate` 一份。

4. **返回值**

    * 非 aggregate：普通 `ret value`；
    * aggregate：函数 IR 签名使用 sret 参数；`return expr` 使用 `genExprInto(expr, %ret_ptr)`。

5. **拷贝**

    * 只有在“赋值、按值传参、按值返回”这些语义上需要 copy 的地方调用 `emitCopyAggregate`；
    * 其它初始化场景尽量使用“目标传递”直接构造到目的地。

6. **控制流**

    * if/loop 中 aggregate 值用 `phi of pointer` 或共享 alloca，不在 phi 上拼 struct 值。

7. **引用**

    * `&expr` 对 lvalue 直接取其地址；
    * 对临时值，依据语言规则决定是否延长 lifetime 或直接禁止。

只要这几块打通，你的 IR 阶段在“临时大型变量”这一块就会非常清爽，而且和 Rust / Swift 这类现代编译器的思路是一致的。后面你要做 move 语义、drop、借用检查，也都是在这之上往前一层做语义分析即可。
