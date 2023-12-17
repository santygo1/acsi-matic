package ru.danilspirin.cache;

import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

public class RedisCache<T, V> implements Cache<T, V> {

    private final RMap<T, V> store;

    public RedisCache(RedissonClient client,  String mapName) {
        store = client.getMap(mapName);
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
