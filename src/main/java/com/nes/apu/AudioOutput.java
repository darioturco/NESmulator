package com.nes.apu;

import javax.sound.sampled.*;

/**
 * Wraps a Java {@link SourceDataLine} to accept normalized float samples
 * and stream them to the audio hardware as 16-bit PCM mono.
 */
public class AudioOutput {

    private static final int WRITE_BUFFER_SAMPLES = 512;

    private final int sampleRate;
    private SourceDataLine line;

    private final byte[] writeBuffer;
    private int writePos = 0;

    public AudioOutput(int sampleRate) {
        this.sampleRate  = sampleRate;
        this.writeBuffer = new byte[WRITE_BUFFER_SAMPLES * 2]; // 16-bit samples
    }

    /** Open the audio line. Safe to call multiple times (idempotent). */
    public void start() {
        if (line != null && line.isOpen()) return;
        try {
            AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
            DataLine.Info info  = new DataLine.Info(SourceDataLine.class, format);
            line = (SourceDataLine) AudioSystem.getLine(info);
            // Large internal buffer to absorb timing jitter without blocking
            line.open(format, WRITE_BUFFER_SAMPLES * 4 * 2);
            line.start();
        } catch (LineUnavailableException e) {
            System.err.println("[APU] Audio output unavailable: " + e.getMessage());
            line = null;
        }
    }

    /**
     * Queue one normalized float sample in [-1, 1].
     * Flushes to the hardware line when the write buffer is full.
     */
    public void write(float sample) {
        if (line == null) return;
        sample = Math.max(-1.0f, Math.min(1.0f, sample));
        int pcm = (int) (sample * 32767);
        writeBuffer[writePos++] = (byte)  (pcm & 0xFF);
        writeBuffer[writePos++] = (byte) ((pcm >> 8) & 0xFF);
        if (writePos >= writeBuffer.length) {
            line.write(writeBuffer, 0, writeBuffer.length);
            writePos = 0;
        }
    }

    public void stop() {
        if (line != null) {
            line.drain();
            line.stop();
            line.close();
            line = null;
        }
    }
}
