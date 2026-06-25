package io.sentry.uitest.android.benchmark

import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.sentry.util.AutoClosableReentrantLock
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.test.Test
import org.junit.runner.RunWith

/**
 * On-device A/B benchmark for JAVA-588 (lazy lock allocation in [AutoClosableReentrantLock]).
 *
 * Before the change `AutoClosableReentrantLock extends ReentrantLock`, so constructing each
 * lock-bearing SDK object allocated a `ReentrantLock` (+ its `AbstractQueuedSynchronizer`) eagerly,
 * even when the lock was never acquired during `SentryAndroid.init`. After the change the
 * underlying `ReentrantLock` is created lazily on first `acquire()`.
 *
 * Each phase is wrapped in [Debug.startMethodTracing] so the produced ART `.trace` files can be
 * loaded into Perfetto `trace_processor` to count `ReentrantLock.<init>` /
 * `AbstractQueuedSynchronizer` slices. btrace app tracing is broken on Pixel 3 / Android 12, which
 * is why this uses ART method tracing.
 *
 * Not run in CI; it exists to quantify the SDK-init lock-allocation change on a real device.
 */
@RunWith(AndroidJUnit4::class)
class LockAllocationBenchmarkTest {

  private val tag = "LockAllocBench"
  private val count = 10_000
  private val dir: File
    get() = ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null)!!

  /**
   * Construction without ever acquiring: this is the common init case. OLD eagerly allocates a
   * ReentrantLock per object; NEW allocates none.
   */
  @Test
  fun construct_withoutAcquire() {
    val old = File(dir, "lockalloc_old")
    Debug.startMethodTracing(old.absolutePath)
    val oldSink = arrayOfNulls<ReentrantLock>(count)
    for (i in 0 until count) {
      oldSink[i] = ReentrantLock() // what `extends ReentrantLock` allocated at construction
    }
    Debug.stopMethodTracing()

    val new = File(dir, "lockalloc_new")
    Debug.startMethodTracing(new.absolutePath)
    val newSink = arrayOfNulls<AutoClosableReentrantLock>(count)
    for (i in 0 until count) {
      newSink[i] = AutoClosableReentrantLock() // lazy: no ReentrantLock yet
    }
    Debug.stopMethodTracing()

    Log.i(tag, "CONSTRUCT count=$count old=eager-ReentrantLock new=lazy (see lockalloc_*.trace)")
    // Keep the arrays reachable so the allocations are not optimized away.
    Log.i(tag, "sinks ${oldSink.size}/${newSink.size}")
  }

  /** acquire()/close throughput must be unchanged on the hot path (lock already created). */
  @Test
  fun acquireThroughput() {
    val iterations = 200_000
    val rounds = 6
    val lock = AutoClosableReentrantLock()

    fun time(): Long {
      val start = System.nanoTime()
      repeat(iterations) { lock.acquire().close() }
      return System.nanoTime() - start
    }

    repeat(2) { time() } // warmup
    var total = 0L
    for (round in 0 until rounds) {
      total += time()
    }
    Log.i(tag, "ACQUIRE ${total / (rounds.toLong() * iterations)}ns/op")
  }
}
