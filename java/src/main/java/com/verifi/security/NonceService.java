package com.verifi.security;

import com.verifi.support.Body;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class NonceService {
    private static final long TTL_MS = 5 * 60 * 1000L;
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public String create(String address) {
        String key = Body.walletKey(address);
        String nonce = "fp-" + System.currentTimeMillis() + "-" + Long.toString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), 36);
        store.put(key, new Entry(nonce, System.currentTimeMillis() + TTL_MS));
        return nonce;
    }

    public boolean consume(String address, String nonce) {
        String key = Body.walletKey(address);
        Entry stored = store.get(key);
        if (stored == null || !stored.nonce.equals(nonce) || System.currentTimeMillis() > stored.expires) return false;
        store.remove(key);
        return true;
    }

    private record Entry(String nonce, long expires) {}
}
