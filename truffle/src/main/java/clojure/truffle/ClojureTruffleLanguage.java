package clojure.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.source.Source;
import clojure.truffle.nodes.ExpressionNode;
import clojure.truffle.nodes.ProgramRootNode;
import clojure.truffle.parser.Analyzer;

@TruffleLanguage.Registration(
        id = ClojureTruffleLanguage.ID,
        name = "Clojure",
        defaultMimeType = "application/x-clojure",
        characterMimeTypes = {"application/x-clojure"},
        contextPolicy = TruffleLanguage.ContextPolicy.SHARED)
public class ClojureTruffleLanguage extends TruffleLanguage<ClojureContext> {

    public static final String ID = "clj";

    private volatile ClojureContext currentContext;

    @Override
    protected ClojureContext createContext(Env env) {
        ClojureContext ctx = new ClojureContext(this, env);
        this.currentContext = ctx;
        return ctx;
    }

    @Override
    protected CallTarget parse(ParsingRequest request) throws Exception {
        Source source = request.getSource();
        String code = source.getCharacters().toString();
        Analyzer analyzer = new Analyzer(this);
        analyzer.setContext(currentContext);
        ExpressionNode[] body = analyzer.analyzeProgram(code);
        ProgramRootNode rootNode = new ProgramRootNode(
                this, analyzer.getFrameDescriptor(), body);
        return rootNode.getCallTarget();
    }
}
