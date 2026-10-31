package ru.otus.socialnetwork.load;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import io.gatling.javaapi.core.Session;

final class StageStats {
    final int users;
    final int repeat;
    final String name;
    final LongAdder ok = new LongAdder();
    final LongAdder failed = new LongAdder();
    final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
    final Map<String, LongAdder> errors = new ConcurrentHashMap<>();
    final AtomicLong firstStart = new AtomicLong(Long.MAX_VALUE);
    final AtomicLong lastEnd = new AtomicLong();
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger peak = new AtomicInteger();

    StageStats(int users, int repeat) {
        this.users = users;
        this.repeat = repeat;
        this.name = repeat == 0 ? "Warm-up" : "Search / users=" + users + " / repeat=" + repeat;
    }

    Session begin(Session session) {
        long now = System.nanoTime();
        if (firstStart.compareAndSet(Long.MAX_VALUE, now)) {
            System.out.println("BENCHMARK START: " + name);
        }
        firstStart.accumulateAndGet(now, Math::min);
        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
        return session;
    }

    Session end(Session session) {
        if (session.isFailed()) {
            failed.increment();
            Number status = session.get("httpStatus");
            String error = status == null ? "Transport error / timeout" : "HTTP " + status;
            errors.computeIfAbsent(error, ignored -> new LongAdder()).increment();
        } else {
            Number responseMillis = session.get("responseMillis");
            latencies.add(responseMillis.longValue());
            ok.increment();
        }
        lastEnd.accumulateAndGet(System.nanoTime(), Math::max);
        active.decrementAndGet();
        return session;
    }

    long total() {
        return ok.sum() + failed.sum();
    }

    double seconds() {
        return total() == 0 ? 0 : (lastEnd.get() - firstStart.get()) / 1_000_000_000.0;
    }
}
