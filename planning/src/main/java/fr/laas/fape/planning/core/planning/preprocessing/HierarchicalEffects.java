package fr.laas.fape.planning.core.planning.preprocessing;

import fr.laas.fape.anml.model.*;
import fr.laas.fape.anml.model.abs.time.AbsTP;
import fr.laas.fape.anml.model.concrete.InstanceRef;
import fr.laas.fape.anml.model.concrete.VarRef;
import fr.laas.fape.constraints.bindings.Domain;
import fr.laas.fape.planning.core.planning.planner.GlobalOptions;
import fr.laas.fape.planning.core.planning.states.PartialPlan;
import fr.laas.fape.planning.core.planning.timelines.Timeline;
import lombok.Value;
import fr.laas.fape.anml.model.abs.AbstractAction;
import fr.laas.fape.anml.model.abs.AbstractTask;
import fr.laas.fape.anml.model.abs.statements.AbstractLogStatement;
import fr.laas.fape.anml.model.concrete.Task;

import java.util.*;
import java.util.stream.Collectors;

public class HierarchicalEffects {
    private final boolean CHECK_DELAY_FROM_TASK_TO_OG = GlobalOptions.getBooleanOption("check-delay-from-task-to-og");

    @Value private class StatementPointer {
        final AbstractAction act;
        final LStatementRef ref;
        final boolean isTransition;
    }

    @Value private class TemporalFluent {
        @Value class Fluent {
            final Function func;
            final List<VarPlaceHolder> args;
            final VarPlaceHolder value;
        }

        final int delayFromStart;
        final int delayToEnd;
        final Fluent f;
        final List<StatementPointer> origin;

        TemporalFluent(int delayFromStart, int delayToEnd,
                       Function func, List<VarPlaceHolder> args, VarPlaceHolder value,
                       List<StatementPointer> origin) {
            this.delayFromStart = delayFromStart;
            this.delayToEnd = delayToEnd;
            this.f = new Fluent(func, args, value);
            this.origin = origin;
        }

        @Override public String toString() { return "Eff(~>"+delayFromStart+" "+delayToEnd+"<~ "+f.func.name()+f.args+"="+f.value; }

        TemporalFluent asEffectBySubtask(AbstractAction a, AbstractTask t) {
            Map<LVarRef,VarPlaceHolder> mapping = new HashMap<>();
            List<LVarRef> taskVars = pb.actionsByTask().get(t.name()).get(0).args();
            for(int i=0 ; i<taskVars.size() ; i++) {
                LVarRef right = t.jArgs().get(i);
                if(a.context().contains(right) && a.context().hasGlobalVar(right) && a.context().getGlobalVar(right) instanceof InstanceRef)
                    mapping.put(taskVars.get(i), new InstanceArg((InstanceRef) a.context().getGlobalVar(right)));
                else
                    mapping.put(taskVars.get(i), new LVarArg(t.jArgs().get(i)));
            }
            java.util.function.Function<VarPlaceHolder,VarPlaceHolder> trans = (v) -> {
                if(v.isVar())
                    return mapping.get(v.asVar());
                else
                    return v;
            };
            return new TemporalFluent(
                    delayFromStart + a.minDelay(a.start(), t.start()).lb(),
                    delayToEnd + a.minDelay(t.end(), a.end()).lb(),
                    f.func,
                    f.args.stream().map(trans).collect(Collectors.toList()),
                    trans.apply(f.value),
                    origin);
        }

        TemporalFluent asEffectOfSubtask(AbstractAction a, Subtask t) {
            assert a.taskName().equals(t.taskName);
            java.util.function.Function<VarPlaceHolder,VarPlaceHolder> trans = (v) -> {
                if(v.isVar() && a.args().contains(v.asVar()))
                    return t.args.get(a.args().indexOf(v.asVar()));
                else if(v.isVar())
                    return new TypePlaceHolder(v.asType());
                else
                    return v;
            };
            return new TemporalFluent(
                    delayFromStart + t.delayFromStart,
                    delayToEnd + t.delayToEnd,
                    f.func,
                    f.args.stream().map(trans).collect(Collectors.toList()),
                    trans.apply(f.value),
                    origin);
        }
    }
    @Value private class Subtask {
        final int delayFromStart;
        final int delayToEnd;
        final String taskName;
        final List<VarPlaceHolder> args;
    }
    private interface VarPlaceHolder {
        boolean isVar();
        default boolean isInstance() { return false; };
        default LVarRef asVar() { throw new UnsupportedOperationException("Not supported yet."); }
        Type asType();
        default InstanceRef asInstance() { throw new UnsupportedOperationException(); }
    }
    @Value private class LVarArg implements VarPlaceHolder {
        final LVarRef var;
        @Override public boolean isVar() { return true; }
        @Override public LVarRef asVar() { return var; }
        @Override public Type asType() { return var.getType(); }
        @Override public String toString() { return var.toString(); }
    }
    @Value private class InstanceArg implements VarPlaceHolder {
        final InstanceRef var;
        @Override public boolean isVar() { return false; }
        @Override public boolean isInstance() { return true; }
        @Override public InstanceRef asInstance() { return var; }
        @Override public Type asType() { return var.getType(); }
        @Override public String toString() { return var.toString(); }
    }
    @Value private class TypePlaceHolder implements VarPlaceHolder {
        final Type type;
        @Override public boolean isVar() { return false; }
        @Override public Type asType() { return type; }
        @Override public String toString() { return type.toString(); }
    }

    private final AnmlProblem pb;
    private Map<String, List<TemporalFluent>> tasksEffects = new HashMap<>();
    private Map<AbstractAction, List<TemporalFluent>> actionEffects = new HashMap<>();
    private Map<AbstractAction, List<TemporalFluent>> directActionEffects = new HashMap<>();
    private Map<AbstractAction, List<TemporalFluent>> directActionConditions = new HashMap<>();
    private Map<String, List<Subtask>> subtasks = new HashMap<>();

    HierarchicalEffects(AnmlProblem pb) {
        this.pb = pb;
        for(AbstractAction a : pb.abstractActions()) {
            directConditionsOf(a);
        }
    }

    private List<TemporalFluent> regrouped(List<TemporalFluent> effects) {
        Map<TemporalFluent.Fluent, List<TemporalFluent>> grouped = effects.stream().collect(Collectors.groupingBy(TemporalFluent::getF));
        List<TemporalFluent> reduced = grouped.keySet().stream().map(f -> {
            int minFromStart = grouped.get(f).stream().map(e -> e.delayFromStart).min(Integer::compare).get();
            int minToEnd = grouped.get(f).stream().map(e -> e.delayToEnd).min(Integer::compare).get();
            List<StatementPointer> origins = grouped.get(f).stream().flatMap(e -> e.getOrigin().stream()).collect(Collectors.toList());
            return new TemporalFluent(minFromStart, minToEnd, f.func, f.args, f.value, origins);
        }).collect(Collectors.toList());
        return reduced;
    }

    private List<LVarRef> argsOfTask(String t) { return pb.actionsByTask().get(t).get(0).args(); }
    private List<AbstractAction> methodsOfTask(String t) { return pb.actionsByTask().get(t); }

    /** Returns the effects that appear in the body of this action (regardless of its subtasks). */
    private List<TemporalFluent> directEffectsOf(AbstractAction a) {
        java.util.function.Function<LVarRef,VarPlaceHolder> asPlaceHolder = (v) -> {
            if(a.context().contains(v) && a.context().hasGlobalVar(v) && a.context().getGlobalVar(v) instanceof InstanceRef)
                return new InstanceArg((InstanceRef) a.context().getGlobalVar(v));
            else
                return new LVarArg(v);
        };
        if (!directActionEffects.containsKey(a)) {
            List<TemporalFluent> directEffects = a.jLogStatements().stream()
                    .filter(AbstractLogStatement::hasEffectAtEnd)
                    .map(s -> new TemporalFluent(
                            a.minDelay(a.start(), s.end()).lb(),
                            a.minDelay(s.end(), a.end()).lb(),
                            s.sv().func(),
                            s.sv().jArgs().stream().map(asPlaceHolder).collect(Collectors.toList()),
                            asPlaceHolder.apply(s.effectValue()),
                            Collections.singletonList(new StatementPointer(a, s.id(), s.hasConditionAtStart()))))
                    .collect(Collectors.toList());

            directActionEffects.put(a, regrouped(directEffects));
        }

        return directActionEffects.get(a);
    }

    private List<TemporalFluent> directConditionsOf(AbstractAction a) {
        java.util.function.Function<LVarRef,VarPlaceHolder> asPlaceHolder = (v) -> {
            if(a.context().contains(v) && a.context().hasGlobalVar(v) && a.context().getGlobalVar(v) instanceof InstanceRef)
                return new InstanceArg((InstanceRef) a.context().getGlobalVar(v));
            else
                return new LVarArg(v);
        };
        if (!directActionConditions.containsKey(a)) {
            List<TemporalFluent> effectFluents = a.jLogStatements().stream()
                    .filter(AbstractLogStatement::hasEffectAtEnd)
                    .map(s -> new TemporalFluent(
                            a.minDelay(a.start(), s.end()).lb(),
                            a.minDelay(s.end(), a.end()).lb(),
                            s.sv().func(),
                            s.sv().jArgs().stream().map(asPlaceHolder).collect(Collectors.toList()),
                            asPlaceHolder.apply(s.effectValue()),
                            Collections.singletonList(new StatementPointer(a, s.id(), s.hasConditionAtStart()))))
                    .collect(Collectors.toList());

            List<TemporalFluent> conditionFluents = a.jLogStatements().stream()
                    .filter(AbstractLogStatement::hasConditionAtStart)
                    .map(s -> {
                        AbsTP endTP = s.hasEffectAtEnd() ? s.start() : s.end();
                        return new TemporalFluent(
                                a.minDelay(a.start(), s.start()).lb(),
                                a.minDelay(endTP, a.end()).lb(),
                                s.sv().func(),
                                s.sv().jArgs().stream().map(asPlaceHolder).collect(Collectors.toList()),
                                asPlaceHolder.apply(s.conditionValue()),
                                Collections.singletonList(new StatementPointer(a, s.id(), s.hasConditionAtStart())));
                    })
                    .collect(Collectors.toList());

            List<TemporalFluent> allFluents = new ArrayList<>(effectFluents);
            allFluents.addAll(conditionFluents);

            directActionConditions.put(a, regrouped(allFluents));
        }

        return directActionConditions.get(a);
    }

    /** Union of the direct effects of all methods for this task */
    private List<TemporalFluent> directEffectsOf(Subtask t) {
        return methodsOfTask(t.taskName).stream()
                .flatMap(a -> directEffectsOf(a).stream().map(e -> e.asEffectOfSubtask(a, t)))
                .collect(Collectors.toList());
    }

    /** all effects that can be introduced in the plan by completly decomposing this task */
    private List<TemporalFluent> effectsOf(String task) {
        if(!tasksEffects.containsKey(task)) {
            tasksEffects.put(task,
                    subtasksOf(task).stream()
                            .flatMap(sub -> directEffectsOf(sub).stream())
                            .collect(Collectors.toList()));
        }
        return tasksEffects.get(task);
    }

    /** All subtasks of t, including t */
    private Collection<Subtask> subtasksOf(String task) {
        if(!this.subtasks.containsKey(task)) {
            HashSet<Subtask> subtasks = new HashSet<>();
            Subtask t = new Subtask(0, 0, task, argsOfTask(task).stream().map(LVarArg::new).collect(Collectors.toList()));
            populateSubtasksOf(t, subtasks);
            this.subtasks.put(task, subtasks.stream().collect(Collectors.toList()));
        }
        return this.subtasks.get(task);
    }

    /** This method recursively populates the set of subtasks with all subtasks of t (including t). */
    private void populateSubtasksOf(Subtask t, Set<Subtask> allsubtasks) {
        for(Subtask prev : new ArrayList<>(allsubtasks)) {
            if(t.taskName.equals(prev.taskName) && t.args.equals(prev.args)) {
                allsubtasks.remove(prev);
                Subtask merged = new Subtask(
                        Math.min(t.delayFromStart, prev.delayFromStart),
                        Math.min(t.delayToEnd, prev.delayToEnd),
                        t.taskName,
                        t.args);
                allsubtasks.add(merged);
                if(prev.delayFromStart <= t.delayFromStart)
                    return; // previous was already better
            }
        }
        allsubtasks.add(t);

        for(AbstractAction a : methodsOfTask(t.taskName)) {
            java.util.function.Function<LVarRef,VarPlaceHolder> trans = v -> {
                if(a.args().contains(v))
                    return t.args.get(a.args().indexOf(v));
                else if(a.context().contains(v) && a.context().hasGlobalVar(v) && a.context().getGlobalVar(v) instanceof InstanceRef)
                    return new InstanceArg((InstanceRef) a.context().getGlobalVar(v));
                else
                    return new TypePlaceHolder(v.getType());
            };
            for(AbstractTask at : a.jSubTasks()) {
                Subtask sub = new Subtask(
                        a.minDelay(a.start(), at.start()).lb(),
                        a.minDelay(at.end(), a.end()).lb(),
                        at.name(),
                        at.jArgs().stream().map(trans).collect(Collectors.toList())
                );
                populateSubtasksOf(sub, allsubtasks);
            }
        }
    }

    private List<TemporalFluent> effectsOf(AbstractAction a) {
        if(!actionEffects.containsKey(a)) {
            List<TemporalFluent> undirEffects = a.jSubTasks().stream()
                    .flatMap(t -> effectsOf(t.name()).stream().map(e -> e.asEffectBySubtask(a, t)))
                    .collect(Collectors.toList());

            List<TemporalFluent> effects = new ArrayList<>(directEffectsOf(a));
            effects.addAll(undirEffects);

            actionEffects.put(a, regrouped(effects));
        }

        return actionEffects.get(a);
    }

    private static class DomainList {
        Domain[] l = new Domain[8];
        int size = 0;

        void add(Domain d) {
            assert size < 8;
            l[size] = d;
            size++;
        }
        void clear() {
            size = 0;
        }

        /** True if the two lists are compatible: all pairs of domains have a non empty intersection */
        boolean compatible(DomainList dl) {
            assert size == dl.size;
            for(int i=0 ; i < size ; i++) {
                if(!l[i].hasOneCommonElement(dl.l[i]))
                    return false;
            }
            return true;
        }

        private static Domain asDomain(VarRef v, PartialPlan st) { return st.csp.bindings().rawDomain(v); }
        private static Domain asDomain(Type t, PartialPlan st) { return st.csp.bindings().defaultDomain(t); }
        private static Domain asDomain(VarPlaceHolder v, Map<LVarRef,VarRef> bindings, PartialPlan st) {
            if(v.isVar()) {
                assert bindings.containsKey(v.asVar());
                return asDomain(bindings.get(v.asVar()), st);
            } else if(v.isInstance()) {
                return asDomain(v.asInstance(), st);
            } else {
                return asDomain(v.asType(), st);
            }
        }
        private static Domain asDomainFromLocalVars(VarPlaceHolder v, PartialPlan st) {
            if(v.isInstance()) {
                return asDomain(v.asInstance(), st);
            } else {
                return asDomain(v.asType(), st);
            }
        }
        private static void loadFrom(ParameterizedStateVariable sv, VarRef value, PartialPlan st, DomainList out) {
            out.clear();
            for(VarRef v : sv.args())
                out.add(asDomain(v, st));
            out.add(asDomain(value, st));
        }
        private static void loadFrom(TemporalFluent.Fluent f, Map<LVarRef,VarRef> bindings, PartialPlan st, DomainList out) {
            out.clear();
            for(VarPlaceHolder v : f.args) {
                out.add(asDomain(v, bindings, st));
            }
            out.add(asDomain(f.value, bindings, st));
        }
        private static void loadFrom(TemporalFluent.Fluent f, PartialPlan st, DomainList out) {
            out.clear();
            for(VarPlaceHolder v : f.args) {
                out.add(asDomainFromLocalVars(v, st));
            }
            out.add(asDomainFromLocalVars(f.value, st));
        }
    }
    DomainList DL_WORKING = new DomainList();
    DomainList DL2_WORKING = new DomainList();
    Map<LVarRef, VarRef> MAP_WORKING = new HashMap<>();

    /**
     * A task can indirectly support an open goal if it can be decomposed in an action
     * producing a statement (i) that can support the open goal (ii) that can be early enough to support it
     */
    public boolean canIndirectlySupport(Timeline og, Task t, PartialPlan st) {
        DomainList.loadFrom(og.stateVariable, og.getGlobalConsumeValue(), st, DL_WORKING);
        Map<LVarRef, VarRef> bindings = MAP_WORKING;
        bindings.clear();
        for(int i=0 ; i<t.args().size() ; i++) {
            assert st.pb.actionsByTask().keySet().contains(t.name()) : "Task '"+t.name()+"'is not recorded.\n Recorded tasks: "+st.pb.actionsByTask().keySet();
            LVarRef localVar = st.pb.actionsByTask().get(t.name()).get(0).args().get(i);
            bindings.put(localVar, t.args().get(i));
        }
        for(TemporalFluent effect : effectsOf(t.name())) {
            if(effect.f.func != og.stateVariable.func())
                continue;
            boolean temporallyPossible = st.csp.stn().isDelayPossible(t.start(), og.getConsumeTimePoint(), effect.delayFromStart) || !CHECK_DELAY_FROM_TASK_TO_OG;
            if(!temporallyPossible)
                continue;
            DomainList.loadFrom(effect.f, bindings, st, DL2_WORKING);
            if(DL_WORKING.compatible(DL2_WORKING))
                return true;
        }
        return false;
    }

    /**
     * A task can indirectly support an open goal if it can be decomposed in an action
     * producing a statement (i) that can support the open goal (ii) that can be early enough to support it
     */
    public boolean canSupport(Timeline og, AbstractAction aa, PartialPlan st) {
        DomainList.loadFrom(og.stateVariable, og.getGlobalConsumeValue(), st, DL_WORKING);

        for(TemporalFluent effect : effectsOf(aa)) {
            if(effect.f.func != og.stateVariable.func())
                continue;
            DomainList.loadFrom(effect.f, st, DL2_WORKING);
            if(DL2_WORKING.compatible(DL_WORKING))
                return true;
        }
        return false;
    }

    private HashMap<Function,Boolean> _hasAssignmentInAction = new HashMap<>();

    public boolean hasAssignmentsInAction(Function func) {
        return _hasAssignmentInAction.computeIfAbsent(func, f ->
                pb.abstractActions().stream().flatMap(a -> effectsOf(a).stream())
                        .filter(effect -> effect.f.func == f)
                        .flatMap(effect -> effect.origin.stream())
                        .anyMatch(statementPointer -> !statementPointer.isTransition));
    }
}
