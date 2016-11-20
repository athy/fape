package fr.laas.fape.constraints.stnu.structurals

import fr.laas.fape.anml.model.concrete.{ContingentConstraint, MinDelayConstraint, TPRef, TemporalConstraint}
import fr.laas.fape.anml.pending.IntExpression
import fr.laas.fape.constraints.stn.{DistanceGraphEdge, STN}
import fr.laas.fape.constraints.stnu.{Constraint, Controllability, InconsistentTemporalNetwork, STNU}
import fr.laas.fape.constraints.stnu.parser.STNUParser
import planstack.graph.core.LabeledEdge
import planstack.graph.printers.NodeEdgePrinter
import planstack.structures.IList

import scala.collection.mutable

object StnWithStructurals {

  var debugging = false

  val INF: Int = Int.MaxValue /2 -1 // set to avoid overflow on addition of int values
  val NIL: Int = 0

  def buildFromString(str: String) : StnWithStructurals[String] = {
    val stn = new StnWithStructurals[String]()
    val parser = new STNUParser
    parser.parseAll(parser.problem, str) match {
      case parser.Success((tps,constraints, optStart, optEnd),_) => {
        for(tp <- tps) {
          stn.recordTimePoint(tp)
        }
        optStart match {
          case Some(start) => stn.setStart(start)
          case None =>
        }
        optEnd match {
          case Some(end) => stn.setEnd(end)
          case None =>
        }
        for(constraint <- constraints) {
          stn.addConstraint(constraint)
        }
      }
      case x =>
        throw new RuntimeException("Malformed STNU textual input:\n"+x)
    }
    stn
  }
}

import StnWithStructurals._

class StnWithStructurals[ID](val nonRigidIndexes: mutable.Map[TPRef,Int],
                             val timepointByIndex: mutable.ArrayBuffer[TPRef],
                             var dist: DistanceMatrix,
                             val rigidRelations: RigidRelations,
                             val contingentLinks: mutable.ArrayBuffer[ContingentConstraint],
                             var optStart: Option[TPRef],
                             var optEnd: Option[TPRef],
                             var originalEdges: List[DistanceGraphEdge],
                             var consistent: Boolean
                            )
  extends STNU[ID] with DistanceMatrixListener {

  /** If true, the STNU will check that the network is Pseudo Controllable when incoking isConsistent */
  var shouldCheckPseudoControllability = true

  def this() = this(mutable.Map(), mutable.ArrayBuffer(), new DistanceMatrix(), new RigidRelations(), mutable.ArrayBuffer(), None, None, Nil, true)

  override def clone() : StnWithStructurals[ID] = new StnWithStructurals[ID](
    nonRigidIndexes.clone(), timepointByIndex.clone(), dist.clone(), rigidRelations.clone(), contingentLinks.clone(),
    optStart, optEnd, originalEdges, consistent
  )

  /** Callbacks to be invoked whenever the earliest start time of a timepoint changes */
  val earliestExecutionUpdatesListener = mutable.ArrayBuffer[TPRef => Unit]()

  /** Record a callback to be invoked whenever a the earliest start time of a timepoint is updated */
  def addEarliestExecutionUpdateListener(callback: TPRef => Unit) { earliestExecutionUpdatesListener += callback }

  // make sure we are notified of any change is the distance matrix
  dist.addListener(this)

  def timepoints = new IList[TPRef]((nonRigidIndexes.keySet ++ rigidRelations._anchorOf.keySet).toList)

  private def toIndex(tp:TPRef) : Int = nonRigidIndexes(tp)
  def timepointFromIndex(index: Int) : TPRef = timepointByIndex(index)

  private def isKnown(tp: TPRef) = nonRigidIndexes.contains(tp) || rigidRelations.isAnchored(tp)

  override def recordTimePoint(tp: TPRef): Int = {
    assert(!isKnown(tp))
    val id = dist.createNewNode()
    nonRigidIndexes.put(tp, id)
    rigidRelations.addAnchor(tp)
    while(timepointByIndex.size <= id) {
      timepointByIndex.append(null)
    }
    assert(timepointByIndex(id) == null)
    timepointByIndex(id) = tp
    optEnd match {
      case Some(end)  => enforceMinDelay(tp, end, 0)
      case None =>
    }
    id
  }

  def addMinDelay(from:TPRef, to:TPRef, minDelay:Int) =
    addEdge(to, from, -minDelay)

  def addMaxDelay(from: TPRef, to: TPRef, maxDelay: Int) =
    addMinDelay(to, from, -maxDelay)

  private def addEdge(a:TPRef, b :TPRef, t:Int): Unit = {
    originalEdges = new DistanceGraphEdge(a, b, t) :: originalEdges
    if(!isKnown(a))
      recordTimePoint(a)
    if(!isKnown(b))
      recordTimePoint(b)

    val (aRef:TPRef, aToRef:Int) =
      if(rigidRelations.isAnchored(a))
        (rigidRelations._anchorOf(a), rigidRelations.distFromAnchor(a))
      else
        (a, 0)
    val (bRef:TPRef, refToB) =
      if(rigidRelations.isAnchored(b))
        (rigidRelations._anchorOf(b), rigidRelations.distToAnchor(b))
      else (b, 0)
    dist.enforceDist(toIndex(aRef), toIndex(bRef), DistanceMatrix.plus(DistanceMatrix.plus(aToRef, t), refToB))
  }

  def addConstraint(c: TemporalConstraint): Unit = {
    c match {
      case req: MinDelayConstraint if req.minDelay.isKnown =>
        addMinDelay(req.src, req.dst, req.minDelay.get)
      case cont: ContingentConstraint if cont.min.isKnown && cont.max.isKnown =>
        addMinDelay(cont.src, cont.dst, cont.min.get)
        addMaxDelay(cont.src, cont.dst, cont.max.get)
        contingentLinks.append(cont)
      case _ =>
        throw new RuntimeException("Constraint: "+c+" is not properly supported")
    }
  }

  private def rigidAwareDist(a:TPRef, b:TPRef) : Int = {
    val (aRef:TPRef, aToRef:Int) =
      if(rigidRelations.isAnchored(a))
        (rigidRelations._anchorOf(a), rigidRelations.distToAnchor(a))
      else
        (a, 0)
    val (bRef:TPRef, refToB) =
      if(rigidRelations.isAnchored(b))
        (rigidRelations._anchorOf(b), rigidRelations.distFromAnchor(b))
      else (b, 0)

    val refAToRefB = distanceBetweenNonRigid(aRef, bRef)
    DistanceMatrix.plus(aToRef, DistanceMatrix.plus(refAToRefB, refToB))
  }

  private def distanceBetweenNonRigid(a: TPRef, b: TPRef) = {
    dist.getDistance(toIndex(a), toIndex(b))
  }

  def concurrent(tp1: TPRef, tp2: TPRef) = rigidAwareDist(tp1,tp2) == rigidAwareDist(tp2,tp1)

  private def minDelay(from: TPRef, to:TPRef) = -rigidAwareDist(to, from)
  private def maxDelay(from: TPRef, to: TPRef) = rigidAwareDist(from, to)
  private def beforeOrConcurrent(first: TPRef, second: TPRef) = rigidAwareDist(second, first) <= NIL
  private def strictlyBefore(first: TPRef, second: TPRef) = rigidAwareDist(second, first) < NIL
  private def between(tp: TPRef, min:TPRef, max:TPRef) = beforeOrConcurrent(min, tp) && beforeOrConcurrent(tp, max)
  private def strictlyBetween(tp: TPRef, min:TPRef, max:TPRef) = strictlyBefore(min, tp) && strictlyBefore(tp, max)

  override def distanceUpdated(a: Int, b: Int): Unit = {
    // check if the network is now inconsistent
    if (dist.getDistance(a, b) + dist.getDistance(b, a) < 0) {
      if(debugging)
        assert(!consistencyWithBellmanFord(), "Problem with the consistency of the STN")
      consistent = false
      throw new InconsistentTemporalNetwork
    }

    if (a == b)
      return

    // All timepoints whose earliest execution time is modified by this update.
    // This is only computed if their are listener to those changes and computed
    // before modifying the network to facilitate reasoning on anchored timepoints.
    val timepointsWithUpdatedStart =
      if(earliestExecutionUpdatesListener.nonEmpty && start.nonEmpty) {
        val stIndex =
          if (rigidRelations.isAnchored(start.get))
            toIndex(rigidRelations.anchorOf(start.get))
          else
            toIndex(start.get)
        if (b == stIndex) // b the start timepoint (or its anchor)
          timepointFromIndex(a) :: rigidRelations.getTimepointsAnchoredTo(timepointFromIndex(a))
        else
          Nil
      } else
        Nil

    // if there is a structural timepoint rigidly fixed to another, record this relation and simplify
    // the distance matrix
    if(dist.getDistance(a,b) == -dist.getDistance(b,a)) {
      val originalDist = dist.getDistance(a, b)
      val tpA = timepointByIndex(a)
      val tpB = timepointByIndex(b)
      assert(!rigidRelations.isAnchored(tpA))
      assert(!rigidRelations.isAnchored(tpB))

      // record rigid relation
      rigidRelations.addRigidRelation(tpA, tpB, dist.getDistance(a, b))

      val (anchored, anchor) =
        if(rigidRelations.isAnchored(tpA)) (tpA, tpB)
        else if(rigidRelations.isAnchored(tpB)) (tpB,tpA)
        else throw new RuntimeException("No timepoint is considered as anchored after recording a new rigid relation")

      // remove the anchored timepoint from distance matrix
      dist.compileAwayRigid(toIndex(anchored), toIndex(anchor))
      timepointByIndex(toIndex(anchored)) = null
      nonRigidIndexes.remove(anchored)
      assert(originalDist == rigidAwareDist(tpA, tpB))
    }

    // notify listeners of updated start times
    for(listener <- earliestExecutionUpdatesListener ; tp <- timepointsWithUpdatedStart)
      listener.apply(tp)
  }

  /** Makes an independent clone of this STN. */
  override def deepCopy(): StnWithStructurals[ID] = clone()

  /** Record this time point as the global start of the STN */
  override def recordTimePointAsStart(tp: TPRef): Int = {
    if(!isKnown(tp))
      recordTimePoint(tp)
    setStart(tp)
    nonRigidIndexes(tp)
  }

  def setStart(start: TPRef): Unit = {
    assert(isKnown(start))
    assert(optStart.isEmpty || optStart.get == start)
    optStart = Some(start)
    optEnd match {
      case Some(end) => enforceMinDelay(start, end, 0)
      case None =>
    }
  }

  /** Unifies this time point with the global end of the STN */
  override def recordTimePointAsEnd(tp: TPRef): Int = {
    if(!isKnown(tp))
      recordTimePoint(tp)
    setEnd(tp)
    nonRigidIndexes(tp)
  }

  def setEnd(end: TPRef): Unit = {
    assert(isKnown(end))
    assert(optEnd.isEmpty || optEnd.get == end)
    optEnd = Some(end)
    for(tp <- timepoints.asScala) {
      enforceBefore(tp, end)
    }
    optStart match {
      case Some(start) => enforceMinDelay(start, end, 0)
      case None =>
    }
  }

  /** Returns true if the STN is consistent (might trigger a propagation */
  override def isConsistent(): Boolean = {
    if(debugging) {
      checkCoherenceWrtBellmanFord
    }
    consistent &&
      (!shouldCheckPseudoControllability ||
        contingentLinks.forall(l => isDelayPossible(l.src, l.dst, l.min.lb) && isConstraintPossible(l.src, l.dst, l.max.ub)))
  }

  /** Removes all constraints that were recorded with this id */
  override def removeConstraintsWithID(id: ID): Boolean = ???

  override protected def addConstraint(u: TPRef, v: TPRef, w: Int): Unit =
    addMaxDelay(u, v, w)

  override protected def isConstraintPossible(u: TPRef, v: TPRef, w: Int): Boolean =
    w + rigidAwareDist(v, u) >= 0

  override def exportToDotFile(filename: String, printer: NodeEdgePrinter[Object, Object, LabeledEdge[Object, Object]]): Unit = ???

  /** Remove a timepoint and all associated constraints from the STN */
  override def removeTimePoint(tp: TPRef): Unit = ???

  /** Set the distance from the global start of the STN to tp to time */
  override def setTime(tp: TPRef, time: Int): Unit =
    optStart match {
      case Some(st) =>
        addMinDelay(st, tp, time)
        addMaxDelay(st, tp, time)
      case None => sys.error("This STN has no start timepoint")
    }


  /** Returns the minimal time from the start of the STN to u */
  override def getEarliestStartTime(u: TPRef): Int =
    optStart match {
      case Some(st) => minDelay(st, u)
      case None => sys.error("This STN has no start timepoint")
    }

  /** Returns the maximal time from the start of the STN to u */
  override def getLatestStartTime(u: TPRef): Int =
    optStart match {
      case Some(st) => maxDelay(st, u)
      case None => sys.error("This STN has no start timepoint")
    }


  override protected def addConstraintWithID(u: TPRef, v: TPRef, w: Int, id: ID): Unit =
    addConstraint(u, v, w)

  /**
    * Computes the max delay from a given timepoint to all others using Bellman-Ford on the original edges.
    * This is expensive (O(V*E)) but is useful for providing a reference to compare to when debugging.
    */
  private def distancesFromWithBellmanFord(from: TPRef) : Array[Int] = {
    // initialize distances
    val d = new Array[Int](99999)
    for(tp <- timepoints.asScala)
      d(tp.id) = INF
    d(from.id) = 0

    // compute distances
    val numIters = timepoints.size
    for(i <- 0 until numIters) {
      for(e <- originalEdges) {
        d(e.to.id) = Math.min(d(e.to.id), DistanceMatrix.plus(d(e.from.id), e.value))
      }
    }
    d
  }

  /**
    * Computes the max delay between two timepoints using Bellman-Ford on the original edges.
    * This is expensive (O(V*E)) but is useful for providing a reference to compare to when debugging.
    */
  private def distanceWithBellmanFord(from: TPRef, to: TPRef): Int = {
    distancesFromWithBellmanFord(from)(to.id)
  }

  /**
    * Determine whether the STN is consistent using Bellman-Ford on the original edges.
    * This is expensive (O(V*E)) but is useful for providing a reference to compare to when debugging.
    */
  private def consistencyWithBellmanFord(): Boolean = {
    // when possible, use "end" as the source as it normally linked with all other timepoints
    val from = optEnd match {
      case Some(end) => end
      case None => timepoints.head
    }
  val d = distancesFromWithBellmanFord(from)

    // if a distance can still be updated, there is a negative cycle
    for(e <- originalEdges) {
      if(d(e.to.id) > d(e.from.id) + e.value)
        return false
    }
    true
  }

  private def checkCoherenceWrtBellmanFord: Unit = {
    for(tp <- timepoints.asScala) {
      val d = distancesFromWithBellmanFord(tp)
      for(to <- timepoints.asScala) {
        assert(maxDelay(tp, to) == d(to.id))
      }
    }
  }

  override def enforceContingent(u: TPRef, v: TPRef, min: Int, max: Int, optID: Option[ID]): Unit = {
    addMinDelay(u, v, min)
    addMaxDelay(u, v, max)
    contingentLinks.append(new ContingentConstraint(u, v, IntExpression.lit(min), IntExpression.lit(max)))
  }

  override def getMaxDelay(u: TPRef, v: TPRef): Int = maxDelay(u, v)

  override def checksPseudoControllability: Boolean = true

  override def checksDynamicControllability: Boolean = false

  override def controllability: Controllability = Controllability.PSEUDO_CONTROLLABILITY

  /** If there is a contingent constraint [min, max] between those two timepoints, it returns
    * Some((min, max).
    * Otherwise, None is returned.
    */
  override def contingentDelay(from: TPRef, to: TPRef): Option[(Integer, Integer)] =
    contingentLinks.find(l => l.src == from && l.dst == to) match {
      case Some(x) => Some(x.min.lb.asInstanceOf[Integer], x.max.ub.asInstanceOf[Integer])
      case None => None
    }

  override def getMinDelay(u: TPRef, v: TPRef): Int = minDelay(u, v)

  override def start: Option[TPRef] = optStart

  override def end: Option[TPRef] = optEnd

  override def constraints : IList[TemporalConstraint] = {
    /** Builds the neighborhood of a groupd of structural timepoints */
    def structuralNeighborhood(neighborhood: Set[TPRef], nextNeighbors: Set[TPRef]): Set[TPRef] = {
      assert(neighborhood.intersect(nextNeighbors).isEmpty)
      assert(nextNeighbors.forall(_.genre.isStructural))
      assert(neighborhood.forall(_.genre.isStructural))

      if(nextNeighbors.isEmpty)
        return neighborhood // no new nodes to process, return the current neightborhood
      val tp = nextNeighbors.head

      if(rigidRelations.isAnchored(tp) && !rigidRelations.anchorOf(tp).genre.isStructural) {
        // node is rigid, do not consider its own neighbors
        return structuralNeighborhood(neighborhood + tp, nextNeighbors.tail)
      }
      // in other cases, the neighborhood is expanded with this node and the neighborhood of all its neighbors
      val directNeighbors = originalEdges // should include neighborhood of other (non-rigid?) structurals
        .filter(e => e.from == tp || e.to == tp)
        .flatMap(e => e.from :: e.to :: Nil)
        .filter(_.genre.isStructural)
        .toSet
      return structuralNeighborhood(neighborhood+tp, nextNeighbors ++ (directNeighbors--neighborhood) -tp)
    }
    /** Returns the anchor of 'tp' if tp is anchored and 'tp' otherwise*/
    def anchorOrSelf(tp: TPRef) =
      if(rigidRelations.isAnchor(tp)) tp else rigidRelations.anchorOf(tp)

    /** Returns all non-structural nodes taht touch the structural neighborhood **/
    def connections(tp: TPRef) = {
      assert(tp.genre.isStructural)
      assert(rigidRelations.isAnchor(tp))
      val structuralNeighbors = structuralNeighborhood(Set(), Set(tp))
      val nonStructuralNeighbours = originalEdges
        .filter(e => structuralNeighbors.contains(e.from) || structuralNeighbors.contains(e.to))
        .flatMap(e => e.from :: e.to :: Nil)
        .filter(!_.genre.isStructural)
        .toSet ++
        structuralNeighbors
          .filter(x => rigidRelations.isAnchored(x) && !rigidRelations._anchorOf(x).genre.isStructural)
          .map(x => rigidRelations.anchorOf(x))
      nonStructuralNeighbours
    }

    val pairs = mutable.Set[(TPRef,TPRef)]()
    // consider all edges, with start/end timepoints projected on their anchors
    for(c <- originalEdges) {
      pairs += ((anchorOrSelf(c.from), anchorOrSelf(c.to)))
    }
    pairs.retain(p => !p._1.genre.isStructural && !p._2.genre.isStructural)
    for(tp <- timepoints.asScala if tp.genre.isStructural && rigidRelations.isAnchor(tp)) {
      val neighborhood = connections(tp)
      for(tp1 <- neighborhood ; tp2 <- neighborhood) {
        pairs += ((tp1,tp2))
        pairs += ((tp2,tp1))
      }
    }
    pairs.retain(p => p._1 != p._2)

    // constraints between non structurals that are anchored
    for(tp <- timepoints.asScala if !tp.genre.isStructural && rigidRelations.isAnchored(tp) && !rigidRelations.anchorOf(tp).genre.isStructural) {
      pairs += ((tp, rigidRelations.anchorOf(tp)))
      pairs += ((rigidRelations.anchorOf(tp), tp))
    }

    return new IList(contingentLinks.toList ++
      pairs.map(p => new MinDelayConstraint(p._2, p._1, IntExpression.lit(minDelay(p._2, p._1)))))
  }
}
