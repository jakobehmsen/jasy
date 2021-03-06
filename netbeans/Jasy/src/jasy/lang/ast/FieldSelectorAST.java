package jasy.lang.ast;

import jasy.lang.ASMCompiler;
import jasy.lang.ClassResolver;
import jasy.runtime.asm.CommonClassTransformer;
import jasy.runtime.asm.IfAllTransformer;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldNode;

public class FieldSelectorAST {
    public Integer accessModifier;
    public Boolean isStatic;
    public TypeAST fieldType;
    public String name;

    public FieldSelectorAST(Integer accessModifier, Boolean isStatic, TypeAST fieldType, String name) {
        this.accessModifier = accessModifier;
        this.isStatic = isStatic;
        this.fieldType = fieldType;
        this.name = name;
    }

    public void resolve(Scope thisClass, TypeAST expectedResultType, ClassResolver resolver, ClassLoader classLoader, List<ASMCompiler.Message> errorMessages) {
        if(fieldType != null)
            fieldType.resolve(thisClass, expectedResultType, resolver, classLoader, errorMessages);
    }

    public void populate(CommonClassTransformer transformer) {
        
    }

    public void populate(IfAllTransformer<Transformation<FieldNode>> transformer) {
        if(accessModifier != null)
            transformer.addPredicate(f -> (f.getTarget().access & accessModifier) != 0);
        if(isStatic != null)
            transformer.addPredicate(f -> (f.getTarget().access & Opcodes.ACC_STATIC) != 0);
        if(fieldType != null)
            transformer.addPredicate(f -> Type.getType(f.getTarget().desc).getClassName().equals(fieldType.getDescriptor()));
        if(name != null)
            transformer.addPredicate(f -> f.getTarget().name.equals(name));
    }
}
