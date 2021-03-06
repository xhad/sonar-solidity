package org.sonarsource.solidity.checks;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.sonarsource.solidity.frontend.SolidityParser;
import org.sonarsource.solidity.frontend.SolidityParser.ContractDefinitionContext;
import org.sonarsource.solidity.frontend.SolidityParser.ExpressionContext;
import org.sonarsource.solidity.frontend.SolidityParser.FunctionCallContext;
import org.sonarsource.solidity.frontend.SolidityParser.FunctionDefinitionContext;
import org.sonarsource.solidity.frontend.SolidityParser.IdentifierContext;
import org.sonarsource.solidity.frontend.SolidityParser.IfStatementContext;
import org.sonarsource.solidity.frontend.SolidityParser.ModifierListContext;
import org.sonarsource.solidity.frontend.SolidityParser.ReturnStatementContext;
import org.sonarsource.solidity.frontend.SolidityParser.SimpleStatementContext;
import org.sonarsource.solidity.frontend.SolidityParser.StateMutabilityContext;
import org.sonarsource.solidity.frontend.SolidityParser.StatementContext;
import org.sonarsource.solidity.frontend.SolidityParser.VariableDeclarationStatementContext;

public class CheckUtils {

  private CheckUtils() {
  }

  private static final List<String> COMPARING_OPERATORS = ImmutableList.<String>builder()
    .add("==")
    .add("!=")
    .add("<")
    .add(">")
    .add("<=")
    .add(">=")
    .build();

  public static String returnContentOfComments(String comment) {
    int idx = comment.indexOf('{');
    return comment.substring(idx + 2, comment.length() - 2).trim();

  }

  public static boolean isCommentForReporting(String comment) {
    return comment.startsWith("// Noncompliant {{") && comment.endsWith("}}");
  }

  public static boolean isParenthesized(ParseTree expr) {
    if (ExpressionContext.class.isInstance(expr)) {
      String expression = expr.getText();
      return expression.startsWith("(") && expression.endsWith(")");
    }
    return false;
  }

  public static ParseTree removeParenthesis(ParseTree tree) {
    return tree.getChild(1);
  }

  public static boolean isBooleanExpression(ParseTree tree) {
    ExpressionContext expr = (ExpressionContext) tree;
    ParseTree expression = expr.getChild(1);
    return expression != null && COMPARING_OPERATORS.contains(expression.getText());
  }

  public static boolean treeMatches(ParseTree tree, Class context) {
    return context.isInstance(tree);
  }

  public static Optional<ParseTree> checkForElseStatement(ParserRuleContext ctxNode) {
    TerminalNode elseNode = CheckUtils.findTerminalNode(ctxNode, "'else'");
    if (!ctxNode.children.isEmpty() && elseNode != null) {
      ParseTree childNode = ctxNode.children.get(6);
      // exclude else - if cases
      if (childNode.getChildCount() > 0 && !childNode.getChild(0).getClass().equals(IfStatementContext.class))
        return Optional.of(childNode);
    }
    return Optional.empty();
  }

  public static ParseTree findContractParentNode(ParseTree tree) {
    tree = tree.getParent();
    while (!treeMatches(tree, ContractDefinitionContext.class)) {
      tree = tree.getParent();
    }
    return tree;
  }

  public static boolean isElseIfStatement(IfStatementContext ctx) {
    return ctx.getParent().getRuleIndex() == SolidityParser.RULE_statement &&
      ctx.getParent().getParent().getRuleIndex() == returnTtypeFromLiteralName("'if'");
  }

  public static boolean isTernaryExpression(StatementContext ctx) {
    String statementName = ctx.getChild(0).getClass().getSimpleName();
    ExpressionContext expr = null;
    switch (statementName) {
      case "ReturnStatementContext":
        ReturnStatementContext retStmt = ctx.returnStatement();
        expr = retStmt.expression();
        return expr != null && (expr.getToken(CheckUtils.returnTtypeFromLiteralName("'?'"), 0) != null);
      case "SimpleStatementContext":
        SimpleStatementContext simpleStmt = ctx.simpleStatement();
        VariableDeclarationStatementContext varDeclStmt = simpleStmt.variableDeclarationStatement();
        if (varDeclStmt != null) {
          expr = varDeclStmt.expression();
          return expr != null && (expr.getToken(CheckUtils.returnTtypeFromLiteralName("'?'"), 0) != null);
        }
        return false;
      default:
    }
    return false;
  }

  public static TerminalNode getOpenCurlyBrace(ParserRuleContext ctx) {
    return ctx.getTokens(returnTtypeFromLiteralName("'{'")).get(0);
  }

  public static Optional<String> extractNameFromFunction(ParserRuleContext functionContext) {
    IdentifierContext functionIdentifier = null;
    String functionName = null;
    if (CheckUtils.treeMatches(functionContext, FunctionDefinitionContext.class)) {
      functionIdentifier = ((FunctionDefinitionContext) functionContext).identifier();
    } else if (CheckUtils.treeMatches(functionContext, FunctionCallContext.class)) {
      functionIdentifier = ((FunctionCallContext) functionContext).identifier(0);
    }
    if (functionIdentifier != null) {
      functionName = functionIdentifier.getText();
    }
    return Optional.ofNullable(functionName);
  }

  public static boolean isPayableFunction(List<StateMutabilityContext> stateMutabilityListCtx) {
    return stateMutabilityListCtx.stream()
      .map(StateMutabilityContext::PayableKeyword)
      .filter(Objects::nonNull)
      .count() == 1;
  }

  public static boolean isPublicOrExternalFunction(ModifierListContext modifierList) {
    return noVisibilitySpecified(modifierList)
      || modifierList.PublicKeyword(0) != null || modifierList.ExternalKeyword(0) != null;
  }

  public static boolean isViewOrPureFunction(List<StateMutabilityContext> stateMutabilityListCtx) {
    return stateMutabilityListCtx.stream()
      .filter(stateMutability -> {
        return stateMutability.ViewKeyword() != null || stateMutability.PureKeyword() != null;
      })
      .count() == 1;
  }

  public static boolean noVisibilitySpecified(ModifierListContext modifierList) {
    return modifierList.InternalKeyword(0) == null && modifierList.ExternalKeyword(0) == null
      && modifierList.PublicKeyword(0) == null && modifierList.PrivateKeyword(0) == null;
  }

  public static boolean isCallBackFunction(FunctionDefinitionContext functionCtx) {
    IdentifierContext functionIdentifier = functionCtx.identifier();
    return functionIdentifier != null && "__callback".equals(functionIdentifier.getText());
  }

  public static int returnTtypeFromLiteralName(String literal) {
    for (int i = 1; i < SolidityParser.VOCABULARY.getMaxTokenType(); i++) {
      if (literal.equals(SolidityParser.VOCABULARY.getLiteralName(i))) {
        return i;
      }
    }
    return 0;
  }

  public static TerminalNode findTerminalNode(ParserRuleContext node, String literal) {
    return node.getToken(returnTtypeFromLiteralName(literal), 0);
  }
}
