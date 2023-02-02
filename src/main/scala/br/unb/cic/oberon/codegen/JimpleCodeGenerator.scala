package br.unb.cic.oberon.codegen

import br.unb.cic.oberon.ir.ast.{BoolValue => OberonBoolValue, CharValue => OberonCharValue, Expression => OberonExpression, IntValue => OberonIntValue, NullValue => OberonNullValue, RealValue => OberonRealValue, Statement => OberonStmt, StringValue => OberonStringValue, Type => OberonType, Value => OberonValue, _}
import br.unb.cic.oberon.ir.jimple._
import br.unb.cic.oberon.tc.{ExpressionTypeVisitor, TypeChecker}

import scala.collection.mutable.ListBuffer

object JimpleCodeGenerator extends CodeGenerator[ClassDecl] {
  override def generateCode(module: OberonModule): ClassDecl = {
    ClassDecl(
      modifiers = List(PublicModifer),
      classType = TObject(module.name),
      superClass = TObject("java.lang.Object"),
      interfaces = List.empty[Type],
      fields = generateFields(module),
      methods = List(generateMainMethod(module))
    )
  }

  def generateMainMethod(module: OberonModule): Method = Method(
    modifiers = List(PublicModifer, StaticModifier, FinalModifier),
    returnType = TVoid,
    name = "main",
    formals = List(TArray(TString)),
    exceptions = List.empty[Type],
    body = generateMainMethodBody(module),
  )

  def generateMainMethodBody(module: OberonModule): MethodBody = DefaultMethodBody(
    localVariableDecls = List.empty[LocalVariableDeclaration],
    stmts = generateStmt(module.stmt),
    catchClauses = List.empty[CatchClause],
  )

  def generateStmt(oberonStmt: OberonStmt): List[Statement] = generateStmt(Some(oberonStmt))

  def generateStmt(oberonStmt: Option[OberonStmt]): List[Statement] = oberonStmt match {
    case Some(someStmt) => someStmt match {
      case SequenceStmt(stmts) => stmts.flatMap(stmt => generateStmt(Some(stmt)))
      case AssignmentStmt(designator, exp) => designator match {
        case VarAssignment(varName) => List(AssignStmt(LocalVariable(varName), generateExpression(exp)))
      }
      case IfElseStmt(condition, thenStmt, elseStmt) => generateIfStmt(generateExpression(condition), generateStmt(thenStmt), generateStmt(elseStmt))
      case WhileStmt(condition, stmt) => generateWhileStmt(generateExpression(condition), generateStmt(stmt))
      case ForStmt(init, condition, stmt) => generateForStmt(generateStmt(init), generateExpression(condition), generateStmt(stmt))
    }
    case None => List.empty[Statement]
  }

  // FIXME: current label generation doesn't allow for multiple statements of the same kind
  def generateIfStmt(condition: Expression, thenStmts: List[Statement], elseStmts: List[Statement]): List[Statement] = {
    val buffer = ListBuffer[Statement]()

    buffer += IfStmt(condition, "then")
    buffer ++= elseStmts
    buffer += GotoStmt("endIf")
    buffer += LabelStmt("then")
    buffer ++= thenStmts
    buffer += LabelStmt("endIf")

    buffer.result()
  }

  def generateWhileStmt(condition: Expression, stmts: List[Statement]): List[Statement] = {
    val buffer = ListBuffer[Statement]()

    buffer += LabelStmt("while")
    buffer += IfStmt(condition, "endWhile")
    buffer ++= stmts
    buffer += GotoStmt("while")
    buffer += LabelStmt("endWhile")

    buffer.result()
  }

  // FIXME: currently behaves like a WHILE statement
  def generateForStmt(init: List[Statement], condition: Expression, stmts: List[Statement]) = {
    val buffer = ListBuffer[Statement]()

    buffer ++= init
    buffer += LabelStmt("for")
    buffer += IfStmt(condition, "endFor")
    buffer ++= stmts
    buffer += GotoStmt("for")
    buffer += LabelStmt("endFor")

    buffer.result()
  }

  def generateFields(module: OberonModule) = generateConstants(module) ::: generateVariables(module)

  def generateConstants(module: OberonModule): List[Field] = {
    val visitor = new ExpressionTypeVisitor(new TypeChecker())

    module.constants.map(constant => Field(
      modifiers = List(PublicModifer, FinalModifier),
      fieldType = jimpleType(visitor.visitExpression(constant.exp), module),
      name = constant.name
    ))
  }

  def generateVariables(module: OberonModule): List[Field] = module.variables.map(variable => Field(
    modifiers = List(PublicModifer),
    fieldType = jimpleType(variable.variableType, module),
    name = variable.name
  ))

  def generateUserDefinedTypes(module: OberonModule): List[Type] =
    module.userTypes.map(userType => jimpleUserDefinedType(userType.name, module))

  def generateMethodSignatures(module: OberonModule): List[MethodSignature] = module.procedures.map(procedure => MethodSignature(
    className = module.name,
    returnType = jimpleType(procedure.returnType, module),
    methodName = procedure.name,
    formals = procedure.args.map(arg => jimpleType(arg.argumentType, module))
  ))

  def generateExpression(oberonExpression: OberonExpression): Expression = oberonExpression match {
    case value: OberonValue => ImmediateExpression(ImmediateValue(jimpleValue(value)))
    case Brackets(exp) => generateExpression(exp)
    case PointerAccessExpression(_) => throw new Exception("Pointers are not yet supported by Jimple code generation.")

    case _ => throw new Exception("Non-exhaustive match in case statement.")
  }

  def jimpleValue(oberonValue: OberonValue): Value = oberonValue match {
    case OberonIntValue(int) => IntValue(int)
    case OberonBoolValue(bool) => BooleanValue(bool)
    case OberonRealValue(real) => DoubleValue(real)
    case OberonCharValue(char) => StringValue(char.toString)
    case OberonStringValue(string) => StringValue(string)
    case OberonNullValue => NullValue

    case _ => throw new Exception("Non-exhaustive match in case statement.")
  }

  def jimpleType(oberonType: Option[OberonType], module: OberonModule): Type = oberonType match {
    case Some(someType) => jimpleType(someType, module)
    case None => TVoid
  }

  def jimpleType(oberonType: OberonType, module: OberonModule): Type = oberonType match {
    case IntegerType => TInteger
    case RealType => TFloat
    case BooleanType => TBoolean
    case CharacterType => TCharacter
    case StringType => TString
    case UndefinedType => TUnknown
    case NullType => TNull

    case ReferenceToUserDefinedType(name) => jimpleUserDefinedType(name, module)
    case PointerType(_) => throw new Exception("Pointers are not yet supported by Jimple code generation.")

    case _ => throw new Exception("Non-exhaustive match in case statement.")
  }

  def jimpleUserDefinedType(name: String, module: OberonModule): Type =
    jimpleUserDefinedType(module.userTypes.find(userType => userType.name == name).get, module)

  def jimpleUserDefinedType(userType: UserDefinedType, module: OberonModule): Type = userType.baseType match {
    case RecordType(_) => TObject(userType.name)
    case ArrayType(_, baseType) => TArray(jimpleType(baseType, module))

    case _ => throw new Exception("Non-exhaustive match in case statement.")
  }
}