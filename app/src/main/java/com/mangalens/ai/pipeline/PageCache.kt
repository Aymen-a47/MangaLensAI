package com.mangalens.ai.pipeline

import com.mangalens.ai.capture.ChangeDetector
import com.mangalens.ai.data.Region

/** LRU of recently translated pages, matched by visual similarity (so swiping back is instant). */
class PageCache(private val max: Int) {
    private class E(val grid: IntArray, val regions: List<Region>)
    private val list = ArrayList<E>()

    @Synchronized fun find(grid: IntArray): List<Region>? {
        val i = list.indexOfFirst { ChangeDetector.ratio(grid, it.grid) < 0.012f }
        if (i < 0) return null
        val e = list.removeAt(i); list.add(0, e)
        return e.regions
    }

    @Synchronized fun put(grid: IntArray, regions: List<Region>) {
        list.removeAll { ChangeDetector.ratio(grid, it.grid) < 0.012f }
        list.add(0, E(grid, regions))
        while (list.size > max) list.removeAt(list.size - 1)
    }

    @Synchronized fun clear() = list.clear()
}
