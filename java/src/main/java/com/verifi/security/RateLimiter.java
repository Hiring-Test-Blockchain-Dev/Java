package com.verifi.security;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimiter {
    public static final long WINDOW_MS = 15 * 60 * 1000L;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public boolean allow(String key, int max) {
        long now = System.currentTimeMillis();
        Deque<Long> queue = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && now - queue.peekFirst() > WINDOW_MS) queue.removeFirst();
            if (queue.size() >= max) return false;
            queue.addLast(now);
            return true;
        }
    }

    public void refund(String key) {
        Deque<Long> queue = hits.get(key);
        if (queue == null) return;
        synchronized (queue) {
            if (!queue.isEmpty()) queue.removeLast();
        }
    }
}
