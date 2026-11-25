package cn.edu.cczu.course.module.auth.service;

import cn.edu.cczu.course.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 按登录方式、账号和来源地址统计失败次数，短时间内失败过多时暂时限制登录。 */
@Service
public class LoginThrottle {
    public enum Method { PASSWORD, LOCAL }

    // 五分钟窗口内同一组合累计五次失败后，临时锁定十五分钟。使用可注入时钟方便测试模拟时间流逝。
    private static final int MAX_FAILURES = 5;
    private static final int MAX_KEYS = 5000;
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final Duration LOCK = Duration.ofMinutes(15);
    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(1);

    private final Clock clock;
    // 这些失败记录保存在本进程内。调用入口串行更新记录，防止多个同时失败的请求互相覆盖。
    private final Map<Key, Attempts> attempts = new HashMap<>();
    private Instant nextCleanup = Instant.MIN;

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    // 在尝试登录前检查当前账号和来源组合是否仍处于限制期。
    public synchronized void assertAllowed(Method method, String username, String ip) {
        var now = clock.instant();
        cleanup(now);
        var current = attempts.get(key(method, username, ip));
        if (current != null && current.isLocked(now)) {
            throw tooManyRequests();
        }
        if (current == null && attempts.size() >= MAX_KEYS) {
            throw tooManyRequests();
        }
    }

    // 记录本次失败并在达到阈值时开启临时限制。
    public synchronized void onFailure(Method method, String username, String ip) {
        var now = clock.instant();
        cleanup(now);
        var key = key(method, username, ip);
        var current = attempts.get(key);
        if (current == null) {
            if (attempts.size() >= MAX_KEYS) return;
            current = new Attempts();
            attempts.put(key, current);
        }
        if (current.isLocked(now)) return;
        current.pruneFailures(now);
        current.failures.addLast(now);
        if (current.failures.size() >= MAX_FAILURES) {
            current.lockedUntil = now.plus(LOCK);
            current.failures.clear();
        }
    }

    // 清除同一登录方式、账号与来源组合的失败记录。
    public synchronized void onSuccess(Method method, String username, String ip) {
        var key = key(method, username, ip);
        var current = attempts.get(key);
        // 早于锁定进入的成功请求也要重新检查，避免清掉并发建立的锁。
        if (current != null && current.isLocked(clock.instant())) {
            throw tooManyRequests();
        }
        attempts.remove(key);
    }

    // 清理超过统计窗口并且限制已过期的记录。
    private void cleanup(Instant now) {
        if (now.isBefore(nextCleanup) && attempts.size() < MAX_KEYS) return;
        attempts.values().removeIf(attempt -> {
            attempt.pruneFailures(now);
            return !attempt.isLocked(now) && attempt.failures.isEmpty();
        });
        nextCleanup = now.plus(CLEANUP_INTERVAL);
    }

    // 把登录方式、用户名和来源地址整理成统一的计数键。
    private Key key(Method method, String username, String ip) {
        // 本机 SQL Server 对账号通常按大小写不敏感比较，并忽略尾部空格。
        return new Key(method, username.stripTrailing().toLowerCase(Locale.ROOT), ip);
    }

    // 构造失败次数过多时使用的 HTTP 429 业务异常。
    private static BusinessException tooManyRequests() {
        return new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "登录尝试过于频繁，请稍后再试");
    }

    // 由登录方式、用户名和来源地址共同确定一组失败计数。
    private record Key(Method method, String username, String ip) { }

    private static final class Attempts {
        private final ArrayDeque<Instant> failures = new ArrayDeque<>();
        private Instant lockedUntil = Instant.MIN;

        private boolean isLocked(Instant now) {
            return now.isBefore(lockedUntil);
        }

        private void pruneFailures(Instant now) {
            // 失败记录按时间入队，过期的记录总在队首，从队首移除即可更新统计窗口。
            var cutoff = now.minus(WINDOW);
            while (!failures.isEmpty() && !failures.getFirst().isAfter(cutoff)) {
                failures.removeFirst();
            }
        }
    }
}
