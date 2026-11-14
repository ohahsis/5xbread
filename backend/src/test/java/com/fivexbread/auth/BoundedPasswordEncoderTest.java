package com.fivexbread.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class BoundedPasswordEncoderTest {

    @Test
    void 동시에_들어온_비교는_상한까지만_함께_계산하고_나머지는_기다렸다가_모두_끝난다() throws Exception {
        int permits = 3;
        int requests = 20;
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        CountDownLatch firstWave = new CountDownLatch(permits);
        CountDownLatch release = new CountDownLatch(1);
        PasswordEncoder slow = new SlowEncoder(() -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            firstWave.countDown();
            await(release);
            running.decrementAndGet();
        });
        BoundedPasswordEncoder encoder = new BoundedPasswordEncoder(slow, permits);

        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> encoder.matches("pw", "hash")));
            }
            assertThat(firstWave.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);                                      // 상한을 넘는 요청이 들어갈 기회를 준다
            assertThat(running.get()).isEqualTo(permits);
            assertThat(encoder.availablePermits()).isZero();

            release.countDown();
            for (Future<Boolean> result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(maxRunning.get()).isEqualTo(permits);
        assertThat(encoder.availablePermits()).isEqualTo(permits);   // 모두 돌려줬다
    }

    @Test
    void 계산이_예외로_끝나도_자리를_돌려준다() {
        PasswordEncoder failing = new SlowEncoder(() -> { throw new IllegalStateException("boom"); });
        BoundedPasswordEncoder encoder = new BoundedPasswordEncoder(failing, 1);

        assertThatThrownBy(() -> encoder.encode("pw")).isInstanceOf(IllegalStateException.class);
        assertThat(encoder.availablePermits()).isEqualTo(1);
    }

    @Test
    void 기다리다_인터럽트되면_계산하지_않고_503() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch holding = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        BoundedPasswordEncoder encoder = new BoundedPasswordEncoder(new SlowEncoder(() -> {
            if (calls.incrementAndGet() == 1) {
                holding.countDown();
                await(release);
            }
        }), 1);

        Thread holder = Thread.ofPlatform().start(() -> encoder.encode("pw"));
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
        AtomicInteger waiterResult = new AtomicInteger();
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                encoder.matches("pw", "hash");
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.SERVICE_UNAVAILABLE && Thread.currentThread().isInterrupted()) {
                    waiterResult.set(1);
                }
            }
        });
        Thread.sleep(100);
        waiter.interrupt();
        waiter.join(5_000);
        release.countDown();
        holder.join(5_000);

        assertThat(waiterResult.get()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(encoder.availablePermits()).isEqualTo(1);
    }

    @Test
    void BCrypt_를_감싸도_해시와_비교_결과는_같다() {
        BoundedPasswordEncoder encoder = new BoundedPasswordEncoder(new BCryptPasswordEncoder(4), 2);
        String hash = encoder.encode("secret-1");

        assertThat(encoder.matches("secret-1", hash)).isTrue();
        assertThat(encoder.matches("secret-2", hash)).isFalse();
    }

    @Test
    void 상한이_0이면_CPU_수() {
        assertThat(new PasswordHashProperties(0).permits()).isEqualTo(Runtime.getRuntime().availableProcessors());
        assertThat(new PasswordHashProperties(7).permits()).isEqualTo(7);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 계산 대신 주어진 동작을 한다. encode·matches 모두 같은 동작 */
    private record SlowEncoder(Runnable work) implements PasswordEncoder {
        @Override
        public String encode(CharSequence rawPassword) {
            work.run();
            return "hash";
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            work.run();
            return true;
        }
    }
}
