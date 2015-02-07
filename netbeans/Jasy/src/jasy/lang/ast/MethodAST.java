package jasy.lang.ast;

import jasy.lang.ASMCompiler;
import jasy.lang.ASMCompiler.Region;
import jasy.lang.ClassResolver;
import jasy.lang.SingleClassLoader;
import static jasy.lang.ast.BinaryExpressionAST.OPERATOR_ADD;
import jasy.runtime.asm.CommonClassTransformer;
import jasy.runtime.asm.CompositeTransformer;
import jasy.runtime.asm.IfAllTransformer;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.OptionalInt;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.beanutils.ConstructorUtils;
import org.apache.commons.beanutils.MethodUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

public class MethodAST extends AbstractAST implements MemberAST {
    public boolean isAdd;
    public MethodSelectorAST selector;
    public CodeAST body;
//    public List<CodeAST> body;

    public MethodAST(Region region, boolean isAdd, MethodSelectorAST selector, CodeAST body) {
        super(region);
        this.isAdd = isAdd;
        this.selector = selector;
        this.body = body;
    }

    @Override
    public void accept(MemberVisitor visitor) {
        visitor.visitMethod(this);
    }

    @Override
    public void resolve(Scope thisClass, TypeAST expectedResultType, ClassResolver resolver, List<jasy.lang.ASMCompiler.Message> errorMessages) {
        selector.resolve(thisClass, expectedResultType, resolver, errorMessages);
        body.resolve(thisClass, expectedResultType, resolver, errorMessages);
//        body.forEach(s -> 
//            s.resolve(thisClass, expectedResultType, resolver, errorMessages));
    }

    public void populate(CompositeTransformer<Transformation<ClassNode>> classTransformer, IfAllTransformer<Transformation<MethodNode>> transformer) {
        if(!isAdd) {
            selector.populate(transformer);
        } else {
            classTransformer.addTransformer(c -> {
                return () -> {
                    int methodAccess = AST.Util.getAccessModifier(selector.accessModifier, selector.isStatic);
                    String methodName = selector.name;
                    Type[] argumentTypes = selector.parameterTypes.stream()
                        .map(x -> Type.getType(x.getDescriptor(c.getTarget().name)))
                        .toArray(size -> new Type[size]);
                    String methodDescriptor = Type.getMethodDescriptor(
                        Type.getType(selector.returnType.getDescriptor(c.getTarget().name)), 
                        argumentTypes);
                    
                    MethodNode methodNode = new MethodNode(methodAccess, methodName, methodDescriptor, null, null);
                    GeneratorAdapter generatorAdapter = new GeneratorAdapter(methodNode, methodAccess, methodName, methodDescriptor);
                    MethodCodeGenerator generator = new MethodCodeGenerator(generatorAdapter, selector.returnType);

                    methodNode.visitCode();
                    generator.start();
                    toCode(c, body, generator, new InsnList() /*Something that generates a default values for non-void returns?*/);
                    generator.end();
                    methodNode.visitEnd();

                    OptionalInt existingMethodIndex =
                        IntStream.range(0, c.getTarget().methods.size())
                        .filter(i -> 
                            ((MethodNode)c.getTarget().methods.get(i)).name.equals(methodName) && 
                            ((MethodNode)c.getTarget().methods.get(i)).desc.equals(methodDescriptor))
                        .findFirst();
                    if(existingMethodIndex.isPresent())
                        c.getTarget().methods.remove(existingMethodIndex.getAsInt());

                    c.getTarget().methods.add(methodNode);
                };
            });
        }
    }

    public static void toCode(Transformation<ClassNode> c, CodeAST body, MethodCodeGenerator generator) {
        toCode(c, body, generator, new InsnList());
    }

    private static void toCode(Transformation<ClassNode> c, CodeAST body, MethodCodeGenerator generator, InsnList originalIl) {
        Hashtable<String, TypeAST> variables = new Hashtable<>();
        
        PreparedAST pa = toCode(new ClassNodeScope(c.getTarget()), body, variables);
        pa.generate(c, generator, originalIl);
        
//        body.forEach(ctx -> {
//            PreparedAST pa = toCode(new ClassNodeScope(c.getTarget()), ctx, variables);
//            pa.generate(c, generator, originalIl);
//        });
    }

    public static PreparedAST toCode(Scope thisClass, CodeAST ctx, Hashtable<String, TypeAST> variables) {
        return ctx.accept(new CodeVisitor<PreparedAST>() {
            @Override
            public PreparedAST visitReturn(ReturnAST ctx) {
                PreparedExpressionAST expression = toExpression(thisClass, ctx.expression, variables, true);
                
                return new PreparedAST() {
                    @Override
                    public void generate(Transformation<ClassNode> c, MethodCodeGenerator generator, InsnList originalIl) {
                        expression.generate(c, generator, originalIl);
                        generator.methodNode.returnValue();
                    }

                    @Override
                    public TypeAST returns() {
                        return expression.resultType();
                    }
                };
                
//                ctx.expression.accept(this);
//                expression.generate(null, null, null, isAdd);
//                generator.methodNode.returnValue();
            }

            @Override
            public PreparedAST visitBlock(BlockAST ctx) {
                List<PreparedAST> pas = ctx.statements.stream().map(s -> s.accept(this)).collect(Collectors.toList());
                
                return new PreparedAST() {
                    @Override
                    public void generate(Transformation<ClassNode> c, MethodCodeGenerator generator, InsnList originalIl) {
                        pas.forEach(pa -> pa.generate(c, generator, originalIl));
                    }
                };
            }

            @Override
            public PreparedAST visitStringLiteral(StringLiteralAST ctx) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PreparedAST visitIntLiteral(IntLiteralAST ctx) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PreparedAST visitLongLiteral(LongLiteralAST ctx) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PreparedAST visitBinaryExpression(BinaryExpressionAST ctx) {
                throw new UnsupportedOperationException();
//                ctx.lhs.accept(this);
//                ctx.rhs.accept(this);
//                
//                switch(ctx.resultType().getSimpleName(c.getTarget().name)) {
//                    case "String":
//                        generator.methodNode.invokeVirtual(Type.getType("java/lang/String"), new Method("concat", "(Ljava/lang/String;)Ljava/lang/String;"));
//                        break;
//                    case "short":
//                        generator.methodNode.visitInsn(Opcodes.IADD);
//                        break;
//                    case "int":
//                        generator.methodNode.visitInsn(Opcodes.IADD);
//                        break;
//                    case "long":
//                        generator.methodNode.visitInsn(Opcodes.LADD);
//                        break;
//                    case "float":
//                        generator.methodNode.visitInsn(Opcodes.FADD);
//                        break;
//                    case "double":
//                        generator.methodNode.visitInsn(Opcodes.DADD);
//                        break;
//                }
            }

            @Override
            public PreparedAST visitInvocation(InvocationAST ctx) {
                return toExpression(thisClass, ctx, variables, false);
//                ctx.arguments.forEach(a -> a.accept(this));
//                
//                Type[] argumentTypes = ctx.arguments.stream().map(a -> Type.getType(a.resultType().getDescriptor(c.getTarget().name))).toArray(size -> new Type[size]);
//                Type returnType = Type.getType(ctx.resultType().getDescriptor(c.getTarget().name));
//                Method method = new Method(ctx.methodName, returnType, argumentTypes);
//                
//                if(ctx.target != null)
//                    generator.methodNode.invokeVirtual(Type.getType(ctx.target.resultType().getDescriptor(c.getTarget().name)), method);
//                else
//                    generator.methodNode.invokeStatic(Type.getType(ctx.declaringClass.getDescriptor(c.getTarget().name)), method);
            }

            @Override
            public PreparedAST visitFieldSet(FieldSetAST ctx) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PreparedAST visitMeta(MetaExpressionAST ctx) {
//                
//                Class<?> generatorClass2 = ctx.bodyAsMethod.getDeclaringClass();
//                try {
//                    Object generator = generatorClass2.newInstance();
//
//                    // 2) Evaluate the generated code which result in a String
//                    for(String fieldName: ctx.mp.metaScope.getFieldNames()) {
//                        try {
//                            Field f = generatorClass2.getField(fieldName);
//                            Object value = c.getVariableValue(fieldName);
//                            f.set(generator, value);
//                        } catch (NoSuchFieldException ex) {
//                            Logger.getLogger(MethodAST.class.getName()).log(Level.SEVERE, null, ex);
//                        }
//                    }
//
//                    // Expression is derived pr transformation
//                    Object value = ctx.bodyAsMethod.invoke(generator, null);
//                    ExpressionAST generatedExpression = ctx.convertToExpression(value, ctx.bodyAsMethod.getReturnType());
//                    generatedExpression.accept(this);
//                } catch (SecurityException | IllegalArgumentException | InstantiationException | IllegalAccessException | InvocationTargetException ex) {
//                    Logger.getLogger(MetaExpressionAST.class.getName()).log(Level.SEVERE, null, ex);
//                }
                
                throw new UnsupportedOperationException();
            }

            @Override
            public PreparedAST visitThis(ThisAST ctx) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PreparedAST visitFieldGet(FieldGetAST ctx) {
                throw new UnsupportedOperationException();
//                ctx.target.accept(this);
//                generator.methodNode.getField(Type.getType(ctx.target.resultType().getDescriptor(c.getTarget().name)), ctx.fieldName, Type.getType(ctx.resultType().getDescriptor(c.getTarget().name)));
            }

            @Override
            public PreparedAST visitVariableDeclaration(VariableDeclarationAST ctx) {
                variables.put(ctx.name, ctx.type);
                
                PreparedExpressionAST value = ctx.value != null ? toExpression(thisClass, ctx.value, variables) : null;
                
                return new PreparedAST() {
                    @Override
                    public void generate(Transformation<ClassNode> c, MethodCodeGenerator generator, InsnList originalIl) {
                        int ordinal = generator.declareVariable(ctx.name, ctx.type.getDescriptor(), ctx.type);
                        
                        if(value != null) {
                            value.generate(c, generator, originalIl);
                            
                            appendStore(generator, ordinal, ctx.type);
                        }
                    }
                };
            }

            @Override
            public PreparedAST visitLookup(LookupAST ctx) {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public PreparedAST visitVariableAssignment(VariableAssignmentAST ctx) {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public PreparedAST visitRootExpression(RootExpressionAST ctx) {
                return toExpression(thisClass, ctx.expression, variables, false);
            }

            @Override
            public PreparedAST visitQuote(QuoteAST ctx) {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public PreparedAST visitNew(NewAST ctx) {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public PreparedAST visitArray(ArrayAST ctx) {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public PreparedAST visitNull(NullAST ctx) {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }
        });
    }

    private static void appendStore(MethodCodeGenerator generator, int ordinal, TypeAST type) {
        switch(type.getSimpleName()) {
            case "int":
                generator.methodNode.visitVarInsn(Opcodes.ISTORE, ordinal);
                break;
            default:
                generator.methodNode.visitVarInsn(Opcodes.ASTORE, ordinal);
                break;
        }
    }
    
    public static PreparedExpressionAST toExpression(Scope thisClass, ExpressionAST expression, Hashtable<String, TypeAST> variables) {
        return toExpression(thisClass, expression, variables, true);
    }
    
    public static PreparedExpressionAST toExpression(Scope thisClass, ExpressionAST expression, Hashtable<String, TypeAST> variables, boolean asExpression) {
        return expression.accept(new CodeVisitor<PreparedExpressionAST>() {
            @Override
            public PreparedExpressionAST visitReturn(ReturnAST ctx) {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public PreparedExpressionAST visitBlock(BlockAST ctx) {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public PreparedExpressionAST visitStringLiteral(StringLiteralAST ctx) {
                return new PreparedExpressionAST() {
                    @Override
                    public TypeAST resultType() {
                        return new NameTypeAST(ctx.getRegion(), String.class);
                    }

                    @Override
                    public void generate(Transformation<ClassNode> c, MethodCodeGenerator generator, InsnList originalIl) {
                        if(asExpression)
                            generator.methodNode.push(ctx.value);
                    }
                };
            }

            @Override
            public PreparedExpressionAST visitIntLiteral(IntLiteralAST ctx) {
                return new PreparedExpressionAST() {
                    @Override
                    public TypeAST resultType() {
                        return new NameTypeAST(ctx.getRegion(), int.class);
                    }

                    @Override
                    public void generate(Transformation<ClassNode> c, MethodCodeGenerator generator, InsnList originalIl) {
                        if(asExpression)
                            generator.methodNode.push(ctx.value);
                    }
                };
            }

            @Override
            public PreparedExpressionAST visitBinaryExpression(BinaryExpressionAST ctx) {
                PreparedExpressionAST lhsTmp = ctx.lhs.accept(this);             
                PreparedExpressionAST rhsTmp = ctx.rhs.accept(this);
                
                TypeAST resultType;
                
                if(lhsTmp.resultType().getDescriptor().equals("Ljava/lang/String;") || rhsTmp.resultType().getDescriptor().equals("Ljava/lang/String;")) {
                    switch(ctx.operator) {
                        case OPERATOR_ADD:
                            if(!lhsTmp.resultType().getDescriptor().equals("Ljava/lang/String;"))
                                lhsTmp = expressionAsString(lhsTmp);
                            if(!rhsTmp.resultType().getDescriptor().equals("Ljava/lang/String;"))
                                rhsTmp = expressionAsString(rhsTmp);
                            resultType = new NameTypeAST(ctx.getRegion(), String.class);
                            break;
                        default:
                            resultType = null;
//                            errorMessages.add(new ASMCompiler.Message(getRegion(), "Bad operand types for binary operator '" + getOperatorString() + "'"));
                            break;
                    }
                } else if(lhsTmp.resultType().getSimpleName().equals("int") && rhsTmp.resultType().getSimpleName().equals("int")) {
                    resultType = new NameTypeAST(ctx.getRegion(), int.class);
                } else
                    resultType = null;
                
                PreparedExpressionAST lhs = lhsTmp;             
                PreparedExpressionAST rhs = rhsTmp;
                
                return new PreparedExpressionAST() {
                    @Override
                    public TypeAST resultType() {
                        return resultType;
                    }

                    @Override
                    public void generate(Transformation<ClassNode> c, MethodCodeGenerator generator, InsnList originalIl) {
                        lhs.generate(c, generator, originalIl);
                        rhs.generate(c, generator, originalIl);
                        
                        switch(resultType().getSimpleName(c.getTarget().name)) {
                            case "String":
                                generator.methodNode.invokeVirtual(Type.getType("java/lang/String"), new Method("concat", "(Ljava/lang/String;)Ljava/lang/String;"));
                                break;
                            case "short":
                                generator.methodNode.visitInsn(Opcodes.IADD);
                                break;
                            case "int":
                                generator.methodNode.visitInsn(Opcodes.IADD);
                                break;
                            case "long":
                                generator.methodNode.visitInsn(Opcodes.LADD);
                                break;
                            case "float":
                                generator.methodNode.visitInsn(Opcodes.FADD);
                                break;
                            case "double":
                                generator.methodNode.visitInsn(Opcodes.DADD);
                                break;
                        }
                    }
                };
            }
            
            private PreparedExpressionAST expressionAsString(PreparedExpressionAST ctx) {
                switch(ctx.resultType().getSimpleName()) {
                    case "int":
                        return createInvocation(null, new NameTypeAST(null, Integer.class), "toString", Arrays.asList(ctx));
//                        return new InvocationAST(ctx.getRegion(), null, new NameTypeAST(ctx.getRegion(), Integer.class), "toString", Arrays.asList(ctx), new NameTypeAST(ctx.getRegion(), String.class));
                    default:
                        return createInvocation(ctx, null, "toString", Collections.emptyList());
//                        return new InvocationAST(ctx.getRegion(), ctx, null, "toString", Collections.emptyList(), new NameTypeAST(ctx.getRegion(), String.class));
                }
            }

            @Override
            public PreparedExpressionAST visitInvocation(InvocationAST ctx) {
                PreparedExpressionAST target = ctx.target != null ? ctx.target.accept(this) : null;
                
                List<PreparedExpressionAST> arguments = ctx.arguments.stream().map(a -> a.accept(this)).collect(Collectors.toList());
                return createInvocation(target, ctx.declaringClass, ctx.methodName, arguments);
//                Class<?>[] parameterTypes = arguments.stream().map(a -> a.resultType()).toArray(size -> new Class<?>[size]);
//                Type[] argumentTypes = Arrays.asList(parameterTypes).stream().map(a -> Type.getType(a)).toArray(size -> new Type[size]);
//        
//                java.lang.reflect.Method method;
//
//                if(target != null) {
//                    // Generalize getAccessibleMethod
//                    method = MethodUtils.getAccessibleMethod(((NameTypeAST)target.resultType()).getType(), ctx.methodName, parameterTypes);
//                } else {
//                    method = MethodUtils.getAccessibleMethod(((NameTypeAST)ctx.declaringClass).getType(), ctx.methodName, parameterTypes);
//                }
//                
//                return new PreparedExpressionAST() {
//                    @Override
//                    public TypeAST resultType() {
//                        return new NameTypeAST(ctx.getRegion(), method.getReturnType());
//                    }
//
//                    @Override
//                    public void generate(Transformation<ClassNode> c, MethodCodeGenerator generator, InsnList originalIl) {
//                        arguments.forEach(a -> a.generate(c, generator, originalIl));
//                        Method asmMethod = new Method(ctx.methodName, Type.VOID_TYPE, argumentTypes);
//                        if(target != null)
//                            generator.methodNode.invokeVirtual(Type.getType(target.resultType().getDescriptor()), asmMethod);
//                        else
//                            generator.methodNode.invokeStatic(Type.getType(ctx.declaringClass.getDescriptor()), asmMethod);
//                        
//                        if(!asExpression && method.getReturnType() != Void.class)
//                            generator.methodNode.pop();
//                    }
//                };
            }
            
            private PreparedExpressionAST createInvocation(PreparedExpressionAST target, TypeAST declaringClass, String methodName, List<PreparedExpressionAST> arguments) {
                Class<?>[] parameterTypes = arguments.stream().map(a -> 
                    ((NameTypeAST)a.resultType()).getType()
                ).toArray(size -> new Class<?>[size]);
                Type[] argumentTypes = Arrays.asList(parameterTypes).stream().map(a -> Type.getType(a)).toArray(size -> new Type[size]);
        
                java.lang.reflect.Method tmpMethod;
                Type tmpTargetType = null;

                if(target != null) {
                    // Generalize getAccessibleMethod
                    tmpTargetType = Type.getType(target.resultType().getDescriptor());
                    tmpMethod = MethodUtils.getAccessibleMethod(((NameTypeAST)target.resultType()).getType(), methodName, parameterTypes);
                    
                    if(tmpMethod == null) {
                        // Generalize getAccessibleMethod
                        tmpTargetType = Type.getType(Object.class);
                        tmpMethod = MethodUtils.getAccessibleMethod(Object.class, methodName, parameterTypes);
                    }
                } else {
                    tmpMethod = MethodUtils.getAccessibleMethod(((NameTypeAST)declaringClass).getType(), methodName, parameterTypes);
                }
                   
                Type tmpCastType = null; 
                if(tmpMethod.getGenericReturnType() instanceof TypeVariable) {
                    TypeVariable rtv = (TypeVariable)tmpMethod.getGenericReturnType();
                    TypeAST typeArgument = target.resultType().getTypeArgument(rtv.getName());
                    tmpCastType = Type.getType(typeArgument.getDescriptor());
                }
                
                Type targetType = tmpTargetType;
                Type castType = tmpCastType;
                
                java.lang.reflect.Method method = tmpMethod;
                
                return new PreparedExpressionAST() {
                    @Override
                    public TypeAST resultType() {
                        return castType == null ? new NameTypeAST(null, method.getReturnType()) : NameTypeAST.fromDescriptor(castType.getDescriptor());
                    }

                    @Override
                    public void generate(Transformation<ClassNode> c, MethodCodeGenerator generator, InsnList originalIl) {
                        if(target != null)
                            target.generate(c, generator, originalIl);
                        
                        arguments.forEach(a -> 
                            a.generate(c, generator, originalIl));
                        
                        Type returnType = /*castType != null ? castType : */Type.getType(method.getReturnType());
                        Method asmMethod = new Method(methodName, returnType, argumentTypes);
                        if(target != null) {
                            if(method.getDeclaringClass().isInterface())
                                generator.methodNode.invokeInterface(targetType, asmMethod);
                            else
                                generator.methodNode.invokeVirtual(targetType, asmMethod);
                        } else
                            generator.methodNode.invokeStatic(Type.getType(declaringClass.getDescriptor()), asmMethod);
                        
                        if(asExpression && castType != null) {
                            generator.methodNode.checkCast(castType);
                        }
                        
                        if(!asExpression && method.getReturnType() != Void.class)
                            generator.methodNode.pop();
                    }
                };
            }

            @Override
            public PreparedExpressionAST visitFieldSet(FieldSetAST ctx) {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public PreparedExpressionAST visitLongLiteral(LongLiteralAST ctx) {
                return new PreparedExpressionAST() {
                    @Override
                    public TypeAST resultType() {
                        return new NameTypeAST(ctx.getRegion(), long.class);
                    }

                    @Override
                    public void generate(Transformation<ClassNode> c, MethodCodeGenerator generator, InsnList originalIl) {
                        if(asExpression)
                            generator.methodNode.push(ctx.value);
                    }
                };
            }

            @Override
            public PreparedExpressionAST visitMeta(MetaExpressionAST ctx) {
                // Doesn't make sense here?... Ony within quoted asts, right?
                
                // expectedResultType should for body should a type pattern including String, int, ...rest primitive types..., ExpressionAST
//                ctx.body.forEach(s -> s.resolve(mp.metaScope, new NameTypeAST(getRegion(), ExpressionAST.class), resolver, errorMessages));
//                ctx.body.stream().collect(Collectors.toList())

                
                
                
//                // 1) Generate code to generate code
//                ClassNode metaObjectClassNode = new ClassNode(Opcodes.ASM5);
//
//                ctx.mp.metaScope.addFields(metaObjectClassNode);
//                
//                Hashtable<String, TypeAST> metaVariables = new Hashtable<>();
//                List<PreparedAST> body = ((List<CodeAST>)ctx.body).stream().map(c -> 
////                    toCode(new ClassNodeScope(metaObjectClassNode), c)).collect(Collectors.toList());
//                    toCode(ctx.mp.metaScope, c, metaVariables)).collect(Collectors.toList());
//                List<TypeAST> returnTypes = body.stream().map(c -> 
//                    c.returns()).filter(r -> r != null).collect(Collectors.toList());
//                Class<?> returnTypeClass = ((NameTypeAST)returnTypes.get(0)).getType();
//                
//                metaObjectClassNode.version = MetaExpressionAST.getOpcodesVersion();
//                metaObjectClassNode.access = Opcodes.ACC_PUBLIC;
//                metaObjectClassNode.name = "dejain/generator/ASMGenerator" + ctx.mp.generatorCount;
//                metaObjectClassNode.superName = "java/lang/Object";
//                MethodNode generatorMethod = new MethodNode(Opcodes.ACC_PUBLIC, "generator", Type.getMethodDescriptor(Type.getType(returnTypeClass)), null, new String[]{});
//                metaObjectClassNode.methods.add(generatorMethod);
//
//                MethodNode defaultConstructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
//                defaultConstructor.visitCode();
//                defaultConstructor.visitVarInsn(Opcodes.ALOAD, 0);
//                defaultConstructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
//                defaultConstructor.visitInsn(Opcodes.RETURN);
//                defaultConstructor.visitMaxs(1,1);
//                defaultConstructor.visitEnd();
//                metaObjectClassNode.methods.add(defaultConstructor);
//
//                GeneratorAdapter generatorAdapter = new GeneratorAdapter(generatorMethod, generatorMethod.access, generatorMethod.name, generatorMethod.desc);
////                MethodAST.toCode(new Transformation<>(generatorClassNode), body, new MethodAST.MethodCodeGenerator(generatorAdapter, null));
//                MethodCodeGenerator metaCodeGenerator = new MethodCodeGenerator(generatorAdapter, null);
//                metaCodeGenerator.start();
//                body.forEach(c -> 
//                    c.generate(new Transformation<>(metaObjectClassNode), metaCodeGenerator, new InsnList()));
//                metaCodeGenerator.end();
//                generatorMethod.visitEnd();
//
//                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
//                metaObjectClassNode.accept(cw);
//                
//                TraceClassVisitor traceClassVisitor = new TraceClassVisitor(null, new Textifier(), new PrintWriter(System.out));
//                metaObjectClassNode.accept(traceClassVisitor);
//                CheckClassAdapter.verify(new ClassReader(cw.toByteArray()), false, new PrintWriter(System.out));
//                
//                SingleClassLoader classLoader = new SingleClassLoader(metaObjectClassNode);
//                Class<?> metaObjectClass = classLoader.loadClass();
//                java.lang.reflect.Method bodyAsMethodTmp = null;
//                
//                try {
//                    bodyAsMethodTmp = metaObjectClass.getMethod("generator", null);
//                } catch (NoSuchMethodException | SecurityException ex) {
//                    Logger.getLogger(MetaExpressionAST.class.getName()).log(Level.SEVERE, null, ex);
//                }
//                
//                java.lang.reflect.Method bodyAsMethod = bodyAsMethodTmp;
//
//                ctx.mp.generatorCount++;
//
////                resultType = new NameTypeAST(getRegion(), resultType(bodyAsMethod.getReturnType()));
//                
//                TypeAST resultType = returnTypes.get(0);
//                
//                return new PreparedExpressionAST() {
//                    @Override
//                    public TypeAST resultType() {
//                        return resultType;
//                    }
//
//                    @Override
//                    public void generate(Transformation<ClassNode> c, MethodCodeGenerator generator, InsnList originalIl) {
//                        try {
//                            Object metaObject = metaObjectClass.newInstance();
//
//                            // 2) Evaluate the generated code which result in a String
//                            for(String fieldName: ctx.mp.metaScope.getFieldNames()) {
//                                try {
//                                    Field f = metaObjectClass.getField(fieldName);
//                                    Object value = c.getVariableValue(fieldName);
//                                    f.set(metaObject, value);
//                                } catch (NoSuchFieldException ex) {
//                                    Logger.getLogger(MethodAST.class.getName()).log(Level.SEVERE, null, ex);
//                                }
//                            }
//
//                            // Expression is derived pr transformation
//                            Object astValue = bodyAsMethod.invoke(metaObject, null);
//                            ExpressionAST generatedExpression = ctx.convertToExpression(astValue, bodyAsMethod.getReturnType());
//                            PreparedAST preparedGeneratedExpression = toExpression(thisClass, generatedExpression, variables, true);
//                            preparedGeneratedExpression.generate(c, generator, originalIl);
////                            generatedExpression.accept(this);
//                        } catch (SecurityException | IllegalArgumentException | InstantiationException | IllegalAccessException | InvocationTargetException ex) {
//                            Logger.getLogger(MetaExpressionAST.class.getName()).log(Level.SEVERE, null, ex);
//                        }
//                    }
//                };
//                
////                Class<?> generatorClass2 = ctx.bodyAsMethod.getDeclaringClass();
//                
//                
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public PreparedExpressionAST visitThis(ThisAST ctx) {
                return new PreparedExpressionAST() {
                    @Override
                    public TypeAST resultType() {
                        return new ThisTypeAST(ctx.getRegion(), thisClass);
                    }

                    @Override
                    public void generate(Transformation<ClassNode> c, MethodCodeGenerator generator, InsnList originalIl) {
                        generator.methodNode.loadThis();
                    }
                };
            }

            @Override
            public PreparedExpressionAST visitFieldGet(FieldGetAST ctx) {
                PreparedExpressionAST target = ctx.target != null ? ctx.target.accept(this) : null;
                TypeAST fieldType = target != null ? target.resultType().getFieldType(ctx.fieldName) : null /*from declared class instead*/;
                
                return new PreparedExpressionAST() {
                    @Override
                    public TypeAST resultType() {
                        return fieldType;
                    }

                    @Override
                    public void generate(Transformation<ClassNode> c, MethodCodeGenerator generator, InsnList originalIl) {
                        if(asExpression) {
                            target.generate(c, generator, originalIl);
                            generator.methodNode.getField(Type.getType(target.resultType().getDescriptor(c.getTarget().name)), ctx.fieldName, Type.getType(fieldType.getDescriptor(c.getTarget().name)));
                        }
                    }
                };
            }

            @Override
            public PreparedExpressionAST visitVariableDeclaration(VariableDeclarationAST ctx) {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public PreparedExpressionAST visitLookup(LookupAST ctx) {
                if(variables.containsKey(ctx.name)) {
                    TypeAST resultType = variables.get(ctx.name);
                    return new PreparedExpressionAST() {
                        @Override
                        public TypeAST resultType() {
                            return resultType;
                        }

                        @Override
                        public void generate(Transformation<ClassNode> c, MethodCodeGenerator generator, InsnList originalIl) {
                            int ordinal = generator.getVariableIndex(ctx.name);
                            
                            switch(resultType.getSimpleName()) {
                                case "int":
                                    generator.methodNode.visitVarInsn(Opcodes.ILOAD, ordinal);
                                    break;
                                default:
                                    generator.methodNode.visitVarInsn(Opcodes.ALOAD, ordinal);
                                    break;
                            }
                        }
                    };
                } else {
                    return 
                        new FieldGetAST(ctx.getRegion(), new ThisAST(ctx.getRegion()), ctx.name)
                        .accept(this);
                }
            }

            @Override
            public PreparedExpressionAST visitVariableAssignment(VariableAssignmentAST ctx) {
                PreparedExpressionAST value = getAsExpression(ctx.value);
                
                return new PreparedExpressionAST() {
                    @Override
                    public TypeAST resultType() {
                        return value.resultType();
                    }

                    @Override
                    public void generate(Transformation<ClassNode> c, MethodCodeGenerator generator, InsnList originalIl) {
                        value.generate(c, generator, originalIl);
                        
                        if(asExpression)
                            generator.methodNode.dup();
                        
                        int index = generator.getVariableIndex(ctx.name);
                        TypeAST type = generator.getVariableType(ctx.name);
                        appendStore(generator, index, type);
                    }
                };
            }
            
            private PreparedExpressionAST getAsExpression(ExpressionAST ctx) {
                return toExpression(thisClass, ctx, variables, true);
                
//                boolean changedAsExpression = false;
//                if(!asExpression) {
//                    changedAsExpression = true;
//                    asExpression = true;
//                }
//                
//                PreparedExpressionAST expression = ctx.accept(this);
//                
//                if(changedAsExpression)
//                    asExpression = false;
//                
//                return expression;
            }

            @Override
            public PreparedExpressionAST visitRootExpression(RootExpressionAST ctx) {
                return ctx.accept(this);
            }

            @Override
            public PreparedExpressionAST visitQuote(QuoteAST ctx) {
                ExpressionAST quotedAST = quote(thisClass, ctx.ast, variables);
                
                return quotedAST.accept(this);
            }

            @Override
            public PreparedExpressionAST visitNull(NullAST ctx) {
                return new PreparedExpressionAST() {
                    @Override
                    public TypeAST resultType() {
                        return null; // Result type should be expected result in the outer context
                    }

                    @Override
                    public void generate(Transformation<ClassNode> c, MethodCodeGenerator generator, InsnList originalIl) {
                        generator.methodNode.visitInsn(Opcodes.ACONST_NULL);
                    }
                };
            }

            @Override
            public PreparedExpressionAST visitArray(ArrayAST ctx) {
                List<PreparedExpressionAST> elements = ctx.elements.stream().map(e -> e.accept(this)).collect(Collectors.toList());
                
                return new PreparedExpressionAST() {
                    @Override
                    public TypeAST resultType() {
                        return NameTypeAST.fromDescriptor("[" + ctx.type.getDescriptor());
                    }

                    @Override
                    public void generate(Transformation<ClassNode> c, MethodCodeGenerator generator, InsnList originalIl) {
                        generator.methodNode.push(ctx.elements.size());
                        generator.methodNode.newArray(Type.getType(ctx.type.getDescriptor()));
                        
                        for(int i = 0; i < elements.size(); i++) {
                            generator.methodNode.dup();
                            generator.methodNode.push(i);
                            PreparedExpressionAST e = elements.get(i);
                            e.generate(c, generator, originalIl);
                            generator.methodNode.arrayStore(Type.getType(ctx.type.getDescriptor()));
                        }
                    }
                };
            }

            @Override
            public PreparedExpressionAST visitNew(NewAST ctx) {
                List<PreparedExpressionAST> arguments = ctx.arguments.stream().map(e -> e.accept(this)).collect(Collectors.toList());
                
                return new PreparedExpressionAST() {
                    @Override
                    public TypeAST resultType() {
                        return ctx.c;
                    }

                    @Override
                    public void generate(Transformation<ClassNode> c, MethodCodeGenerator generator, InsnList originalIl) {
                        generator.methodNode.newInstance(Type.getType(ctx.c.getDescriptor()));
                        generator.methodNode.dup();
                        
                        arguments.forEach(a -> a.generate(c, generator, originalIl));
                        
                        Class<?>[] parameterTypes = arguments.stream().map(a -> ((NameTypeAST)a.resultType()).getType()).toArray(size -> new Class<?>[size]);
                        Type[] argumentTypes = arguments.stream().map(a -> Type.getType(a.resultType().getDescriptor())).toArray(size -> new Type[size]);
                        Constructor<?> constructor = ConstructorUtils.getAccessibleConstructor(((NameTypeAST)ctx.c).getType(), parameterTypes);
                        
                        Method mConstructor = new Method("<init>", Type.VOID_TYPE, argumentTypes);
                        generator.methodNode.invokeConstructor(Type.getType(ctx.c.getDescriptor()), mConstructor);
                    }
                };
            }
        });
    }
    
    public static ExpressionAST quote(Scope thisClass, CodeAST ctx, Hashtable<String, TypeAST> variables) {
        return ctx.accept(new CodeVisitor<ExpressionAST>() {
            @Override
            public ExpressionAST visitReturn(ReturnAST ctx) {
                ExpressionAST quotedExpression = ctx.expression.accept(this);
                
                return new NewAST(ctx.getRegion(), new NameTypeAST(null, ReturnAST.class), Arrays.asList(null, quotedExpression));
            }

            @Override
            public ExpressionAST visitStringLiteral(StringLiteralAST ctx) {
                return new NewAST(ctx.getRegion(), new NameTypeAST(null, StringLiteralAST.class), Arrays.asList(null, new StringLiteralAST(null, ctx.value)));
            }

            @Override
            public ExpressionAST visitIntLiteral(IntLiteralAST ctx) {
                return new NewAST(ctx.getRegion(), new NameTypeAST(null, LongLiteralAST.class), Arrays.asList(null, new LongLiteralAST(null, ctx.value)));
            }

            @Override
            public ExpressionAST visitBinaryExpression(BinaryExpressionAST ctx) {
                ExpressionAST quotedLhs = ctx.lhs.accept(this);
                ExpressionAST quotedRhs = ctx.rhs.accept(this);
                
                return new NewAST(ctx.getRegion(), new NameTypeAST(null, BinaryExpressionAST.class), Arrays.asList(null, quotedLhs, quotedRhs));
            }

            @Override
            public ExpressionAST visitInvocation(InvocationAST ctx) {
                ExpressionAST quotedTarget = ctx.target != null ? ctx.target.accept(this) : new NullAST(null);
                ExpressionAST quotedDeclaringClass = ctx.declaringClass != null ? quote(ctx.declaringClass) : new NullAST(null);
                ExpressionAST quotedMethodName = quote(ctx.methodName);
                ExpressionAST quotedArguments = quote(ctx.arguments);
                        
                // Arrays.asList(new ExpressionAST[]{arg0, arg1, ..., argN-1, argN});
                
                return new NewAST(
                    ctx.getRegion(), 
                    new NameTypeAST(null, BinaryExpressionAST.class), 
                    Arrays.asList(new NullAST(null), quotedTarget, quotedDeclaringClass, quotedMethodName, quotedArguments, null));
            }
            
            private ExpressionAST quote(ExpressionAST expression) {
                return expression.accept(this);
            }
            
            private ExpressionAST quote(String str) {
                return new StringLiteralAST(null, str);
            }
            
            private ExpressionAST quote(TypeAST type) {
//                return new NewAST(ctx.getRegion(), new NameTypeAST(null, NameTypeAST.class), Arrays.asList(null, quotedLhs, quotedRhs));
                return new InvocationAST(null, null, new NameTypeAST(null, NameTypeAST.class), "fromDescriptor", Arrays.asList(quote(type.getDescriptor())), null);
            }
            
            private <T extends CodeAST> ExpressionAST quote(List<T> expressions) {
                List<ExpressionAST> expressionList = expressions.stream().map(a -> ctx.accept(this)).collect(Collectors.toList());
                ExpressionAST quotedExpressionsAsArray = new ArrayAST(null, new NameTypeAST(null, ExpressionAST.class), expressionList);
                return new InvocationAST(null, null, new NameTypeAST(null, Arrays.class), "asList", Arrays.asList(quotedExpressionsAsArray), null);
            }

            @Override
            public ExpressionAST visitFieldSet(FieldSetAST ctx) {
                ExpressionAST quotedTarget = quote(ctx.target);
                ExpressionAST quotedDeclaringClass = quote(ctx.target);
                ExpressionAST quotedFieldName = quote(ctx.fieldName);
                ExpressionAST quotedValue = quote(ctx.value);
                
                return new NewAST(
                    ctx.getRegion(), new NameTypeAST(null, BinaryExpressionAST.class), 
                    Arrays.asList(null, quotedTarget, quotedDeclaringClass, quotedFieldName, quotedValue));
            }

            @Override
            public ExpressionAST visitLongLiteral(LongLiteralAST ctx) {
                return new NewAST(ctx.getRegion(), new NameTypeAST(null, LongLiteralAST.class), Arrays.asList(null, new LongLiteralAST(null, ctx.value)));
            }

            @Override
            public ExpressionAST visitMeta(MetaExpressionAST ctx) {
                // Should just return body as is.
                // Body should be an ExpressionAST.
                
                return ctx.body;
                
//                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public ExpressionAST visitThis(ThisAST ctx) {
                return new NewAST(ctx.getRegion(), new NameTypeAST(null, ThisAST.class), Arrays.<ExpressionAST>asList(null));
            }

            @Override
            public ExpressionAST visitFieldGet(FieldGetAST ctx) {
                ExpressionAST quotedTarget = ctx.target.accept(this);
                ExpressionAST quotedFieldName = quote(ctx.fieldName);
                
                return new NewAST(
                    ctx.getRegion(), new NameTypeAST(null, FieldGetAST.class), 
                    Arrays.asList(null, quotedTarget, quotedFieldName));
            }

            @Override
            public ExpressionAST visitVariableDeclaration(VariableDeclarationAST ctx) {
                ExpressionAST quotedName = quote(ctx.name);
                ExpressionAST quotedType = quote(ctx.type);
                ExpressionAST quotedValue = ctx.value.accept(this);
                
                return new NewAST(
                    ctx.getRegion(), new NameTypeAST(null, VariableDeclarationAST.class), 
                    Arrays.asList(null, quotedName, quotedType, quotedValue));
            }

            @Override
            public ExpressionAST visitLookup(LookupAST ctx) {
                ExpressionAST quotedName = quote(ctx.name);
                
                return new NewAST(
                    ctx.getRegion(), new NameTypeAST(null, LookupAST.class), 
                    Arrays.asList(null, quotedName));
            }

            @Override
            public ExpressionAST visitVariableAssignment(VariableAssignmentAST ctx) {
                ExpressionAST quotedName = quote(ctx.name);
                ExpressionAST quotedValue = ctx.value.accept(this);
                
                return new NewAST(
                    ctx.getRegion(), new NameTypeAST(null, VariableAssignmentAST.class), 
                    Arrays.asList(null, quotedName, quotedValue));
            }

            @Override
            public ExpressionAST visitRootExpression(RootExpressionAST ctx) {
                ExpressionAST quotedExpression = ctx.expression.accept(this);
                
                return new NewAST(
                    ctx.getRegion(), new NameTypeAST(null, RootExpressionAST.class), 
                    Arrays.asList(null, quotedExpression));
            }

            @Override
            public ExpressionAST visitQuote(QuoteAST ctx) {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }

            @Override
            public ExpressionAST visitBlock(BlockAST ctx) {
                ExpressionAST quotedStatements = quote(ctx.statements);
                
                return new NewAST(
                    ctx.getRegion(), new NameTypeAST(null, BlockAST.class), 
                    Arrays.asList(null, quotedStatements));
            }

            @Override
            public ExpressionAST visitNew(NewAST ctx) {
                ExpressionAST quotedC = quote(ctx.c);
                ExpressionAST quotedArguments = quote(ctx.arguments);
                
                return new NewAST(
                    ctx.getRegion(), new NameTypeAST(null, NewAST.class), 
                    Arrays.asList(null, quotedC, quotedArguments));
            }

            @Override
            public ExpressionAST visitArray(ArrayAST ctx) {
                ExpressionAST quotedType = quote(ctx.type);
                ExpressionAST quotedElements = quote(ctx.elements);
                
                return new NewAST(
                    ctx.getRegion(), new NameTypeAST(null, ArrayAST.class), 
                    Arrays.asList(null, quotedType, quotedElements));
            }

            @Override
            public ExpressionAST visitNull(NullAST ctx) {
                return new NewAST(
                    ctx.getRegion(), new NameTypeAST(null, NullAST.class), 
                    Arrays.<ExpressionAST>asList(null));
            }
        });
    }
    
    public static class MethodCodeGenerator {
        private static class VariableInfo {
            public int index;
            public TypeAST type;

            public VariableInfo(int index, TypeAST type) {
                this.index = index;
                this.type = type;
            }
        }
        
        public GeneratorAdapter methodNode;
        private TypeAST returnType;
        private HashMap<String, VariableInfo> localNameToIndexMap = new HashMap<>();
//        private Label start;
//        private Label end;

        public MethodCodeGenerator(GeneratorAdapter methodNode, TypeAST returnType) {
            this.methodNode = methodNode;
            this.returnType = returnType;
//            this.start = new Label();
//            this.end = new Label();
        }
        
        public void start() {
//            methodNode.visitLabel(start);
        }
        
        public void end() {
//            methodNode.visitLabel(end);
        }
        
        public int declareVariable(String name, String desc, TypeAST type) {
//            int index = localNameToIndexMap.size();
            int index = methodNode.newLocal(Type.getType(type.getDescriptor()));
            localNameToIndexMap.put(name, new VariableInfo(index, type));
//            methodNode.visitLocalVariable(name, desc, null, start, end, index);
            return index;
        }
        
        public int getVariableIndex(String name) {
            return localNameToIndexMap.get(name).index;
        }

        private boolean isVariable(String name) {
            return localNameToIndexMap.containsKey(name);
        }

        private TypeAST getVariableType(String name) {
            return localNameToIndexMap.get(name).type;
        }

        private TypeAST getReturnType() {
            return returnType;
        }
    }

    @Override
    public void populate(CommonClassTransformer transformer) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
}