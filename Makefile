.PHONY: build run clean

GRADLE_HOME := $(CURDIR)/.gradle-user

build:
	@GRADLE_USER_HOME=$(GRADLE_HOME) ./gradlew build

# Read source from stdin, run compiler, emit LLVM IR to stdout.
run:
	@GRADLE_USER_HOME=$(GRADLE_HOME) ./gradlew -q run --args="-" | cat

clean:
	@GRADLE_USER_HOME=$(GRADLE_HOME) ./gradlew clean
