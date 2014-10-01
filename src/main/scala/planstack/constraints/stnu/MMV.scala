package planstack.constraints.stnu

class MMV[ID](
    val edg : EDG,
    private var modified : List[E],
    protected var _consistent : Boolean)
  extends ISTNU[ID] with EDGListener
{
  def this() = this(new EDG(), Nil, true)

  def this(toCopy:MMV[ID]) = this(new EDG(toCopy.edg), toCopy.modified, toCopy._consistent)

  // record ourself as the listener of any event in the EDG
  assert(edg.listener == null, "Error: already a listener on this EDG")
  edg.listener = this

  // create start and end time points
  if(size == 0) {
    val myStart = addVar()
    val myEnd = addVar()
    assert(myStart == start)
    assert(myEnd == end)
  }

  def consistent = {
    checkConsistency()
  }

  /**
   * Creates a new time point and returns its ID. New constraints are inserted to place it before end and after start.
   *
   * @return ID of the created time point
   */
  def addVar(): Int =  {
    val n = edg.addVar()
    if(n != start && n != end) {
      this.enforceBefore(start, n)
      this.enforceBefore(n, end)
    } else if(n == end) {
      enforceBefore(start, end)
    }
    enforceInterval(n, n, 0, 0)
    n
  }

  def checkConsistency(): Boolean = checkConsistencyFromScratch()

  def checkConsistencyFromScratch(): Boolean = {
    while(modified.nonEmpty) {
      edg.apsp()
      var queue = modified
      modified = Nil
      while(queue.nonEmpty) {
        val e = queue.head
        queue = queue.tail

        var finished = false
        while(!finished) {
          var noNewEdges = true
          for (e <- edg.allEdges; if noNewEdges) {
            val additionAndRemovals: List[(List[E], List[E])] =
              edg.classicalDerivations(e)
            for ((toAdd, toRemove) <- additionAndRemovals) {
              for (edge <- toAdd)
                if(edg.addEdge(edge).nonEmpty)
                  noNewEdges = false
            }
            if(noNewEdges) finished = true
          }
        }
        /*
        val additionAndRemovals : List[(List[E],List[E])]=
//          edg.derivationsFastIDC(e)
          edg.classicalDerivations(e)
        var i = 0
        for((toAdd,toRemove) <- additionAndRemovals) {
          i += 1
          println("D"+i)
          for (edge <- toAdd)
            edg.addEdge(edge)
        }*/
      }
    }
    _consistent
  }

  /**
   * Write a dot serialisation of the graph to file
   * @param file
   */
  def writeToDotFile(file: String): Unit = ???

  /**
   * Returns the earliest start time of time point u with respect to the start time point of the STN
   * @param u
   * @return
   */
  def earliestStart(u: Int): Int =
    -edg.requirements.edgeValue(u, start).value

  /**
   * Returns the latest start time of time point u with respect to the start TP of the STN
   * @param u
   * @return
   */
  def latestStart(u: Int): Int =
    edg.requirements.edgeValue(start, u).value

  /**
   * Return the number of time points in the STN
   * @return
   */
  def size: Int = edg.size

  def addRequirement(from: Int, to: Int, value: Int): Boolean =
    edg.addRequirement(from, to, value).nonEmpty

  def addContingent(from: Int, to: Int, lb: Int, ub: Int): Boolean =
    (edg.addContingent(from, to, ub) ++ edg.addContingent(to, from, -lb)).nonEmpty

  def addConditional(from: Int, to: Int, on: Int, value: Int): Boolean =
    edg.addConditional(from, to, on, value).nonEmpty

  /**
   * Returns a complete clone of the STN.
   * @return
   */
  def cc(): MMV[ID] = new MMV[ID](this)

  /** Returns true if the given requirement edge is present in the STNU */
  protected[stnu] def hasRequirement(from: Int, to: Int, value: Int): Boolean =
    edg.hasRequirement(from, to, value)

  def edgeAdded(e: E): Unit = modified = e :: modified

  def cycleDetected(): Unit = _consistent = false
    //assert(!_consistent, "Should have been already detected through inconsistency.")

  def squeezingDetected(): Unit = _consistent = false

  /** Adds a constraint to the STN specifying that v - u <= w.
    * The constraint is associated with an ID than can be later used to remove the constraint.
    * @return True if the STN tightened due to this operation.
    */
  override def addConstraintWithID(u: Int, v: Int, w: Int, id: ID): Boolean = ???

  /** Removes all constraints that were recorded with the given ID */
  override def removeConstraintsWithID(id: ID): Boolean = ???

  override def inconsistencyDetected(): Unit = _consistent = false
}
