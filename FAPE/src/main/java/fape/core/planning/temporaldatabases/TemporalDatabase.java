/*
 * Author:  Filip Dvořák <filip.dvorak@runbox.com>
 *
 * Copyright (c) 2013 Filip Dvořák <filip.dvorak@runbox.com>, all rights reserved
 *
 * Publishing, providing further or using this program is prohibited
 * without previous written permission of the author. Publishing or providing
 * further the contents of this file is prohibited without previous written
 * permission of the author.
 */
package fape.core.planning.temporaldatabases;

import fape.core.planning.search.strategies.plans.LMC;
import fape.core.planning.states.State;
import fape.exceptions.FAPEException;
import planstack.anml.model.ParameterizedStateVariable;
import planstack.anml.model.concrete.TPRef;
import planstack.anml.model.concrete.VarRef;
import planstack.anml.model.concrete.statements.LogStatement;
import planstack.structures.IList;
import planstack.structures.Pair;
import scala.Tuple2;

import java.util.LinkedList;
import java.util.List;

/**
 * records the events for one state variable
 *
 * @author FD
 */
public class TemporalDatabase {

    private static int nextID = 0;
    public final int mID;

    public final ParameterizedStateVariable stateVariable;

    public final LinkedList<ChainComponent> chain = new LinkedList<>();

    public TemporalDatabase(LogStatement s) {
        chain.add(new ChainComponent(s));
        mID = nextID++;
        stateVariable = s.sv();
    }

    public TemporalDatabase(ParameterizedStateVariable sv) {
        mID = nextID++;
        stateVariable = sv;
    }

    public TemporalDatabase(TemporalDatabase toCopy) {
        mID = toCopy.mID;
        for (ChainComponent cc : toCopy.chain) {
            chain.add(cc.DeepCopy());
        }
        stateVariable = toCopy.stateVariable;
    }

    public boolean contains(LogStatement s) {
        for (ChainComponent cc : chain) {
            if (cc.contains(s)) {
                return true;
            }
        }
        return false;
    }

    public String Report() {
        String ret = "";
        //ret += "{\n";

        ret += "    " + this.stateVariable + "  :  id=" + mID + "\n";
        for (ChainComponent c : chain) {
            for (LogStatement e : c.contents) {
                ret += "    " + e;
            }
            ret += "\n";
        }

        //ret += "}\n";
        return ret;
    }

    /**
     * @return True if the first statement of this TDB requires support (ie not
     * an assignment)
     */
    public boolean isConsumer() {
        return chain.getFirst().contents.getFirst().needsSupport();
    }

    /**
     * @return
     */
    public TemporalDatabase DeepCopy() {
        return new TemporalDatabase(this);
    }

    /**
     * @return The end time point of the last component inducing a change.
     */
    public TPRef getSupportTimePoint() {
        assert getSupportingComponent() != null : "This database appears to be containing only a persitence. "
                + "Hence it not available for support. " + this.toString();
        return getSupportingComponent().getSupportTimePoint();
    }

    /**
     * @return All time points from the last component.
     */
    public LinkedList<TPRef> getLastTimePoints() {
        LinkedList<TPRef> tps = new LinkedList<>();
        for(LogStatement s : chain.getLast().contents) {
            tps.add(s.end());
        }
        return tps;
    }

    /**
     * @return All time points from the first component.
     */
    public LinkedList<TPRef> getFirstTimePoints() {
        LinkedList<TPRef> tps = new LinkedList<>();
        for(LogStatement s : chain.getFirst().contents) {
            tps.add(s.start());
        }
        return tps;
    }

    public TPRef getConsumeTimePoint() {
        return chain.getFirst().getConsumeTimePoint();
    }

    public List<String> GetPossibleSupportAtomNames(State st) {
        return LMC.GetAtomNames(st, this.stateVariable, this.GetGlobalSupportValue());
    }

    /**
     * @return The last component of the database containing a change (i.e. an
     * assignment or a transition). It returns null if no such element exists.
     */
    public ChainComponent getSupportingComponent() {
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (chain.get(i).change) {
                return chain.get(i);
            }
        }
        return null;
    }

    public ChainComponent GetChainComponent(int precedingChainComponent) {
        return chain.get(precedingChainComponent);
    }

    /**
     * @return A global variable representing the value at the end of the
     * temporal database
     */
    public boolean HasSinglePersistence() {
        return chain.size() == 1 && !chain.get(0).change;
    }

    /**
     * @return A global variable representing the value at the end of the
     * temporal database
     */
    public VarRef GetGlobalSupportValue() {
        return chain.getLast().GetSupportValue();
    }

    /**
     *
     * @return
     */
    public VarRef GetGlobalConsumeValue() {
        return chain.getFirst().GetConsumeValue();
    }

    @Override
    public String toString() {
        String res = "(tdb:" + mID + " dom=[" + this.stateVariable + "] chains=[";

        for (ChainComponent comp : this.chain) {
            for (LogStatement ev : comp.contents) {
                res += ev.toString() + ", ";
            }
        }
        res += "])";

        return res;
    }

    /**
     * Checks if there is not two persistence events following each other in the
     * chain.
     */
    public void CheckChainComposition() {
        boolean wasPreviousTransition = true;
        for (ChainComponent cc : this.chain) {
            if (!wasPreviousTransition && !cc.change) {
                throw new FAPEException("We have two persistence events following each other.");
            }
            wasPreviousTransition = cc.change;
        }
    }

    public IList<Pair<LogStatement, LogStatement>> allCausalLinks() {
        IList<Pair<LogStatement, LogStatement>> cls = new IList<>();
        for(int i=0 ; i<chain.size() ; i++) {
            ChainComponent supCC = chain.get(i);

            if(!supCC.change) //supporter must be a change
                continue;

            assert supCC.contents.size() == 1;
            LogStatement sup = supCC.contents.getFirst();

            if(i+1<chain.size()) {
                for(LogStatement cons : chain.get(i+1).contents) {
                    cls = cls.with(new Pair<>(sup, cons));
                }
            }

            if(i+2 < chain.size() && !chain.get(i+1).change) {
                assert chain.get(i+2).change;
                for(LogStatement cons : chain.get(i+2).contents) {
                    cls = cls.with(new Pair<>(sup, cons));
                }
            }
        }

        return cls;
    }
}
