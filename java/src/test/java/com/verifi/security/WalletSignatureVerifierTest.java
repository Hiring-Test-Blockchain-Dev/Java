package com.verifi.security;

import com.verifi.crypto.Hashes;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.math.ec.ECPoint;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletSignatureVerifierTest {
    @Test
    void recoversPersonalSignAddress() throws Exception {
        org.bouncycastle.asn1.x9.X9ECParameters params = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256k1");
        org.bouncycastle.crypto.params.ECDomainParameters domain = new org.bouncycastle.crypto.params.ECDomainParameters(
                params.getCurve(), params.getG(), params.getN(), params.getH());
        ECKeyPairGenerator generator = new ECKeyPairGenerator();
        generator.init(new ECKeyGenerationParameters(domain, new SecureRandom()));
        AsymmetricCipherKeyPair pair = generator.generateKeyPair();
        ECPrivateKeyParameters priv = (ECPrivateKeyParameters) pair.getPrivate();
        ECPublicKeyParameters pub = (ECPublicKeyParameters) pair.getPublic();

        String message = "fp-test-nonce";
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        String prefix = "\u0019Ethereum Signed Message:\n" + messageBytes.length;
        byte[] hash = Hashes.keccak256(concat(prefix.getBytes(StandardCharsets.UTF_8), messageBytes));
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, priv);
        BigInteger[] rs = signer.generateSignature(hash);

        WalletSignatureVerifier verifier = new WalletSignatureVerifier();
        int recId = -1;
        for (int candidate = 0; candidate < 4; candidate++) {
            ECPoint point = WalletSignatureVerifier.recover(candidate, rs[0], rs[1], hash);
            if (point != null && point.normalize().equals(pub.getQ().normalize())) {
                recId = candidate;
                break;
            }
        }
        assertTrue(recId >= 0);
        byte[] sig = new byte[65];
        System.arraycopy(fixed(rs[0]), 0, sig, 0, 32);
        System.arraycopy(fixed(rs[1]), 0, sig, 32, 32);
        sig[64] = (byte) (recId + 27);
        String address = WalletSignatureVerifier.addressOf(pub.getQ());
        assertTrue(verifier.verify(address, message, Hashes.hex(sig)));
        assertFalse(verifier.verify(address, message, "0x" + "11".repeat(65)));
        assertEquals(address, WalletSignatureVerifier.addressOf(pub.getQ()));
    }

    private static byte[] fixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        if (raw.length == 32) return raw;
        if (raw.length > 32) return Arrays.copyOfRange(raw, raw.length - 32, raw.length);
        byte[] padded = new byte[32];
        System.arraycopy(raw, 0, padded, 32 - raw.length, raw.length);
        return padded;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
