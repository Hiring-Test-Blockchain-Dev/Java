package com.verifi.security;

import com.verifi.crypto.Hashes;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.x9.X9IntegerConverter;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECPoint;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

@Component
public class WalletSignatureVerifier {
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final ECDomainParameters SECP256K1 = domain();

    public boolean verify(String address, String message, String signature) {
        try {
            if (isEvm(address)) return verifyEvm(address, message, signature);
            return verifySolana(address, message, signature);
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean verifyEvm(String address, String message, String signature) {
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        String prefix = "\u0019Ethereum Signed Message:\n" + messageBytes.length;
        byte[] hash = Hashes.keccak256(concat(prefix.getBytes(StandardCharsets.UTF_8), messageBytes));
        byte[] sig = hexToBytes(signature.startsWith("0x") || signature.startsWith("0X") ? signature.substring(2) : signature);
        if (sig.length != 65) return false;
        int recId = sig[64] & 0xFF;
        if (recId >= 27) recId -= 27;
        if (recId < 0 || recId > 3) return false;
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(sig, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(sig, 32, 64));
        ECPoint point = recover(recId, r, s, hash);
        if (point == null) return false;
        String recovered = addressOf(point);
        String expected = address.toLowerCase(Locale.ROOT);
        if (!expected.startsWith("0x")) expected = "0x" + expected;
        return recovered.equals(expected);
    }

    private static boolean verifySolana(String address, String message, String signature) {
        byte[] publicKey = base58(address);
        byte[] sig = Base64.getDecoder().decode(signature);
        if (publicKey.length != 32 || sig.length != 64) return false;
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
        byte[] msg = message.getBytes(StandardCharsets.UTF_8);
        verifier.update(msg, 0, msg.length);
        return verifier.verifySignature(sig);
    }

    static String addressOf(ECPoint point) {
        byte[] encoded = point.normalize().getEncoded(false);
        byte[] hash = Hashes.keccak256(Arrays.copyOfRange(encoded, 1, encoded.length));
        return Hashes.hex(Arrays.copyOfRange(hash, 12, 32));
    }

    static ECPoint recover(int recId, BigInteger r, BigInteger s, byte[] hash) {
        BigInteger n = SECP256K1.getN();
        BigInteger i = BigInteger.valueOf((long) recId / 2);
        BigInteger x = r.add(i.multiply(n));
        if (x.compareTo(SECP256K1.getCurve().getField().getCharacteristic()) >= 0) return null;
        ECPoint R = decompress(x, (recId & 1) == 1);
        if (R == null || !R.multiply(n).isInfinity()) return null;
        BigInteger e = new BigInteger(1, hash);
        BigInteger rInv = r.modInverse(n);
        BigInteger eInv = BigInteger.ZERO.subtract(e).mod(n);
        return ECAlgorithms.sumOfTwoMultiplies(SECP256K1.getG(), rInv.multiply(eInv).mod(n), R, rInv.multiply(s).mod(n));
    }

    private static ECPoint decompress(BigInteger x, boolean yBit) {
        X9IntegerConverter converter = new X9IntegerConverter();
        byte[] encoded = converter.integerToBytes(x, 1 + converter.getByteLength(SECP256K1.getCurve()));
        encoded[0] = (byte) (yBit ? 0x03 : 0x02);
        try {
            return SECP256K1.getCurve().decodePoint(encoded);
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean isEvm(String address) {
        return address.matches("(?i)0x[a-f0-9]{40}") || address.matches("(?i)[a-f0-9]{40}");
    }

    private static byte[] hexToBytes(String hex) {
        if ((hex.length() & 1) == 1) throw new IllegalArgumentException("odd hex");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static byte[] base58(String input) {
        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < input.length(); i++) {
            int digit = ALPHABET.indexOf(input.charAt(i));
            if (digit < 0) throw new IllegalArgumentException("Invalid base58");
            value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
        }
        byte[] bytes = value.equals(BigInteger.ZERO) ? new byte[0] : value.toByteArray();
        int start = bytes.length > 0 && bytes[0] == 0 ? 1 : 0;
        int leading = 0;
        for (int i = 0; i < input.length() && input.charAt(i) == '1'; i++) leading++;
        byte[] result = new byte[leading + (bytes.length - start)];
        System.arraycopy(bytes, start, result, leading, bytes.length - start);
        return result;
    }

    private static ECDomainParameters domain() {
        X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
        return new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
    }
}
