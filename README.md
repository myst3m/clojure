# Clojure Truffle

A Clojure implementation on [GraalVM Truffle](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/), providing JIT compilation for Clojure programs.

This project implements Clojure as a Truffle language, enabling runtime JIT optimization through GraalVM's compiler infrastructure. It reuses `clojure.lang` data structures (persistent collections, Vars, Namespaces, etc.) from upstream Clojure while replacing the interpreter and compiler with a Truffle-based AST interpreter.

## Features

- Truffle AST interpreter with JIT compilation via GraalVM
- Native image support with runtime JIT (Truffle Enterprise)
- Reuses upstream `clojure.lang` persistent data structures compiled from source
- Self-contained reader (`TruffleReader`) and runtime (`ClojureRT`) with no dependency on `clojure.lang.RT` initialization
- No patches to GraalVM required (uses Feature API for native image configuration)
- REPL, file execution, and `-e` expression evaluation

## Requirements

- [GraalVM JDK 25+](https://www.graalvm.org/downloads/) (Oracle Enterprise Edition for native image with JIT)
- Maven 3.x

## Build

```bash
JAVA_HOME=/opt/graalvm-jdk-25.0.1+8.1 mvn -f truffle/pom.xml package -q -DskipTests
```

This produces:
- `truffle/target/clojure-truffle-0.1.0-SNAPSHOT.jar` (includes `clojure.lang`, `clojure.asm`, and `clojure.truffle`)
- `truffle/target/dependency/` (Truffle runtime JARs)

## Run (JVM)

```bash
CP="truffle/target/clojure-truffle-0.1.0-SNAPSHOT.jar:$(echo truffle/target/dependency/*.jar | tr ' ' ':')"

# Evaluate an expression
java -Xss4m -cp "$CP" clojure.truffle.Main -e '(println (+ 1 2 3))'

# Run a file
java -Xss4m -cp "$CP" clojure.truffle.Main my_program.clj

# Start a REPL
java -Xss4m -cp "$CP" clojure.truffle.Main
```

## Native Image (with JIT)

Build a native executable with Truffle JIT compilation enabled:

```bash
CP="truffle/target/clojure-truffle-0.1.0-SNAPSHOT.jar:$(echo truffle/target/dependency/*.jar | tr ' ' ':')"

native-image -cp "$CP" \
  clojure.truffle.Main --no-fallback \
  --features=clojure.truffle.FixHostedOptionsFeature \
  --initialize-at-build-time=clojure.truffle \
  --initialize-at-run-time=sun.awt.X11 \
  -H:+UnlockExperimentalVMOptions \
  -H:-TruffleCheckBlockListMethods \
  -H:DeadlockWatchdogInterval=300 -H:NumberOfThreads=4 \
  -J-Xss8m \
  -o clojure-truffle-jit
```

Run:

```bash
./clojure-truffle-jit -e '(println "hello")'
```

## Project Structure

```
truffle/
  src/main/java/
    clojure/lang/
      TruffleReader.java          # Self-contained Clojure reader (replaces LispReader)
    clojure/truffle/
      Main.java                   # Entry point (REPL, file, -e)
      ClojureContext.java         # Truffle language context and builtins
      ClojureTruffleLanguage.java # Truffle language registration
      FixHostedOptionsFeature.java # Native image Feature (JIT, continuations)
      parser/
        Analyzer.java             # Clojure form -> Truffle AST compiler
      runtime/
        ClojureRT.java            # RT replacement (no static initialization)
        ClojureFunction.java      # Truffle-optimized function objects
      nodes/                      # Truffle AST nodes
src/jvm/
  clojure/lang/                   # Upstream clojure.lang (compiled from source)
  clojure/asm/                    # Bundled ASM bytecode library
```

## Architecture

The implementation consists of three layers:

1. **`clojure.lang`** (upstream) -- Persistent data structures, Vars, Namespaces, Symbols, Keywords. Compiled directly from source in `src/jvm/`. The `RT` class has a minimal patch to skip `clojure/core.clj` loading when in Truffle mode.

2. **`clojure.truffle.runtime`** -- `ClojureRT` provides static utility methods (`seq`, `cons`, `list`, `map`, `get`, etc.) without triggering `RT`'s static initializer. `TruffleReader` is a self-contained Clojure reader with all necessary helper methods built in.

3. **`clojure.truffle`** -- The Truffle language implementation. `Analyzer` parses Clojure forms into Truffle AST nodes. `ClojureContext` manages namespaces, builtins, and var resolution. Truffle's partial evaluation and compilation pipeline handles JIT optimization.

## License

Eclipse Public License 1.0 (same as Clojure). See [epl-v10.html](epl-v10.html).
