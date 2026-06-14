package tech.provokedynamic.iastm.mvcc;

public sealed interface AdaptiveHistory<T> permits CircularArray {

    T scan(long readPoint);

    void append(T val, long version);

    void expandByFactor(float delta);

    void shrinkByFactor(float delta);
}
