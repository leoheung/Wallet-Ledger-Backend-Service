package com.wallet.ledger.it;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Fires all tasks at once from a start barrier, so requests genuinely overlap. */
final class StressHarness {

    private StressHarness() {
    }

    interface Task<T> {
        T run(int index) throws Exception;
    }

    static <T> List<T> runConcurrently(int threadCount, Task<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.run(index);
                }));
            }
            if (!ready.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("threads did not become ready in time");
            }
            start.countDown();

            List<T> results = new ArrayList<>(threadCount);
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdown();
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                throw new IllegalStateException("thread pool did not terminate in time");
            }
        }
    }
}
