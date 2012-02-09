package org.scalaquery.ast

import OptimizerUtil._
import collection.mutable.HashMap
import org.scalaquery.ql.{ConstColumn, RawNamedColumn, AbstractTable}

/**
 * Rewrite all generators to return exactly the required fields.
 */
object RewriteGenerators {

  def apply(tree: Node): Node = memoized[Node, Node](r => {
    //case fs @ RealFilterChain(filterSyms, Bind(_, _, _)) =>
    //TODO push additional columns needed by the filters into the Bind (without rewriting the existing ones)
    case b @ Bind(gen, from, what) if (what match {
      case Pure(StructNode(_)) => false // generated by rewriting a FilteredJoin, so skip it
      case _ => true
    }) =>
      val (skip, rewrite) = collectSkipAndRewriteSymbols(gen, from)
      println("*** skip: "+skip)
      println("*** rewrite: "+rewrite)
      val allSyms = rewrite.map(_._1).toSet
      val refsMap = collectReferences(b, skip, allSyms).iterator.map(n => (n, new AnonSymbol)).toMap
      val struct = refsMap.iterator.map{ case (n,s) => (s,n) }.toIndexedSeq
      //println("*** refs for "+allSyms+": "+refsMap.keys)
      //struct.dump("*** struct: ")

      val (withNewSelect, replacementMap) = {
        val isTableBased = findSelect(b.from).isInstanceOf[AbstractTable[_]]
        println("*** isTableBased: "+isTableBased)
        println("*** keys: "+refsMap.keys)
        if(isTableBased && refsMap.keys.forall(_.isInstanceOf[RawNamedColumn])) {
          // only column refs -> rewrite directly
          (b, refsMap.map { case (r: RawNamedColumn, _) => (r: Node, r.symbol) })
        } else (b.copy(from = replaceSelect(b.from, struct, allSyms)), refsMap)
      }
      val rewritten = replaceReferences(withNewSelect, skip, allSyms, replacementMap)
      rewritten.nodeMapChildren(r)
    case n => n.nodeMapChildren(r)
  })(tree)

  def collectSkipAndRewriteSymbols(s: Symbol, n: Node): (Set[Symbol], Seq[(Symbol, Node)]) = n match {
    case fq @ FilteredQuery(gen, from) =>
      val f = collectSkipAndRewriteSymbols(gen, from)
      (f._1, ((s, fq)) +: f._2)
    case _ =>
      (Set.empty[Symbol], Seq((s, n)))
  }

  def collectReferences(n: Node, skip: Set[Symbol], syms: Set[Symbol]): Set[Node] = n match {
    case InRef(sym, what) if syms contains sym => Set(what)
    case InRef(sym, _) if !(skip contains sym) => Set.empty
    case n => n.nodeChildren.map(ch => collectReferences(ch, skip, syms)).flatten.toSet
  }

  def replaceReferences(n: Node, skip: Set[Symbol], syms: Set[Symbol], repl: Map[Node, Symbol]): Node = n match {
    case InRef(sym, what) if syms contains sym => Path(sym, repl(what))
    case InRef(sym, _) if !(skip contains sym) => n
    case n => n.nodeMapChildren(ch => replaceReferences(ch, skip, syms, repl))
  }

  def findSelect(in: Node): Node = in match {
    case f: FilteredQuery => findSelect(f.from)
    case b @ Bind(_, _, Pure(_)) => b.from
    case b @ Bind(_, _, nonPure) => findSelect(nonPure)
    case n => n
  }

  def replaceSelect(in: Node, struct: IndexedSeq[(Symbol, Node)], genChain: Set[Symbol]): Node = in match {
    case f: FilteredQuery => f.nodeMapFrom(n => replaceSelect(n, struct, genChain))
    case b @ Bind(_, _, Pure(_)) => b.copy(select = StructNode(struct))
    case b @ Bind(gen, _, nonPure) => b.copy(select = replaceSelect(nonPure, struct, genChain))
    case t @ AbstractTable(_) =>
      val gen = new AnonSymbol
      val rewrapped = StructNode(struct.map { case (s,n) => (s, rewrap(n, genChain.iterator.map(s => (s, gen)).toMap, gen)) })
      rewrapped.dump("*** actual replacement: ")
      Bind(gen, t, Pure(rewrapped))
    case f @ FilteredJoin(leftGen, rightGen, left, right, jt, on) =>
      val gen = new AnonSymbol
      val rewrapped = StructNode(struct.map { case (s,n) => (s, rewrap(n, genChain.iterator.map(s => (s, gen)).toMap + (leftGen -> leftGen) + (rightGen -> rightGen), gen)) })
      StructNode(struct).dump("*** struct: ")
      rewrapped.dump("*** replacement for FilteredJoin: ")
      Bind(gen, f, Pure(rewrapped))
      //sys.error("not implemented")
  }

  def rewrap(n: Node, wrappers: Map[Symbol, Symbol], newWrapper: Symbol): Node = n match {
    case c @ RawNamedColumn(_, _, _) => Path(newWrapper, c.symbol)
    case InRef(sym, what) if wrappers.keySet contains sym => rewrap(what, wrappers, wrappers(sym))
    case n => n.nodeMapChildren(ch => rewrap(ch, wrappers, newWrapper))
  }



  def applyOld(tree: Node): Node = memoized[Node, Node](r => {
    case b @ Bind(gen, from, select) if !(from.isInstanceOf[BaseJoin] || from.isInstanceOf[FilteredJoin]) =>
      val selRefs = select.collectInRefTargets(gen)
      if(selRefs.isEmpty || !(findFilterSource(from).isInstanceOf[Bind]) ) b.nodeMapChildren(r)
      else { //TODO what if selRefs.isEmpty && !filterRefs.isEmpty?
        val (filterRefsSyms, filterRefs) = findFilterRefs(from)
        val selRefsToUnwrapped = selRefs.toSeq.map(r => (r, unwrap(filterRefsSyms, r))).toMap
        val filterRefsToUnwrapped = filterRefs.toSeq.map(r => (r, unwrap(filterRefsSyms, r))).toMap
        val allUnwrappedRefsToSyms = (selRefsToUnwrapped.values ++ filterRefsToUnwrapped.values).toSet.iterator.map((u: Node) => (u, new AnonSymbol)).toMap
        val struct = StructNode(allUnwrappedRefsToSyms.iterator.map{ case (u,s) => (s,u) }.toIndexedSeq)
        val fromRep = replaceSelect(from, Pure(struct), Nil)
        val newGens = fromRep.generatorsReplacer
        val newFilterRefsSyms = filterRefsSyms.map(newGens)
        val rFrom = r(fromRep.replaceSymbols(newGens))
        val rSel = r(select)
        val fromReplMap = filterRefsToUnwrapped.map{ case (w,u) => (u.replaceSymbols(newGens), Path(gen, allUnwrappedRefsToSyms(u)).replaceSymbols(newGens)) }
        //println("*** fromReplMap: "+fromReplMap)
        //fromReplMap.foreach(t => t._1.dump(t._2+" <- "))
        val selReplMap = selRefsToUnwrapped.mapValues(u => Path(gen, allUnwrappedRefsToSyms(u)).replaceSymbols(newGens))
        //println("*** selReplMap: "+selReplMap)
        //selReplMap.foreach(t => t._1.dump(t._2+" <- "))
        val b2 = b.copy(
          from = replaceReferences(gen, newFilterRefsSyms, fromReplMap, rFrom),
          select = replaceReferences(gen, newFilterRefsSyms, selReplMap, rSel))
        b2
      }
    case n => n.nodeMapChildren(r)
  })(tree)

  def unwrap(wrappers: Set[Symbol], n: Node): Node = n.replace {
    case InRef(sym, what) if wrappers contains sym => what
  }

  def replaceSelect(in: Node, select: Node, wrappers: List[Symbol]): Node = in match {
    case f: FilteredQuery => f.nodeMapFrom(n => replaceSelect(n, select, f.generator :: wrappers))
    case b @ Bind(_, _, Pure(_)) => b.copy(select = unwrap(wrappers.toSet, select))
    case b @ Bind(gen, _, nonPure) => b.copy(select = replaceSelect(nonPure, select, gen :: wrappers))
    //case FilteredJoin(_, _, )
  }

  def findFilterSource(n: Node): Node = n match {
    case FilteredQuery(_, from) => findFilterSource(from)
    case n => n
  }

  def findFilterRefs(n: Node): (Set[Symbol], Seq[Node]) = {
    def findSyms(n: Node): Seq[Symbol] = n match {
      case FilteredQuery(gen, from) => gen +: findSyms(from)
      case _ => IndexedSeq.empty
    }
    val syms = findSyms(n)
    val refs = syms.flatMap(n.collectInRefTargets)
    (syms.toSet, refs)
  }

  def replaceReferences(Sym: Symbol, filterGens: Set[Symbol], m: Map[Node, Node], n: Node): Node = {
    object TransitiveRef {
      def unapply(n: Node): Option[(Symbol, Node)] = {
        n match {
          case InRef(sym, what) if filterGens contains sym =>
            unapply(what) match {
              case Some((_, what)) => Some((sym, what))
              case None => Some((sym, what))
            }
          case _ => None
        }
      }
    }
    n.replace {
      case t @ TransitiveRef(sym, value) =>
        //value.dump("*** matched in "+sym+": "+m.get(value)+": ")
        m.get(value).map{ case Path(gen, n) => Path(sym, n) }.getOrElse(t)
      case i @ InRef(Sym, value) => m.get(value).getOrElse(i)
    }
  }
}
