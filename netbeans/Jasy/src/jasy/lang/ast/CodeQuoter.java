package jasy.lang.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.stream.Collectors;

public class CodeQuoter implements CodeVisitor<ExpressionAST> {
    private Scope thisClass;
    private CodeAST ctx;
    private Hashtable<String, TypeAST> variables;

    public CodeQuoter(Scope thisClass, CodeAST ctx, Hashtable<String, TypeAST> variables) {
        this.thisClass = thisClass;
        this.ctx = ctx;
        this.variables = variables;
    }

    @Override
    public ExpressionAST visitReturn(ReturnAST ctx) {
        ExpressionAST quotedExpression = ctx.expression != null ? ctx.expression.accept(this) : new NullAST(null);
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, ReturnAST.class), Arrays.asList(new NullAST(null), quotedExpression));
    }

    @Override
    public ExpressionAST visitMetaCode(MetaCodeAST ctx) {
        return ctx;
    }

    @Override
    public ExpressionAST visitStringLiteral(StringLiteralAST ctx) {
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, StringLiteralAST.class), Arrays.asList(new NullAST(null), new StringLiteralAST(null, ctx.value)));
    }

    @Override
    public ExpressionAST visitIntLiteral(IntLiteralAST ctx) {
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, IntLiteralAST.class), Arrays.asList(new NullAST(null), new IntLiteralAST(null, ctx.value)));
    }

    @Override
    public ExpressionAST visitLongLiteral(LongLiteralAST ctx) {
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, LongLiteralAST.class), Arrays.asList(new NullAST(null), new LongLiteralAST(null, ctx.value)));
    }

    @Override
    public ExpressionAST visitFloatLiteral(FloatLiteralAST ctx) {
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, FloatLiteralAST.class), Arrays.asList(new NullAST(null), new FloatLiteralAST(null, ctx.value)));
    }

    @Override
    public ExpressionAST visitDoubleLiteral(DoubleLiteralAST ctx) {
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, DoubleLiteralAST.class), Arrays.asList(new NullAST(null), new DoubleLiteralAST(null, ctx.value)));
    }

    @Override
    public ExpressionAST visitBoolean(BooleanLiteralAST ctx) {
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, BooleanLiteralAST.class), Arrays.asList(new NullAST(null), new BooleanLiteralAST(null, ctx.value)));
    }
 
    @Override
    public ExpressionAST visitBinaryExpression(BinaryExpressionAST ctx) {
        ExpressionAST quotedLhs = ctx.lhs.accept(this);
        ExpressionAST quotedRhs = ctx.rhs.accept(this);
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, BinaryExpressionAST.class), Arrays.asList(new NullAST(null), new IntLiteralAST(null, ctx.operator), quotedLhs, quotedRhs));
    }

    @Override
    public ExpressionAST visitInvocation(InvocationAST ctx) {
        ExpressionAST quotedTarget = quoteTarget(ctx.target);
        ExpressionAST quotedMethodName = MethodAST.quote(ctx.methodName);
        ExpressionAST quotedArguments = quote(ctx.arguments);
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, InvocationAST.class), Arrays.asList(new NullAST(null), quotedTarget, quotedMethodName, quotedArguments));
    }
    
    private ExpressionAST quoteTarget(AST target) {
        if(target instanceof ExpressionAST)
            return ((ExpressionAST)target).accept(this);
        else // Assumed to be TypeAST
            return MethodAST.quote((TypeAST)target);
    }

    private <T extends CodeAST> ExpressionAST quote(List<T> expressions) {
        List<ExpressionAST> expressionList = expressions.stream().map((a) -> a.accept(this)).collect(Collectors.toList());
        ExpressionAST quotedExpressionsAsArray = new ArrayAST(null, new NameTypeAST(null, CodeAST.class), expressionList);
        return new InvocationAST(null, new NameTypeAST(null, Arrays.class), "asList", Arrays.asList(quotedExpressionsAsArray));
    }

    @Override
    public ExpressionAST visitFieldSet(FieldSetAST ctx) {
        ExpressionAST quotedTarget = ctx.target.accept(this);
        ExpressionAST quotedDeclaringClass = MethodAST.quote(ctx.declaringClass);
        ExpressionAST quotedFieldName = MethodAST.quote(ctx.fieldName);
        ExpressionAST quotedValue = ctx.value.accept(this);
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, FieldSetAST.class), Arrays.asList(null, quotedTarget, quotedDeclaringClass, quotedFieldName, quotedValue));
    }

    @Override
    public ExpressionAST visitMetaExpression(MetaExpressionAST ctx) {
        // Should just return body in executed form.
        // Body should be an ExpressionAST.
        /*
        Somehow, it should be figured out, what the result type of the body is.
        If the result type is not an ExpressionAST, then it is decided whether
        it is possible to convert the result type into an ExpressionAST.
        - E.g. string expresions are converted into a "new StringLiteral(...)" expression
         */
        return ctx;
    }

    @Override
    public ExpressionAST visitThis(ThisAST ctx) {
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, ThisAST.class), Arrays.<ExpressionAST>asList(new NullAST(null)));
    }

    @Override
    public ExpressionAST visitFieldGet(FieldGetAST ctx) {
        ExpressionAST quotedTarget = quoteTarget(ctx.target);
        ExpressionAST quotedFieldName = ctx.fieldName.accept(this);
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, FieldGetAST.class), Arrays.asList(new NullAST(null), quotedTarget, quotedFieldName));
    }

    @Override
    public ExpressionAST visitVariableDeclaration(VariableDeclarationAST ctx) {
        ExpressionAST quotedName = MethodAST.quote(ctx.name);
        ExpressionAST quotedType = MethodAST.quote(ctx.type);
        ExpressionAST quotedValue = ctx.value != null ? ctx.value.accept(this) : new NullAST(null);
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, VariableDeclarationAST.class), Arrays.asList(new NullAST(null), quotedName, quotedType, quotedValue));
    }

    @Override
    public ExpressionAST visitLookup(LookupAST ctx) {
        ExpressionAST quotedName = ctx.name.accept(this);
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, LookupAST.class), Arrays.asList(new NullAST(null), quotedName));
    }

    @Override
    public ExpressionAST visitVariableAssignment(VariableAssignmentAST ctx) {
        ExpressionAST quotedName = MethodAST.quote(ctx.name);
        ExpressionAST quotedValue = ctx.value.accept(this);
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, VariableAssignmentAST.class), Arrays.asList(new NullAST(null), quotedName, quotedValue));
    }

    @Override
    public ExpressionAST visitRootExpression(RootExpressionAST ctx) {
        ExpressionAST quotedExpression = ctx.expression.accept(this);
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, RootExpressionAST.class), Arrays.asList(new NullAST(null), quotedExpression));
    }

    @Override
    public ExpressionAST visitQuote(QuoteAST ctx) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public ExpressionAST visitBlock(BlockAST ctx) {
        // Some of the statements may be root meta expressions
        // root meta expressions are interpolated
        
        ExpressionAST concatenation = null;
        
        for(int i = 0; i < ctx.statements.size(); i++) {
            CodeAST statement = ctx.statements.get(i);
            ExpressionAST quotedStatement = statement.accept(this);
            
            if(statement instanceof RootExpressionAST && ((RootExpressionAST)statement).expression instanceof MetaExpressionAST)
                quotedStatement = ((RootExpressionAST)statement).expression;
            
            if(i == 0)
                concatenation = quotedStatement;
            else {
                ExpressionAST lhs = concatenation;
                ExpressionAST rhs = quotedStatement;
                concatenation = new BinaryExpressionAST(null, BinaryExpressionAST.OPERATOR_ADD, lhs, rhs);
            }
        }
        
        if(concatenation != null)
            return concatenation;
        
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, BlockAST.class), Arrays.asList(new NullAST(null), quote(Collections.emptyList())));
        
//        List<InjectAST> injectStatements = ctx.statements.stream()
//            .map(s -> s.accept(this))
//            .map(s -> new InjectAST(null, s))
//            .collect(Collectors.toList());
//        InjectionBlockAST statementInjectorBlock = new InjectionBlockAST(null, injectStatements);
//        return new NewAST(ctx.getRegion(), new NameTypeAST(null, BlockAST.class), Arrays.asList(new NullAST(null), statementInjectorBlock));
    }

    @Override
    public ExpressionAST visitNew(NewAST ctx) {
        ExpressionAST quotedC = MethodAST.quote(ctx.c);
        ExpressionAST quotedArguments = quote(ctx.arguments);
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, NewAST.class), Arrays.asList(new NullAST(null), quotedC, quotedArguments));
    }

    @Override
    public ExpressionAST visitArray(ArrayAST ctx) {
        ExpressionAST quotedType = MethodAST.quote(ctx.type);
        ExpressionAST quotedElements = quote(ctx.elements);
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, ArrayAST.class), Arrays.asList(new NullAST(null), quotedType, quotedElements));
    }

    @Override
    public ExpressionAST visitNull(NullAST ctx) {
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, NullAST.class), Arrays.<ExpressionAST>asList(new NullAST(null)));
    }

    @Override
    public ExpressionAST visitTypecast(TypecastAST ctx) {
        ExpressionAST quotedExpression = ctx.expression.accept(this);
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, TypecastAST.class), Arrays.asList(new NullAST(null), quotedExpression));
    }

    @Override
    public ExpressionAST visitGetClass(GetClassAST ctx) {
        ExpressionAST quotedType = MethodAST.quote(ctx.t);
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, GetClassAST.class), Arrays.asList(new NullAST(null), quotedType));
    }

    @Override
    public ExpressionAST visitInject(InjectAST ctx) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public ExpressionAST visitInjectionBlock(InjectionBlockAST ctx) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public ExpressionAST visitWhile(WhileAST ctx) {
        ExpressionAST quotedCondition = ctx.condition.accept(this);
        ExpressionAST quotedBody = ctx.body.accept(this);
        return new NewAST(ctx.getRegion(), new NameTypeAST(null, WhileAST.class), Arrays.asList(new NullAST(null), quotedCondition, quotedBody));
    }

    @Override
    public ExpressionAST visitIfElse(IfElseAST ctx) {
        ExpressionAST quotedCondition = ctx.condition.accept(this);
        ExpressionAST quotedIfTrueBody = ctx.ifTrueBody.accept(this);
        ExpressionAST quotedIfFalseBody = ctx.ifFalseBody != null ? ctx.ifFalseBody.accept(this) : new NullAST(null);
        
        return new NewAST(
            ctx.getRegion(), 
            new NameTypeAST(null, IfElseAST.class), 
            Arrays.asList(new NullAST(null), quotedCondition, quotedIfTrueBody, quotedIfFalseBody)
        );
    }

    @Override
    public ExpressionAST visitUnary(UnaryExpression ctx) {
        ExpressionAST quotedOperand = ctx.operand.accept(this);
        
        return new NewAST(
            ctx.getRegion(), 
            new NameTypeAST(null, UnaryExpression.class), 
            Arrays.asList(new NullAST(null), new IntLiteralAST(null, ctx.operator), quotedOperand)
        );
    }

    @Override
    public ExpressionAST visitIncDec(IncDecExpression ctx) {
        ExpressionAST quotedOperand = ctx.operand.accept(this);
        
        return new NewAST(
            ctx.getRegion(), 
            new NameTypeAST(null, IncDecExpression.class), 
            Arrays.asList(new NullAST(null), new IntLiteralAST(null, ctx.timing), new IntLiteralAST(null, ctx.operator), quotedOperand)
        );
    }

    @Override
    public ExpressionAST visitAmbiguousName(AmbiguousNameAST ctx) {
        ExpressionAST quotedParts = quote(ctx.nameParts);
        
        return new NewAST(
            ctx.getRegion(), 
            new NameTypeAST(null, AmbiguousNameAST.class), 
            Arrays.asList(new NullAST(null), quotedParts)
        );
    }
}
