package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.profiles.ConditionProfile;
import clojure.truffle.ClojureContext;

public class IfNode extends ExpressionNode {

    @Child private ExpressionNode conditionNode;
    @Child private ExpressionNode thenNode;
    @Child private ExpressionNode elseNode;
    private final ConditionProfile conditionProfile = ConditionProfile.createBinaryProfile();

    public IfNode(ExpressionNode conditionNode, ExpressionNode thenNode, ExpressionNode elseNode) {
        this.conditionNode = conditionNode;
        this.thenNode = thenNode;
        this.elseNode = elseNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object condValue = conditionNode.executeGeneric(frame);
        if (conditionProfile.profile(ClojureContext.isTruthy(condValue))) {
            return thenNode.executeGeneric(frame);
        } else {
            return elseNode.executeGeneric(frame);
        }
    }
}
