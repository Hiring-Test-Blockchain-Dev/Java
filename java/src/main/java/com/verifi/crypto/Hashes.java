package com.verifi.crypto;

import org.bouncycastle.jcajce.provider.digest.Keccak;

public final class Hashes {
    private Hashes() {}

    public static byte[] keccak256(byte[] input) {
        Keccak.Digest256 digest = new Keccak.Digest256();
        digest.update(input, 0, input.length);
        return digest.digest();
    }

    public static String keccakHex(byte[] input) {
        return hex(keccak256(input));
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(2 + bytes.length * 2);
        sb.append("0x");
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
