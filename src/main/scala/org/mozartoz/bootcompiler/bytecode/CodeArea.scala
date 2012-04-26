package org.mozartoz.bootcompiler
package bytecode

import scala.collection.mutable._

import ast._
import symtab._
import util._

class CodeArea(val abstraction: Abstraction) {
  val opCodes = new ArrayBuffer[OpCode]

  private val registerAllocs = new HashMap[Any, Register]

  def isDefined = !opCodes.isEmpty

  def size = opCodes.map(_.size).sum

  override def toString() = "<CodeArea %s>" format (abstraction.fullName)

  def dump(includeByteCode: Boolean = true) {
    println("constants:")
    for ((constant, index) <- constants.zipWithIndex)
      println("  K(%d) = %s" format (index, constant))

    if (includeByteCode) {
      println()
      for (opCode <- opCodes)
        println(opCode.code)
    }
  }

  private val YCounter = new RegCounter(YReg)

  val constants = new ArrayBuffer[Any]

  def registerFor(symbol: VariableSymbol): YOrGReg =
    innerRegisterFor(symbol).asInstanceOf[YOrGReg]

  def registerFor(symbol: BuiltinSymbol): KReg =
    innerRegisterFor(symbol).asInstanceOf[KReg]

  def registerFor(symbol: Symbol) =
    innerRegisterFor(symbol)

  def registerFor(codeArea: CodeArea) =
    innerRegisterFor(codeArea).asInstanceOf[KReg]

  def registerFor(constant: Constant) =
    innerRegisterFor(constant).asInstanceOf[KReg]

  def registerFor(arity: ConstantArity) =
    innerRegisterFor(arity).asInstanceOf[KReg]

  def registerFor(expr: VarOrConst): Register = expr match {
    case variable:Variable => registerFor(variable.symbol)
    case constant:Constant => registerFor(constant)
  }

  private def innerRegisterFor(key: Any) = {
    registerAllocs.getOrElseUpdate(key, {
      key match {
        case sym:VariableSymbol =>
          if (sym.isGlobal) GReg(sym.owner.globals.indexOf(sym))
          else YCounter.next()

        case _:BuiltinSymbol | _:CodeArea | _:Constant | _:ConstantArity =>
          constants += key
          KReg(constants.size - 1)
      }
    })
  }

  def += (opCode: OpCode) =
    opCodes += opCode

  class Hole(index: Int) {
    def fillWith(opCode: OpCode) {
      opCodes(index) = opCode
    }
  }

  def addHole(size: Int = 1) = {
    val index = opCodes.size
    opCodes += OpHole(size)
    new Hole(index)
  }

  def counting(body: => Unit) = {
    val before = opCodes.size
    body
    (before until opCodes.size) map (i => opCodes(i).size) sum
  }

  def computeXCount() = {
    var maxX = 0

    for {
      opCode <- opCodes
      XReg(index) <- opCode.arguments
    } {
      maxX = maxX max index
    }

    maxX + 1
  }

  val ccCodeArea = "codeArea" + abstraction.id.toString()
  val ccCreateMethodName = "createCodeArea" + abstraction.id.toString()

  def produceCC(out: Output) {
    import Output._

    out << """
       |/*
       |""".stripMargin

    Console.withOut(out.underlying) {
      abstraction.dump(includeByteCode = false)
    }

    out << """
       |*/
       |
       |void Program::%s() {
       |  ByteCode codeBlock[] = {
       |""".stripMargin % ccCreateMethodName

    for (opCode <- opCodes)
      out << "    %s,\n" % opCode.code

    out << """
       |  };
       |
       |  %s = new (vm) UnstableNode;
       |  %s->make<CodeArea>(vm, %d, codeBlock, sizeof(codeBlock), %d);
       |""".stripMargin % (ccCodeArea, ccCodeArea,
           constants.size, computeXCount())

    if (!constants.isEmpty) {
      out << """
         |  ArrayInitializer initializer = *%s;
         |  UnstableNode temp;
         |""".stripMargin % ccCodeArea

      for ((constant, index) <- constants.zipWithIndex) {
        produceCCInitConstant(out, constant)
        out << "  initializer.initElement(vm, %d, temp);\n" % (
            index, index)
      }
    }

    out << """
       |}
       |""".stripMargin
  }

  private def produceCCInitConstant(out: Output, constant: Any) {
    import Output._

    constant match {
      case ConstantArity(label, features) =>
        out << "  temp = buildArity(vm, "
        produceCCForConstant(out, label)

        for (feature <- features) {
          out << ", "
          produceCCForConstant(out, feature)
        }

        out << ");\n"

      case _ =>
        out << "  temp = trivialBuild(vm, "
        produceCCForConstant(out, constant)
        out << ");\n"
    }
  }

  private def produceCCForConstant(out: Output, constant: Any) {
    import Output._

    constant match {
      case builtin:BuiltinSymbol =>
        out << "::%s::builtin()" % builtin.ccFullName

      case codeArea:CodeArea =>
        out << "*%s" % codeArea.ccCodeArea

      case IntLiteral(value) =>
        out << value.toString()

      case FloatLiteral(value) =>
        out << value.toString()

      case Atom(value) =>
        out << "u\"%s\"" % value

      case True() =>
        out << "true"

      case False() =>
        out << "false"

      case UnitVal() =>
        out << "unit"
    }
  }
}
