package org.cache2k.benchmark;

/*
 * #%L
 * Benchmarks: Implementation and eviction variants
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

import org.cache2k.benchmark.util.TraceSupplier;
import org.junit.After;
import org.cache2k.benchmark.util.AccessTrace;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * @author Jens Wilke; created: 2013-12-08
 */
public class BenchmarkingBase {

  protected BenchmarkCacheFactory factory = new Cache2kForEvictionBenchmarkFactory();

  BenchmarkCache<Integer, Integer> cache = null;

  public BenchmarkCache<Integer, Integer> freshCache(AccessTrace t, int _maxElements) {
    return freshCache(_maxElements);
  }

  public BenchmarkCache<Integer, Integer> freshCache(int _maxElements) {
    if (cache != null) {
      throw new IllegalStateException("Two caches in one test? Please call destroyCache() first");
    }
    return cache = factory.create(Integer.class, Integer.class, _maxElements);
  }

  public void destroyCache() {
    if (cache != null) {
      cache.close();
      cache = null;
    }
  }

  @After
  public void tearDown() {
    destroyCache();
  }

  public static final long runBenchmark(BenchmarkCache<Integer, Integer> c, AccessTrace t) {
    Integer[] _trace = t.getObjectArray();
    if (c instanceof SimulatorPolicy) {
      SimulatorPolicy p = (SimulatorPolicy) c;
      for (Integer k : _trace) {
        ((SimulatorPolicy) c).record(k);
      }
      return p.getMissCount();
    }
    long _missCount =  0;
    for (Integer k : _trace) {
      Integer v = c.get(k);
      if (v == null) {
        c.put(k, k);
        _missCount++;
      }
    }
    return _missCount;
  }

  public final int runBenchmark(TraceSupplier sup, int _cacheSize) {
    return runBenchmark(sup.get(), _cacheSize);
  }

  public final int runBenchmark(AccessTrace t, int _cacheSize) {
    BenchmarkCache<Integer, Integer> c;
    c = freshCache(t, _cacheSize);
    long _missCount = runBenchmark(c, t);
    logHitRate(c, t, _missCount);
    c.close();
    return
      ((t.getTraceLength() - (int) _missCount) * 10000 + t.getTraceLength() / 2) / t.getTraceLength();
  }

  public void logHitRate(BenchmarkCache c, AccessTrace _trace, long _missCount) {
    int _optHitRate = -1;
    int _optHitCount = -1;
    String _testName = _trace.getName();
    saveHitRate(_testName, c.getCapacity(), _trace, _optHitRate,_optHitCount, _missCount, 0);
    String _cacheStatistics = c.toString();
    System.out.println(_cacheStatistics);
    System.out.flush();
  }

  void saveHitRate(String _testName, int _cacheSize, AccessTrace _trace, int _optHitRate, int _optHitCount, long _missCount, long _usedMem) {
    double _hitRateTimes100 =
      (_trace.getTraceLength() - _missCount) * 100D / _trace.getTraceLength();
    String _hitRate = String.format("%.2f", _hitRateTimes100);
    String s = "";
    if (_cacheSize > 0) {
      s += "size=" + _cacheSize + ", ";
    }
    s += "accessCount=" + _trace.getTraceLength();
    s += ", missCount=" + _missCount + ", hitRatePercent=" + _hitRate;
    if (_optHitRate >= 0) {
      s += ", optHitRatePercent=" + String.format("%.2f", _optHitRate * 1D / 100);
      s += ", optHitCount=" + _optHitCount;
    }
    s += ", uniqueValues=" + _trace.getValueCount();
    String _cacheImplementation = factory.getName();
    System.out.println(_cacheImplementation + "@" + _testName + ": " + s);
    String _benchmarkName = _testName;
    String _csvLine =
      _benchmarkName + "|" +  // 1
      _cacheImplementation + "|" + // 2
        String.format("%.2f", _hitRateTimes100) + "|" + // 3
        _cacheSize + "|" + // 4
        _missCount + "|" + // 5
      _trace.getTraceLength() + "|" + // 6
      _trace.getValueCount(); // 7

    writeCsv(_csvLine);
  }

  void writeCsv(String _csvLine) {
    String s = System.getProperty("cache2k.benchmark.result.csv");
    if (s == null) {
      return;
    }
    try {
      PrintWriter w = new PrintWriter(new FileWriter(s, true));
      w.println(_csvLine);
      w.close();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

}
