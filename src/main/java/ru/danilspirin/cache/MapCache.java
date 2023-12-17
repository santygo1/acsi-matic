package ru.danilspirin.cache;

import java.util.HashMap;
import java.util.Map;

public class MapCache<T, V> implements Cache<T, V> {

    private final Map<T, V> store;

    public MapCache(){
        store = new HashMap<>();
    }

    public MapCache(Map<T, V> store){
        this.store = store;
    }

    @Override
    public void put(T key, V value) {
        store.put(key, value);
    }

    @Override
    public V get(T key) {
        return store.get(key);
    }
}
