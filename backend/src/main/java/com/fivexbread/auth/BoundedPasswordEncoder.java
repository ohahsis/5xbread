package com.fivexbread.auth;

import com.fivexbread.common.BusinessException;
import com.fivexbread.common.ErrorCode;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt 동시 계산 수를 제한한다.
 *
 * 재시작 직후 로그인이 몰리면 Tomcat 스레드 200개가 모두 BCrypt(cost 10, CPU 전용)를 돌려 CPU 를 다 쓴다.
 * 그러면 Lettuce 의 Netty 스레드가 CPU 를 받지 못해 이미 도착한 Redis 응답도 늦게 읽어 2초 시간 제한에 걸리고 503이 난다.
 * 동시 계산을 CPU 수 정도로 묶으면 처리량은 같고(어차피 CPU 수만큼만 동시에 돈다) 나머지 스레드는 잠들어 기다려
 * Redis·DB 응답을 처리할 CPU 가 남는다. 측정은 decisions.md "로그인 BCrypt 동시 실행 제한".
 *
 * 기다리는 시간에는 제한을 두지 않는다. 로그인은 비교 전에 시도 횟수를 세므로(LoginFailureCounter)
 * 대기 중에 503으로 끊으면 결과 없이 횟수만 늘어 바쁜 시간에 잠금이 걸릴 수 있다.
 */
public class BoundedPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final Semaphore permits;

    public BoundedPasswordEncoder(PasswordEncoder delegate, int permits) {
        this.delegate = delegate;
        this.permits = new Semaphore(permits, true);    // 공정 모드: 먼저 온 요청이 먼저 계산한다
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return bounded(() -> delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return bounded(() -> delegate.matches(rawPassword, encodedPassword));
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);   // 해시 형식만 본다
    }

    private <T> T bounded(Supplier<T> hashing) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();     // 종료 중. 계산하지 않고 끝낸다
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            return hashing.get();
        } finally {
            permits.release();
        }
    }

    int availablePermits() {
        return permits.availablePermits();
    }
}
