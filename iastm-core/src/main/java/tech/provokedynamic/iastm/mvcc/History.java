package tech.provokedynamic.iastm.mvcc;

public interface History<T> {

    T scan(long readPoint);

    void append(T val, long version);
}
