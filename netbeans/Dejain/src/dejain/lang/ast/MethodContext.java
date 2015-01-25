package dejain.lang.ast;

import dejain.lang.ClassResolver;
import dejain.runtime.asm.CommonClassTransformer;
import dejain.runtime.asm.IfAllTransformer;
import java.util.List;
import org.objectweb.asm.tree.MethodNode;

public class MethodContext implements MemberContext {
    public boolean isAdd;
    public MethodSelectorContext selector;
    public List<StatementContext> body;

    public MethodContext(boolean isAdd, MethodSelectorContext selector, List<StatementContext> body) {
        this.isAdd = isAdd;
        this.selector = selector;
        this.body = body;
    }

    @Override
    public void accept(MemberVisitor visitor) {
        visitor.visitMethod(this);
    }

    @Override
    public void resolve(ClassResolver resolver, List<dejain.lang.ASMCompiler.Message> errorMessages) {
//        selector.resolve(resolver, errorMessages);
//        body.forEach(s -> s.resolve(resolver, errorMessages));
    }

    public void populate(IfAllTransformer<MethodNode> transformer) {
        if(!isAdd) {
            selector.populate(transformer);
        } else {
            
        }
    }

    @Override
    public void populate(CommonClassTransformer transformer) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
}
