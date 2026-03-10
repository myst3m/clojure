package clojure.truffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            // Evaluate expression from command line
            evalAndPrint(args[0]);
        } else {
            // REPL mode
            repl();
        }
    }

    private static void evalAndPrint(String code) {
        try (Context context = Context.newBuilder("clj")
                .allowAllAccess(true)
                .build()) {
            Value result = context.eval("clj", code);
            System.out.println(formatResult(result));
        }
    }

    private static void repl() throws Exception {
        System.out.println("Clojure Truffle REPL (Phase 1)");
        System.out.println("Type :quit to exit");
        System.out.println();

        try (Context context = Context.newBuilder("clj")
                .allowAllAccess(true)
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
