package amyc
package parsing

import grammarcomp.grammar.CFGrammar._
import grammarcomp.grammar.GrammarDSL._
import grammarcomp.grammar.GrammarUtils.InLL1
import grammarcomp.grammar._
import grammarcomp.parsing._
import amyc.utils._
import ast.NominalTreeModule._
import Tokens._

// The parser for Amy
// Absorbs tokens from the Lexer and then uses grammarcomp to generate parse trees.
// Defines two different grammars, a naive one which does not obey operator precedence (for demonstration purposes)
// and an LL1 grammar that implements the true syntax of Amy
object Parser extends Pipeline[Stream[Token], Program] {

  /* This grammar does not implement the correct syntax of Amy and is not LL1
   * It is given as an example
   */
  val amyGrammar = Grammar('Program, List[Rules[Token]](
    'Program ::= 'ModuleDefs,
    'ModuleDefs ::= 'ModuleDef ~ 'ModuleDefs | epsilon(),
    'ModuleDef ::= OBJECT() ~ 'Id ~ LBRACE() ~ 'Definitions ~ 'OptExpr ~ RBRACE() ~ EOF(),
    'Definitions ::= 'Definition ~ 'Definitions | epsilon(),
    'Definition ::= 'AbstractClassDef | 'CaseClassDef | 'FunDef,
    'AbstractClassDef ::= ABSTRACT() ~ CLASS() ~ 'Id,
    'CaseClassDef ::= CASE() ~ CLASS() ~ 'Id ~ LPAREN() ~ 'Params ~ RPAREN() ~ EXTENDS() ~ 'Id,
    'FunDef ::= DEF() ~ 'Id ~ LPAREN() ~ 'Params ~ RPAREN() ~ COLON() ~ 'Type ~ EQSIGN() ~ LBRACE() ~ 'Expr ~ RBRACE(),
    'Params ::= epsilon() | 'Param ~ 'ParamList,
    'ParamList ::= epsilon() | COMMA() ~ 'Param ~ 'ParamList,
    'Param ::= 'Id ~ COLON() ~ 'Type,
    'OptExpr ::= 'Expr | epsilon(),
    'Type ::= INT() | STRING() | BOOLEAN() | UNIT() | 'QName,
    'QName ::= 'Id | 'Id ~ DOT() ~ 'Id,
    'Expr ::= 'Id | 'Literal | 'Expr ~ 'BinOp ~ 'Expr | BANG() ~ 'Expr | MINUS() ~ 'Expr |
              'QName ~ LPAREN() ~ 'Args ~ RPAREN() | 'Expr ~ SEMICOLON() ~ 'Expr |
              VAL() ~ 'Param ~ EQSIGN() ~ 'Expr ~ SEMICOLON() ~ 'Expr |
              IF() ~ LPAREN() ~ 'Expr ~ RPAREN() ~ LBRACE() ~ 'Expr ~ RBRACE() ~ ELSE() ~ LBRACE() ~ 'Expr ~ RBRACE() |
              'Expr ~ MATCH() ~ LBRACE() ~ 'Cases ~ RBRACE() |
              ERROR() ~ LPAREN() ~ 'Expr ~ RPAREN() |
              LPAREN() ~ 'Expr ~ RPAREN(),
    'Literal ::= TRUE() | FALSE() | LPAREN() ~ RPAREN() | INTLITSENT | STRINGLITSENT,
    'BinOp ::= PLUS() | MINUS() | TIMES() | DIV() | MOD() | LESSTHAN() | LESSEQUALS() |
               AND() | OR() | EQUALS() | CONCAT(),
    'Cases ::= 'Case | 'Case ~ 'Cases,
    'Case ::= CASE() ~ 'Pattern ~ RARROW() ~ 'Expr,
    'Pattern ::= UNDERSCORE() | 'Literal | 'Id | 'QName ~ LPAREN() ~ 'Patterns ~ RPAREN(),
    'Patterns ::= epsilon() | 'Pattern ~ 'PatternList,
    'PatternList ::= epsilon() | COMMA() ~ 'Pattern ~ 'PatternList,
    'Args ::= epsilon() | 'Expr ~ 'ExprList,
    'ExprList ::= epsilon() | COMMA() ~ 'Expr ~ 'ExprList,
    'Id ::= IDSENT
  ))

  // TODO: Write a grammar that implements the correct syntax of Amy and is LL1.
  // You can start from the example above and work your way from there.
  // Make sure you use the warning (see `run` below) that tells you which part is not in LL1.
  lazy val amyGrammarLL1 = Grammar('Program, List[Rules[Token]](
    'Program ::= 'ModuleDefs,
    'ModuleDefs ::= 'ModuleDef ~ 'ModuleDefs | epsilon(),
    'ModuleDef ::= OBJECT() ~ 'Id ~ LBRACE() ~ 'Definitions ~ 'OptExpr ~ RBRACE() ~ EOF(),
    'Definitions ::= 'Definition ~ 'Definitions | epsilon(),
    'Definition ::= 'AbstractClassDef | 'CaseClassDef | 'FunDef,
    'AbstractClassDef ::= ABSTRACT() ~ CLASS() ~ 'Id,
    'CaseClassDef ::= CASE() ~ CLASS() ~ 'Id ~ LPAREN() ~ 'Params ~ RPAREN() ~ EXTENDS() ~ 'Id,
    'FunDef ::= DEF() ~ 'Id ~ LPAREN() ~ 'Params ~ RPAREN() ~ COLON() ~ 'Type ~ EQSIGN() ~ LBRACE() ~ 'Expr ~ RBRACE(),
    'Params ::= epsilon() | 'Param ~ 'ParamList,
    'ParamList ::= epsilon() | COMMA() ~ 'Param ~ 'ParamList,
    'Param ::= 'Id ~ COLON() ~ 'Type,
    'OptExpr ::= 'Expr | epsilon(),
    'Type ::= INT() | STRING() | BOOLEAN() | UNIT() | 'QName,
    'QName ::= 'Id ~ 'QNameSt,
  'QNameSt ::= epsilon() | DOT() ~ 'Id,
  'Expr   ::= 'E2 ~ 'E1Hlpr | VAL() ~ 'Param ~ EQSIGN() ~ 'E2 ~ SEMICOLON() ~ 'E2, 
  'E2   ::= 'E3 ~ 'E2Hlpr,
  'E3   ::= 'E4 ~ 'E3Hlpr,
  'E4   ::= 'E5 ~ 'E4Hlpr,
  'E5   ::= 'E6 ~ 'E5Hlpr,
  'E6   ::= 'E7 ~ 'E6Hlpr,
  'E7   ::= 'E8 ~ 'E7Hlpr,
  'E8   ::= 'E9 ~ 'E8Hlpr,
  'E1Hlpr ::= SEMICOLON() ~ 'Expr | epsilon(),
  'E2Hlpr ::= MATCH() ~ LBRACE() ~ 'Cases ~ RBRACE() | epsilon(),
  'E3Hlpr ::= OR() ~ 'E3 | epsilon(),
  'E4Hlpr ::= AND() ~ 'E4 | epsilon(),
  'E5Hlpr ::= EQUALS() ~ 'E5 | epsilon(),
  'E6Hlpr ::= LESSEQUALS() ~ 'E6 | LESSTHAN() ~ 'E6 | epsilon(),
  'E7Hlpr ::= CONCAT() ~ 'E7 | MINUS() ~ 'E7 | PLUS() ~ 'E7 | epsilon(),
  'E8Hlpr ::= TIMES() ~ 'E8 | DIV() ~ 'E8 | MOD() ~ 'E8 | epsilon(),
  'E9   ::= BANG() ~ 'E10 | MINUS() ~ 'E10 | 'E10,
  'E10  ::= 'Id ~ 'QNameHlpr | 'LiteralHlpr | LPAREN() ~ 'ParenHlpr | 
        ERROR() ~ LPAREN() ~ 'Expr ~ RPAREN() |
        IF() ~ LPAREN() ~ 'Expr ~ RPAREN() ~ LBRACE() ~ 'ElseHlpr,
  'QNameHlpr ::= 'QNameSt ~ LPAREN() ~ 'Args ~ RPAREN() | epsilon(),
  'ElseHlpr ::= epsilon() | ELSE() ~ LBRACE() ~ 'Expr ~ RBRACE(),
  'ParenHlpr ::= RPAREN() | 'Expr ~ RPAREN(),
  'LiteralHlpr ::= TRUE() | FALSE() | INTLITSENT | STRINGLITSENT,
  'Literal ::= TRUE() | FALSE() | LPAREN() ~ RPAREN() | INTLITSENT | STRINGLITSENT,
  'Cases ::= 'Case ~ 'CasesSt,
  'CasesSt ::= epsilon() | 'Cases,
  'Case ::= CASE() ~ 'Pattern ~ RARROW() ~ 'Expr,
  'Pattern ::= UNDERSCORE() | 'Literal | 'Id ~ 'PaternSt,
  'PaternSt ::= epsilon() | 'QNameSt ~ LPAREN() ~ 'Patterns ~ RPAREN(),
  'Patterns ::= epsilon() | 'Pattern ~ 'PatternList,
  'PatternList ::= epsilon() | COMMA() ~ 'Pattern ~ 'PatternList,
  'Args ::= epsilon() | 'Expr ~ 'ExprList,
  'ExprList ::= epsilon() | COMMA() ~ 'Expr ~ 'ExprList,
  'Id ::= IDSENT
  ))

  def run(ctx: Context)(tokens: Stream[Token]): Program = {
    // TODO: Switch to LL1 when you are ready
    val (grammar, constructor) = (amyGrammarLL1, new ASTConstructorLL1)
    //val (grammar, constructor) = (amyGrammar, new ASTConstructor)

    import ctx.reporter._
    implicit val gc = new GlobalContext()
    implicit val pc = new ParseContext()

    GrammarUtils.isLL1WithFeedback(grammar) match {
      case InLL1() =>
        warning("Grammar is in LL1")
      case other =>
        warning(other)
        fatal(s"RATE MON GARS")

    }
    
    val feedback = ParseTreeUtils.parseWithTrees(grammar, tokens.toList)
    feedback match {
      case s: Success[Token] =>
        constructor.constructProgram(s.parseTrees.head)
      case err@LL1Error(_, Some(tok)) =>
        fatal(s"Parsing failed: $err", tok.obj.position)
      case err =>
        fatal(s"Parsing failed: $err")
    }
  }

}
