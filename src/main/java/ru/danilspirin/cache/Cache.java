package ru.danilspirin.cache;

public interface Cache<T, V> {

    void put(T key, V value);
    V get(T key);
}
