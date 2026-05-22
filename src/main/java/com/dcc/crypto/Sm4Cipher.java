package com.dcc.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

@Component
public class Sm4Cipher {
    private static final byte[] IV = "1234567890123456".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEX = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);

    static {
        // BouncyCastle 提供 SM4/CBC/PKCS5Padding；静态注册保证测试和应用启动路径都能找到 Provider。
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public Context newContext(String key) {
        try {
            return new Context(key.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid SM4 key", e);
        }
    }

    public static final class Context {
        private final Cipher cipher;
        private final SecretKeySpec keySpec;
        private final IvParameterSpec ivSpec;

        private Context(byte[] key) throws GeneralSecurityException {
            if (key.length != 16) {
                throw new IllegalArgumentException("SM4 key must be 16 bytes");
            }
            // 严格按题目要求：SM4-CBC、固定 IV、PKCS5/PKCS7 padding。
            // Context 按请求创建一次，同一请求内反复复用 Cipher、KeySpec 和 IvSpec，减少对象创建。
            this.cipher = Cipher.getInstance("SM4/CBC/PKCS5Padding", "BC");
            this.keySpec = new SecretKeySpec(key, "SM4");
            this.ivSpec = new IvParameterSpec(IV);
        }

        public int encryptToHex(byte[] source, int offset, int length, byte[] target, int targetOffset) {
            try {
                // 每个单元格都从固定 IV 开始加密，符合“字段独立加密”的 baseline 行为。
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
                int encrypted = cipher.doFinal(source, offset, length, target, targetOffset);
                int hexEnd = targetOffset + encrypted * 2;
                // 直接在目标 byte[] 中原地倒序转大写 HEX，避免生成中间 String 或额外密文字节数组。
                for (int src = targetOffset + encrypted - 1, dst = hexEnd - 2; src >= targetOffset; src--, dst -= 2) {
                    int value = target[src] & 0xFF;
                    target[dst] = HEX[value >>> 4];
                    target[dst + 1] = HEX[value & 0x0F];
                }
                return encrypted * 2;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("SM4 encryption failed", e);
            }
        }
    }
}
