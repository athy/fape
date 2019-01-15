package fr.laas.fape.structures;

import java.util.*;

public class IRSet<T extends Identifiable> implements Set<T> {
    private final IntRep<T> intRep;
    private final BitSet bitset;

    public IRSet(IntRep<T> intRep) {
        this.intRep = intRep;
        bitset = new BitSet();
    }
    public IRSet(IntRep<T> intRep, BitSet values) {
        this.intRep = intRep;
        bitset = values;
    }
    public static <T extends Identifiable> IRSet<T> ofSingleton(IntRep<T> intRep, T value) {
        IRSet<T> x = new IRSet<>(intRep);
        x.add(value);
        return x;
    }
    @Override
    public int size() {
        return bitset.cardinality();
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override @SuppressWarnings("unchecked")
    public boolean contains(Object o) {
        return contains(((Identifiable) o).getID());
    }

    public final boolean contains(int id) {
        return bitset.get(id);
    }

    @Override
    public final Iterator<T> iterator() {
        return new Iterator<T>() {
            int current = bitset.nextSetBit(0);
            @Override
            public boolean hasNext() { return current != -1; }

            @Override
            public T next() {
                final int toRet = current;
                current = bitset.nextSetBit(current+1);
                return intRep.fromInt(toRet);
            }
        };
    }

    public final PrimitiveIterator.OfInt primitiveIterator() {
        return new PrimitiveIterator.OfInt() {
            int current = bitset.nextSetBit(0);
            @Override
            public boolean hasNext() { return current != -1; }

            @Override
            public int nextInt() {
                final int toRet = current;
                current = bitset.nextSetBit(current+1);
                return toRet;
            }
        };
    }

    public BitSet toBitSet() { return BitSet.valueOf(bitset.toLongArray()); }

    @Override
    public Object[] toArray() {
        Object[] arr = new Object[size()];
        int i = 0;
        for(T o : this)
            arr[i++] = o;
        return arr;
    }
    @Override
    public <T1> T1[] toArray(T1[] t1s) {
        throw new UnsupportedOperationException("");
    }

    @Override
    public final boolean add(T t) {
        return add(t.getID());
    }

    public final boolean add(int tID) {
        if(bitset.get(tID))
            return false;
        else {
            bitset.set(tID);
            return true;
        }
    }

    @Override @SuppressWarnings("unchecked")
    public boolean remove(Object o) {
        final int tID = ((T) o).getID();
        return remove(tID);
    }

    public boolean remove(int tID) {
        if(bitset.get(tID)) {
            bitset.clear(tID);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean containsAll(Collection<?> collection) {
        for(Object o : collection)
            if(!contains(o))
                return false;
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends T> collection) {
        if(collection instanceof IRSet) {
            bitset.or(((IRSet) collection).bitset);
        } else {
            for(T x : collection)
                add(x);
        }
        return true;
    }

    @Override
    public boolean retainAll(Collection<?> collection) {
        if(collection instanceof IRSet) {
            bitset.and(((IRSet) collection).bitset);
        } else {
            int next = bitset.nextSetBit(0);
            while(next != -1) {
                if(!collection.contains(intRep.fromInt(next)))
                    remove(next);
                next = bitset.nextSetBit(next+1);
            }
        }
        return true;
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
        if(collection instanceof IRSet) {
            bitset.andNot(((IRSet) collection).bitset);
        } else {
            for(Object x : collection)
                remove(x);
        }
        return true;
    }

    @Override
    public void clear() {
        bitset.clear();
    }

    @Override
    public IRSet<T> clone() {
        return new IRSet<T>(intRep, this.toBitSet());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        Iterator<T> it = iterator();
        while(it.hasNext()) {
            T o = it.next();
            sb.append(o.toString());
            if(it.hasNext())
                sb.append(", ");
        }
        sb.append("}");
        return sb.toString();
    }
}