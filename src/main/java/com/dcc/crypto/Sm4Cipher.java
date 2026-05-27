package com.dcc.crypto;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class Sm4Cipher {
    private static final byte[] IV = "1234567890123456".getBytes(StandardCharsets.US_ASCII);
    private static final int IV0 = 0x31323334;
    private static final int IV1 = 0x35363738;
    private static final int IV2 = 0x39303132;
    private static final int IV3 = 0x33343536;
    private static final byte[] HEX = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);
    private static final int[] FK = {
            0xa3b1bac6, 0x56aa3350, 0x677d9197, 0xb27022dc
    };
    private static final int[] CK = {
            0x00070e15, 0x1c232a31, 0x383f464d, 0x545b6269,
            0x70777e85, 0x8c939aa1, 0xa8afb6bd, 0xc4cbd2d9,
            0xe0e7eef5, 0xfc030a11, 0x181f262d, 0x343b4249,
            0x50575e65, 0x6c737a81, 0x888f969d, 0xa4abb2b9,
            0xc0c7ced5, 0xdce3eaf1, 0xf8ff060d, 0x141b2229,
            0x30373e45, 0x4c535a61, 0x686f767d, 0x848b9299,
            0xa0a7aeb5, 0xbcc3cad1, 0xd8dfe6ed, 0xf4fb0209,
            0x10171e25, 0x2c333a41, 0x484f565d, 0x646b7279
    };
    private static final byte[] SBOX = {
            (byte) 0xd6, (byte) 0x90, (byte) 0xe9, (byte) 0xfe, (byte) 0xcc, (byte) 0xe1, 0x3d, (byte) 0xb7,
            0x16, (byte) 0xb6, 0x14, (byte) 0xc2, 0x28, (byte) 0xfb, 0x2c, 0x05,
            0x2b, 0x67, (byte) 0x9a, 0x76, 0x2a, (byte) 0xbe, 0x04, (byte) 0xc3,
            (byte) 0xaa, 0x44, 0x13, 0x26, 0x49, (byte) 0x86, 0x06, (byte) 0x99,
            (byte) 0x9c, 0x42, 0x50, (byte) 0xf4, (byte) 0x91, (byte) 0xef, (byte) 0x98, 0x7a,
            0x33, 0x54, 0x0b, 0x43, (byte) 0xed, (byte) 0xcf, (byte) 0xac, 0x62,
            (byte) 0xe4, (byte) 0xb3, 0x1c, (byte) 0xa9, (byte) 0xc9, 0x08, (byte) 0xe8, (byte) 0x95,
            (byte) 0x80, (byte) 0xdf, (byte) 0x94, (byte) 0xfa, 0x75, (byte) 0x8f, 0x3f, (byte) 0xa6,
            0x47, 0x07, (byte) 0xa7, (byte) 0xfc, (byte) 0xf3, 0x73, 0x17, (byte) 0xba,
            (byte) 0x83, 0x59, 0x3c, 0x19, (byte) 0xe6, (byte) 0x85, 0x4f, (byte) 0xa8,
            0x68, 0x6b, (byte) 0x81, (byte) 0xb2, 0x71, 0x64, (byte) 0xda, (byte) 0x8b,
            (byte) 0xf8, (byte) 0xeb, 0x0f, 0x4b, 0x70, 0x56, (byte) 0x9d, 0x35,
            0x1e, 0x24, 0x0e, 0x5e, 0x63, 0x58, (byte) 0xd1, (byte) 0xa2,
            0x25, 0x22, 0x7c, 0x3b, 0x01, 0x21, 0x78, (byte) 0x87,
            (byte) 0xd4, 0x00, 0x46, 0x57, (byte) 0x9f, (byte) 0xd3, 0x27, 0x52,
            0x4c, 0x36, 0x02, (byte) 0xe7, (byte) 0xa0, (byte) 0xc4, (byte) 0xc8, (byte) 0x9e,
            (byte) 0xea, (byte) 0xbf, (byte) 0x8a, (byte) 0xd2, 0x40, (byte) 0xc7, 0x38, (byte) 0xb5,
            (byte) 0xa3, (byte) 0xf7, (byte) 0xf2, (byte) 0xce, (byte) 0xf9, 0x61, 0x15, (byte) 0xa1,
            (byte) 0xe0, (byte) 0xae, 0x5d, (byte) 0xa4, (byte) 0x9b, 0x34, 0x1a, 0x55,
            (byte) 0xad, (byte) 0x93, 0x32, 0x30, (byte) 0xf5, (byte) 0x8c, (byte) 0xb1, (byte) 0xe3,
            0x1d, (byte) 0xf6, (byte) 0xe2, 0x2e, (byte) 0x82, 0x66, (byte) 0xca, 0x60,
            (byte) 0xc0, 0x29, 0x23, (byte) 0xab, 0x0d, 0x53, 0x4e, 0x6f,
            (byte) 0xd5, (byte) 0xdb, 0x37, 0x45, (byte) 0xde, (byte) 0xfd, (byte) 0x8e, 0x2f,
            0x03, (byte) 0xff, 0x6a, 0x72, 0x6d, 0x6c, 0x5b, 0x51,
            (byte) 0x8d, 0x1b, (byte) 0xaf, (byte) 0x92, (byte) 0xbb, (byte) 0xdd, (byte) 0xbc, 0x7f,
            0x11, (byte) 0xd9, 0x5c, 0x41, 0x1f, 0x10, 0x5a, (byte) 0xd8,
            0x0a, (byte) 0xc1, 0x31, (byte) 0x88, (byte) 0xa5, (byte) 0xcd, 0x7b, (byte) 0xbd,
            0x2d, 0x74, (byte) 0xd0, 0x12, (byte) 0xb8, (byte) 0xe5, (byte) 0xb4, (byte) 0xb0,
            (byte) 0x89, 0x69, (byte) 0x97, 0x4a, 0x0c, (byte) 0x96, 0x77, 0x7e,
            0x65, (byte) 0xb9, (byte) 0xf1, 0x09, (byte) 0xc5, 0x6e, (byte) 0xc6, (byte) 0x84,
            0x18, (byte) 0xf0, 0x7d, (byte) 0xec, 0x3a, (byte) 0xdc, 0x4d, 0x20,
            0x79, (byte) 0xee, 0x5f, 0x3e, (byte) 0xd7, (byte) 0xcb, 0x39, 0x48
    };
    private static final int[] T0 = new int[256];
    private static final int[] T1 = new int[256];
    private static final int[] T2 = new int[256];
    private static final int[] T3 = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            T0[i] = linear((SBOX[i] & 0xFF) << 24);
            T1[i] = linear((SBOX[i] & 0xFF) << 16);
            T2[i] = linear((SBOX[i] & 0xFF) << 8);
            T3[i] = linear(SBOX[i] & 0xFF);
        }
    }

    private static int linear(int value) {
        return value ^ Integer.rotateLeft(value, 2) ^ Integer.rotateLeft(value, 10)
                ^ Integer.rotateLeft(value, 18) ^ Integer.rotateLeft(value, 24);
    }

    public Context newContext(String key) {
        return new Context().reset(key);
    }

    public static final class Context {
        private final int[] roundKeys = new int[32];

        private Context() {
        }

        public Context reset(String key) {
            reset(key.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        private void reset(byte[] key) {
            if (key.length != 16) {
                throw new IllegalArgumentException("SM4 key must be 16 bytes");
            }
            expandKey(key, roundKeys);
        }

        public int encryptToHex(byte[] source, int offset, int length, byte[] target, int targetOffset) {
            if (length == 4) {
                return encryptLength4(source, offset, target, targetOffset);
            }
            if (length == 5) {
                return encryptLength5(source, offset, target, targetOffset);
            }
            if (length == 6) {
                return encryptLength6(source, offset, target, targetOffset);
            }
            if (length < 16) {
                return encryptOneBlock(source, offset, length, target, targetOffset);
            }
            if (length < 32) {
                return encryptTwoBlocks(source, offset, length, target, targetOffset);
            }
            return encryptGeneric(source, offset, length, target, targetOffset);
        }

        private int encryptLength4(byte[] source, int offset, byte[] target, int targetOffset) {
            encryptBlockToHex(
                    word(source, offset) ^ IV0,
                    0x393A3B34,
                    0x353C3D3E,
                    0x3F38393A,
                    target, targetOffset);
            return 32;
        }

        private int encryptLength5(byte[] source, int offset, byte[] target, int targetOffset) {
            int x1 = (((source[offset + 4] & 0xFF) ^ (IV[4] & 0xFF)) << 24)
                    | 0x3D3C33;
            encryptBlockToHex(
                    word(source, offset) ^ IV0,
                    x1,
                    0x323B3A39,
                    0x383F3E3D,
                    target, targetOffset);
            return 32;
        }

        private int encryptLength6(byte[] source, int offset, byte[] target, int targetOffset) {
            int x1 = (((source[offset + 4] & 0xFF) ^ (IV[4] & 0xFF)) << 24)
                    | (((source[offset + 5] & 0xFF) ^ (IV[5] & 0xFF)) << 16)
                    | 0x3D32;
            encryptBlockToHex(
                    word(source, offset) ^ IV0,
                    x1,
                    0x333A3B38,
                    0x393E3F3C,
                    target, targetOffset);
            return 32;
        }

        private int encryptOneBlock(byte[] source, int offset, int length, byte[] target, int targetOffset) {
            int padding = 16 - length;
            encryptBlockToHex(
                    wordWithIv(source, offset, length, padding, 0),
                    wordWithIv(source, offset, length, padding, 4),
                    wordWithIv(source, offset, length, padding, 8),
                    wordWithIv(source, offset, length, padding, 12),
                    target, targetOffset);
            return 32;
        }

        private int encryptTwoBlocks(byte[] source, int offset, int length, byte[] target, int targetOffset) {
            encryptBlock(
                    word(source, offset) ^ IV0,
                    word(source, offset + 4) ^ IV1,
                    word(source, offset + 8) ^ IV2,
                    word(source, offset + 12) ^ IV3,
                    target, targetOffset);

            int remaining = length - 16;
            int padding = 16 - remaining;
            int secondOffset = targetOffset + 16;
            encryptBlock(
                    wordWithPrevious(source, offset + 16, remaining, padding, target, targetOffset, 0),
                    wordWithPrevious(source, offset + 16, remaining, padding, target, targetOffset, 4),
                    wordWithPrevious(source, offset + 16, remaining, padding, target, targetOffset, 8),
                    wordWithPrevious(source, offset + 16, remaining, padding, target, targetOffset, 12),
                    target, secondOffset);
            toHexInPlace(target, targetOffset, 32);
            return 64;
        }

        private int encryptGeneric(byte[] source, int offset, int length, byte[] target, int targetOffset) {
            int encrypted = encryptedLength(length);
            int output = targetOffset;
            int consumed = 0;
            while (consumed < encrypted) {
                int remaining = length - consumed;
                int padding = remaining >= 16 ? 0 : 16 - Math.max(remaining, 0);
                if (consumed == 0) {
                    encryptBlock(
                            wordWithIv(source, offset, remaining, padding, 0),
                            wordWithIv(source, offset, remaining, padding, 4),
                            wordWithIv(source, offset, remaining, padding, 8),
                            wordWithIv(source, offset, remaining, padding, 12),
                            target, output);
                } else {
                    int blockOffset = offset + consumed;
                    int previousOffset = output - 16;
                    encryptBlock(
                            wordWithPrevious(source, blockOffset, remaining, padding, target, previousOffset, 0),
                            wordWithPrevious(source, blockOffset, remaining, padding, target, previousOffset, 4),
                            wordWithPrevious(source, blockOffset, remaining, padding, target, previousOffset, 8),
                            wordWithPrevious(source, blockOffset, remaining, padding, target, previousOffset, 12),
                            target, output);
                }
                output += 16;
                consumed += 16;
            }
            toHexInPlace(target, targetOffset, encrypted);
            return encrypted * 2;
        }

        private void toHexInPlace(byte[] target, int targetOffset, int encrypted) {
            int hexEnd = targetOffset + encrypted * 2;
            for (int src = targetOffset + encrypted - 1, dst = hexEnd - 2; src >= targetOffset; src--, dst -= 2) {
                int value = target[src] & 0xFF;
                target[dst] = HEX[value >>> 4];
                target[dst + 1] = HEX[value & 0x0F];
            }
        }

        private int encryptedLength(int plainLength) {
            return ((plainLength / 16) + 1) * 16;
        }

        private void encryptBlock(int x0, int x1, int x2, int x3, byte[] output, int offset) {
            encryptBlock0(x0, x1, x2, x3, output, offset, false);
        }

        private void encryptBlockToHex(int x0, int x1, int x2, int x3, byte[] output, int offset) {
            encryptBlock0(x0, x1, x2, x3, output, offset, true);
        }

        private void encryptBlock0(int x0, int x1, int x2, int x3, byte[] output, int offset, boolean hexOutput) {
            int[] rk = roundKeys;
            int rk0 = rk[0], rk1 = rk[1], rk2 = rk[2], rk3 = rk[3];
            int rk4 = rk[4], rk5 = rk[5], rk6 = rk[6], rk7 = rk[7];
            int rk8 = rk[8], rk9 = rk[9], rk10 = rk[10], rk11 = rk[11];
            int rk12 = rk[12], rk13 = rk[13], rk14 = rk[14], rk15 = rk[15];
            int rk16 = rk[16], rk17 = rk[17], rk18 = rk[18], rk19 = rk[19];
            int rk20 = rk[20], rk21 = rk[21], rk22 = rk[22], rk23 = rk[23];
            int rk24 = rk[24], rk25 = rk[25], rk26 = rk[26], rk27 = rk[27];
            int rk28 = rk[28], rk29 = rk[29], rk30 = rk[30], rk31 = rk[31];

            x0 ^= transform(x1 ^ x2 ^ x3 ^ rk0);
            x1 ^= transform(x2 ^ x3 ^ x0 ^ rk1);
            x2 ^= transform(x3 ^ x0 ^ x1 ^ rk2);
            x3 ^= transform(x0 ^ x1 ^ x2 ^ rk3);
            x0 ^= transform(x1 ^ x2 ^ x3 ^ rk4);
            x1 ^= transform(x2 ^ x3 ^ x0 ^ rk5);
            x2 ^= transform(x3 ^ x0 ^ x1 ^ rk6);
            x3 ^= transform(x0 ^ x1 ^ x2 ^ rk7);
            x0 ^= transform(x1 ^ x2 ^ x3 ^ rk8);
            x1 ^= transform(x2 ^ x3 ^ x0 ^ rk9);
            x2 ^= transform(x3 ^ x0 ^ x1 ^ rk10);
            x3 ^= transform(x0 ^ x1 ^ x2 ^ rk11);
            x0 ^= transform(x1 ^ x2 ^ x3 ^ rk12);
            x1 ^= transform(x2 ^ x3 ^ x0 ^ rk13);
            x2 ^= transform(x3 ^ x0 ^ x1 ^ rk14);
            x3 ^= transform(x0 ^ x1 ^ x2 ^ rk15);
            x0 ^= transform(x1 ^ x2 ^ x3 ^ rk16);
            x1 ^= transform(x2 ^ x3 ^ x0 ^ rk17);
            x2 ^= transform(x3 ^ x0 ^ x1 ^ rk18);
            x3 ^= transform(x0 ^ x1 ^ x2 ^ rk19);
            x0 ^= transform(x1 ^ x2 ^ x3 ^ rk20);
            x1 ^= transform(x2 ^ x3 ^ x0 ^ rk21);
            x2 ^= transform(x3 ^ x0 ^ x1 ^ rk22);
            x3 ^= transform(x0 ^ x1 ^ x2 ^ rk23);
            x0 ^= transform(x1 ^ x2 ^ x3 ^ rk24);
            x1 ^= transform(x2 ^ x3 ^ x0 ^ rk25);
            x2 ^= transform(x3 ^ x0 ^ x1 ^ rk26);
            x3 ^= transform(x0 ^ x1 ^ x2 ^ rk27);
            x0 ^= transform(x1 ^ x2 ^ x3 ^ rk28);
            x1 ^= transform(x2 ^ x3 ^ x0 ^ rk29);
            x2 ^= transform(x3 ^ x0 ^ x1 ^ rk30);
            x3 ^= transform(x0 ^ x1 ^ x2 ^ rk31);
            if (hexOutput) {
                writeHexInt(x3, output, offset);
                writeHexInt(x2, output, offset + 8);
                writeHexInt(x1, output, offset + 16);
                writeHexInt(x0, output, offset + 24);
            } else {
                writeInt(x3, output, offset);
                writeInt(x2, output, offset + 4);
                writeInt(x1, output, offset + 8);
                writeInt(x0, output, offset + 12);
            }
        }

        private static void expandKey(byte[] key, int[] roundKeys) {
            int k0 = word(key, 0) ^ FK[0];
            int k1 = word(key, 4) ^ FK[1];
            int k2 = word(key, 8) ^ FK[2];
            int k3 = word(key, 12) ^ FK[3];
            for (int i = 0; i < 32; i += 4) {
                k0 ^= keyTransform(k1 ^ k2 ^ k3 ^ CK[i]);
                roundKeys[i] = k0;
                k1 ^= keyTransform(k2 ^ k3 ^ k0 ^ CK[i + 1]);
                roundKeys[i + 1] = k1;
                k2 ^= keyTransform(k3 ^ k0 ^ k1 ^ CK[i + 2]);
                roundKeys[i + 2] = k2;
                k3 ^= keyTransform(k0 ^ k1 ^ k2 ^ CK[i + 3]);
                roundKeys[i + 3] = k3;
            }
        }

        private static int wordWithIv(byte[] source, int offset, int remaining, int padding, int blockOffset) {
            return ((value(source, offset, remaining, padding, blockOffset) ^ (IV[blockOffset] & 0xFF)) << 24)
                    | ((value(source, offset, remaining, padding, blockOffset + 1) ^ (IV[blockOffset + 1] & 0xFF)) << 16)
                    | ((value(source, offset, remaining, padding, blockOffset + 2) ^ (IV[blockOffset + 2] & 0xFF)) << 8)
                    | (value(source, offset, remaining, padding, blockOffset + 3) ^ (IV[blockOffset + 3] & 0xFF));
        }

        private static int wordWithPrevious(byte[] source, int offset, int remaining, int padding,
                                            byte[] previous, int previousOffset, int blockOffset) {
            return ((value(source, offset, remaining, padding, blockOffset) ^ (previous[previousOffset + blockOffset] & 0xFF)) << 24)
                    | ((value(source, offset, remaining, padding, blockOffset + 1) ^ (previous[previousOffset + blockOffset + 1] & 0xFF)) << 16)
                    | ((value(source, offset, remaining, padding, blockOffset + 2) ^ (previous[previousOffset + blockOffset + 2] & 0xFF)) << 8)
                    | (value(source, offset, remaining, padding, blockOffset + 3) ^ (previous[previousOffset + blockOffset + 3] & 0xFF));
        }

        private static int value(byte[] source, int offset, int remaining, int padding, int index) {
            return index < remaining ? source[offset + index] & 0xFF : padding;
        }

        private static int word(byte[] input, int offset) {
            return ((input[offset] & 0xFF) << 24)
                    | ((input[offset + 1] & 0xFF) << 16)
                    | ((input[offset + 2] & 0xFF) << 8)
                    | (input[offset + 3] & 0xFF);
        }

        private static void writeInt(int value, byte[] output, int offset) {
            output[offset] = (byte) (value >>> 24);
            output[offset + 1] = (byte) (value >>> 16);
            output[offset + 2] = (byte) (value >>> 8);
            output[offset + 3] = (byte) value;
        }

        private static void writeHexInt(int value, byte[] output, int offset) {
            writeHexByte(value >>> 24, output, offset);
            writeHexByte(value >>> 16, output, offset + 2);
            writeHexByte(value >>> 8, output, offset + 4);
            writeHexByte(value, output, offset + 6);
        }

        private static void writeHexByte(int value, byte[] output, int offset) {
            int b = value & 0xFF;
            output[offset] = HEX[b >>> 4];
            output[offset + 1] = HEX[b & 0x0F];
        }

        private static int transform(int value) {
            return T0[value >>> 24]
                    ^ T1[(value >>> 16) & 0xFF]
                    ^ T2[(value >>> 8) & 0xFF]
                    ^ T3[value & 0xFF];
        }

        private static int keyTransform(int value) {
            int b = substitute(value);
            return b ^ Integer.rotateLeft(b, 13) ^ Integer.rotateLeft(b, 23);
        }

        private static int substitute(int value) {
            return ((SBOX[value >>> 24] & 0xFF) << 24)
                    | ((SBOX[(value >>> 16) & 0xFF] & 0xFF) << 16)
                    | ((SBOX[(value >>> 8) & 0xFF] & 0xFF) << 8)
                    | (SBOX[value & 0xFF] & 0xFF);
        }

    }
}
