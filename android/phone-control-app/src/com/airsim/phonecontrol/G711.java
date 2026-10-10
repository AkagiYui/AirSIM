package com.airsim.phonecontrol;

/** ITU-T G.711 μ-law (PCMU, payload 0) and A-law (PCMA, payload 8) for 16-bit little-endian PCM. */
final class G711 {
    static final int PCMU = 0;
    static final int PCMA = 8;

    private static final short[] ULAW_DECODE = new short[256];
    private static final short[] ALAW_DECODE = new short[256];
    private static final int[] ALAW_SEGMENT_END = {0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF};

    static {
        for (int i = 0; i < 256; i++) {
            ULAW_DECODE[i] = ulawToLinear(i);
            ALAW_DECODE[i] = alawToLinear(i);
        }
    }

    private G711() {}

    /** Encodes {@code samples} little-endian PCM samples from {@code pcm} into {@code out}. */
    static void encode(int payloadType, byte[] pcm, int samples, byte[] out) {
        for (int i = 0; i < samples; i++) {
            int sample = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
            out[i] = payloadType == PCMA ? linearToAlaw(sample) : linearToUlaw(sample);
        }
    }

    /** Decodes {@code count} G.711 bytes into little-endian PCM in {@code out} (2 bytes per sample). */
    static void decode(int payloadType, byte[] data, int offset, int count, byte[] out) {
        short[] table = payloadType == PCMA ? ALAW_DECODE : ULAW_DECODE;
        for (int i = 0; i < count; i++) {
            short sample = table[data[offset + i] & 0xff];
            out[2 * i] = (byte) sample;
            out[2 * i + 1] = (byte) (sample >> 8);
        }
    }

    static byte linearToUlaw(int sample) {
        final int bias = 0x84;
        final int clip = 32635;
        int sign = (sample >> 8) & 0x80;
        if (sign != 0) sample = -sample;
        if (sample > clip) sample = clip;
        sample += bias;
        int exponent = 7;
        for (int mask = 0x4000; (sample & mask) == 0 && exponent > 0; exponent--, mask >>= 1) { }
        int mantissa = (sample >> (exponent + 3)) & 0x0F;
        return (byte) ~(sign | (exponent << 4) | mantissa);
    }

    private static short ulawToLinear(int value) {
        value = ~value & 0xff;
        int sign = value & 0x80;
        int exponent = (value >> 4) & 0x07;
        int mantissa = value & 0x0F;
        int sample = (((mantissa << 3) + 0x84) << exponent) - 0x84;
        return (short) (sign != 0 ? -sample : sample);
    }

    static byte linearToAlaw(int sample) {
        int value = sample >> 3;
        int mask;
        if (value >= 0) {
            mask = 0xD5;
        } else {
            mask = 0x55;
            value = -value - 1;
        }
        int segment = 0;
        while (segment < 8 && value > ALAW_SEGMENT_END[segment]) segment++;
        if (segment >= 8) return (byte) (0x7F ^ mask);
        int encoded = segment << 4;
        encoded |= segment < 2 ? (value >> 1) & 0x0F : (value >> segment) & 0x0F;
        return (byte) (encoded ^ mask);
    }

    private static short alawToLinear(int value) {
        value ^= 0x55;
        int sample = (value & 0x0F) << 4;
        int segment = (value & 0x70) >> 4;
        switch (segment) {
            case 0 -> sample += 8;
            case 1 -> sample += 0x108;
            default -> {
                sample += 0x108;
                sample <<= segment - 1;
            }
        }
        return (short) ((value & 0x80) != 0 ? sample : -sample);
    }
}
