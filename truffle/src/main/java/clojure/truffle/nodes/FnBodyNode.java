package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import clojure.lang.IPersistentCollection;
import clojure.lang.PersistentList;
import clojure.truffle.ClojureTruffleLanguage;
import clojure.truffle.runtime.ClojureFunction;
import clojure.truffle.runtime.ClojureNil;
import clojure.truffle.runtime.RecurException;

public class FnBodyNode extends RootNode {

    @Child private ExpressionNode bodyNode;
    private final int[] paramSlots;
    private final int paramCount;
    private final int variadicSlot;     // -1 if not variadic
    private final int[] capturedSlots;
    private final String name;
    private final int selfSlot;         // -1 if not a named fn

    public FnBodyNode(ClojureTruffleLanguage language, FrameDescriptor frameDescriptor,
                      String name, int[] paramSlots, int variadicSlot,
                      int[] capturedSlots, ExpressionNode bodyNode) {
        this(language, frameDescriptor, name, paramSlots, variadicSlot, capturedSlots, bodyNode, -1);
    }

    public FnBodyNode(ClojureTruffleLanguage language, FrameDescriptor frameDescriptor,
                      String name, int[] paramSlots, int variadicSlot,
                      int[] capturedSlots, ExpressionNode bodyNode, int selfSlot) {
        super(language, frameDescriptor);
        this.name = name;
        this.paramSlots = paramSlots;
        this.paramCount = paramSlots.length;
        this.variadicSlot = variadicSlot;
        this.capturedSlots = capturedSlots;
        this.bodyNode = bodyNode;
        this.selfSlot = selfSlot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        ClojureFunction self = (ClojureFunction) args[0];

        // Copy positional params (args[1..paramCount])
        for (int i = 0; i < paramCount; i++) {
            frame.setObject(paramSlots[i], args[i + 1]);
        }

        // Handle variadic parameter
        if (variadicSlot >= 0) {
            collectVariadic(frame, args, paramCount + 1);
        }

        // Write self-reference for named fns
        if (selfSlot >= 0) {
            frame.setObject(selfSlot, self);
        }

        // Copy captured values
        Object[] captured = self.getCapturedValues();
        if (captured != null && capturedSlots != null) {
            for (int i = 0; i < capturedSlots.length; i++) {
                frame.setObject(capturedSlots[i], captured[i]);
            }
        }

        // Execute body with recur support
        while (true) {
            try {
                return bodyNode.executeGeneric(frame);
            } catch (RecurException e) {
                Object[] newValues = e.getValues();
                for (int i = 0; i < paramCount; i++) {
                    frame.setObject(paramSlots[i], newValues[i]);
                }
                if (variadicSlot >= 0 && newValues.length > paramCount) {
                    frame.setObject(variadicSlot, newValues[paramCount]);
                }
            }
        }
    }

    private void collectVariadic(VirtualFrame frame, Object[] args, int restStart) {
        if (args.length > restStart) {
            IPersistentCollection list = PersistentList.EMPTY;
            for (int i = args.length - 1; i >= restStart; i--) {
                list = list.cons(args[i]);
            }
            frame.setObject(variadicSlot, list);
        } else {
            frame.setObject(variadicSlot, ClojureNil.INSTANCE);
        }
    }

    @Override
    public String getName() {
        return name != null ? name : "<fn>";
    }
}
