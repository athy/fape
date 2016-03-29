package fape.core.planning.search.strategies.flaws;

import fape.core.planning.search.flaws.flaws.*;

/**
 * This flaw comparator is always the last one to be used.
 */
public class TieBreaker implements FlawComparator {
    @Override
    public String shortName() {
        return "tb";
    }

    @Override
    public int compare(Flaw f1, Flaw f2) {
        if(f1 instanceof UnsupportedTimeline && !(f2 instanceof UnsupportedTimeline))
            return -1;
        if(f2 instanceof UnsupportedTimeline && !(f1 instanceof UnsupportedTimeline))
            return 1;
        if(f1 instanceof UnsupportedTask && !(f2 instanceof UnsupportedTask))
            return -1;
        if(f2 instanceof UnsupportedTask && !(f1 instanceof UnsupportedTask))
            return 1;
        if(f1 instanceof UnmotivatedAction && !(f2 instanceof UnmotivatedAction))
            return -1;
        if(f2 instanceof UnmotivatedAction && !(f1 instanceof UnmotivatedAction))
            return 1;
        if(f1 instanceof Threat && !(f2 instanceof Threat))
            return -1;
        if(f2 instanceof Threat && !(f1 instanceof Threat))
            return 1;
        if(f1 instanceof ResourceFlaw && !(f2 instanceof ResourceFlaw))
            return -1;
        if(f2 instanceof ResourceFlaw && !(f1 instanceof ResourceFlaw))
            return 1;
        if(f1 instanceof UnboundVariable && !(f2 instanceof UnboundVariable))
            return -1;
        if(f2 instanceof UnboundVariable && !(f1 instanceof UnboundVariable))
            return 1;

        assert f1.getClass() == f2.getClass();
        int finalComp = f1.compareTo(f2);
        assert finalComp != 0;
        return finalComp;
    }
}
