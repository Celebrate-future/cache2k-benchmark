package org.cache2k.benchmark;

/*
 * #%L
 * Benchmarks: implementation variants
 * %%
 * Copyright (C) 2013 - 2019 headissue GmbH, Munich
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Use a {@link ConcurrentHashMap} as cache implementation. No eviction is done. Intended
 * for benchmarks when no eviction is needed, meaning, the cache size is larger then the
 * data set.
 *
 * @author Jens Wilke
 */
public class ConcurrentHashMapFactory extends BenchmarkCacheFactory {

  @Override
  protected <K, V> BenchmarkCache<K, V> createSpecialized(final Class<K> _keyType, final Class<V> _valueType, final int _maxElements) {
    return new MyCache(new ConcurrentHashMap<K, V>(), _maxElements);
  }

  static class MyCache<K,V> extends BenchmarkCache<K, V> {

    int maxElements;
    Map<K, V> map;

    public MyCache(Map<K, V> map, int maxElements) {
      this.map = map;
      this.maxElements = maxElements;
    }

    @Override
    public void close() {
      map = null;
    }

    @Override
    public void put(K key, V value) {
      map.put(key, value);
    }

    @Override
    public V get(K key) {
      return map.get(key);
    }

    @Override
    public void remove(final K key) {
      map.remove(key);
    }

    @Override
    public int getCapacity() {
      return maxElements;
    }

    @Override
    public String toString() {
      return "mapimpl=" + map.getClass().getName();
    }
  }

}
