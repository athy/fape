package fape.core.planning.planner;

import fape.core.execution.model.AtomicAction;
import fape.core.planning.Plan;
import fape.core.planning.planninggraph.PGPlanner;
import fape.core.planning.preprocessing.ActionDecompositions;
import fape.core.planning.preprocessing.ActionSupporterFinder;
import fape.core.planning.preprocessing.LiftedDTG;
import fape.core.planning.search.*;
import fape.core.planning.search.strategies.flaws.FlawCompFactory;
import fape.core.planning.search.strategies.plans.PlanCompFactory;
import fape.core.planning.states.State;
import fape.core.planning.temporaldatabases.ChainComponent;
import fape.core.planning.temporaldatabases.TemporalDatabase;
import fape.exceptions.FAPEException;
import fape.util.Pair;
import fape.util.TimeAmount;
import fape.util.TinyLogger;
import planstack.anml.model.LVarRef;
import planstack.anml.model.concrete.ActRef;
import planstack.anml.model.AnmlProblem;
import planstack.anml.model.abs.AbstractAction;
import planstack.anml.model.abs.AbstractDecomposition;
import planstack.anml.model.concrete.*;
import planstack.anml.model.concrete.statements.LogStatement;
import planstack.anml.model.concrete.statements.Statement;
import planstack.anml.parser.ParseResult;

import java.util.*;


/**
 * Base for any planner in FAPE. It defines all basic operations useful for planning such
 * as alterations of search states, inclusions of ANML blocks ...
 *
 * Classes that inherit from it only have to implement the abstract methods to provide a search policy.
 * Overriding methods can also be done to override the default behaviour.
 */
public abstract class APlanner {


    public static boolean debugging = true;
    public static boolean logging = true;
    public static boolean actionResolvers = true; // do we add actions to resolve flaws?

    public int GeneratedStates = 1; //count the initial state
    public int OpenedStates = 0;

    public final AnmlProblem pb = new AnmlProblem();
    LiftedDTG dtg = null;

    /**
     * Used to build comparators for flaws.
     * Default to a least commiting first.
     */
    public String[] flawSelStrategies = { "lcf" };

    /**
     * Used to build comparators for partial plans.
     */
    public String[] planSelStrategies = { "soca" };

    /**
     * A short identifier for the planner.
     * @return THe planner ID.
     */
    public abstract String shortName();

    public abstract ActionSupporterFinder getActionSupporterFinder();

    /**
     *
     */
    public PriorityQueue<State> queue;

    public boolean ApplyOption(State next, SupportOption o, TemporalDatabase consumer) {
        TemporalDatabase supporter = null;
        ChainComponent precedingComponent = null;
        if (o.temporalDatabase != -1) {
            supporter = next.GetDatabase(o.temporalDatabase);
            if (o.precedingChainComponent != -1) {
                precedingComponent = supporter.GetChainComponent(o.precedingChainComponent);
            }
        }

        //now we can happily apply all the options
        if (supporter != null && precedingComponent != null) {
            assert consumer != null : "Consumer was not passed as an argument";
            // this is database merge of one persistence into another
            assert consumer.chain.size() == 1 && !consumer.chain.get(0).change
                    : "This is restricted to databases containing single persistence only";

            ChainComponent supportingStatement = precedingComponent;
            if(this instanceof PGPlanner && supportingStatement.change) {
                Action supportingAction = next.getActionContaining(precedingComponent.contents.getFirst());
                if(supportingAction != null) {
                    PGPlanner self = (PGPlanner) this;
                    for(ActionWithBindings candidate : self.rpgActionSupporters(consumer, next)) {
                        if(candidate.act != supportingAction.abs())
                            continue;
                        for(LVarRef lvar : candidate.values.keySet()) {
                            next.conNet.restrictDomain(supportingAction.context().getGlobalVar(lvar), candidate.values.get(lvar));
                        }
                    }
                }

            }

            next.tdb.InsertDatabaseAfter(next, supporter, consumer, precedingComponent);



        } else if (supporter != null) {

            assert consumer != null : "Consumer was not passed as an argument";

            ChainComponent supportingStatement = null;
            if(supporter.chain.getLast().change)
                supportingStatement = supporter.chain.getLast();
            else if(supporter.chain.size()>1) {
                supportingStatement = supporter.chain.get(supporter.chain.size()-2);
            }
            if(this instanceof PGPlanner && supportingStatement != null && supportingStatement.change) {
                Action supportingAction = next.getActionContaining(supportingStatement.contents.getFirst());
                if(supportingAction != null) {
                    PGPlanner self = (PGPlanner) this;
                    for(ActionWithBindings candidate : self.rpgActionSupporters(consumer, next)) {
                        if(candidate.act != supportingAction.abs())
                            continue;
                        for(LVarRef lvar : candidate.values.keySet()) {
                            next.conNet.restrictDomain(supportingAction.context().getGlobalVar(lvar), candidate.values.get(lvar));
                        }
                    }
                }
            }


            // database concatenation
            next.tdb.InsertDatabaseAfter(next, supporter, consumer, supporter.chain.getLast());

        } else if (o.supportingAction != null) {

            assert consumer != null : "Consumer was not passed as an argument";

            Action action = Factory.getStandaloneAction(pb, o.supportingAction);
            next.insert(action);

            // create the binding between consumer and the new statement in the action that supports it
            TemporalDatabase supportingDatabase = null;
            for (Statement s : action.statements()) {
                if(s instanceof LogStatement && next.canBeEnabler((LogStatement) s, consumer)) {
                    assert supportingDatabase == null : "Error: several statements might support the database";
                    supportingDatabase = next.tdb.getDBContaining((LogStatement) s);
                }
            }
            if (supportingDatabase == null) {
                return false;
            } else {
                SupportOption opt = new SupportOption();
                opt.temporalDatabase = supportingDatabase.mID;
                return ApplyOption(next, opt, consumer);
            }
        } else if (o.actionToDecompose != null) {
            // Apply the i^th decomposition of o.actionToDecompose, where i is given by
            // o.decompositionID

            // Action to decomposed
            Action decomposedAction = o.actionToDecompose;

            // Abstract version of the decomposition
            AbstractDecomposition absDec = decomposedAction.decompositions().get(o.decompositionID);

            // Decomposition (ie implementing StateModifier) containing all changes to be made to a search state.
            Decomposition dec = Factory.getDecomposition(pb, decomposedAction, absDec);

            // remember that the consuming db has to be supporting by a descendant of this decomposition.
            if(consumer != null)
                next.addSupportConstraint(consumer.GetChainComponent(0), dec);

            next.applyDecomposition(dec);
        } else if(o.actionWithBindings != null) {
            Action act = Factory.getStandaloneAction(pb, o.actionWithBindings.act);
            next.insert(act);

            // restrict domain of given variables to the given set of variables.
            for(LVarRef lvar : o.actionWithBindings.values.keySet()) {
                next.conNet.restrictDomain(act.context().getGlobalVar(lvar), o.actionWithBindings.values.get(lvar));
            }

            // create the binding between consumer and the new statement in the action that supports it
            if(consumer != null)  {
                TemporalDatabase supportingDatabase = null;
                for (Statement s : act.statements()) {
                    if(s instanceof LogStatement && next.canBeEnabler((LogStatement) s, consumer)) {
                        assert supportingDatabase == null : "Error: several statements might support the database";
                        supportingDatabase = next.tdb.getDBContaining((LogStatement) s);
                    }
                }
                if (supportingDatabase == null) {
                    return false;
                } else {
                    SupportOption opt = new SupportOption();
                    opt.temporalDatabase = supportingDatabase.mID;
                    return ApplyOption(next, opt, consumer);
                }
            }

        } else if(o instanceof TemporalSeparation) {
            for(LogStatement first : ((TemporalSeparation) o).first.chain.getLast().contents) {
                for(LogStatement second : ((TemporalSeparation) o).second.chain.getLast().contents) {
                    next.tempoNet.EnforceBefore(first.end(), second.start());
                }
            }
        } else if(o instanceof BindingSeparation) {
            next.conNet.AddSeparationConstraint(((BindingSeparation) o).a, ((BindingSeparation) o).b);
        } else if(o instanceof VarBinding) {
            List<String> values = new LinkedList<>();
            values.add(((VarBinding) o).value);
            next.conNet.restrictDomain(((VarBinding) o).var, values);
        } else {
            throw new FAPEException("Unknown option.");
        }

        // if the propagation failed and we have achieved an inconsistent state
        return next.isConsistent();
    }

    /**
     * we remove the action results from the system
     *
     * @param
     */
    public void FailAction(ActRef actionRef) {
        KeepBestStateOnly();
        if (best == null) {
            throw new FAPEException("No current state.");
        } else {
            State bestState = GetCurrentState();

            bestState.setActionFailed(actionRef);
        }
    }

    /**
     * Set the action ending to the its real end time. It removes the duration
     * constraints between the starts and the end of the action (as given by the
     * duration anml variable). Adds a new constraint [realEndTime, realEndtime]
     * between the global start and the end of action time points.
     *
     * @param actionID
     * @param realEndTime
     */
    public void AddActionEnding(ActRef actionID, int realEndTime) {
        KeepBestStateOnly();
        State bestState = GetCurrentState();
        bestState.setActionSuccess(actionID);
        Action a = bestState.taskNet.GetAction(actionID);
        // remove the duration constraints of the action
        bestState.tempoNet.RemoveConstraints(new Pair(a.start(), a.end()), new Pair(a.end(), a.start()));
        // insert new constraint specifying the end time of the action
        bestState.tempoNet.EnforceConstraint(pb.start(), a.end(), realEndTime, realEndTime);
        TinyLogger.LogInfo("Overriding constraint.");
    }

    /**
     * Pushes the earliest execution time point forward.
     * Causes all pending actions to be delayed
     * @param earliestExecution
     */
    public void SetEarliestExecution(int earliestExecution) {
        KeepBestStateOnly();
        State s = GetCurrentState();
        s.tempoNet.EnforceDelay(pb.start(), pb.earliestExecution(), earliestExecution);
        // If the STN is not consistent after this addition, the the current plan is not feasible.
        // Full replan is necessary
        if(!s.tempoNet.IsConsistent()) {
            this.best = null;
            this.queue.clear();
        }
    }

    /**
     *
     */
    public enum EPlanState {

        TIMEOUT,
        /**
         *
         */
        CONSISTENT,
        /**
         *
         */
        INCONSISTENT,
        /**
         *
         */
        INFESSIBLE,
        /**
         *
         */
        UNINITIALIZED
    }
    /**
     * what is the current state of the plan
     */
    public EPlanState planState = EPlanState.UNINITIALIZED;

    //current best state
    private State best = null;

    /**
     * initializes the data structures of the planning problem
     *
     */
    public void Init() {
        queue = new PriorityQueue<State>(100, this.stateComparator());
        queue.add(new State(pb));
        best = queue.peek();
    }

    /**
     *
     * @return
     */
    public State GetCurrentState() {
        return best;
    }

    /**
     * Remove all states in the queues except for the best one (which is stored
     * in best). This is to be used when updating the problem to make sure we
     * don't keep any outdated states.
     */
    public void KeepBestStateOnly() {
        queue.clear();

        if (best == null) {
            TinyLogger.LogInfo("No known best state.");
        } else {
            queue.add(best);
        }

    }

    public final List<SupportOption> GetResolvers(State st, Flaw f) {
        List<SupportOption> candidates;
        if(f instanceof UnsupportedDatabase) {
            candidates = GetSupporters(((UnsupportedDatabase) f).consumer, st);
        } else if(f instanceof UndecomposedAction) {
            UndecomposedAction ua = (UndecomposedAction) f;
            candidates = new LinkedList<>();
            for(int decompositionID=0 ; decompositionID < ua.action.decompositions().size() ; decompositionID++) {
                SupportOption res = new SupportOption();
                res.actionToDecompose = ua.action;
                res.decompositionID = decompositionID;
                candidates.add(res);
            }
        } else if(f instanceof Threat) {
            candidates = GetResolvers(st, (Threat) f);
        } else if(f instanceof UnboundVariable) {
            candidates = GetResolvers(st, (UnboundVariable) f);
        } else {
            throw new FAPEException("Unknown flaw type: " + f);
        }

        return st.retainValidOptions(f, candidates);
    }

    public final List<SupportOption> GetResolvers(State st, UnboundVariable uv) {
        List<SupportOption> bindings = new LinkedList<>();
        for(String value : st.conNet.domainOf(uv.var)) {
            bindings.add(new VarBinding(uv.var, value));
        }
        return bindings;
    }

    public final List<SupportOption> GetResolvers(State st, Threat f) {
        List<SupportOption> options = new LinkedList<>();
        options.add(new TemporalSeparation(f.db1, f.db2));
        options.add(new TemporalSeparation(f.db2, f.db1));
        for(int i=0 ; i<f.db1.stateVariable.jArgs().size() ; i++) {
            options.add(new BindingSeparation(
                    f.db1.stateVariable.jArgs().get(i),
                    f.db2.stateVariable.jArgs().get(i)));
        }
        return options;
    }

    /**
     * Finds all flaws of a given state.
     * Currently, threats and unbound variables are considered only if no other flaws are present.
     * @return A list of flaws present in the system. The set of flaw might not be exhaustive.
     */
    public List<Flaw> GetFlaws(State st) {
        List<Flaw> flaws = new LinkedList<>();
        for(TemporalDatabase consumer : st.consumers) {
            flaws.add(new UnsupportedDatabase(consumer));
        }
        for(Action refinable : st.taskNet.GetOpenLeaves()) {
            flaws.add(new UndecomposedAction(refinable));
        }
        if(flaws.isEmpty()) {
            for(int i=0 ; i<st.tdb.vars.size() ; i++) {
                TemporalDatabase db1 = st.tdb.vars.get(i);
                for(int j=i+1 ; j<st.tdb.vars.size() ; j++) {
                    TemporalDatabase db2 = st.tdb.vars.get(j);
                    if(isThreatening(st, db1, db2)) {
                        flaws.add(new Threat(db1, db2));
                    }
                }
            }
        }
        if(flaws.isEmpty()) {
            for(VarRef v : st.conNet.getUnboundVariables()) {
                flaws.add(new UnboundVariable(v));
            }
        }
        return flaws;
    }

    /**
     * Implementation of search. An easy thing to do to forward this call to the aStar method.
     * @param forhowLong Max time the planner is allowed to run.
     * @return A solution state if the planner found one. null otherwise.
     */
    public abstract State search(TimeAmount forhowLong);

    /**
     * Provides a comparator that is used to sort flaws. THe first flaw will be selected to be resolved.
     * @param st State in which the flaws appear.
     * @return The comparator to use for ordering.
     */
    public final Comparator<Pair<Flaw, List<SupportOption>>> flawComparator(State st) {
        return FlawCompFactory.get(st, flawSelStrategies);
    }

    /**
     * The comparator used to order the queue. THe first state in the queue (according to this comparator,
     * will be selected for expansion.
     * @return The comparator to use for ordering the queue.
     */
    public final Comparator<State> stateComparator() {
        return PlanCompFactory.get(planSelStrategies);
    }

    /**
     * Checks if two temporal databases are threatening each others.
     * It is the case if:
     *   - both are not consuming databases (start with an assignment). Otherwise, threat is naturally handled
     *     by looking for supporters.
     *   - their state variables are unifiable.
     *   - they can overlap
     * TODO: handle case where multiple persitences are in the last part.
     * @return True there is a threat.
     */
    protected boolean isThreatening(State st, TemporalDatabase db1, TemporalDatabase db2) {
        if(db1.isConsumer() || db2.isConsumer()) {
            return false;
        } else if(st.Unifiable(db1, db2)) {
            if(st.tempoNet.CanBeBefore(
                    db1.GetChainComponent(0).getConsumeTimePoint(),                        // c1
                    db2.GetChainComponent(db2.chain.size()-1).getSupportTimePoint()) &&    // s2
               st.tempoNet.CanBeBefore(
                    db2.GetChainComponent(db2.chain.size()-1).getSupportTimePoint(),       // s2
                    db1.GetChainComponent(db1.chain.size()-1).getSupportTimePoint())) {    // s1
                return true;
            } else if(
                    st.tempoNet.CanBeBefore(
                            db2.GetChainComponent(0).getConsumeTimePoint(),                     // c2
                            db1.GetChainComponent(db1.chain.size()-1).getSupportTimePoint()) && // s1
                    st.tempoNet.CanBeBefore(
                            db1.GetChainComponent(0).getConsumeTimePoint(),                     // c1
                            db2.GetChainComponent(0).getConsumeTimePoint())) {                  // c2
                return true;
            } else {
                return false;
            }
        }
        return false;
    }

    protected State aStar(TimeAmount forHowLong) {
        // first start by checking all the consistencies and propagating necessary constraints
        // those are irreversible operations, we do not make any decisions on them
        //State st = GetCurrentState();
        //
        //st.bindings.PropagateNecessary(st);
        //st.tdb.Propagate(st);
        long deadLine = System.currentTimeMillis() + forHowLong.val;
        /**
         * search
         */
        while (true) {
            if (System.currentTimeMillis() > deadLine) {
                TinyLogger.LogInfo("Timeout.");
                this.planState = EPlanState.INCONSISTENT;
                break;
            }
            if (queue.isEmpty()) {
                TinyLogger.LogInfo("No plan found.");
                this.planState = EPlanState.INFESSIBLE;
                break;
            }
            //get the best state and continue the search
            State st = queue.remove();
            OpenedStates++;

            List<Flaw> flaws = GetFlaws(st);

            TinyLogger.LogInfo(st);
            if (flaws.isEmpty()) {
                this.planState = EPlanState.CONSISTENT;
                TinyLogger.LogInfo("Plan found:");
                TinyLogger.LogInfo(st.taskNet);
                TinyLogger.LogInfo(st.tdb);
                TinyLogger.LogInfo(st.tempoNet);
                return st;
            }
            //continue the search
            LinkedList<Pair<Flaw, List<SupportOption>>> opts = new LinkedList<>();
            for(Flaw flaw : flaws) {
                opts.add(new Pair(flaw, GetResolvers(st, flaw)));
            }

            //do some sorting here - min domain
            //Collections.sort(opts, optionsComparatorMinDomain);
            Collections.sort(opts, this.flawComparator(st));

            if (opts.isEmpty()) {
                throw new FAPEException("Error: no flaws but state was not found to be a solution.");
            }

            if (opts.getFirst().value2.isEmpty()) {
                TinyLogger.LogInfo("Dead-end, flaw without resolvers: " + opts.getFirst().value1);
                //dead end
                continue;
            }

            //we just take the first option here as a tie breaker by min-domain
            Pair<Flaw, List<SupportOption>> opt = opts.getFirst();

            if(APlanner.logging)
                TinyLogger.LogInfo(" Flaw:" +opt.value1.toString());

            for (SupportOption o : opt.value2) {
                if(APlanner.logging)
                    TinyLogger.LogInfo("   Res: "+o);
                State next = new State(st);
                boolean success = false;
                if(opt.value1 instanceof Threat ||
                        opt.value1 instanceof UnboundVariable ||
                        opt.value1 instanceof UndecomposedAction) {
                    success = ApplyOption(next, o, null);
                } else {
                    success = ApplyOption(next, o, next.GetDatabase(((UnsupportedDatabase) opt.value1).consumer.mID));
                }
                //TinyLogger.LogInfo(next.Report());
                if (success) {
                    queue.add(next);
                    GeneratedStates++;
                } else {
                    TinyLogger.LogInfo("   Dead-end reached for state: " + next.mID);
                    //inconsistent state, doing nothing
                }
            }

        }
        return null;
    }

    /**
     * starts plan repair, records the best plan, produces the best plan after
     * <b>forHowLong</b> miliseconds or null, if no plan was found
     *
     * @param forHowLong
     */
    public boolean Repair(TimeAmount forHowLong) {
        KeepBestStateOnly();
        best = search(forHowLong);
        if (best == null) {
            return false;
        }
        //dfs(forHowLong);

        //we empty the queue now and leave only the best state there
        KeepBestStateOnly();
        return true;
    }

    /**
     *
     * @param db
     * @param st
     * @return
     */
    public List<SupportOption> GetSupporters(TemporalDatabase db, State st) {
        //here we need to find several types of supporters
        //1) chain parts that provide the value we need
        //2) actions that provide the value we need and can be added
        //3) tasks that can decompose into an action we need
        List<SupportOption> ret = new LinkedList<>();

        //get chain connections
        for (TemporalDatabase b : st.tdb.vars) {
            if (db == b || !st.Unifiable(db, b)) {
                continue;
            }
            // if the database has a single persistence we try to integrate it with other persistences.
            // except if the state variable is constant, in which case looking only for the assignments saves search effort.
            if (db.HasSinglePersistence() && !db.stateVariable.func().isConstant()) {
                //we are looking for chain integration too
                int ct = 0;
                for (ChainComponent comp : b.chain) {
                    if (comp.change && st.Unifiable(comp.GetSupportValue(), db.GetGlobalConsumeValue())
                            && st.tempoNet.CanBeBefore(comp.getSupportTimePoint(), db.getConsumeTimePoint())) {
                        SupportOption o = new SupportOption();
                        o.precedingChainComponent = ct;
                        o.temporalDatabase = b.mID;
                        ret.add(o);
                    }
                    ct++;
                }

                // Otherwise, check for databases containing a change whose support value can
                // be unified with our consume value.
            } else if(st.Unifiable(b.GetGlobalSupportValue(), db.GetGlobalConsumeValue())
                    && !b.HasSinglePersistence()
                    && st.tempoNet.CanBeBefore(b.getSupportTimePoint(), db.getConsumeTimePoint())) {
                SupportOption o = new SupportOption();
                o.temporalDatabase = b.mID;
                ret.add(o);
            }
        }

        // adding actions
        // ... the idea is to decompose actions as long as they provide some support that I need, if they cant, I start adding actions
        //find actions that help me with achieving my value through some decomposition in the task network
        //they are those that I can find in the virtual decomposition tree
        //first get the action names from the abstract dtgs
        //StateVariable[] varis = null;
        //varis = db.domain.values().toArray(varis);

        ActionSupporterFinder supporters = getActionSupporterFinder();
        ActionDecompositions decompositions = new ActionDecompositions(pb);
        Collection<AbstractAction> potentialSupporters = supporters.getActionsSupporting(st, db);

        for(Action leaf : st.taskNet.GetOpenLeaves()) {
            for(Integer decID : decompositions.possibleDecompositions(leaf, potentialSupporters)) {
                SupportOption opt = new SupportOption();
                opt.actionToDecompose = leaf;
                opt.decompositionID = decID;
                ret.add(opt);
            }
        }


        //now we can look for adding the actions ad-hoc ...
        if (APlanner.actionResolvers) {
            for (AbstractAction aa : potentialSupporters) {
                // only considere action that are not marked motivated.
                // TODO: make it complete (consider a task hierarchy where an action is a descendant of unmotivated action)
                if(!aa.isMotivated()) {
                    SupportOption o = new SupportOption();
                    o.supportingAction = aa;
                    ret.add(o);
                }
            }
        }

        return ret;
    }

    /**
     * progresses in the plan up for howFarToProgress, returns either
     * AtomicActions that were instantiated with corresponding start times, or
     * null, if not solution was found in the given time
     *
     * @param howFarToProgress
     * @return
     */
    public List<AtomicAction> Progress(TimeAmount howFarToProgress) {
        State myState = best;
        Plan plan = new Plan(myState);

        List<AtomicAction> ret = new LinkedList<>();
        for (Action a : plan.GetNextActions()) {
            long startTime = myState.tempoNet.GetEarliestStartTime(a.start());
            if (startTime > howFarToProgress.val) {
                continue;
            }
            if(a.status() != ActionStatus.PENDING) {
                continue;
            }
            AtomicAction aa = new AtomicAction(a, startTime, a.maxDuration(), best);
            ret.add(aa);
        }

        Collections.sort(ret, new Comparator<AtomicAction>() {
            @Override
            public int compare(AtomicAction o1, AtomicAction o2) {
                return (int) (o1.mStartTime - o2.mStartTime);
            }
        });

        // for all selecting actions, we set them as being executed and we bind their start time point
        // to the one we requested.
        for(AtomicAction aa : ret) {
            Action a = myState.taskNet.GetAction(aa.id);
            myState.setActionExecuting(a.id());
            myState.tempoNet.RemoveConstraints(new Pair(pb.earliestExecution(), a.start()),
                    new Pair(a.start(), pb.earliestExecution()));
            myState.tempoNet.EnforceConstraint(pb.start(), a.start(), (int) aa.mStartTime, (int) aa.mStartTime);
        }

        return ret;
    }

    public boolean hasPendingActions() {
        for(Action a : best.taskNet.GetAllActions()) {
            if(a.status() == ActionStatus.PENDING)
                return true;
        }
        return false;
    }

    /**
     * restarts the planning problem into its initial state
     */
    public void Restart() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * enforces given facts into the plan (possibly breaking it) this is an
     * incremental step, if there was something already defined, the name
     * collisions are considered to be intentional
     *
     * @param anml
     * @return True if the planner is applicable to resulting anml problem.
     */
    public boolean ForceFact(ParseResult anml) {
        //read everything that is contained in the ANML block
        if (logging) {
            TinyLogger.LogInfo("Forcing new fact into best state.");
        }

        KeepBestStateOnly();

        //TODO: apply ANML to more states and choose the best after the applciation
        pb.addAnml(anml);
        this.dtg = new LiftedDTG(this.pb);

        // apply revisions to best state and check if it is consistent
        State st = GetCurrentState();

        boolean consistent = st.update();
        if(!consistent) {
            this.planState = EPlanState.INFESSIBLE;
        }

        return true;
    }

}
