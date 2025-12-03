.PHONY: build run clean

build:
	@./gradlew build

# Read source from stdin, run compiler, emit LLVM IR to stdout.
run:
	@./gradlew -q run --args="--ir-out=- -" | cat

clean:
	@./gradlew clean
