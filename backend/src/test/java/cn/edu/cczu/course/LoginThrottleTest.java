package cn.edu.cczu.course;

import cn.edu.cczu.course.common.exception.BusinessException;
import cn.edu.cczu.course.module.auth.service.LoginThrottle;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static cn.edu.cczu.course.module.auth.service.LoginThrottle.Method.LOCAL;
import static cn.edu.cczu.course.module.auth.service.LoginThrottle.Method.PASSWORD;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证登录限制的计数范围、过期恢复和并发失败计数。 */
class LoginThrottleTest {
    private static final String IP = "127.0.0.1";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    // 校验限制十五分钟后解除，并按账号、地址和登录方式分别计数。
    @Test
    void lockExpiresAfterFifteenMinutesAndIsScopedToAccountAddressAndMethod() {
        // 测试通过控制时钟模拟失败统计窗口与锁定期，无需等待实际分钟数。
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START);
        var throttle = new LoginThrottle(clock);
        for (int i = 0; i < 5; i++) throttle.onFailure(PASSWORD, "Student", IP);
        assertThatThrownBy(() -> throttle.assertAllowed(PASSWORD, "STUDENT ", IP))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> throttle.onSuccess(PASSWORD, "Student", IP)).isInstanceOf(BusinessException.class);
        throttle.assertAllowed(PASSWORD, "another", IP);
        throttle.assertAllowed(PASSWORD, "Student", "127.0.0.2");
        throttle.assertAllowed(LOCAL, "Student", IP);
        when(clock.instant()).thenReturn(START.plusSeconds(899));
        assertThatThrownBy(() -> throttle.assertAllowed(PASSWORD, "Student", IP)).isInstanceOf(BusinessException.class);
        when(clock.instant()).thenReturn(START.plusSeconds(900));
        throttle.assertAllowed(PASSWORD, "Student", IP);
    }

    // 验证登录成功及失败记录过期都能清除之前的累计次数。
    @Test
    void successfulLoginAndExpiredFailuresResetTheWindow() {
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START);
        var throttle = new LoginThrottle(clock);
        for (int i = 0; i < 4; i++) throttle.onFailure(PASSWORD, "student", IP);
        throttle.onSuccess(PASSWORD, "student", IP);
        for (int i = 0; i < 4; i++) throttle.onFailure(PASSWORD, "student", IP);
        throttle.assertAllowed(PASSWORD, "student", IP);
        when(clock.instant()).thenReturn(START.plusSeconds(300));
        throttle.onFailure(PASSWORD, "student", IP);
        throttle.assertAllowed(PASSWORD, "student", IP);
    }

    // 让多个失败请求并发上报，核对每一次失败都被计入。
    @Test
    void concurrentFailuresAreCountedWithoutLosingAttempts() throws Exception {
        var throttle = new LoginThrottle(Clock.fixed(START, java.time.ZoneOffset.UTC));
        try (var threads = Executors.newFixedThreadPool(5)) {
            var failures = IntStream.range(0, 5).<Callable<Void>>mapToObj(i -> () -> {
                throttle.onFailure(PASSWORD, "student", IP);
                return null;
            }).toList();
            for (var result : threads.invokeAll(failures)) result.get();
        }
        assertThatThrownBy(() -> throttle.assertAllowed(PASSWORD, "student", IP)).isInstanceOf(BusinessException.class);
    }
}
