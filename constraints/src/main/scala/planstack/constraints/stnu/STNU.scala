package planstack.constraints.stnu

import planstack.anml.model.concrete.TPRef
import planstack.constraints.stn.STN
import planstack.structures.IList

trait STNU[ID] extends STN[TPRef,ID] {

  def start : Option[TPRef]
  def end : Option[TPRef]

  final def enforceContingent(u:TPRef, v:TPRef, min:Int, max:Int): Unit = {
    enforceContingent(u, v, min, max, None)
  }

  def enforceContingent(u:TPRef, v:TPRef, min:Int, max:Int, optID:Option[ID])

  def enforceContingentWithID(u:TPRef, v:TPRef, min:Int, max:Int, constID:ID) = {
    enforceContingent(u, v, min, max, Some(constID))
  }

  def addDispatchableTimePoint(tp : TPRef) : Int
  def addContingentTimePoint(tp : TPRef) : Int

  def recordTimePoint(tp: TPRef): Int

  def removeTimePoint(tp: TPRef): Unit = ???

  /** Set the distance from the global start of the STN to tp to time */
  override def setTime(tp: TPRef, time: Int): Unit = {
    assert(start.nonEmpty, "This stn has no recorded start time point.")
    addConstraint(start.get, tp, time)
    addConstraint(tp, start.get, -time)
  }

  protected def commitConstraint(u:Int, v:Int, w:Int, optID:Option[ID])

  /** creates a virtual time point virt with the constraint virt -- [dist,dist] --> real */
  def addVirtualTimePoint(virt: TPRef, real: TPRef, dist: Int) { ??? }

  /** Records a virtual time point that is still partially defined.
    * All constraints on this time point will only be processed when defined with method*/
  def addPendingVirtualTimePoint(virt: TPRef): Unit = ???

  /** Set a constraint virt -- [dist,dist] --> real. virt must have been already recorded as a pending virtual TP */
  def setVirtualTimePoint(virt: TPRef, real: TPRef, dist: Int): Unit = ???

  /** Record this time point as the global start of the STN */
  def recordTimePointAsStart(tp: TPRef): Int

  /** Unifies this time point with the global end of the STN */
  def recordTimePointAsEnd(tp: TPRef): Int

  /** Is this constraint possible in the underlying stnu ? */
  protected def isConstraintPossible(u: Int, v: Int, w: Int): Boolean

  def checksPseudoControllability : Boolean
  def checksDynamicControllability : Boolean

  /** If there is a contingent constraint [min, max] between those two timepoints, it returns
    * Some((min, max).
    * Otherwise, None is returned.
    */
  def contingentDelay(from:TPRef, to:TPRef) : Option[(Integer, Integer)]

  def controllability : Controllability

  /** Makes an independent clone of this STNU. */
  override def deepCopy(): STNU[ID]

  /** Returns a list of all timepoints in this STNU, associated with a flag giving its status
    * (contingent or controllable. */
  def timepoints : IList[TPRef]

  /** Returns the number of timep oints, exclding virtual time points */
  def numRealTimePoints : Int

  final def getEndTimePoint: Option[TPRef] = end

  final def getStartTimePoint: Option[TPRef] = start

  /** Returns the earliest time for the time point with id u */
  protected def earliestStart(u:Int) : Int

  /** Returns the latest time for the time point with id u */
  protected def latestStart(u:Int) : Int

  def getMinDelay(u:TPRef, v:TPRef) : Int
  def getMaxDelay(u: TPRef, v:TPRef) : Int

  /** Returns a list of all constraints that were added to the STNU.
    * Each constraint is associated with flaw to distinguish between contingent and controllable ones. */
  def constraints : IList[Constraint[ID]]
}