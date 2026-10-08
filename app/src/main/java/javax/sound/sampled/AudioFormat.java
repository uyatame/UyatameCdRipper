/*
 * javax.sound.sampled.AudioFormat 互換クラス(本アプリ独自実装)。
 * Androidには javax.sound が無いため、MP3エンコーダ(jump3r)が必要とする
 * 公開APIと同じ形のクラスをアプリ側で用意している。
 */
package javax.sound.sampled;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class AudioFormat {
    protected Encoding encoding;
    protected float sampleRate;
    protected int sampleSizeInBits;
    protected int channels;
    protected int frameSize;
    protected float frameRate;
    protected boolean bigEndian;
    private final HashMap<String, Object> props = new HashMap<>();

    public AudioFormat(Encoding encoding, float sampleRate, int sampleSizeInBits, int channels,
                       int frameSize, float frameRate, boolean bigEndian) {
        this.encoding = encoding;
        this.sampleRate = sampleRate;
        this.sampleSizeInBits = sampleSizeInBits;
        this.channels = channels;
        this.frameSize = frameSize;
        this.frameRate = frameRate;
        this.bigEndian = bigEndian;
    }

    public AudioFormat(Encoding encoding, float sampleRate, int sampleSizeInBits, int channels,
                       int frameSize, float frameRate, boolean bigEndian, Map<String, Object> properties) {
        this(encoding, sampleRate, sampleSizeInBits, channels, frameSize, frameRate, bigEndian);
        if (properties != null) props.putAll(properties);
    }

    public AudioFormat(float sampleRate, int sampleSizeInBits, int channels, boolean signed, boolean bigEndian) {
        this(signed ? Encoding.PCM_SIGNED : Encoding.PCM_UNSIGNED, sampleRate, sampleSizeInBits, channels,
                (channels == AudioSystem.NOT_SPECIFIED || sampleSizeInBits == AudioSystem.NOT_SPECIFIED)
                        ? AudioSystem.NOT_SPECIFIED : ((sampleSizeInBits + 7) / 8) * channels,
                sampleRate, bigEndian);
    }

    public Encoding getEncoding() { return encoding; }
    public float getSampleRate() { return sampleRate; }
    public int getSampleSizeInBits() { return sampleSizeInBits; }
    public int getChannels() { return channels; }
    public int getFrameSize() { return frameSize; }
    public float getFrameRate() { return frameRate; }
    public boolean isBigEndian() { return bigEndian; }
    public Map<String, Object> properties() { return Collections.unmodifiableMap(props); }
    public Object getProperty(String key) { return props.get(key); }

    public boolean matches(AudioFormat f) {
        return f.getEncoding().equals(encoding)
                && (f.getChannels() == AudioSystem.NOT_SPECIFIED || f.getChannels() == channels)
                && (f.getSampleRate() == AudioSystem.NOT_SPECIFIED || f.getSampleRate() == sampleRate)
                && (f.getSampleSizeInBits() == AudioSystem.NOT_SPECIFIED || f.getSampleSizeInBits() == sampleSizeInBits)
                && (f.getFrameSize() == AudioSystem.NOT_SPECIFIED || f.getFrameSize() == frameSize)
                && (f.getFrameRate() == AudioSystem.NOT_SPECIFIED || f.getFrameRate() == frameRate)
                && (sampleSizeInBits <= 8 || f.isBigEndian() == bigEndian);
    }

    @Override
    public String toString() {
        return encoding + " " + sampleRate + " Hz, " + sampleSizeInBits + " bit, " + channels + " ch";
    }

    public static class Encoding {
        public static final Encoding PCM_SIGNED = new Encoding("PCM_SIGNED");
        public static final Encoding PCM_UNSIGNED = new Encoding("PCM_UNSIGNED");
        public static final Encoding PCM_FLOAT = new Encoding("PCM_FLOAT");
        public static final Encoding ULAW = new Encoding("ULAW");
        public static final Encoding ALAW = new Encoding("ALAW");

        private final String name;

        public Encoding(String name) { this.name = name; }

        @Override
        public final boolean equals(Object o) {
            return o instanceof Encoding && name != null && name.equals(((Encoding) o).name);
        }

        @Override
        public final int hashCode() { return name == null ? 0 : name.hashCode(); }

        @Override
        public final String toString() { return name; }
    }
}
