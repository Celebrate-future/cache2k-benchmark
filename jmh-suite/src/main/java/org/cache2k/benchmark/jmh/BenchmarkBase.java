package org.cache2k.benchmark.jmh;

/*
 * #%L
 * Benchmarks: JMH suite.
 * %%
 * Copyright (C) 2013 - 2017 headissue GmbH, Munich
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

import org.cache2k.benchmark.BenchmarkCache;
import org.cache2k.benchmark.BenchmarkCacheFactory;
import org.cache2k.benchmark.Cache2kFactory;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.io.Closeable;

/**
 * Base for all JMH cache benchmarks, controlling the cache lifecycle and
 * recording memory usage.
 *
 * @author Jens Wilke
 */
@State(Scope.Benchmark)
public class BenchmarkBase {

  protected Closeable getsDestroyed;

  @Param("DEFAULT")
  public String cacheFactory;

  public BenchmarkCacheFactory getFactory() {
    try {
      if ("DEFAULT".equals(cacheFactory)) {
        cacheFactory = Cache2kFactory.class.getCanonicalName();
      }
      BenchmarkCacheFactory _factoryInstance =
        (BenchmarkCacheFactory) Class.forName(cacheFactory).newInstance();
      return _factoryInstance;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @TearDown(Level.Iteration)
  public void tearDownBase() throws Exception {
    ForcedGcMemoryProfiler.recordUsedMemory();
    if (getsDestroyed != null) {
      System.out.println();
      System.out.println(getsDestroyed);
      System.out.println("availableProcessors: " + Runtime.getRuntime().availableProcessors());
      getsDestroyed.close();
      getsDestroyed = null;
    }
  }

}
