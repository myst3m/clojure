package clojure.truffle.nodes.interop;

import clojure.truffle.nodes.ExpressionNode;
import com.oracle.truffle.api.frame.VirtualFrame;

import java.lang.reflect.Field;

public class JavaStaticFieldNode extends ExpressionNode {

    private final Class<?> clazz;
    private final String fieldName;

    public JavaStaticFieldNode(Class<?> clazz, String fieldName) {
        this.clazz = clazz;
        this.fieldName = fieldName;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        try {
            Field field = clazz.getField(fieldName);
            Object result = field.get(null);
            return JavaInteropUtil.wrapResult(result);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("No such field: " + clazz.getName() + "/" + fieldName);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot access field: " + clazz.getName() + "/" + fieldName);
        }
    }
}
