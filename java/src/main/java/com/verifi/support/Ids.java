package com.verifi.support;

import com.verifi.error.ApiException;

import java.util.UUID;

public final class Ids {
    private Ids() {}

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    public static void checkUuid(String id) {
        try {
            UUID.fromString(id);
        } catch (Exception ex) {
            throw new ApiException(400, "id: Invalid value", "VALIDATION_ERROR");
        }
    }
}
