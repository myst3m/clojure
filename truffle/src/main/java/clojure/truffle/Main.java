package clojure.truffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) throws Exception {
        // Signal to RT that we're in Truffle mode - skip loading clojure/core.clj
        System.setProperty("clojure.truffle.mode", "true");
        String classpath = null;
        String evalExpr = null;
        List<String> remaining = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (("-cp".equals(args[i]) || "-classpath".equals(args[i])) && i + 1 < args.length) {
                classpath = args[++i];
            } else if ("-e".equals(args[i]) && i + 1 < args.length) {
                evalExpr = args[++i];
            } else if (args[i].startsWith("-D") && args[i].contains("=")) {
                String prop = args[i].substring(2);
                int eq = prop.indexOf('=');
                System.setProperty(prop.substring(0, eq), prop.substring(eq + 1));
            } else {
                remaining.add(args[i]);
            }
        }

        // Pass classpath via system property so ClojureContext can read it
        if (classpath != null) {
            System.setProperty("clojure.truffle.classpath", classpath);
        }

        if (evalExpr != null) {
            evalAndPrint(evalExpr);
        } else if (remaining.size() > 0 && (remaining.get(0).endsWith(".clj") || remaining.get(0).endsWith(".cljc"))) {
            String code = Files.readString(Path.of(remaining.get(0)));
            evalAndPrint(code);
        } else if (remaining.size() > 0) {
            evalAndPrint(remaining.get(0));
        } else {
            repl();
        }
    }

    private static void evalAndPrint(String code) {
        Context.Builder builder = Context.newBuilder("clj")
                .allowAllAccess(true)
                .allowExperimentalOptions(true);

        // Set trace options when requested
        if ("true".equals(System.getProperty("truffle.trace"))) {
            builder.option("engine.TraceCompilation", "true");
            builder.option("engine.TraceInlining", "true");
            builder.option("engine.CompileImmediately", "true");
            builder.option("compiler.TraceCompilation", "true");
        }
        // Lower compilation threshold for faster JIT
        builder.option("engine.SingleTierCompilationThreshold", "100");

        try (Context context = builder.build()) {
            Value result = context.eval("clj", code);
            System.out.println(formatResult(result));
        }
    }

    private static void repl() throws Exception {
        System.out.println("Clojure Truffle REPL");
        System.out.println("Type :quit to exit");
        System.out.println();

        try (Context context = Context.newBuilder("clj")
                .allowAllAccess(true)
                .option("engine.WarnInterpreterOnly", "false")
                .build()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            while (true) {
                System.out.print("clj=> ");
                System.out.flush();
                String line = reader.readLine();
                if (line == null || line.trim().equals(":quit")) {
                    System.out.println("Bye!");
                    break;
                }
                if (line.trim().isEmpty()) continue;
                try {
                    Value result = context.eval("clj", line);
                    System.out.println(formatResult(result));
                } catch (Exception e) {
                    System.err.println("Error: " + e.getMessage());
                }
            }
        }
    }

    private static String formatResult(Value result) {
        if (result.isNull()) return "nil";
        return result.toString();
    }
}
