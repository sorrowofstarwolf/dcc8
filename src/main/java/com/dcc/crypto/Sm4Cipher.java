package com.dcc.crypto;

import org.bouncycastle.crypto.BufferedBlockCipher;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.SM4Engine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Security;

@Component
public class Sm4Cipher {
    private static final byte[] IV = "1234567890123456".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEX = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public Context newContext(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != 16) {
            throw new IllegalArgumentException("SM4 key must be 16 bytes");
        }
        return new Context(keyBytes);
    }

    public static final class Context {
        private final BufferedBlockCipher cipher;
        private final ParametersWithIV parameters;
        private final byte[] encryptedBuffer;

        private Context(byte[] key) {
            this.cipher = new PaddedBufferedBlockCipher(new CBCBlockCipher(new SM4Engine()));
            this.parameters = new ParametersWithIV(new KeyParameter(key), IV);
            this.encryptedBuffer = new byte[256];
        }

        public int encryptToHex(byte[] source, int offset, int length, byte[] target, int targetOffset) {
            try {
                cipher.init(true, parameters);
                int encrypted = cipher.processBytes(source, offset, length, encryptedBuffer, 0);
                encrypted += cipher.doFinal(encryptedBuffer, encrypted);
                int dst = targetOffset + encrypted * 2 - 2;
                for (int src = encrypted - 1; src >= 0; src--, dst -= 2) {
                    int value = encryptedBuffer[src] & 0xFF;
                    target[dst] = HEX[value >>> 4];
                    target[dst + 1] = HEX[value & 0x0F];
                }
                return encrypted * 2;
            } catch (InvalidCipherTextException e) {
                throw new IllegalStateException("SM4 encryption failed", e);
            }
        }
    }
}
