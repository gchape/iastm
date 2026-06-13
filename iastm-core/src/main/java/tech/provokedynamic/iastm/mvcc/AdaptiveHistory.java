package tech.provokedynamic.iastm.mvcc;

public interface AdaptiveHistory<T> {

    T scan(long readPoint);

    void append(T val, long version);

    void expandByFactor(float delta);

    void shrinkByFactor(float delta);
}
