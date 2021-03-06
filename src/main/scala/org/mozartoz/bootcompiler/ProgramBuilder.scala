package org.mozartoz.bootcompiler

import ast._
import symtab._
import oz._
import transform._

/** Provides a method for building a program out of its parts */
object ProgramBuilder extends TreeDSL with TransformUtils {
  val treeCopy = new TreeCopier

  /** Builds a program that registers a user-defined module (i.e., a functor)
   *
   *  Given a URL <url> and a functor <functor>, the whole program is
   *  straightforward:
   *  {{{
   *  {<BootMM>.registerFunctor '<url>' <functor>}
   *  }}}
   */
  def buildModuleProgram(prog: Program, url: String, functor: Expression) {
    val registerProc = getBootMMProc(prog, "registerFunctor")
    prog.rawCode = registerProc.call(OzAtom(url), functor)
  }

  /** Builds a program that creates the base environment
   *
   *  The base functors must be functors indeed, and should have the following
   *  structure:
   *  {{{
   *  functor
   *
   *  require
   *     Boot_ModA at 'x-oz://boot/ModA'
   *     ...
   *
   *  prepare
   *     ...
   *
   *  exports
   *     'SomeVar':SomeVar
   *     ...
   *  end
   *  }}}
   *
   *  All the base functors are merged together as a single functor, that we
   *  call the base functor and write <BaseFunctor> from here.
   *
   *  The program statement applies this functor, giving it as imports all
   *  the boot modules. These are looked up in the boot modules map.
   *  The result of the application, which is the Base module, is stored in
   *  the outer-global variable <Base>.
   *
   *  Hence the program looks like this:
   *  {{{
   *  local
   *     Imports = 'import'(
   *        'Boot_ModA': <constant looked up in the boot modules map>
   *        ...
   *        'Boot_ModN': <...>
   *     )
   *  in
   *     <Base> = {<BaseFunctor>.apply Imports}
   *  end
   *  }}}
   *
   *  The resulting Base module must export an unbound variable under feature
   *  'Base', and the boot module manager it is supposed to have created under
   *  feature '$BootMM'.
   *
   *  The program then binds <Base>.'Base' to <Base>, and registers the boot
   *  modules in the boot module manager.
   *
   *  {{{
   *  <Base>.'Base' = <Base>
   *  <BootMM> = <Base>.'$BootMM'
   *  {<BootMM>.registerModule 'x-oz://boot/ModA' <constant Boot ModA>}
   *  ...
   *  {<BootMM>.registerModule 'x-oz://boot/ModN' <constant Boot ModN>}
   *  }}}
   */
  def buildBaseEnvProgram(prog: Program,
      bootModulesMap: Map[String, Expression],
      baseFunctors: List[Expression]) {

    // Merge all the base functors in one
    val baseFunctor = mergeBaseFunctors(
        baseFunctors map (_.asInstanceOf[FunctorExpression]))

    // Extract exports to fill in `prog.baseDeclarations`
    for (FunctorExport(Constant(OzAtom(name)), _) <- baseFunctor.exports) {
      prog.baseDeclarations += name
    }

    // Now starts the synthesis of the program statement

    // Application of the base functor
    val applyBaseFunctorStat = {
      val imports = {
        val reqs = baseFunctor.require ++ baseFunctor.imports

        val fields =
          for (FunctorImport(RawVariable(name), _, Some(location)) <- reqs)
            yield RecordField(OzAtom(name), bootModulesMap(location))

        Record(OzAtom("import"), fields.toList)
      }

      (baseFunctor dot OzAtom("apply")) call (imports, prog.baseEnvSymbol)
    }

    // Fill in <Base>.'Base'
    val bindBaseBaseStat = {
      (prog.baseEnvSymbol dot OzAtom("Base")) === prog.baseEnvSymbol
    }

    // Fetch the boot MM
    val fetchBootMMStat = {
      prog.bootMMSymbol === (prog.baseEnvSymbol dot OzAtom("$BootMM"))
    }

    // Register the boot modules
    val registerBootModulesStat = CompoundStatement {
      val registerProc = getBootMMProc(prog, "registerModule")
      for ((url, module) <- bootModulesMap.toList) yield {
        registerProc.call(OzAtom(url), module)
      }
    }

    // Put things together
    val wholeProgram = {
      applyBaseFunctorStat ~
      bindBaseBaseStat ~
      fetchBootMMStat ~
      registerBootModulesStat
    }

    prog.rawCode = wholeProgram
  }

  private def mergeBaseFunctors(functors: List[FunctorExpression]) = {
    atPos(functors.head) {
      functors.tail.foldLeft(functors.head) { (lhs, rhs) =>
        val FunctorExpression(lhsName, lhsRequire, lhsPrepare,
            lhsImports, lhsDefine, lhsExports) = lhs

        val FunctorExpression(rhsName, rhsRequire, rhsPrepare,
            rhsImports, rhsDefine, rhsExports) = rhs

        FunctorExpression(if (lhsName.isEmpty) rhsName else lhsName,
            lhsRequire ::: rhsRequire, mergePrepares(lhsPrepare, rhsPrepare),
            lhsImports ::: rhsImports, mergePrepares(lhsDefine, rhsDefine),
            lhsExports ::: rhsExports)
      }
    }
  }

  private def mergePrepares(lhs: Option[LocalStatementOrRaw],
      rhs: Option[LocalStatementOrRaw]) = {
    if (lhs.isEmpty) rhs
    else if (rhs.isEmpty) lhs
    else {
      val RawLocalStatement(lhsDecls, lhsStat) = lhs.get
      val RawLocalStatement(rhsDecls, rhsStat) = rhs.get

      Some(RawLocalStatement(lhsDecls ::: rhsDecls, lhsStat ~ rhsStat))
    }
  }

  /** Builds a linker program
   *
   *  The statement that is built is straightforward:
   *  {{{
   *  {<BootMM>.run '<mainURL>'}
   *  }}}
   */
  def buildLinkerProgram(prog: Program, urls: List[String], mainURL: String) {
    val runProc = getBootMMProc(prog, "run")
    prog.rawCode = runProc.call(OzAtom(mainURL))
  }

  private def getBootMMProc(prog: Program, proc: String): Expression = {
    Variable(prog.bootMMSymbol) dot OzAtom(proc)
  }
}
