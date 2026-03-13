package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.ClojureContext;
import clojure.truffle.runtime.ClojureNil;
import clojure.truffle.runtime.ClojureProtocol;
import clojure.truffle.parser.Analyzer;

import java.util.HashMap;
import java.util.Map;

public class ExtendTypeNode extends ExpressionNode {
    private final ClojureContext context;
    @Child private ExpressionNode typeNode;
    @Child private ExpressionNode protoNode;
    private final Map<String, Object> methodForms;
    private final Analyzer analyzer;

    public ExtendTypeNode(ClojureContext context, ExpressionNode typeNode,
                          ExpressionNode protoNode, Map<String, Object> methodForms,
                          Analyzer analyzer) {
        this.context = context;
        this.typeNode = typeNode;
        this.protoNode = protoNode;
        this.methodForms = methodForms;
        this.analyzer = analyzer;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object type = typeNode.executeGeneric(frame);
        Object proto = protoNode.executeGeneric(frame);
        return doExtendType(type, proto, methodForms, analyzer, frame);
    }

    @TruffleBoundary
    private static Object doExtendType(Object type, Object proto, Map<String, Object> methodForms,
                                        Analyzer analyzer, Object frameObj) {
        VirtualFrame frame = (VirtualFrame) frameObj;
        if (!(proto instanceof ClojureProtocol protocol)) {
            throw new RuntimeException("extend-type: second arg must be a protocol, got: " + proto);
        }

        Object typeKey;
        if (type instanceof Class<?> clazz) {
            typeKey = clazz;
        } else if (type instanceof String s) {
            typeKey = s;
        } else {
            typeKey = type.toString();
        }

        Map<String, Object> methods = new HashMap<>();
        for (var entry : methodForms.entrySet()) {
            ExpressionNode fnNode = analyzer.analyzePublic(entry.getValue());
            Object fn = fnNode.executeGeneric(frame);
            methods.put(entry.getKey(), fn);
        }

        protocol.extend(typeKey, methods);
        return ClojureNil.INSTANCE;
    }
}
