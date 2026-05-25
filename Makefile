.PHONY: build run run-ir run-asm clean

JAR := RCompiler-1.0-SNAPSHOT-all.jar
COMPILER_FLAGS ?=

# Verify the runnable fat jar exists; if missing, tell the user how to create it.
build:

# Read source from stdin, emit RISC-V assembly to stdout via the fat jar.
run: build
	@java -jar "$(JAR)" --emit=asm $(COMPILER_FLAGS) -

# Read source from stdin, emit LLVM IR to stdout via the fat jar.
run-ir: build
	@java -jar "$(JAR)" --emit=ir $(COMPILER_FLAGS) -

# Backward-compatible explicit ASM target.
run-asm: build
	@java -jar "$(JAR)" --emit=asm $(COMPILER_FLAGS) -

# Remove built jars if you want a clean state (does not invoke Gradle).
clean:
	@rm -f RCompiler-1.0-SNAPSHOT*.jar
