package jasy.lang.ast;

import jasy.lang.ASMCompiler;
import jasy.lang.ASMCompiler.Region;
import jasy.lang.ClassResolver;
import jasy.runtime.asm.CommonClassTransformer;
import jasy.runtime.asm.CompositeTransformer;
import jasy.runtime.asm.IfAllTransformer;
import jasy.runtime.asm.IfAllWithin;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;

public class FieldAST extends AbstractAST implements MemberAST {
    public String variableId;
    public boolean isAdd;
    public FieldSelectorAST selector;
    public ExpressionAST value;

    public FieldAST(Region region, String variableId, boolean isAdd, FieldSelectorAST selector, ExpressionAST value) {
        super(region);
        this.variableId = variableId;
        this.isAdd = isAdd;
        this.selector = selector;
        this.value = value;
    }
    
    @Override
    public void accept(MemberVisitor visitor) {
        visitor.visitField(this);
    }

    @Override
    public void resolve(Scope thisClass, TypeAST expectedResultType, ClassResolver resolver, ClassLoader classLoader, List<ASMCompiler.Message> errorMessages) {
        selector.resolve(thisClass, expectedResultType, resolver, classLoader, errorMessages);
        if(value != null)
            value.resolve(thisClass, expectedResultType, resolver, classLoader, errorMessages);
    }

    @Override
    public void populate(CommonClassTransformer transformer) {
        if(!isAdd) {
            selector.populate(transformer);
        }
    }

    public void populate(ClassResolver classResolver, ClassLoader classLoader, CompositeTransformer<Transformation<ClassNode>> classTransformer, IfAllTransformer<Transformation<FieldNode>> transformer) {
        if(!isAdd) {
            IfAllTransformer<Transformation<ClassNode>> fieldsTransformerSequence = new IfAllTransformer<>();
            
            fieldsTransformerSequence.addTransformer(c -> {
                if(variableId != null)
                    c.putVariableValue(variableId, new ArrayList<>());
                return() -> { };
            });
            
            Predicate<FieldNode> fieldFilter = f -> true;
            
            if(selector.accessModifier != null)
                fieldFilter = fieldFilter.and(f -> (f.access & selector.accessModifier) != 0);
            if(selector.isStatic != null) {
                if(selector.isStatic)
                    fieldFilter = fieldFilter.and(f -> (f.access & Opcodes.ACC_STATIC) != 0);
                else
                    fieldFilter = fieldFilter.and(f -> (f.access & Opcodes.ACC_STATIC) == 0);
            }
            if(selector.fieldType != null)
                fieldFilter = fieldFilter.and(f -> Type.getType(f.desc).getClassName().equals(selector.fieldType.getDescriptor()));
            if(selector.name != null)
                fieldFilter = fieldFilter.and(f -> f.name.equals(selector.name));
            
            Predicate<FieldNode> ff = fieldFilter;
            fieldFilter = f ->
                ff.test(f);
            
            fieldsTransformerSequence.addTransformer(new IfAllWithin<>(c -> c.getTarget().fields, fieldFilter, (c, f) -> {
                if(variableId != null)
                    ((ArrayList<FieldNode>)c.getVariableValue(variableId)).add(f);
                
                return () -> { };
            }));
            
            classTransformer.addTransformer(fieldsTransformerSequence);
        } else {
            classTransformer.addTransformer(c -> {
                return () -> {
                    int fieldAccess = AST.Util.getAccessModifier(selector.accessModifier, selector.isStatic);
                    String fieldName = selector.name;
                    String fieldDescriptor = selector.fieldType.getDescriptor();
                    if(this.value != null) {
                        ((List<MethodNode>)c.getTarget().methods).stream().filter(m -> m.name.equals("<init>")).forEach(cons -> {
                            InsnList originalInstructions = cons.instructions;
                            cons.instructions = new InsnList();
                            
                            originalInstructions.accept(new MethodVisitor(Opcodes.ASM5, cons) {
                                @Override
                                public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                                    super.visitMethodInsn(opcode, owner, name, desc, itf);
                                    
                                    if(name.equals("<init>")) {
                                        GeneratorAdapter generatorAdapter = new GeneratorAdapter(cons, cons.access, cons.name, cons.desc);
                                        generatorAdapter.loadThis();
                                        PreparedAST pa = MethodAST.toExpression(new ClassNodeScope(c.getTarget()), FieldAST.this.value, classResolver, classLoader, new Hashtable<>(), new Hashtable<>());
                                        pa.generate(c, new MethodCodeGenerator(generatorAdapter, null), new InsnList());
                                        String className = c.getTarget().name;
                                        generatorAdapter.putField(Type.getType(className), selector.name, Type.getType(selector.fieldType.getDescriptor(className)));
                                    }
                                }
                            });
                        });
                    }
                    c.getTarget().fields.add(new FieldNode(fieldAccess, fieldName, fieldDescriptor, null, null));
                };
            });
        }
    }
}
