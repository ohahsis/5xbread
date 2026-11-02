package com.fivexbread.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 동시 요청 테스트: 작업을 모두 대기시켰다가 한꺼번에 출발시키고, 넣은 순서대로 결과를 모은다 */
public final class Concurrently {

    private Concurrently() {
    }

    public static <T> List<T> run(List<Callable<T>> tasks, int threads) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(executor.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        try {
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdown();
        }
        return results;
    }
}
