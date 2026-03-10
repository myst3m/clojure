package clojure.truffle.parser;

import clojure.lang.*;
import clojure.truffle.ClojureContext;
import clojure.truffle.ClojureTruffleLanguage;
import clojure.truffle.nodes.*;
import clojure.truffle.nodes.interop.*;
import clojure.truffle.runtime.ClojureDeftypeInstance;
import clojure.truffle.runtime.ClojureFunction;
import clojure.truffle.runtime.ClojureMultiMethod;
import clojure.truffle.runtime.ClojureNamespace;
import clojure.truffle.runtime.ClojureNil;
import clojure.truffle.runtime.ClojureProtocol;
import clojure.truffle.runtime.MultiArityFunction;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;

import java.io.PushbackReader;
import java.io.StringReader;
import java.util.*;

public class Analyzer {

    private final ClojureTruffleLanguage language;
    private ClojureContext context;
    private Scope currentScope;

    private static final Object EOF = new Object();

    // --- Scope ---

    static class CaptureEntry {
        final int outerSlot;
        final int innerSlot;
        CaptureEntry(int outerSlot, int innerSlot) {
            this.outerSlot = outerSlot;
            this.innerSlot = innerSlot;
        }
    }

    static class Scope {
        final Scope parent;
        final Map<String, Integer> locals = new LinkedHashMap<>();
        final FrameDescriptor.Builder frameBuilder;
        final List<CaptureEntry> captures = new ArrayList<>();

        Scope(Scope parent) {
            this.parent = parent;
            this.frameBuilder = FrameDescriptor.newBuilder();
        }

        int addLocal(String name) {
            int slot = frameBuilder.addSlot(FrameSlotKind.Object, name, null);
            locals.put(name, slot);
            return slot;
        }

        Integer findLocal(String name) {
            Integer slot = locals.get(name);
            if (slot != null) return slot;
            if (parent != null) {
                Integer parentSlot = parent.findLocal(name);
                if (parentSlot != null) {
                    int captureSlot = addLocal(name);
                    captures.add(new CaptureEntry(parentSlot, captureSlot));
                    return captureSlot;
                }
            }
            return null;
        }

        FrameDescriptor buildDescriptor() { return frameBuilder.build(); }
        int[] getOuterCaptureSlots() { return captures.stream().mapToInt(c -> c.outerSlot).toArray(); }
        int[] getInnerCaptureSlots() { return captures.stream().mapToInt(c -> c.innerSlot).toArray(); }
    }

    public Analyzer(ClojureTruffleLanguage language) {
        this.language = language;
    }

    public void setContext(ClojureContext context) { this.context = context; }
    public FrameDescriptor getFrameDescriptor() { return currentScope.buildDescriptor(); }

    // --- Public API ---

    public ExpressionNode[] analyzeProgram(String source) {
        currentScope = new Scope(null);
        List<Object> forms = readAll(source);
        ExpressionNode[] nodes = new ExpressionNode[forms.size()];
        for (int i = 0; i < forms.size(); i++) {
            nodes[i] = analyze(forms.get(i));
        }
        return nodes;
    }

    public ExpressionNode analyzeForm(Object form) {
        if (currentScope == null) currentScope = new Scope(null);
        return analyze(form);
    }

    // --- Reader ---

    private List<Object> readAll(String source) {
        List<Object> forms = new ArrayList<>();
        PushbackReader reader = new PushbackReader(new StringReader(source), 2);
        try {
            while (true) {
                Object form = LispReader.read(reader, false, EOF, false, null);
                if (form == EOF) break;
                forms.add(form);
            }
        } catch (Exception e) {
            throw new RuntimeException("Read error: " + e.getMessage(), e);
        }
        return forms;
    }

    // --- Core Analyzer ---

    private ExpressionNode analyze(Object form) {
        if (form == null) return new NilNode();
        if (form instanceof Long l) return new LongLiteralNode(l);
        if (form instanceof Integer i) return new LongLiteralNode(i.longValue());
        if (form instanceof Double d) return new DoubleLiteralNode(d);
        if (form instanceof Float f) return new DoubleLiteralNode(f.doubleValue());
        if (form instanceof String s) return new StringLiteralNode(s);
        if (form instanceof Boolean b) return new BooleanLiteralNode(b);
        if (form instanceof Keyword kw) return new KeywordLiteralNode(kw);
        if (form instanceof Symbol sym) return analyzeSymbol(sym);
        if (form instanceof ISeq seq) return analyzeList(seq);
        if (form instanceof IPersistentVector vec) return analyzeVector(vec);
        if (form instanceof IPersistentMap map) return analyzeMap(map);
        return new QuoteNode(form);
    }

    private ExpressionNode analyzeSymbol(Symbol sym) {
        String name = sym.getName();
        String ns = sym.getNamespace();
        if (ns == null) {
            switch (name) {
                case "nil": return new NilNode();
                case "true": return new BooleanLiteralNode(true);
                case "false": return new BooleanLiteralNode(false);
            }
        }
        Integer slot = (ns == null) ? currentScope.findLocal(name) : null;
        if (slot != null) return new ReadLocalNode(slot);
        // Static field access: Class/FIELD (outside call position)
        if (ns != null && isJavaClassName(ns)) {
            try {
                Class<?> clazz = JavaInteropUtil.resolveClass(ns);
                return new JavaStaticFieldNode(clazz, name);
            } catch (RuntimeException ignored) {
                // Not a Java class, fall through to var lookup
            }
        }
        String varName = ns != null ? ns + "/" + name : name;
        return new SymbolNode(context, varName);
    }

    private ExpressionNode analyzeList(ISeq seq) {
        if (seq == null) return new QuoteNode(PersistentList.EMPTY);
        Object first = seq.first();

        if (first instanceof Symbol sym) {
            String name = sym.getName();
            String ns = sym.getNamespace();

            // Check for macros first
            if (ns == null && context != null) {
                Object macro = context.getMacro(name);
                if (macro != null) {
                    return expandAndAnalyzeMacro(macro, seq.next());
                }
            }

            if (ns == null) {
                // Field access: (.-field obj)
                if (name.startsWith(".-") && name.length() > 2) {
                    return analyzeFieldAccess(name.substring(2), seq.next());
                }
                // Instance method: (.method obj args...)
                if (name.startsWith(".") && name.length() > 1 && !name.equals("..")) {
                    return analyzeInstanceMethod(name.substring(1), seq.next());
                }
                // Constructor: (ClassName. args...)
                if (name.endsWith(".") && name.length() > 1 && !name.equals("..")) {
                    String className = name.substring(0, name.length() - 1);
                    return analyzeConstructor(className, seq.next());
                }
                switch (name) {
                    case "if":          return analyzeIf(seq);
                    case "do":          return analyzeDo(seq);
                    case "def":         return analyzeDef(seq);
                    case "let*": case "let": return analyzeLet(seq);
                    case "fn*": case "fn": return analyzeFn(seq);
                    case "quote":       return analyzeQuote(seq);
                    case "recur":       return analyzeRecur(seq);
                    case "loop*": case "loop": return analyzeLoop(seq);
                    case "and":         return analyzeAnd(seq);
                    case "or":          return analyzeOr(seq);
                    case "when":        return analyzeWhen(seq);
                    case "cond":        return analyzeCond(seq);
                    case "defn":        return analyzeDefn(seq);
                    case "try":         return analyzeTry(seq);
                    case "throw":       return analyzeThrow(seq);
                    case "defmacro":    return analyzeDefmacro(seq);
                    case "macroexpand": return analyzeMacroexpand(seq);
                    case "new":         return analyzeNew(seq);
                    case "lazy-seq":    return analyzeLazySeq(seq);
                    case "defmulti":    return analyzeDefmulti(seq);
                    case "defmethod":   return analyzeDefmethod(seq);
                    case "defprotocol": return analyzeDefprotocol(seq);
                    case "deftype":     return analyzeDeftype(seq);
                    case "defrecord":   return analyzeDeftype(seq); // same as deftype for now
                    case "ns":          return analyzeNs(seq);
                    case "in-ns":       return analyzeInNs(seq);
                    case "require":     return analyzeRequire(seq);
                    case "->":          return analyzeThreadFirst(seq);
                    case "->>":         return analyzeThreadLast(seq);
                    case "as->":        return analyzeAsThread(seq);
                    case "some->":      return analyzeSomeThread(seq, true);
                    case "some->>":     return analyzeSomeThread(seq, false);
                    case "cond->":      return analyzeCondThread(seq, true);
                    case "cond->>":     return analyzeCondThread(seq, false);
                    case "doto":        return analyzeDoto(seq);
                    case "..":          return analyzeDotDot(seq);
                    case "if-let":      return analyzeIfLet(seq);
                    case "when-let":    return analyzeWhenLet(seq);
                    case "if-some":     return analyzeIfSome(seq);
                    case "when-some":   return analyzeWhenSome(seq);
                    case "case":        return analyzeCase(seq);
                    case "for":         return analyzeFor(seq);
                    case "doseq":       return analyzeDoseq(seq);
                    case "dotimes":     return analyzeDotimes(seq);
                    case "letfn":       return analyzeLetfn(seq);
                    case "do-template": return analyzeDo(seq); // fallback
                }
            }
            // Static method call: (Class/method args...)
            if (ns != null && isJavaClassName(ns)) {
                return analyzeStaticCall(ns, name, seq.next());
            }
        }
        return analyzeInvoke(seq);
    }

    // --- Macro expansion ---

    private ExpressionNode expandAndAnalyzeMacro(Object macro, ISeq argForms) {
        List<Object> rawArgs = new ArrayList<>();
        while (argForms != null) {
            rawArgs.add(argForms.first());
            argForms = argForms.next();
        }
        Object expanded = context.callFunction(macro, rawArgs.toArray());
        return analyze(expanded);
    }

    private ExpressionNode analyzeDefmacro(ISeq seq) {
        // (defmacro name [params] body...)
        ISeq args = seq.next();
        if (args == null) throw err("defmacro: missing name");
        if (!(args.first() instanceof Symbol)) throw err("defmacro: name must be a symbol");
        String macroName = ((Symbol) args.first()).getName();

        // Build (fn* name [params] body...) and compile it
        ISeq fnForm = RT.cons(Symbol.intern("fn*"), args);
        ExpressionNode fnNode = analyzeFn(fnForm);

        // Execute immediately to get the function
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        EvalRootNode evalRoot = new EvalRootNode(language, fd, new ExpressionNode[]{fnNode});
        Object result = evalRoot.getCallTarget().call();

        context.setMacro(macroName, result);
        return new NilNode();
    }

    private ExpressionNode analyzeMacroexpand(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("macroexpand: missing form");
        // Return the quoted expanded form (for debugging)
        return analyze(args.first());
    }

    // --- Lazy Seq ---

    private ExpressionNode analyzeLazySeq(ISeq seq) {
        // (lazy-seq body...) -> wrap body in (fn* [] body...) then LazySeqNode
        ISeq body = seq.next();
        // Build: (fn* [] body...)
        ISeq fnForm = RT.cons(Symbol.intern("fn*"),
                RT.cons(PersistentVector.EMPTY, body));
        ExpressionNode thunkNode = analyzeFn(fnForm);
        return new LazySeqNode(thunkNode);
    }

    // --- Java Interop ---

    private boolean isJavaClassName(String name) {
        if (name.isEmpty()) return false;
        // If it looks like a Clojure namespace (registered or contains lowercase first segment), not Java
        if (name.contains(".")) {
            // Check: if first segment starts with lowercase and matches known Clojure patterns, skip
            String firstSeg = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
            if (Character.isLowerCase(firstSeg.charAt(0))) {
                // Check if there's a registered var with this prefix (e.g., clojure.string/split)
                // Also check known Clojure namespace patterns
                if (name.startsWith("clojure.") || name.startsWith("user.")) return false;
                // Try to resolve as Java class
                try {
                    Class.forName(name);
                    return true;
                } catch (ClassNotFoundException e) {
                    return false;
                }
            }
            return true; // e.g., java.util.List
        }
        return Character.isUpperCase(name.charAt(0));
    }

    private ExpressionNode analyzeInstanceMethod(String methodName, ISeq args) {
        if (args == null) throw err(".method: missing target object");
        ExpressionNode target = analyze(args.first());
        args = args.next();
        List<ExpressionNode> argList = new ArrayList<>();
        while (args != null) { argList.add(analyze(args.first())); args = args.next(); }
        return new JavaInstanceMethodNode(methodName, target, argList.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeStaticCall(String className, String memberName, ISeq args) {
        Class<?> clazz = JavaInteropUtil.resolveClass(className);
        List<ExpressionNode> argList = new ArrayList<>();
        while (args != null) { argList.add(analyze(args.first())); args = args.next(); }
        if (argList.isEmpty()) {
            // Could be static field or zero-arg method - try field first
            try {
                clazz.getField(memberName);
                return new JavaStaticFieldNode(clazz, memberName);
            } catch (NoSuchFieldException ignored) {
                // Fall through to static method
            }
        }
        return new JavaStaticMethodNode(clazz, memberName, argList.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeConstructor(String className, ISeq args) {
        Class<?> clazz = JavaInteropUtil.resolveClass(className);
        List<ExpressionNode> argList = new ArrayList<>();
        while (args != null) { argList.add(analyze(args.first())); args = args.next(); }
        return new JavaConstructorNode(clazz, argList.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeNew(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("new: missing class name");
        Object classForm = args.first();
        if (!(classForm instanceof Symbol classSym))
            throw err("new: class name must be a symbol");
        return analyzeConstructor(classSym.getName(), args.next());
    }

    // --- Field access ---

    private ExpressionNode analyzeFieldAccess(String fieldName, ISeq args) {
        if (args == null) throw err(".-field: missing target");
        ExpressionNode target = analyze(args.first());
        // Return a node that accesses the field
        return new ExpressionNode() {
            @Child ExpressionNode targetNode = target;
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                Object obj = targetNode.executeGeneric(frame);
                if (obj instanceof ClojureDeftypeInstance inst) {
                    return inst.getField(fieldName);
                }
                // Fall back to Java reflection for field access
                try {
                    java.lang.reflect.Field f = obj.getClass().getField(fieldName);
                    return clojure.truffle.nodes.interop.JavaInteropUtil.wrapResult(f.get(obj));
                } catch (Exception e) {
                    throw new RuntimeException("No field '" + fieldName + "' on " + obj.getClass().getName());
                }
            }
        };
    }

    // --- Multimethods ---

    private ExpressionNode analyzeDefmulti(ISeq seq) {
        // (defmulti name dispatch-fn)
        ISeq args = seq.next();
        if (args == null) throw err("defmulti: missing name");
        if (!(args.first() instanceof Symbol sym)) throw err("defmulti: name must be a symbol");
        String name = sym.getName();
        args = args.next();
        if (args == null) throw err("defmulti: missing dispatch function");
        ExpressionNode dispatchFnNode = analyze(args.first());

        return new ExpressionNode() {
            @Child ExpressionNode dispatchNode = dispatchFnNode;
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                Object dispatchFn = dispatchNode.executeGeneric(frame);
                ClojureMultiMethod mm = new ClojureMultiMethod(name, dispatchFn, context);
                context.setVar(name, mm);
                return mm;
            }
        };
    }

    private ExpressionNode analyzeDefmethod(ISeq seq) {
        // (defmethod name dispatch-val [params] body...)
        ISeq args = seq.next();
        if (args == null) throw err("defmethod: missing name");
        if (!(args.first() instanceof Symbol sym)) throw err("defmethod: name must be a symbol");
        String mmName = sym.getName();
        args = args.next();
        if (args == null) throw err("defmethod: missing dispatch value");
        ExpressionNode dispatchValNode = analyze(args.first());
        args = args.next();
        if (args == null) throw err("defmethod: missing fn body");

        // Build fn from remaining args: [params] body...
        ISeq fnForm = RT.cons(Symbol.intern("fn*"), args);
        ExpressionNode fnNode = analyzeFn(fnForm);

        return new ExpressionNode() {
            @Child ExpressionNode dvNode = dispatchValNode;
            @Child ExpressionNode methodFnNode = fnNode;
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                Object dispatchVal = dvNode.executeGeneric(frame);
                Object methodFn = methodFnNode.executeGeneric(frame);
                Object mm = context.getVar(mmName);
                if (!(mm instanceof ClojureMultiMethod multi))
                    throw new RuntimeException("defmethod: " + mmName + " is not a multimethod");
                multi.addMethod(dispatchVal, methodFn);
                return multi;
            }
        };
    }

    // --- Protocols ---

    private ExpressionNode analyzeDefprotocol(ISeq seq) {
        // (defprotocol Name (method-name [this arg1] [this arg1 arg2]) ...)
        ISeq args = seq.next();
        if (args == null) throw err("defprotocol: missing name");
        if (!(args.first() instanceof Symbol sym)) throw err("defprotocol: name must be a symbol");
        String protoName = sym.getName();
        args = args.next();

        List<String> methodNames = new ArrayList<>();
        while (args != null) {
            Object methodSpec = args.first();
            if (methodSpec instanceof ISeq methodSeq) {
                if (!(methodSeq.first() instanceof Symbol methodSym))
                    throw err("defprotocol: method name must be a symbol");
                methodNames.add(methodSym.getName());
            }
            args = args.next();
        }

        List<String> capturedMethodNames = List.copyOf(methodNames);
        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                ClojureProtocol proto = new ClojureProtocol(protoName, capturedMethodNames);
                context.setVar(protoName, proto);
                // Create dispatch functions for each method
                for (String methodName : capturedMethodNames) {
                    context.setVar(methodName, (ClojureContext.BuiltinFunction) fnArgs -> {
                        if (fnArgs.length < 1)
                            throw new RuntimeException(methodName + ": missing target (this)");
                        Object fn = proto.findMethod(methodName, fnArgs[0]);
                        if (fn == null)
                            throw new RuntimeException("No implementation of " + protoName +
                                    "." + methodName + " for " + fnArgs[0].getClass().getName());
                        return context.callFunction(fn, fnArgs);
                    });
                }
                return proto;
            }
        };
    }

    // --- deftype ---

    private ExpressionNode analyzeDeftype(ISeq seq) {
        // (deftype TypeName [field1 field2] ProtoName (method1 [this] body...) ...)
        ISeq args = seq.next();
        if (args == null) throw err("deftype: missing name");
        if (!(args.first() instanceof Symbol typeSym)) throw err("deftype: name must be a symbol");
        String typeName = typeSym.getName();
        args = args.next();
        if (args == null) throw err("deftype: missing fields");
        if (!(args.first() instanceof IPersistentVector fieldVec)) throw err("deftype: fields must be a vector");

        // Parse field names
        List<String> fieldNames = new ArrayList<>();
        for (int i = 0; i < fieldVec.count(); i++) {
            if (!(fieldVec.nth(i) instanceof Symbol fs)) throw err("deftype: field must be a symbol");
            fieldNames.add(fs.getName());
        }
        args = args.next();

        // Parse protocol implementations
        // format: ProtoName (method [this arg] body...) (method2 [this] body...) AnotherProto ...
        java.util.Map<String, java.util.Map<String, ExpressionNode>> protoMethods = new java.util.LinkedHashMap<>();
        String currentProto = null;

        while (args != null) {
            Object form = args.first();
            if (form instanceof Symbol protoSym) {
                currentProto = protoSym.getName();
                protoMethods.putIfAbsent(currentProto, new java.util.LinkedHashMap<>());
            } else if (form instanceof ISeq methodSeq && currentProto != null) {
                if (!(methodSeq.first() instanceof Symbol methodSym))
                    throw err("deftype: method name must be a symbol");
                String methodName = methodSym.getName();
                // Build fn: (fn* [this arg1 ...] (let [field1 (.-field1 this) ...] body...))
                ISeq methodArgs = methodSeq.next();
                if (methodArgs == null || !(methodArgs.first() instanceof IPersistentVector))
                    throw err("deftype: method must have param vector");
                IPersistentVector methodParams = (IPersistentVector) methodArgs.first();
                ISeq methodBody = methodArgs.next();

                // Compile fn with field bindings inside body
                ExpressionNode methodFn = compileDeftypeMethod(typeName, fieldNames, methodParams, methodBody);
                protoMethods.get(currentProto).put(methodName, methodFn);
            }
            args = args.next();
        }

        List<String> capturedFieldNames = List.copyOf(fieldNames);
        // Capture proto methods
        java.util.Map<String, java.util.Map<String, ExpressionNode>> capturedProtoMethods =
                new java.util.LinkedHashMap<>(protoMethods);

        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                // Register constructor ->TypeName
                java.util.Map<String, Integer> fieldIdx = new java.util.LinkedHashMap<>();
                for (int i = 0; i < capturedFieldNames.size(); i++)
                    fieldIdx.put(capturedFieldNames.get(i), i);

                context.setVar("->" + typeName, (ClojureContext.BuiltinFunction) ctorArgs -> {
                    if (ctorArgs.length != capturedFieldNames.size())
                        throw new RuntimeException("->" + typeName + ": expected " +
                                capturedFieldNames.size() + " args");
                    return new ClojureDeftypeInstance(typeName, ctorArgs.clone(), fieldIdx);
                });

                // Register protocol implementations
                for (var entry : capturedProtoMethods.entrySet()) {
                    String protoName = entry.getKey();
                    Object protoObj = context.getVar(protoName);
                    if (protoObj instanceof ClojureProtocol proto) {
                        java.util.Map<String, Object> methodMap = new java.util.HashMap<>();
                        for (var methodEntry : entry.getValue().entrySet()) {
                            Object fn = methodEntry.getValue().executeGeneric(frame);
                            methodMap.put(methodEntry.getKey(), fn);
                        }
                        proto.extend(typeName, methodMap);
                    }
                }
                return ClojureNil.INSTANCE;
            }
        };
    }

    private ExpressionNode compileDeftypeMethod(String typeName, List<String> fieldNames,
                                                 IPersistentVector params, ISeq body) {
        // Build: (fn* [this arg1 ...] (let [field1 (.-field1 this) ...] body...))
        // Create the let bindings for fields
        List<Object> letBindings = new ArrayList<>();
        Symbol thisSym = Symbol.intern("this");

        // Only bind fields that aren't shadowed by params
        java.util.Set<String> paramNames = new java.util.HashSet<>();
        for (int i = 0; i < params.count(); i++) {
            if (params.nth(i) instanceof Symbol s) paramNames.add(s.getName());
        }

        for (String fieldName : fieldNames) {
            if (paramNames.contains(fieldName)) continue;
            letBindings.add(Symbol.intern(fieldName));
            // (.-fieldName this)
            letBindings.add(RT.list(Symbol.intern(".-" + fieldName), thisSym));
        }

        Object wrappedBody;
        if (letBindings.isEmpty()) {
            wrappedBody = body;
        } else {
            // (let [bindings...] body...)
            IPersistentVector bindVec = PersistentVector.create(letBindings);
            wrappedBody = RT.cons(Symbol.intern("let"), RT.cons(bindVec, body));
            wrappedBody = RT.list(wrappedBody);
        }

        // (fn* [params] wrapped-body)
        ISeq fnSeq;
        if (wrappedBody instanceof ISeq ws) {
            fnSeq = RT.cons(Symbol.intern("fn*"), RT.cons(params, ws));
        } else {
            fnSeq = RT.list(Symbol.intern("fn*"), params, wrappedBody);
        }
        return analyzeFn(fnSeq);
    }

    // --- Threading macros ---

    private ExpressionNode analyzeThreadFirst(ISeq seq) {
        // (-> x (f a) (g b)) => (g (f x a) b)
        ISeq args = seq.next();
        if (args == null) return new NilNode();
        Object form = args.first();
        args = args.next();
        while (args != null) {
            Object step = args.first();
            if (step instanceof ISeq stepSeq) {
                // Insert form as first arg: (f a b) -> (f form a b)
                form = RT.cons(stepSeq.first(), RT.cons(form, stepSeq.next()));
            } else {
                // Bare symbol: (f) -> (f form)
                form = RT.list(step, form);
            }
            args = args.next();
        }
        return analyze(form);
    }

    private ExpressionNode analyzeThreadLast(ISeq seq) {
        // (->> x (f a) (g b)) => (g b (f a x))
        ISeq args = seq.next();
        if (args == null) return new NilNode();
        Object form = args.first();
        args = args.next();
        while (args != null) {
            Object step = args.first();
            if (step instanceof ISeq stepSeq) {
                // Append form as last arg
                java.util.List<Object> newList = new ArrayList<>();
                for (ISeq s = stepSeq; s != null; s = s.next()) newList.add(s.first());
                newList.add(form);
                form = PersistentList.create(newList);
            } else {
                form = RT.list(step, form);
            }
            args = args.next();
        }
        return analyze(form);
    }

    private ExpressionNode analyzeAsThread(ISeq seq) {
        // (as-> expr name (f name) (g name)) => (let [name expr, name (f name), name (g name)] name)
        ISeq args = seq.next();
        if (args == null) throw err("as->: missing expression");
        Object expr = args.first();
        args = args.next();
        if (args == null) throw err("as->: missing name");
        if (!(args.first() instanceof Symbol nameSym)) throw err("as->: name must be a symbol");
        args = args.next();

        // Build: (let [name expr name (f name) name (g name) ...] name)
        java.util.List<Object> bindings = new ArrayList<>();
        bindings.add(nameSym);
        bindings.add(expr);
        while (args != null) {
            bindings.add(nameSym);
            bindings.add(args.first());
            args = args.next();
        }
        Object letForm = RT.list(Symbol.intern("let"),
                PersistentVector.create(bindings), nameSym);
        return analyze(letForm);
    }

    private ExpressionNode analyzeSomeThread(ISeq seq, boolean first) {
        // (some-> x f g) => (let [t x, t (if (nil? t) nil (f t)), ...] t)
        ISeq args = seq.next();
        if (args == null) return new NilNode();
        Object expr = args.first();
        args = args.next();
        Symbol tmpSym = Symbol.intern("__some_thread__");
        java.util.List<Object> bindings = new ArrayList<>();
        bindings.add(tmpSym);
        bindings.add(expr);
        while (args != null) {
            Object step = args.first();
            Object threadedForm;
            if (step instanceof ISeq stepSeq) {
                if (first) {
                    threadedForm = RT.cons(stepSeq.first(), RT.cons(tmpSym, stepSeq.next()));
                } else {
                    java.util.List<Object> newList = new ArrayList<>();
                    for (ISeq s = stepSeq; s != null; s = s.next()) newList.add(s.first());
                    newList.add(tmpSym);
                    threadedForm = PersistentList.create(newList);
                }
            } else {
                threadedForm = RT.list(step, tmpSym);
            }
            // (if (nil? t) nil threadedForm)
            bindings.add(tmpSym);
            bindings.add(RT.list(Symbol.intern("if"),
                    RT.list(Symbol.intern("nil?"), tmpSym),
                    null, threadedForm));
            args = args.next();
        }
        return analyze(RT.list(Symbol.intern("let"),
                PersistentVector.create(bindings), tmpSym));
    }

    private ExpressionNode analyzeCondThread(ISeq seq, boolean first) {
        // (cond-> x test1 (f) test2 (g)) => (let [t x, t (if test1 (f t) t), ...] t)
        ISeq args = seq.next();
        if (args == null) return new NilNode();
        Object expr = args.first();
        args = args.next();
        Symbol tmpSym = Symbol.intern("__cond_thread__");
        java.util.List<Object> bindings = new ArrayList<>();
        bindings.add(tmpSym);
        bindings.add(expr);
        while (args != null) {
            Object test = args.first();
            args = args.next();
            if (args == null) throw err("cond->: odd number of forms");
            Object step = args.first();
            Object threadedForm;
            if (step instanceof ISeq stepSeq) {
                if (first) {
                    threadedForm = RT.cons(stepSeq.first(), RT.cons(tmpSym, stepSeq.next()));
                } else {
                    java.util.List<Object> newList = new ArrayList<>();
                    for (ISeq s = stepSeq; s != null; s = s.next()) newList.add(s.first());
                    newList.add(tmpSym);
                    threadedForm = PersistentList.create(newList);
                }
            } else {
                threadedForm = RT.list(step, tmpSym);
            }
            bindings.add(tmpSym);
            bindings.add(RT.list(Symbol.intern("if"), test, threadedForm, tmpSym));
            args = args.next();
        }
        return analyze(RT.list(Symbol.intern("let"),
                PersistentVector.create(bindings), tmpSym));
    }

    private ExpressionNode analyzeDoto(ISeq seq) {
        // (doto x (.method1 a) (.method2 b)) => (let [t x] (.method1 t a) (.method2 t b) t)
        ISeq args = seq.next();
        if (args == null) return new NilNode();
        Object expr = args.first();
        args = args.next();
        Symbol tmpSym = Symbol.intern("__doto_tmp__");
        java.util.List<Object> body = new ArrayList<>();
        while (args != null) {
            Object step = args.first();
            if (step instanceof ISeq stepSeq) {
                body.add(RT.cons(stepSeq.first(), RT.cons(tmpSym, stepSeq.next())));
            } else {
                body.add(RT.list(step, tmpSym));
            }
            args = args.next();
        }
        body.add(tmpSym);
        java.util.List<Object> letForm = new ArrayList<>();
        letForm.add(Symbol.intern("let"));
        letForm.add(PersistentVector.create(java.util.List.of(tmpSym, expr)));
        letForm.addAll(body);
        return analyze(PersistentList.create(letForm));
    }

    private ExpressionNode analyzeDotDot(ISeq seq) {
        // (.. x method1 (method2 a)) => (. (. x method1) method2 a)
        ISeq args = seq.next();
        if (args == null) return new NilNode();
        Object form = args.first();
        args = args.next();
        while (args != null) {
            Object step = args.first();
            if (step instanceof Symbol sym) {
                form = RT.list(Symbol.intern("." + sym.getName()), form);
            } else if (step instanceof ISeq stepSeq) {
                form = RT.cons(Symbol.intern("." + ((Symbol) stepSeq.first()).getName()),
                        RT.cons(form, stepSeq.next()));
            }
            args = args.next();
        }
        return analyze(form);
    }

    // --- Control flow ---

    private ExpressionNode analyzeIfLet(ISeq seq) {
        // (if-let [x expr] then else) => (let [x expr] (if x then else))
        ISeq args = seq.next();
        if (args == null) throw err("if-let: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        if (bindings.count() != 2) throw err("if-let: binding must have exactly 2 forms");
        args = args.next();
        Object thenForm = args != null ? args.first() : null;
        Object elseForm = (args != null && args.next() != null) ? args.next().first() : null;
        return analyze(RT.list(Symbol.intern("let"), bindings,
                RT.list(Symbol.intern("if"), bindings.nth(0), thenForm, elseForm)));
    }

    private ExpressionNode analyzeWhenLet(ISeq seq) {
        // (when-let [x expr] body...) => (let [x expr] (when x body...))
        ISeq args = seq.next();
        if (args == null) throw err("when-let: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        ISeq body = args.next();
        java.util.List<Object> whenForm = new ArrayList<>();
        whenForm.add(Symbol.intern("when"));
        whenForm.add(bindings.nth(0));
        while (body != null) { whenForm.add(body.first()); body = body.next(); }
        return analyze(RT.list(Symbol.intern("let"), bindings,
                PersistentList.create(whenForm)));
    }

    private ExpressionNode analyzeIfSome(ISeq seq) {
        // (if-some [x expr] then else) - like if-let but only nil check (not false)
        ISeq args = seq.next();
        if (args == null) throw err("if-some: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        args = args.next();
        Object thenForm = args != null ? args.first() : null;
        Object elseForm = (args != null && args.next() != null) ? args.next().first() : null;
        return analyze(RT.list(Symbol.intern("let"), bindings,
                RT.list(Symbol.intern("if"),
                        RT.list(Symbol.intern("not"), RT.list(Symbol.intern("nil?"), bindings.nth(0))),
                        thenForm, elseForm)));
    }

    private ExpressionNode analyzeWhenSome(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("when-some: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        ISeq body = args.next();
        java.util.List<Object> whenForm = new ArrayList<>();
        whenForm.add(Symbol.intern("when"));
        whenForm.add(RT.list(Symbol.intern("not"), RT.list(Symbol.intern("nil?"), bindings.nth(0))));
        while (body != null) { whenForm.add(body.first()); body = body.next(); }
        return analyze(RT.list(Symbol.intern("let"), bindings,
                PersistentList.create(whenForm)));
    }

    private ExpressionNode analyzeCase(ISeq seq) {
        // (case expr val1 result1 val2 result2 default)
        ISeq args = seq.next();
        if (args == null) throw err("case: missing expression");
        ExpressionNode exprNode = analyze(args.first());
        args = args.next();

        int tmpSlot = currentScope.addLocal("__case_tmp__");
        List<Object[]> clauses = new ArrayList<>(); // [matchVal, resultForm]
        Object defaultForm = null;

        java.util.List<Object> formList = new ArrayList<>();
        while (args != null) {
            formList.add(args.first());
            args = args.next();
        }
        for (int i = 0; i < formList.size() - 1; i += 2) {
            clauses.add(new Object[]{formList.get(i), formList.get(i + 1)});
        }
        if (formList.size() % 2 == 1) {
            defaultForm = formList.get(formList.size() - 1);
        }

        // Build: set tmp, then chain of if (= tmp val) result ...
        ExpressionNode chain = defaultForm != null ? analyze(defaultForm) :
                new ExpressionNode() {
                    @Override
                    public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame f) {
                        throw new RuntimeException("No matching clause in case");
                    }
                };
        for (int i = clauses.size() - 1; i >= 0; i--) {
            Object matchVal = clauses.get(i)[0];
            ExpressionNode resultNode = analyze(clauses.get(i)[1]);
            ExpressionNode matchNode = analyze(matchVal);
            ExpressionNode condNode = new InvokeNode(
                    new SymbolNode(context, "="),
                    new ExpressionNode[]{new ReadLocalNode(tmpSlot), matchNode});
            chain = new IfNode(condNode, resultNode, chain);
        }

        ExpressionNode finalChain = chain;
        return new LetNode(new int[]{tmpSlot}, new ExpressionNode[]{exprNode}, finalChain);
    }

    // --- Comprehensions ---

    private ExpressionNode analyzeFor(ISeq seq) {
        // (for [x coll :when pred :let [y expr]] body)
        // Simple implementation: convert to nested map/filter/mapcat
        ISeq args = seq.next();
        if (args == null) throw err("for: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        ISeq body = args.next();
        if (body == null) throw err("for: missing body");

        // Build from inside out
        // Single binding: (for [x coll] body) => (map (fn [x] body) coll)
        return analyzeForBindings(bindings, 0, body);
    }

    private ExpressionNode analyzeForBindings(IPersistentVector bindings, int pos, ISeq body) {
        if (pos >= bindings.count()) {
            // All bindings consumed, analyze body
            if (body.next() == null) return analyze(body.first());
            return analyzeDo(RT.cons(Symbol.intern("do"), body));
        }

        Object key = bindings.nth(pos);

        // :when modifier
        if (key instanceof Keyword kw && kw.getName().equals("when")) {
            Object pred = bindings.nth(pos + 1);
            // Wrap remaining in (when pred ...)
            ExpressionNode innerNode = analyzeForBindings(bindings, pos + 2, body);
            return new IfNode(analyze(pred), innerNode, new NilNode());
        }

        // :let modifier
        if (key instanceof Keyword kw && kw.getName().equals("let")) {
            IPersistentVector letBindings = (IPersistentVector) bindings.nth(pos + 1);
            // Wrap remaining in (let [...] ...)
            Object innerForm = buildForInner(bindings, pos + 2, body);
            return analyze(RT.list(Symbol.intern("let"), letBindings, innerForm));
        }

        // Normal binding: sym coll
        if (!(key instanceof Symbol bindSym)) throw err("for: binding must be a symbol");
        Object collForm = bindings.nth(pos + 1);

        if (pos + 2 >= bindings.count()) {
            // Last binding: (map (fn [sym] body) coll)
            Object fnForm = RT.list(Symbol.intern("fn"),
                    PersistentVector.create(java.util.List.of(bindSym)),
                    body.first());
            return analyze(RT.list(Symbol.intern("map"), fnForm, collForm));
        } else {
            // Not last: (mapcat (fn [sym] (for [rest...] body)) coll)
            IPersistentVector restBindings = PersistentVector.EMPTY;
            for (int i = pos + 2; i < bindings.count(); i++)
                restBindings = restBindings.cons(bindings.nth(i));
            Object innerFor = RT.list(Symbol.intern("for"), restBindings, body.first());
            Object fnForm = RT.list(Symbol.intern("fn"),
                    PersistentVector.create(java.util.List.of(bindSym)), innerFor);
            return analyze(RT.list(Symbol.intern("mapcat"), fnForm, collForm));
        }
    }

    private Object buildForInner(IPersistentVector bindings, int pos, ISeq body) {
        if (pos >= bindings.count()) return body.first();
        // Rebuild remaining as (for [rest...] body)
        IPersistentVector restBindings = PersistentVector.EMPTY;
        for (int i = pos; i < bindings.count(); i++)
            restBindings = restBindings.cons(bindings.nth(i));
        return RT.list(Symbol.intern("for"), restBindings, body.first());
    }

    private ExpressionNode analyzeDoseq(ISeq seq) {
        // (doseq [x coll] body...) => (dorun (for [x coll] (do body...)))
        ISeq args = seq.next();
        if (args == null) throw err("doseq: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        ISeq body = args.next();

        // Simple: iterate and execute for side effects
        // (doseq [x coll] body) => (let [s (seq coll)] (loop [] (when s (let [x (first s)] body (recur (next s)))))
        // Actually simpler: just use eager for with dorun
        java.util.List<Object> doBody = new ArrayList<>();
        doBody.add(Symbol.intern("do"));
        while (body != null) { doBody.add(body.first()); body = body.next(); }
        Object forForm = RT.list(Symbol.intern("for"), bindings, PersistentList.create(doBody));
        return analyze(RT.list(Symbol.intern("dorun"), forForm));
    }

    private ExpressionNode analyzeDotimes(ISeq seq) {
        // (dotimes [i n] body...) => (let [n# n] (loop [i 0] (when (< i n#) body... (recur (inc i)))))
        ISeq args = seq.next();
        if (args == null) throw err("dotimes: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        if (bindings.count() != 2) throw err("dotimes: binding must have exactly 2 forms");
        Symbol iSym = (Symbol) bindings.nth(0);
        Object nForm = bindings.nth(1);
        ISeq body = args.next();

        Symbol nSym = Symbol.intern("__dotimes_n__");
        java.util.List<Object> loopBody = new ArrayList<>();
        loopBody.add(Symbol.intern("when"));
        loopBody.add(RT.list(Symbol.intern("<"), iSym, nSym));
        while (body != null) { loopBody.add(body.first()); body = body.next(); }
        loopBody.add(RT.list(Symbol.intern("recur"), RT.list(Symbol.intern("inc"), iSym)));

        Object loopForm = RT.list(Symbol.intern("loop"),
                PersistentVector.create(java.util.List.of(iSym, 0L)),
                PersistentList.create(loopBody));
        return analyze(RT.list(Symbol.intern("let"),
                PersistentVector.create(java.util.List.of(nSym, nForm)), loopForm));
    }

    private ExpressionNode analyzeLetfn(ISeq seq) {
        // (letfn [(f [x] body) (g [y] body)] expr)
        // => (let [f nil g nil] (set! f (fn f [x] body)) (set! g (fn g [y] body)) expr)
        // Actually simpler: use forward declarations via def
        ISeq args = seq.next();
        if (args == null) throw err("letfn: missing bindings");
        IPersistentVector fnBindings = (IPersistentVector) args.first();
        ISeq body = args.next();

        // First pass: declare all names
        List<String> names = new ArrayList<>();
        for (int i = 0; i < fnBindings.count(); i++) {
            ISeq fnSpec = (ISeq) fnBindings.nth(i);
            names.add(((Symbol) fnSpec.first()).getName());
        }

        // Allocate slots and set to nil
        List<Integer> slots = new ArrayList<>();
        List<ExpressionNode> values = new ArrayList<>();
        for (String name : names) {
            slots.add(currentScope.addLocal(name));
            values.add(new NilNode());
        }

        // Second pass: compile fns (they can reference each other via locals)
        List<ExpressionNode> assignments = new ArrayList<>();
        for (int i = 0; i < fnBindings.count(); i++) {
            ISeq fnSpec = (ISeq) fnBindings.nth(i);
            Symbol fnName = (Symbol) fnSpec.first();
            ISeq fnArgs = fnSpec.next();
            ISeq fnForm = RT.cons(Symbol.intern("fn"), RT.cons(fnName, fnArgs));
            ExpressionNode fnNode = analyze(fnForm);
            // Assignment: set the slot
            int slot = slots.get(i);
            assignments.add(new ExpressionNode() {
                @Child ExpressionNode valueNode = fnNode;
                final int s = slot;
                @Override
                public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                    Object val = valueNode.executeGeneric(frame);
                    frame.setObject(s, val);
                    return val;
                }
            });
        }

        ExpressionNode bodyNode = analyzeBody(body);

        // Combine: let [names nil...], then assignments, then body
        ExpressionNode[] allAssignments = assignments.toArray(new ExpressionNode[0]);
        ExpressionNode assignAndBody = new DoNode(
                java.util.stream.Stream.concat(
                        java.util.Arrays.stream(allAssignments),
                        java.util.stream.Stream.of(bodyNode)
                ).toArray(ExpressionNode[]::new));

        return new LetNode(
                slots.stream().mapToInt(Integer::intValue).toArray(),
                values.toArray(new ExpressionNode[0]),
                assignAndBody);
    }

    // --- Namespace ---

    private ExpressionNode analyzeNs(ISeq seq) {
        // (ns my.ns (:require [some.ns :as s]) (:require [other.ns :refer [foo]]))
        ISeq args = seq.next();
        if (args == null) throw err("ns: missing name");
        if (!(args.first() instanceof Symbol nsSym)) throw err("ns: name must be a symbol");
        String nsName = nsSym.getName();
        args = args.next();

        // Collect require directives
        List<Object> requireSpecs = new ArrayList<>();
        while (args != null) {
            Object directive = args.first();
            if (directive instanceof ISeq ds) {
                Object head = ds.first();
                if (head instanceof Keyword kw && kw.getName().equals("require")) {
                    for (ISeq specs = ds.next(); specs != null; specs = specs.next())
                        requireSpecs.add(specs.first());
                }
            }
            args = args.next();
        }

        List<Object> capturedSpecs = List.copyOf(requireSpecs);
        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                context.getOrCreateNamespace(nsName);
                context.setCurrentNamespace(nsName);
                // Refer all of clojure.core
                ClojureNamespace ns = context.getNamespace(nsName);
                ClojureNamespace core = context.getNamespace("clojure.core");
                if (core != null) ns.referAll(core);
                // Process requires
                for (Object spec : capturedSpecs) {
                    processRequireSpec(spec);
                }
                return ClojureNil.INSTANCE;
            }
        };
    }

    private ExpressionNode analyzeInNs(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("in-ns: missing name");
        ExpressionNode nameNode = analyze(args.first());
        return new ExpressionNode() {
            @Child ExpressionNode nsNode = nameNode;
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                Object name = nsNode.executeGeneric(frame);
                String nsName;
                if (name instanceof clojure.lang.Symbol sym) nsName = sym.getName();
                else nsName = name.toString();
                context.getOrCreateNamespace(nsName);
                context.setCurrentNamespace(nsName);
                return ClojureNil.INSTANCE;
            }
        };
    }

    private ExpressionNode analyzeRequire(ISeq seq) {
        ISeq args = seq.next();
        List<Object> specs = new ArrayList<>();
        while (args != null) {
            specs.add(args.first());
            args = args.next();
        }
        List<Object> capturedSpecs = List.copyOf(specs);
        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                for (Object spec : capturedSpecs) processRequireSpec(spec);
                return ClojureNil.INSTANCE;
            }
        };
    }

    private void processRequireSpec(Object spec) {
        if (spec instanceof Symbol sym) {
            // Simple require: (require 'some.ns)
            context.loadNamespace(sym.getName());
        } else if (spec instanceof IPersistentVector v) {
            // [some.ns :as s] or [some.ns :refer [foo bar]]
            if (v.count() == 0) return;
            String nsName = ((Symbol) v.nth(0)).getName();
            context.loadNamespace(nsName);
            ClojureNamespace reqNs = context.getNamespace(nsName);
            ClojureNamespace currentNs = context.getNamespace(context.getCurrentNamespace());
            if (reqNs == null || currentNs == null) return;

            for (int i = 1; i < v.count(); i += 2) {
                Object key = v.nth(i);
                if (key instanceof Keyword kw) {
                    if (kw.getName().equals("as") && i + 1 < v.count()) {
                        String alias = ((Symbol) v.nth(i + 1)).getName();
                        currentNs.alias(alias, reqNs);
                    } else if (kw.getName().equals("refer") && i + 1 < v.count()) {
                        Object referSpec = v.nth(i + 1);
                        if (referSpec instanceof Keyword rk && rk.getName().equals("all")) {
                            currentNs.referAll(reqNs);
                        } else if (referSpec instanceof IPersistentVector rv) {
                            for (int j = 0; j < rv.count(); j++) {
                                String symName = ((Symbol) rv.nth(j)).getName();
                                Object val = reqNs.resolve(symName);
                                if (val != null) currentNs.refer(symName, val);
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Destructuring ---

    private void expandDestructuring(Object bindingForm, int sourceSlot,
                                     List<Integer> slots, List<ExpressionNode> values) {
        if (bindingForm instanceof IPersistentVector vec) {
            expandVectorDestructuring(vec, sourceSlot, slots, values);
        } else if (bindingForm instanceof IPersistentMap map) {
            expandMapDestructuring(map, sourceSlot, slots, values);
        } else {
            throw err("Unsupported destructuring form: " + bindingForm);
        }
    }

    private void expandVectorDestructuring(IPersistentVector pattern, int sourceSlot,
                                           List<Integer> slots, List<ExpressionNode> values) {
        // [a b & rest :as all]
        int positionalIndex = 0;
        for (int i = 0; i < pattern.count(); i++) {
            Object elem = pattern.nth(i);
            if (elem instanceof Symbol sym && sym.getName().equals("&")) {
                // Variadic: next element gets the rest
                i++;
                if (i >= pattern.count()) throw err("destructuring: missing name after &");
                Object restForm = pattern.nth(i);
                ExpressionNode restExpr = makeNthnextNode(sourceSlot, positionalIndex);
                if (restForm instanceof Symbol restSym) {
                    slots.add(currentScope.addLocal(restSym.getName()));
                    values.add(restExpr);
                } else {
                    int tmpSlot = currentScope.addLocal("__rest_tmp_" + positionalIndex);
                    slots.add(tmpSlot);
                    values.add(restExpr);
                    expandDestructuring(restForm, tmpSlot, slots, values);
                }
            } else if ((elem instanceof Keyword kw && kw.getName().equals("as")) ||
                       (elem instanceof Symbol sym2 && sym2.getName().equals(":as"))) {
                // :as binds the whole collection
                i++;
                if (i >= pattern.count()) throw err("destructuring: missing name after :as");
                Symbol asSym = (Symbol) pattern.nth(i);
                slots.add(currentScope.addLocal(asSym.getName()));
                values.add(new ReadLocalNode(sourceSlot));
            } else if (elem instanceof Symbol sym) {
                // Simple positional binding
                slots.add(currentScope.addLocal(sym.getName()));
                values.add(makeNthNode(sourceSlot, positionalIndex));
                positionalIndex++;
            } else {
                // Nested destructuring
                int tmpSlot = currentScope.addLocal("__vec_tmp_" + positionalIndex);
                slots.add(tmpSlot);
                values.add(makeNthNode(sourceSlot, positionalIndex));
                expandDestructuring(elem, tmpSlot, slots, values);
                positionalIndex++;
            }
        }
    }

    private void expandMapDestructuring(IPersistentMap pattern, int sourceSlot,
                                        List<Integer> slots, List<ExpressionNode> values) {
        // {:keys [a b] :strs [c] :or {a 1} :as all}
        // or {localName :mapKey, ...}
        Object keysVec = pattern.valAt(Keyword.intern("keys"));
        Object strsVec = pattern.valAt(Keyword.intern("strs"));
        Object orMap = pattern.valAt(Keyword.intern("or"));
        Object asName = pattern.valAt(Keyword.intern("as"));
        IPersistentMap defaults = (orMap instanceof IPersistentMap m) ? m : null;

        // :keys [a b] -> bind a to (:a source), b to (:b source)
        if (keysVec instanceof IPersistentVector kv) {
            for (int i = 0; i < kv.count(); i++) {
                Symbol sym = (Symbol) kv.nth(i);
                Keyword key = Keyword.intern(sym.getName());
                ExpressionNode getExpr = makeGetNode(sourceSlot, key, defaults, sym);
                slots.add(currentScope.addLocal(sym.getName()));
                values.add(getExpr);
            }
        }

        // :strs [a b] -> bind a to ("a" source), b to ("b" source)
        if (strsVec instanceof IPersistentVector sv) {
            for (int i = 0; i < sv.count(); i++) {
                Symbol sym = (Symbol) sv.nth(i);
                String key = sym.getName();
                ExpressionNode getExpr = makeGetNodeStr(sourceSlot, key, defaults, sym);
                slots.add(currentScope.addLocal(sym.getName()));
                values.add(getExpr);
            }
        }

        // Explicit bindings: {localName :mapKey}
        for (ISeq s = pattern.seq(); s != null; s = s.next()) {
            IMapEntry entry = (IMapEntry) s.first();
            Object k = entry.key();
            Object v = entry.val();
            // Skip :keys, :strs, :or, :as
            if (k instanceof Keyword kw) {
                String kwName = kw.getName();
                if (kwName.equals("keys") || kwName.equals("strs") || kwName.equals("syms") ||
                        kwName.equals("or") || kwName.equals("as"))
                    continue;
            }
            // {localSym :key} or {localSym "key"}
            if (k instanceof Symbol localSym) {
                ExpressionNode getExpr;
                if (v instanceof Keyword kw) {
                    getExpr = makeGetNode(sourceSlot, kw, defaults, localSym);
                } else {
                    getExpr = makeGetNodeObj(sourceSlot, v, defaults, localSym);
                }
                if (localSym.getName().equals("_")) continue; // skip _ bindings
                slots.add(currentScope.addLocal(localSym.getName()));
                values.add(getExpr);
            }
        }

        // :as binds the whole map
        if (asName instanceof Symbol asSym) {
            slots.add(currentScope.addLocal(asSym.getName()));
            values.add(new ReadLocalNode(sourceSlot));
        }
    }

    // Helper: (nth source index)
    private ExpressionNode makeNthNode(int sourceSlot, int index) {
        return new InvokeNode(
                new SymbolNode(context, "nth"),
                new ExpressionNode[]{new ReadLocalNode(sourceSlot), new LongLiteralNode(index)});
    }

    // Helper: (nthnext source index) or (drop index source)
    private ExpressionNode makeNthnextNode(int sourceSlot, int index) {
        return new InvokeNode(
                new SymbolNode(context, "drop"),
                new ExpressionNode[]{new LongLiteralNode(index), new ReadLocalNode(sourceSlot)});
    }

    // Helper: (get source key) or (get source key default)
    private ExpressionNode makeGetNode(int sourceSlot, Keyword key, IPersistentMap defaults, Symbol sym) {
        ExpressionNode[] getArgs;
        Object defaultVal = defaults != null ? defaults.valAt(sym) : null;
        if (defaultVal != null) {
            getArgs = new ExpressionNode[]{
                    new ReadLocalNode(sourceSlot), new KeywordLiteralNode(key), analyze(defaultVal)};
        } else {
            getArgs = new ExpressionNode[]{
                    new ReadLocalNode(sourceSlot), new KeywordLiteralNode(key)};
        }
        return new InvokeNode(new SymbolNode(context, "get"), getArgs);
    }

    private ExpressionNode makeGetNodeStr(int sourceSlot, String key, IPersistentMap defaults, Symbol sym) {
        ExpressionNode[] getArgs;
        Object defaultVal = defaults != null ? defaults.valAt(sym) : null;
        if (defaultVal != null) {
            getArgs = new ExpressionNode[]{
                    new ReadLocalNode(sourceSlot), new StringLiteralNode(key), analyze(defaultVal)};
        } else {
            getArgs = new ExpressionNode[]{
                    new ReadLocalNode(sourceSlot), new StringLiteralNode(key)};
        }
        return new InvokeNode(new SymbolNode(context, "get"), getArgs);
    }

    private ExpressionNode makeGetNodeObj(int sourceSlot, Object key, IPersistentMap defaults, Symbol sym) {
        ExpressionNode[] getArgs;
        Object defaultVal = defaults != null ? defaults.valAt(sym) : null;
        if (defaultVal != null) {
            getArgs = new ExpressionNode[]{
                    new ReadLocalNode(sourceSlot), analyze(key), analyze(defaultVal)};
        } else {
            getArgs = new ExpressionNode[]{
                    new ReadLocalNode(sourceSlot), analyze(key)};
        }
        return new InvokeNode(new SymbolNode(context, "get"), getArgs);
    }

    // --- Special Forms ---

    private ExpressionNode analyzeIf(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("if: missing condition");
        ExpressionNode cond = analyze(args.first());
        args = args.next();
        if (args == null) throw err("if: missing then branch");
        ExpressionNode then = analyze(args.first());
        args = args.next();
        ExpressionNode elseNode = (args != null) ? analyze(args.first()) : new NilNode();
        return new IfNode(cond, then, elseNode);
    }

    private ExpressionNode analyzeDo(ISeq seq) {
        ISeq args = seq.next();
        List<ExpressionNode> nodes = new ArrayList<>();
        while (args != null) { nodes.add(analyze(args.first())); args = args.next(); }
        if (nodes.isEmpty()) return new NilNode();
        if (nodes.size() == 1) return nodes.get(0);
        return new DoNode(nodes.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeDef(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("def: missing name");
        if (!(args.first() instanceof Symbol)) throw err("def: name must be a symbol");
        String name = ((Symbol) args.first()).getName();
        args = args.next();
        ExpressionNode valueNode = (args != null) ? analyze(args.first()) : new NilNode();
        return new DefNode(context, name, valueNode);
    }

    private ExpressionNode analyzeLet(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("let*: missing bindings");
        if (!(args.first() instanceof IPersistentVector))
            throw err("let*: bindings must be a vector");
        IPersistentVector bindings = (IPersistentVector) args.first();
        if (bindings.count() % 2 != 0) throw err("let*: odd number of binding forms");

        List<Integer> slotList = new ArrayList<>();
        List<ExpressionNode> valueList = new ArrayList<>();
        for (int i = 0; i < bindings.count(); i += 2) {
            Object bindingForm = bindings.nth(i);
            ExpressionNode initExpr = analyze(bindings.nth(i + 1));
            if (bindingForm instanceof Symbol sym) {
                slotList.add(currentScope.addLocal(sym.getName()));
                valueList.add(initExpr);
            } else {
                // Destructuring: store init in temp, then expand
                int tempSlot = currentScope.addLocal("__destructure_tmp_" + i);
                slotList.add(tempSlot);
                valueList.add(initExpr);
                expandDestructuring(bindingForm, tempSlot, slotList, valueList);
            }
        }
        int[] slots = slotList.stream().mapToInt(Integer::intValue).toArray();
        ExpressionNode[] values = valueList.toArray(new ExpressionNode[0]);
        return new LetNode(slots, values, analyzeBody(args.next()));
    }

    private ExpressionNode analyzeFn(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("fn*: missing parameters");

        String fnName = null;
        if (args.first() instanceof Symbol) {
            fnName = ((Symbol) args.first()).getName();
            args = args.next();
            if (args == null) throw err("fn*: missing parameters after name");
        }

        // Multi-arity: first arg is a list, not a vector
        if (args.first() instanceof ISeq) {
            return analyzeMultiArityFn(fnName, args);
        }

        // Single arity
        return analyzeSingleArityFn(fnName, args);
    }

    private ExpressionNode analyzeSingleArityFn(String fnName, ISeq args) {
        if (!(args.first() instanceof IPersistentVector))
            throw err("fn*: parameters must be a vector");
        IPersistentVector params = (IPersistentVector) args.first();

        Scope outerScope = currentScope;
        currentScope = new Scope(outerScope);

        List<Integer> destructSlots = new ArrayList<>();
        List<ExpressionNode> destructValues = new ArrayList<>();

        int[] result = parseParams(params, destructSlots, destructValues);
        int[] paramSlots = Arrays.copyOf(result, result.length - 1);
        int variadicSlot = result[result.length - 1];

        ExpressionNode bodyNode = analyzeBody(args.next());

        // Wrap body with destructuring bindings if any
        if (!destructSlots.isEmpty()) {
            int[] dSlots = destructSlots.stream().mapToInt(Integer::intValue).toArray();
            ExpressionNode[] dValues = destructValues.toArray(new ExpressionNode[0]);
            bodyNode = new LetNode(dSlots, dValues, bodyNode);
        }

        int[] outerCaptureSlots = currentScope.getOuterCaptureSlots();
        int[] innerCaptureSlots = currentScope.getInnerCaptureSlots();
        FrameDescriptor fd = currentScope.buildDescriptor();

        FnBodyNode fnBody = new FnBodyNode(language, fd, fnName,
                paramSlots, variadicSlot, innerCaptureSlots, bodyNode);

        currentScope = outerScope;
        return new FnNode(fnName, fnBody.getCallTarget(), outerCaptureSlots);
    }

    private ExpressionNode analyzeMultiArityFn(String fnName, ISeq clauses) {
        Scope outerScope = currentScope;
        List<FnNode> arityFnNodes = new ArrayList<>();
        List<Integer> arities = new ArrayList<>();
        int variadicIndex = -1;

        while (clauses != null) {
            Object clause = clauses.first();
            if (!(clause instanceof ISeq clauseSeq))
                throw err("fn*: arity clause must be a list");

            if (!(clauseSeq.first() instanceof IPersistentVector))
                throw err("fn*: arity parameters must be a vector");
            IPersistentVector params = (IPersistentVector) clauseSeq.first();
            ISeq body = clauseSeq.next();

            currentScope = new Scope(outerScope);

            List<Integer> dSlots = new ArrayList<>();
            List<ExpressionNode> dVals = new ArrayList<>();
            int[] result = parseParams(params, dSlots, dVals);
            int[] paramSlots = Arrays.copyOf(result, result.length - 1);
            int varSlot = result[result.length - 1];

            ExpressionNode bodyNode = analyzeBody(body);
            if (!dSlots.isEmpty()) {
                bodyNode = new LetNode(
                        dSlots.stream().mapToInt(Integer::intValue).toArray(),
                        dVals.toArray(new ExpressionNode[0]), bodyNode);
            }

            int[] outerCaptures = currentScope.getOuterCaptureSlots();
            int[] innerCaptures = currentScope.getInnerCaptureSlots();
            FrameDescriptor fd = currentScope.buildDescriptor();

            FnBodyNode fnBody = new FnBodyNode(language, fd, fnName,
                    paramSlots, varSlot, innerCaptures, bodyNode);
            FnNode fnNode = new FnNode(fnName, fnBody.getCallTarget(), outerCaptures);
            arityFnNodes.add(fnNode);

            if (varSlot >= 0) {
                variadicIndex = arities.size();
            }
            arities.add(paramSlots.length);

            clauses = clauses.next();
        }

        currentScope = outerScope;
        return new MultiArityFnNode(fnName,
                arityFnNodes.toArray(new FnNode[0]),
                arities.stream().mapToInt(Integer::intValue).toArray(),
                variadicIndex);
    }

    /**
     * Parse parameters, returning array where last element is variadic slot (-1 if none).
     * destructBindings is populated with any destructuring that needs to happen.
     */
    private int[] parseParams(IPersistentVector params) {
        return parseParams(params, null, null);
    }

    private int[] parseParams(IPersistentVector params,
                              List<Integer> destructSlots,
                              List<ExpressionNode> destructValues) {
        List<Integer> positional = new ArrayList<>();
        int varSlot = -1;
        for (int i = 0; i < params.count(); i++) {
            Object param = params.nth(i);
            if (param instanceof Symbol sym && "&".equals(sym.getName())) {
                i++;
                if (i >= params.count()) throw err("fn*: missing parameter after &");
                Object varParam = params.nth(i);
                if (varParam instanceof Symbol varSym) {
                    varSlot = currentScope.addLocal(varSym.getName());
                } else {
                    // Destructured variadic param
                    String tmpName = "__var_destructure_" + i;
                    varSlot = currentScope.addLocal(tmpName);
                    if (destructSlots != null) {
                        expandDestructuring(varParam, varSlot, destructSlots, destructValues);
                    }
                }
            } else if (param instanceof Symbol sym) {
                positional.add(currentScope.addLocal(sym.getName()));
            } else {
                // Destructured positional param
                String tmpName = "__param_destructure_" + i;
                int tmpSlot = currentScope.addLocal(tmpName);
                positional.add(tmpSlot);
                if (destructSlots != null) {
                    expandDestructuring(param, tmpSlot, destructSlots, destructValues);
                }
            }
        }
        int[] result = new int[positional.size() + 1];
        for (int i = 0; i < positional.size(); i++) result[i] = positional.get(i);
        result[positional.size()] = varSlot;
        return result;
    }

    private ExpressionNode analyzeQuote(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("quote: missing form");
        return new QuoteNode(convertToRuntime(args.first()));
    }

    private ExpressionNode analyzeRecur(ISeq seq) {
        ISeq args = seq.next();
        List<ExpressionNode> nodes = new ArrayList<>();
        while (args != null) { nodes.add(analyze(args.first())); args = args.next(); }
        return new RecurNode(nodes.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeLoop(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("loop*: missing bindings");
        if (!(args.first() instanceof IPersistentVector))
            throw err("loop*: bindings must be a vector");
        IPersistentVector bindings = (IPersistentVector) args.first();
        if (bindings.count() % 2 != 0) throw err("loop*: odd number of binding forms");

        int count = bindings.count() / 2;
        int[] slots = new int[count];
        ExpressionNode[] values = new ExpressionNode[count];
        for (int i = 0; i < count; i++) {
            if (!(bindings.nth(i * 2) instanceof Symbol))
                throw err("loop*: binding name must be a symbol");
            slots[i] = currentScope.addLocal(((Symbol) bindings.nth(i * 2)).getName());
            values[i] = analyze(bindings.nth(i * 2 + 1));
        }
        return new LoopNode(slots, values, analyzeBody(args.next()));
    }

    private ExpressionNode analyzeAnd(ISeq seq) {
        return new AndNode(analyzeArgList(seq.next()));
    }

    private ExpressionNode analyzeOr(ISeq seq) {
        return new OrNode(analyzeArgList(seq.next()));
    }

    private ExpressionNode analyzeWhen(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("when: missing condition");
        return new IfNode(analyze(args.first()), analyzeBody(args.next()), new NilNode());
    }

    private ExpressionNode analyzeCond(ISeq seq) {
        return buildCondChain(seq.next());
    }

    private ExpressionNode buildCondChain(ISeq pairs) {
        if (pairs == null) return new NilNode();
        Object test = pairs.first();
        ISeq rest = pairs.next();
        if (rest == null) throw err("cond: odd number of forms");
        ExpressionNode thenNode = analyze(rest.first());
        ExpressionNode elseNode = buildCondChain(rest.next());
        if (test instanceof Keyword kw && "else".equals(kw.getName())) return thenNode;
        return new IfNode(analyze(test), thenNode, elseNode);
    }

    private ExpressionNode analyzeDefn(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("defn: missing name");
        if (!(args.first() instanceof Symbol)) throw err("defn: name must be a symbol");
        Symbol nameSym = (Symbol) args.first();
        ISeq fnForm = RT.cons(Symbol.intern("fn*"), args);
        ExpressionNode fnNode = analyzeFn(fnForm);
        return new DefNode(context, nameSym.getName(), fnNode);
    }

    // --- try/catch/throw ---

    private ExpressionNode analyzeTry(ISeq seq) {
        ISeq args = seq.next();
        List<ExpressionNode> bodyForms = new ArrayList<>();
        List<CatchHandlerNode> catchHandlers = new ArrayList<>();
        ExpressionNode finallyNode = null;

        while (args != null) {
            Object form = args.first();
            if (form instanceof ISeq formSeq) {
                Object head = formSeq.first();
                if (head instanceof Symbol s) {
                    if ("catch".equals(s.getName())) {
                        catchHandlers.add(analyzeCatch(formSeq));
                        args = args.next();
                        continue;
                    }
                    if ("finally".equals(s.getName())) {
                        finallyNode = analyzeBody(formSeq.next());
                        args = args.next();
                        continue;
                    }
                }
            }
            bodyForms.add(analyze(form));
            args = args.next();
        }

        ExpressionNode body = bodyForms.size() == 1 ? bodyForms.get(0) :
                new DoNode(bodyForms.toArray(new ExpressionNode[0]));

        return new TryNode(body,
                catchHandlers.toArray(new CatchHandlerNode[0]),
                finallyNode);
    }

    private CatchHandlerNode analyzeCatch(ISeq seq) {
        // (catch ExceptionType e body...)
        ISeq args = seq.next();
        if (args == null) throw err("catch: missing exception type");

        Class<? extends Throwable> exClass = resolveExceptionClass(args.first());
        args = args.next();
        if (args == null) throw err("catch: missing binding name");
        if (!(args.first() instanceof Symbol))
            throw err("catch: binding must be a symbol");

        int slot = currentScope.addLocal(((Symbol) args.first()).getName());
        ExpressionNode handler = analyzeBody(args.next());
        return new CatchHandlerNode(exClass, slot, handler);
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Throwable> resolveExceptionClass(Object name) {
        String className;
        if (name instanceof Symbol s) {
            className = s.getName();
        } else {
            className = name.toString();
        }
        return switch (className) {
            case "Exception" -> Exception.class;
            case "RuntimeException" -> RuntimeException.class;
            case "Throwable" -> Throwable.class;
            case "Error" -> Error.class;
            case "ArithmeticException" -> ArithmeticException.class;
            case "NullPointerException" -> NullPointerException.class;
            case "IndexOutOfBoundsException" -> IndexOutOfBoundsException.class;
            case "IllegalArgumentException" -> IllegalArgumentException.class;
            default -> {
                try {
                    yield (Class<? extends Throwable>) Class.forName(className);
                } catch (ClassNotFoundException e) {
                    yield RuntimeException.class;
                }
            }
        };
    }

    private ExpressionNode analyzeThrow(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("throw: missing expression");
        return new ThrowNode(analyze(args.first()));
    }

    // --- Invoke ---

    private ExpressionNode analyzeInvoke(ISeq seq) {
        ExpressionNode fn = analyze(seq.first());
        return new InvokeNode(fn, analyzeArgList(seq.next()));
    }

    // --- Collections ---

    private ExpressionNode analyzeVector(IPersistentVector vec) {
        ExpressionNode[] elements = new ExpressionNode[vec.count()];
        for (int i = 0; i < vec.count(); i++) elements[i] = analyze(vec.nth(i));
        return new VectorNode(elements);
    }

    private ExpressionNode analyzeMap(IPersistentMap map) {
        List<ExpressionNode> kvNodes = new ArrayList<>();
        for (ISeq s = map.seq(); s != null; s = s.next()) {
            IMapEntry entry = (IMapEntry) s.first();
            kvNodes.add(analyze(entry.key()));
            kvNodes.add(analyze(entry.val()));
        }
        return new MapNode(kvNodes.toArray(new ExpressionNode[0]));
    }

    // --- Helpers ---

    private ExpressionNode analyzeBody(ISeq body) {
        if (body == null) return new NilNode();
        List<ExpressionNode> nodes = new ArrayList<>();
        while (body != null) { nodes.add(analyze(body.first())); body = body.next(); }
        if (nodes.size() == 1) return nodes.get(0);
        return new DoNode(nodes.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode[] analyzeArgList(ISeq args) {
        List<ExpressionNode> nodes = new ArrayList<>();
        while (args != null) { nodes.add(analyze(args.first())); args = args.next(); }
        return nodes.toArray(new ExpressionNode[0]);
    }

    private Object convertToRuntime(Object form) {
        return form == null ? ClojureNil.INSTANCE : form;
    }

    private static RuntimeException err(String msg) {
        return new RuntimeException(msg);
    }
}
