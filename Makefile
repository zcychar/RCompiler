.PHONY: build run run-asm clean

JAR := RCompiler-1.0-SNAPSHOT-all.jar

# Verify the runnable fat jar exists; if missing, tell the user how to create it.
build:

# Read source from stdin, emit LLVM IR to stdout via the fat jar.
run: build
	@java -jar "$(JAR)" --emit=ir -

# Read source from stdin, emit RISC-V assembly to stdout via the fat jar.
run-asm: build
	@java -jar "$(JAR)" --emit=asm -

# Remove built jars if you want a clean state (does not invoke Gradle).
clean:
	@rm -f RCompiler-1.0-SNAPSHOT*.jar
