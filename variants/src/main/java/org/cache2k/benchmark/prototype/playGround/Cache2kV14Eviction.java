package org.cache2k.benchmark.prototype.playGround;

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

import org.cache2k.benchmark.prototype.EvictionPolicy;
import org.cache2k.benchmark.EvictionStatistics;
import org.cache2k.benchmark.prototype.LinkedEntry;

/**
 * Eviction policy based on the Clock-Pro idea.
 *
 * <p>The Clock-Pro algorithm is explained by the authors in
 * <a href="http://www.ece.eng.wayne.edu/~sjiang/pubs/papers/jiang05_CLOCK-Pro.pdf">CLOCK-Pro:
 * An Effective Improvement of the CLOCK Replacement</a>
 * and <a href="http://www.slideshare.net/huliang64/clockpro">Clock-Pro: An Effective
 * Replacement in OS Kernel</a>.
 *
 * <p>
 *
 * @author Jens Wilke
 */
@SuppressWarnings({"Duplicates", "WeakerAccess"})
public class Cache2kV14Eviction<K, V> extends EvictionPolicy<K, V, Cache2kV14Eviction.Entry> {

	private long hotHits;
	private long coldHits;
	private long ghostHits;

	private long hotRunCnt;
	private long hotScanCnt;
	private long coldRunCnt;
	private long coldScanCnt;
	private long reshuffleCnt;

	private int coldSize;
	private int hotSize;

	/** Maximum size of hot clock. 0 means normal clock behaviour */

	private Entry handCold;
	private Entry handHot;

	private Cache2kV14Eviction.Ghost[] ghosts;
	private Cache2kV14Eviction.Ghost ghostHead = new Ghost().shortCircuit();
	private int ghostSize = 0;
	private static final int GHOST_LOAD_PERCENT = 63;
	private Cache2kV1Tuning tuning;

	public Cache2kV14Eviction(int capacity, Cache2kV1Tuning tuning) {
		super(capacity);
		this.tuning = tuning;
		coldSize = 0;
		hotSize = 0;
		handCold = null;
		handHot = null;
		ghosts = new Ghost[4];
	}

	@Override
	public Entry newEntry(K key, V value) {
		Entry e = new Entry(key, value);
		insertIntoReplacementList(e);
		return e;
	}

	@Override
	public void recordHit(final Entry e) {
		e.hitCnt++;
	}

	@Override
	public Entry evict() {
		Entry e = findEvictionCandidate();
		removeFromReplacementListOnEvict(e);
		return e;
	}

	@Override
	public void remove(final Entry e) {
    removeFromReplacementList(e);
	}

	private long sumUpListHits(Entry e) {
		if (e == null) { return 0; }
		long cnt = 0;
		Entry _head = e;
		do {
			// BTW: we need to sum up the stale entries hit count as well
			cnt += e.hitCnt;
			e = e.next;
		} while (e != _head);
		return cnt;
	}

	public long getHotMax() {
		return getCapacity() * tuning.hotMaxPercentage / 100;
	}

	public long getGhostMax() {
		return getSize() / 2 + 1;
	}

	/**
	 * Track the entry on the ghost list and call the usual remove procedure.
	 */
	public void removeFromReplacementListOnEvict(final Entry e) {
		insertCopyIntoGhosts(e);
		removeFromReplacementList(e);
	}

	/**
	 * Remove, expire or eviction of an entry happens. Remove the entry from the
	 * replacement list data structure.
	 */
	protected void removeFromReplacementList(Entry e) {
		// assert getListSize() == getLocalSize();
		// BTW: don't reset value here to save memory, we have parallel fetches!
		if (e.isHot()) {
			hotHits += e.hitCnt;
			handHot = Entry.removeFromCyclicList(handHot, e);
			hotSize--;
		} else {
			coldHits += e.hitCnt;
			handCold = Entry.removeFromCyclicList(handCold, e);
			coldSize--;
		}
	}

	private void insertCopyIntoGhosts(Entry e) {
		int hc = e.getHashCode();
		Ghost g = lookupGhost(hc);
		// FIXME: needed?
		if (g != null) {
			/*
			 * either this is a hash code collision, or a previous ghost hit that was not removed.
			 */
			Ghost.moveToFront(ghostHead, g);
			return;
		}
		if (ghostSize >= getGhostMax()) {
			g = ghostHead.prev;
			Ghost.removeFromList(g);
			boolean f = removeGhostFromHash(g, g.hash);
			assert f;
		} else {
			g = new Ghost();
		}
		g.hash = hc;
		insertGhost(g, hc);
		Ghost.insertInList(ghostHead, g);
	}

	public long getSize() {
		return hotSize + coldSize;
	}

	protected void insertIntoReplacementList(Entry e) {
		// may get hits since it becomes visible
		// assert e.hitCnt == 0;
		Ghost g = lookupGhost(e.getHashCode());
		if (g != null) {
			/*
			 * don't remove ghosts here, save object allocations.
			 * removeGhost(g, g.hash);  Ghost.removeFromList(g);
			 */
			ghostHits++;
		}
		// fill up hot on startup first / glimpse optimization.
		if (g != null || (hotSize < getHotMax())) {
			e.setHot(true);
			hotSize++;
			handHot = Entry.insertIntoTailCyclicList(handHot, e);
			return;
		}
		coldSize++;
		handCold = Entry.insertIntoTailCyclicList(handCold, e);
	}

	private Entry runHandHot() {
		return runHandHot(hotSize >> 2 + 1);
	}
		/**
		 * Scan through hot entries.
		 *
		 * @return candidate for eviction or demotion
		 */
	private Entry runHandHot(final int _initialMaxScan) {
		assert(hotSize > 0);
		assert(handHot != null);
		hotRunCnt++;
		Entry _hand = handHot;
		Entry _coldCandidate = _hand;
		long _lowestHits = Long.MAX_VALUE;
		long _hotHits = hotHits;
		int _maxScan = _initialMaxScan;
		long _decrease = ((_hand.hitCnt + _hand.next.hitCnt) >> tuning.hitCounterDecreaseShift) + 1;
		while (_maxScan-- > 0) {
			long _hitCnt = _hand.hitCnt;
			if (_hitCnt < _lowestHits) {
				_lowestHits = _hitCnt;
				_coldCandidate = _hand;
				if (_hitCnt == 0) {
					break;
				}
			}
			if (_hitCnt < _decrease) {
				_hand.hitCnt = 0;
				_hotHits += _hitCnt;
			} else {
				_hand.hitCnt = _hitCnt - _decrease;
				_hotHits += _decrease;
			}
			_hand = _hand.next;
		}
		hotHits = _hotHits;
		long _scanCount = _initialMaxScan - _maxScan;
		hotScanCnt += _scanCount;
		handHot = _coldCandidate.next;
		return _coldCandidate;
	}

	/**
	 * Runs cold hand an in turn hot hand to find eviction candidate.
	 */
	protected Entry findEvictionCandidate() {
		assert handCold == null || coldSize > 0;
		assert handCold != null || handHot != null;
		Entry _hand = handCold;
		if (hotSize > getHotMax() || _hand == null) {
			return runHandHot();
		}
		coldRunCnt++;
		int _scanCnt = 1;
		assert coldSize > 0;
		if (_hand.hitCnt > 0) {
			Entry _evictFromHot = null;
			do {
				if (hotSize >= getHotMax() && handHot != null) {
					_evictFromHot = runHandHot();
				}
				_scanCnt++;
				coldHits += _hand.hitCnt;
				_hand.hitCnt = 0;
				Entry e = _hand;
				_hand = Entry.removeFromCyclicList(e);
				coldSize--;
				assert !e.isHot();
				e.setHot(true);
				hotSize++;
				handHot = Entry.insertIntoTailCyclicList(handHot, e);
				reshuffleCnt++;
				if (_evictFromHot != null) {
					coldScanCnt += _scanCnt;
					handCold = _hand;
					return _evictFromHot;
				}
			} while (_hand != null && _hand.hitCnt > 0);
		}
		coldScanCnt += _scanCnt;
		if (_hand == null) {
			handCold = null;
			return runHandHot();
		}
		handCold = _hand.next;
		return _hand;
	}

	private Ghost lookupGhost(int _hash) {
		Ghost[] tab = ghosts;
		int n = tab.length;
		int _mask = n - 1;
		int idx = _hash & (_mask);
		Ghost e = tab[idx];
		while (e != null) {
			if (e.hash == _hash) {
				return e;
			}
			e = e.another;
		}
		return null;
	}

	private void insertGhost(Ghost e2, int _hash) {
		Ghost[] tab = ghosts;
		int n = tab.length;
		int _mask = n - 1;
		int idx = _hash & (_mask);
		e2.another = tab[idx];
		tab[idx] = e2;
		ghostSize++;
		int _maxFill = n * GHOST_LOAD_PERCENT / 100;
		if (ghostSize > _maxFill) {
			expand();
		}
	}

	private void expand() {
		Ghost[] tab = ghosts;
		int n = tab.length;
		int _mask;
		int idx;
		Ghost[] _newTab = new Ghost[n * 2];
		_mask = _newTab.length - 1;
		for (Ghost g : tab) {
			while (g != null) {
				idx = g.hash & _mask;
				Ghost _next = g.another;
				g.another = _newTab[idx];
				_newTab[idx] = g;
				g = _next;
			}
		}
		ghosts = _newTab;
	}

	private boolean removeGhostFromHash(Ghost g, int _hash) {
		Ghost[] tab = ghosts;
		int n = tab.length;
		int _mask = n - 1;
		int idx = _hash & (_mask);
		Ghost e = tab[idx];
		if (e == g) {
			tab[idx] = e.another;
			ghostSize--;
			return true;
		} else {
			while (e != null) {
				Ghost _another = e.another;
				if (_another == g) {
					e.another = _another.another;
					ghostSize--;
					return true;
				}
				e = _another;
			}
		}
		return false;
	}

	@Override
	public EvictionStatistics getEvictionStats() {
		return new EvictionStatistics() {
			@Override
			public long getScanCount() {
				return coldScanCnt + hotScanCnt;
			}
			@Override
			public long getReshuffleCount() {
				return reshuffleCnt;
			}
		};
	}

	public String toString() {
		return this.getClass().getSimpleName() +
			"(coldSize=" + coldSize +
			", hotSize=" + hotSize +
			", hotMaxSize=" + getHotMax() +
			", ghostSize=" + ghostSize +
			// cache hits on cold clock
			", coldHits=" + (coldHits + sumUpListHits(handCold)) +
			// cache hits on hot clock
			", hotHits=" + (hotHits + sumUpListHits(handHot)) +
			// hits on ghosts ("ghost" means "cold non resident")
			", ghostHits=" + ghostHits +
			// How often hand cold is triggered
			", coldRunCnt=" + coldRunCnt +// identical to the evictions anyways
			// How many entries are scanned on a hand cold run
			", coldScanCnt=" + coldScanCnt +
			// incremented when hand cold does a full clock cycle
			// How often hand hot is triggered
			", hotRunCnt=" + hotRunCnt +
			// How many entries are scanned on a hand hot run
			", hotScanCnt=" + hotScanCnt +
			", totalScanCnt=" + (hotScanCnt + coldScanCnt) +
			", reshuffleCnt=" + reshuffleCnt + ")";
	}

	/**
	 * Ghost representing a entry we have seen and evicted from the cache. We only store
	 * the hash to save memory, since holding the key references may cause a size overhead.
	 */
	private static class Ghost {

		/** Modified hashcode of the key */
		int hash;
		/** Hash table chain */
		Ghost another;
		/** LRU double linked list */
		Ghost next;
		/** LRU double linked list */
		Ghost prev;

		Ghost shortCircuit() {
			return next = prev = this;
		}

		static void removeFromList(final Ghost e) {
			// check that list is not empty
			assert e != e.prev;
			assert e.next != e;
			assert e.next != null;
			assert e.prev != null;
			e.prev.next = e.next;
			e.next.prev = e.prev;
			e.next = e.prev = null;
		}

		static void insertInList(final Ghost _head, final Ghost e) {
			e.prev = _head;
			e.next = _head.next;
			e.next.prev = e;
			_head.next = e;
		}

		static void moveToFront(final Ghost _head, final Ghost e) {
			removeFromList(e);
			insertInList(_head, e);
		}

	}

	static class Entry extends LinkedEntry<Entry, Object, Object> {

		long hitCnt;
		boolean hot;

		private Entry(final Object _key, final Object _value) {
			super(_key, _value);
		}

		public int getHashCode() {
			int hc = getKey().hashCode();
			return hc ^ hc >>> 16;
		}

		public void setHot(final boolean v) {
			hot = v;
		}

		boolean isHot() {
			return hot;
		}

	}

}
