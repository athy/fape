package fape.core.planning.heuristics.temporal;

import fape.core.planning.grounding.GAction;
import fape.core.planning.planner.APlanner;
import fape.exceptions.FAPEException;
import lombok.Value;
import planstack.anml.model.AbstractParameterizedStateVariable;
import planstack.anml.model.Function;
import planstack.anml.model.LVarRef;
import planstack.anml.model.abs.*;
import planstack.anml.model.abs.statements.AbstractLogStatement;
import planstack.anml.model.abs.time.AbsTP;
import planstack.anml.model.abs.time.StandaloneTP;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class DeleteFreeActionsFactory {

    private static final boolean dbg = false;

    interface Time extends Comparable<Time> {
        static ParameterizedTime of(AbstractParameterizedStateVariable sv, java.util.function.Function<Integer,Integer> trans) {
            return new ParameterizedTime(sv.func(), sv.jArgs(), trans);
        }
        static IntTime of(int value) {
            return new IntTime(value);
        }

        @Override
        default int compareTo(Time t) {
            if(this instanceof ParameterizedTime && t instanceof ParameterizedTime)
                return 0;
            else if(this instanceof ParameterizedTime)
                return 1;
            else if(t instanceof ParameterizedTime)
                return -1;
            else
                return ((IntTime) this).value - ((IntTime) t).value;
        }
        Time delay(int d);
    }

    @Value static class IntTime implements Time {
        public final int value;
        @Override public String toString() { return ""+value; }

        @Override
        public Time delay(int d) { return new IntTime(value+d); }
    }

    @Value static class ParameterizedTime implements Time {
        public final Function f;
        public final List<LVarRef> args;
        public final java.util.function.Function<Integer,Integer> trans;

        @Override
        public Time delay(int d) { throw new UnsupportedOperationException("Not supported yet."); }
    }

    interface FluentTemplate {}

    @Value class SVFluentTemplate implements FluentTemplate {
        public final AbstractParameterizedStateVariable sv;
        public final LVarRef value;
        @Override public String toString() { return sv+"="+value; }
    }
    @Value class DoneFluentTemplate implements FluentTemplate {
        public final RActTemplate act;
        @Override public String toString() { return "startable-act: "+act; }
    }
    @Value class TaskFluentTemplate implements FluentTemplate {
        public final String prop;
        public final String taskName;
        public final List<LVarRef> args;
        @Override public String toString() { return prop+"( "+taskName+args+" )"; }
    }


    class TempFluentTemplate {
        final FluentTemplate fluent;
        final Time time;
        public TempFluentTemplate(FluentTemplate fluent, int time) {
            this.fluent = fluent;
            this.time = Time.of(time);
        }
        public TempFluentTemplate(FluentTemplate fluent, Time time) {
            this.fluent = fluent;
            this.time = time;
        }
        public String toString() {
            return time+": "+fluent;
        }
    }

    public class RActTemplate {
        final AbstractAction abs;
        final AbsTP tp;

        public RActTemplate(AbstractAction abs, AbsTP mainTimepoint) {
            this.abs = abs;
            this.tp = mainTimepoint;
        }

        List<TempFluentTemplate> conditions = new LinkedList<>();
        List<TempFluentTemplate> effects = new LinkedList<>();

//        public LVarRef[] args() { return abs.allVars(); }
        public LVarRef[] args() { return abs.args().toArray(new LVarRef[abs.args().size()]); }

        public void addCondition(AbstractParameterizedStateVariable sv, LVarRef var, int time) {
            addCondition(new SVFluentTemplate(sv, var), time);
        }
        public void addCondition(FluentTemplate ft, int time) {
            addCondition(new TempFluentTemplate(ft, time));

        }
        public void addCondition(TempFluentTemplate ft) {
            conditions.add(ft);
            Collections.sort(conditions, (p1, p2) -> p1.time.compareTo(p2.time));
        }

        public void addEffect(AbstractParameterizedStateVariable sv, LVarRef var, int time) {
            addEffect(new SVFluentTemplate(sv, var), time);
        }
        public void addEffect(FluentTemplate ft, int time) {
            addEffect(new TempFluentTemplate(ft, time));
        }
        public void addEffect(TempFluentTemplate ft) {
            effects.add(ft);
            Collections.sort(effects, (p1, p2) -> p1.time.compareTo(p2.time));
        }

        public String name() { return abs.name()+"--"+tp+Arrays.toString(args()); }

        @Override
        public String toString() {
            return abs.name()+"--"+tp.toString()+Arrays.toString(args());
        }

        public String toStringDetailed() {
            String s = name() +"\n";
            s += "  conditions:\n";
            for(TempFluentTemplate c : conditions)
                s += "    "+c+"\n";
            s += "  effects:\n";
            for(TempFluentTemplate c : effects)
                s += "    "+c+"\n";
            return s;
        }
    }

    private int relativeTimeOf(AbsTP tp, AbstractAction act) {
        if(act.flexibleTimepoints().contains(tp))
            return 0;
        else {
            for(AbstractAction.AnchoredTimepoint anchored : act.anchoredTimepoints()) {
                if(anchored.timepoint().equals(tp))
                    return anchored.delay();
            }
        }
        throw new FAPEException("Unable to find the timepoint: "+tp);
    }

    private AbsTP anchorOf(AbsTP tp, AbstractAction act) {
        if(act.flexibleTimepoints().contains(tp))
            return tp;
        else {
            for(AbstractAction.AnchoredTimepoint anchored : act.anchoredTimepoints()) {
                if(anchored.timepoint().equals(tp))
                    return anchored.anchor();
            }
        }
        throw new FAPEException("Unable to find the timepoint: "+tp);
    }

    public Collection<RAct> getDeleteFrees(AbstractAction abs, Collection<GAction> grounds, APlanner pl) {
        Map<AbsTP, RActTemplate> templates = new HashMap<>();
        for(AbsTP tp : abs.flexibleTimepoints()) {
            templates.put(tp, new RActTemplate(abs, tp));
        }

        for(AbstractLogStatement s : abs.jLogStatements()) {
            if(s.hasConditionAtStart()) {
                AbsTP main = anchorOf(s.start(), abs);
                int delay = -relativeTimeOf(s.start(), abs);
                templates.get(main).addCondition(s.sv(), s.conditionValue(), delay);
            }
            if(s.hasEffectAfterEnd()) {
                AbsTP main = anchorOf(s.end(), abs);
                int delay = -relativeTimeOf(s.end(), abs);
                templates.get(main).addEffect(s.sv(), s.effectValue(), delay);
            }
        }

        // add subtasks statements
        for(AbstractTask task : abs.jSubTasks()) {
            AbsTP start = anchorOf(task.start(), abs);
            int startDelay = -relativeTimeOf(task.start(), abs);
            templates.get(start).addCondition(new TaskFluentTemplate("started", task.name(), task.jArgs()), startDelay);
            templates.get(start).addEffect(new TaskFluentTemplate("task", task.name(), task.jArgs()), startDelay);

            AbsTP end = anchorOf(task.end(), abs);
            int endDelay = -relativeTimeOf(task.end(), abs);
            templates.get(end).addCondition(new TaskFluentTemplate("ended", task.name(), task.jArgs()), endDelay+1);
        }

        // add start of action fluent
        AbsTP startAnchor = anchorOf(abs.start(), abs);
        int startDelay = -relativeTimeOf(abs.start(), abs);
        templates.get(startAnchor).addEffect(new TaskFluentTemplate("started", abs.taskName(), abs.args()), startDelay);

        // add end of action fluent
        AbsTP endAnchor = anchorOf(abs.end(), abs);
        int endDelay = -relativeTimeOf(abs.end(), abs);
        templates.get(endAnchor).addEffect(new TaskFluentTemplate("ended", abs.taskName(), abs.args()), endDelay+1);

        if(abs.motivated()) {
            templates.get(startAnchor).addCondition(new TaskFluentTemplate("task", abs.taskName(), abs.args()), startDelay);
        }

        List<AbstractParameterizedExactDelay> pmds = abs.jConstraints().stream()
                .filter(ac -> ac instanceof AbstractParameterizedExactDelay)
                .map(ac -> (AbstractParameterizedExactDelay) ac).collect(Collectors.toList());

        // merge actions that are binded by a parameterized exact duration
        for(AbstractParameterizedExactDelay ped : pmds) {
            AbsTP leftAnchor = anchorOf(ped.from(), abs);
            AbsTP rightAnchor = anchorOf(ped.to(), abs);
            if(leftAnchor.equals(rightAnchor))
                continue; // nothing to merge, they are already together
            int leftDelay = -relativeTimeOf(ped.from(), abs);
            int rightDelay = -relativeTimeOf(ped.to(), abs);
            // merge right "action" into left "action"
            RActTemplate leftAct = templates.get(leftAnchor);
            RActTemplate rightAct = templates.get(rightAnchor);
            for(TempFluentTemplate f : rightAct.conditions) {
                assert f.time instanceof IntTime;
                Time newTime = Time.of(ped.delay(), t -> ((IntTime) f.time).value - leftDelay +rightDelay + (Integer) ped.trans().apply(t));
                TempFluentTemplate cond = new TempFluentTemplate(f.fluent, newTime);
                leftAct.conditions.add(cond);
            }
            for(TempFluentTemplate f : rightAct.effects) {
                assert f.time instanceof IntTime;
                Time newTime = Time.of(ped.delay(), t -> ((IntTime) f.time).value - leftDelay +rightDelay + (Integer) ped.trans().apply(t));
                TempFluentTemplate eff = new TempFluentTemplate(f.fluent, newTime);
                leftAct.effects.add(eff);
            }

            // remove right action as it was merge into left
            templates.remove(rightAnchor);
        }

        // merge all subactions with no conditions into others
        /*
        for(AbsTP left : new HashSet<AbsTP>(templates.keySet())) {
            RActTemplate leftAct = templates.get(left);
            if(!leftAct.conditions.isEmpty())
                continue;
            for(AbsTP right : templates.keySet()) {
                if(left == right) continue;
                RActTemplate rightAct = templates.get(right);
                int minDelay = fMinDelay.apply(right, left);
                for(TempFluentTemplate f : leftAct.effects) {
                    assert f.time instanceof IntTime;
                    Time newTime = Time.of(minDelay + ((IntTime) f.time).value);
                    TempFluentTemplate eff = new TempFluentTemplate(f.funcName, f.args, f.value, newTime);
                    rightAct.effects.add(eff);
                }
            }
            templates.remove(left);
        }*/

        // add inter subactions conditions
        for(AbsTP left : templates.keySet()) {
            RActTemplate leftAct = templates.get(left);
            leftAct.addEffect(new DoneFluentTemplate(leftAct), 0);
            for(AbsTP right : templates.keySet()) {
                if(left == right) continue;
                RActTemplate rightAct = templates.get(right);
                int maxDelay = abs.maxDelay(left, right);
                leftAct.addCondition(new DoneFluentTemplate(rightAct), maxDelay);
            }
        }
        PostProcessor pp;
        switch (pl.options.depGraphStyle) {
            case "popf":
                pp = new PopfPostProcessor(abs, false);
                break;
            case "full":
                pp = new PopfPostProcessor(abs, true);
                break;
            case "base":
                pp = new BasePostProcessor();
                break;
            default:
                throw new FAPEException("Invalid dependency graph style: "+pl.options.depGraphStyle);
        }
        List<RActTemplate> rActTemplates = pp.postProcess(templates.values());


        if(dbg) {
            System.out.println("\n-----------------\n");
            for (RActTemplate at : rActTemplates) {
                System.out.println(at.toStringDetailed());
            }
        }

        List<RAct> relaxedGround = new LinkedList<>();

        for(GAction ground : grounds) {
            for(RActTemplate template : rActTemplates) {
                relaxedGround.addAll(pp.instantiations(template, ground, pl));
            }
        }
//        for(RAct a : relaxedGround)
//            System.out.println(a.toStringDetailed());
        return relaxedGround;
    }

    interface PostProcessor {
        default List<RActTemplate> postProcess(Collection<RActTemplate> templates) {
            return new ArrayList<>(templates);
        }
        default List<RAct> instantiations(RActTemplate template, GAction groundAct, APlanner pl) {
            return Collections.singletonList(RAct.from(template, groundAct, pl));
        }
    }
    class BasePostProcessor implements PostProcessor {}

    class PopfPostProcessor implements PostProcessor {
        final boolean hasParametrizedTemporalConstraint;
        final boolean includeLateConditions;

        PopfPostProcessor(AbstractAction abs, boolean includeLateConditions) {
            this.includeLateConditions = includeLateConditions;
            hasParametrizedTemporalConstraint =
                    abs.jConstraints().stream()
                            .filter(c -> c instanceof AbstractTemporalConstraint && ((AbstractTemporalConstraint) c).isParameterized())
                            .count() > 0;
        }

        @Override
        public List<RActTemplate> postProcess(Collection<RActTemplate> templates) {
            if (hasParametrizedTemporalConstraint) {
                // we have to wait until grounding to get the real values of parameterized constraints
                return new ArrayList<>(templates);
            } else {
                List<RActTemplate> res = new ArrayList<>();
                for (RActTemplate template : templates) {
                    int currEffect = 0;
                    int currCond = 0;
                    while (currCond < template.conditions.size() && currEffect < template.effects.size()) {
                        int shift = ((IntTime) template.effects.get(currEffect).time).getValue() - 1;
                        RActTemplate act = new RActTemplate(template.abs, new StandaloneTP(template.tp.toString() + "+" + shift));
                        while (currCond < template.conditions.size() && template.conditions.get(currCond).time.compareTo(template.effects.get(currEffect).time) < 0) {
                            TempFluentTemplate condition = template.conditions.get(currCond++);
                            act.addCondition(new TempFluentTemplate(condition.fluent, condition.time.delay(-shift)));
                        }
                        while (currEffect < template.effects.size() &&
                                (currCond >= template.conditions.size() || template.conditions.get(currCond).time.compareTo(template.effects.get(currEffect).time) >= 0)) {
                            TempFluentTemplate eff = template.effects.get(currEffect++);
                            act.addEffect(new TempFluentTemplate(eff.fluent, eff.time.delay(-shift)));
                        }
                        if(includeLateConditions) {
                            for (; currCond < template.conditions.size() ; currCond++) {
                                TempFluentTemplate condition = template.conditions.get(currCond);
                                act.addCondition(new TempFluentTemplate(condition.fluent, condition.time.delay(-shift)));
                            }
                        }
                        currCond = 0;

                        res.add(act);
                    }
                }
                return res;
            }
        }

        @Override
        public List<RAct> instantiations(RActTemplate template, GAction groundAct, APlanner pl) {
            if(!hasParametrizedTemporalConstraint) {
                // work was already done by postProcess(.)
                return PostProcessor.super.instantiations(template, groundAct, pl);
            } else {
                List<RAct> res = new LinkedList<>();

                List<TempFluent> conditions = template.conditions.stream()
                        .map(c -> TempFluent.from(c, groundAct, pl.preprocessor.getGroundProblem(), pl.preprocessor.store))
                        .collect(Collectors.toList());
                List<TempFluent> effects = template.effects.stream()
                        .map(c -> TempFluent.from(c, groundAct, pl.preprocessor.getGroundProblem(), pl.preprocessor.store))
                        .collect(Collectors.toList());

                int currEffect = 0;
                int currCond = 0;
                while (currCond < conditions.size() && currEffect < effects.size()) {
                    int shift = effects.get(currEffect).getTime() -1;
                    List<TempFluent> myConditions = new ArrayList<>();
                    List<TempFluent> myEffects = new ArrayList<>();
                    AbsTP myTP = new StandaloneTP(template.tp.toString() + "+" + shift);

                    while (currCond < conditions.size() && conditions.get(currCond).time < effects.get(currEffect).time) {
                        TempFluent condition = conditions.get(currCond++);
                        myConditions.add(new TempFluent(condition.time - shift, condition.fluent));
                    }
                    while (currEffect < effects.size() &&
                            (currCond >= conditions.size() || conditions.get(currCond).time >= effects.get(currEffect).time)) {
                        TempFluent eff = effects.get(currEffect++);
                        myEffects.add(new TempFluent(eff.time - shift, eff.fluent));
                    }
                    if(includeLateConditions) {
                        for (; currCond < conditions.size() ; currCond++) {
                            TempFluent condition = conditions.get(currCond);
                            myConditions.add(new TempFluent(condition.time -shift, condition.fluent));
                        }
                    }
                    currCond = 0;
                    res.add(pl.preprocessor.store.getRAct(groundAct, myTP, myConditions, myEffects));
                }
                return res;
            }
        }
    }
}
