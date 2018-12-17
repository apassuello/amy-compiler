package amyc
package codegen

import analyzer._
import ast.Identifier
import ast.SymbolicTreeModule.{Call => AmyCall, Div => AmyDiv, And => AmyAnd, Or => AmyOr, _}
import utils.{Context, Pipeline}
import wasm._
import Instructions._
import Utils._

// Generates WebAssembly code for an Amy program
object CodeGen extends Pipeline[(Program, SymbolTable), Module] {
  def run(ctx: Context)(v: (Program, SymbolTable)): Module = {
    val (program, table) = v

    // Generate code for an Amy module
    def cgModule(moduleDef: ModuleDef): List[Function] = {
      val ModuleDef(name, defs, optExpr) = moduleDef
      // Generate code for all functions
      defs.collect { case fd: FunDef if !builtInFunctions(fullName(name, fd.name)) =>
        cgFunction(fd, name, false)
      } ++
      // Generate code for the "main" function, which contains the module expression
      optExpr.toList.map { expr =>
        val mainFd = FunDef(Identifier.fresh("main"), Nil, TypeTree(IntType), expr)
        cgFunction(mainFd, name, true)
      }
    }

    // Generate code for a function in module 'owner'
    def cgFunction(fd: FunDef, owner: Identifier, isMain: Boolean): Function = {
      // Note: We create the wasm function name from a combination of
      // module and function name, since we put everything in the same wasm module.
      val name = fullName(owner, fd.name)
      Function(name, fd.params.size, isMain){ lh =>
        val locals = fd.paramNames.zipWithIndex.toMap
        val body = cgExpr(fd.body)(locals, lh)
        if (isMain) {
          body <:> Drop // Main functions do not return a value,
                        // so we need to drop the value generated by their body
        } else {
          body
        }
      }
    }

    // Generate code for an expression expr.
    // Additional arguments are a mapping from identifiers (parameters and variables) to
    // their index in the wasm local variables, and a LocalsHandler which will generate
    // fresh local slots as required.
    def cgExpr(expr: Expr)(implicit locals: Map[Identifier, Int], lh: LocalsHandler): Code = expr match {
        // Variables
        case Variable(name) => GetLocal(locals(name))
        
        // Literals
        case IntLiteral(i) => i2c(Const(i))
        case BooleanLiteral(b) => i2c(Const(if(b) 1 else 0))
        case StringLiteral(s) => mkString(s)
        case UnitLiteral() => i2c(Const(0))

        // Binary operators
        case Plus(lhs, rhs) => cgExpr(lhs) <:> cgExpr(rhs) <:> Add
        case Minus(lhs, rhs) => cgExpr(lhs) <:> cgExpr(rhs) <:> Sub
        case Times(lhs, rhs) => cgExpr(lhs) <:> cgExpr(rhs) <:> Mul
        case AmyDiv(lhs, rhs) => cgExpr(lhs) <:> cgExpr(rhs) <:> Div
        case Mod(lhs, rhs) => cgExpr(lhs) <:> cgExpr(rhs) <:> Rem
        case LessThan(lhs, rhs) => cgExpr(lhs) <:> cgExpr(rhs) <:> Lt_s
        case LessEquals(lhs, rhs) => cgExpr(lhs) <:> cgExpr(rhs) <:> Le_s
        case AmyAnd(lhs, rhs) => cgExpr(Ite(lhs, rhs, BooleanLiteral(false)))
        case AmyOr(lhs, rhs) => cgExpr(Ite(lhs, BooleanLiteral(true), rhs)) 
        case Concat(lhs, rhs) => cgExpr(lhs) <:> cgExpr(rhs) <:> Call("String_concat")
        case Equals(lhs, rhs) => cgExpr(lhs) <:> cgExpr(rhs) <:> Eq

        // Unary operators
        case Not(e) => cgExpr(e) <:> Eqz
        case Neg(e) => Const(-1) <:> cgExpr(e) <:> Mul

        // Function/ type constructor call
        case AmyCall(qname, args) => 
            val signature = table.getFunction(qname).getOrElse(table.getConstructor(qname).get)
            signature match {
                case FunSig(_, _, owner) =>
                    val argsInstructions = args.map(x => cgExpr(x))
                    cs2c(argsInstructions) <:> Call(fullName(owner, qname))
                  
                case ConstrSig(_, _, index) => // Cf. http://lara.epfl.ch/~gschmid/clp18/codegen.pdf
                    val baseAdr = lh.getFreshLocal()
                    val b = GetGlobal(memoryBoundary)
                    
                    // Save the old memory boundary b
                    val saveBoundary = b <:> SetLocal(baseAdr) 
                    // Increment memory boundary by the size of the allocated ADT
                    val allocateMemory = b <:> adtField(args.size) <:> SetGlobal(memoryBoundary) 
                    // Store the constructor index to address b
                    val storeIndex = GetLocal(baseAdr) <:> Const(index) <:> Store
                    // For each field of the constructor, generate code for it and
                    // store it in memory in the correct offset from b
                    val storeArgs = args.zipWithIndex.foldLeft(Code(Nil)){
                        (acc, arg) => acc <:> GetLocal(baseAdr) <:> adtField(arg._2) <:> cgExpr(arg._1) <:> Store
                    }
                    // Put All together and Push b to the stack (base address of the ADT)
                    saveBoundary <:> allocateMemory<:> storeIndex <:> storeArgs <:> GetLocal(baseAdr)      
            }

        // The ; operator
        case Sequence(e1, e2) => cgExpr(e1) <:> Drop <:> cgExpr(e2)

        // Local variable definition
        case Let(df, value, body) => 
            val id = lh.getFreshLocal()
            val newLocals = locals + (df.name -> id)
            cgExpr(value) <:> SetLocal(id) <:> cgExpr(body)(newLocals, lh)
        
        // If-then-else
        case Ite(cond, thenn, elze) => cgExpr(cond) <:> If_i32 <:> cgExpr(thenn) <:> Else <:> cgExpr(elze) <:> End
        
        // Pattern matching
        case Match(scrut, cases) => // Cf. http://lara.epfl.ch/~gschmid/clp18/codegen.pdf
            def matchAndBind(pat: Pattern, ptr: Int): (Code, Map[Identifier, Int]) = pat match {
                case WildcardPattern() => (GetLocal(ptr) <:> Drop <:> Const(1), Map())
                case IdPattern(name) => 
                    val id = lh.getFreshLocal()
                    (GetLocal(ptr) <:> SetLocal(id) <:> Const(1), Map(name -> id))
                case LiteralPattern(lit) => (GetLocal(ptr) <:> cgExpr(lit) <:> Eq, Map())
                case CaseClassPattern(constr, args) => 
                    // Constructor address and signature
                    val signature = table.getConstructor(constr).get
                    
                    // Check if Both scrut and pattern are equals
                    val checkConstructor = GetLocal(ptr) <:> Load <:> Const(signature.index) <:> Eq 
                    
                    // Save all parameters as locals and check if they match and bind, starting with true (empty args)
                    var (checkArgs, newEnv) = args.zipWithIndex.foldRight((Code(List(Const(1))),  Map[Identifier, Int]())){
                        (arg, acc) => 
                            val ptrArg = lh.getFreshLocal()
                            val (caseMatch, newEnv) = matchAndBind(arg._1, ptrArg)
                            val saveMemory = GetLocal(ptr) <:> adtField(arg._2) <:> Load <:> SetLocal(ptrArg)
                            var matchArg = caseMatch <:> If_i32 <:> Const(1) <:> Else <:> Const(0) <:> End 
    
                            (acc._1 <:> saveMemory <:> matchArg <:> And, acc._2 ++ newEnv)
                    }
                                    
                    // Putting all together
                    (checkConstructor <:> checkArgs <:> And, newEnv)
            }
        
            val baseAdr = lh.getFreshLocal()
            val handleScrut = cgExpr(scrut) <:> SetLocal(baseAdr)
            val handleCases = cs2c(cases.map{
                case cse => 
                    val (caseMatch, newEnv) = matchAndBind(cse.pat, baseAdr)
                    caseMatch <:> If_i32 <:> cgExpr(cse.expr)(locals ++ newEnv, lh) <:> Else
            })
            val handleError = cgExpr(Error(StringLiteral("Match Error"))) <:> is2c(cases.map(x => End))
        
            handleScrut <:> handleCases <:> handleError
            

        // Represents a computational error; prints its message, then exits
        case Error(msg) => cgExpr(msg) <:> Call("Std_printString") <:> Unreachable
    }

    Module(
      program.modules.last.name.name,
      defaultImports,
      globalsNo,
      wasmFunctions ++ (program.modules flatMap cgModule)
    )

  }
}