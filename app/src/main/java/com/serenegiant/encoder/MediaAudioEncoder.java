package com.serenegiant.encoder;

import java.io.IOException;
import java.nio.ByteBuffer;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;

import com.serenegiant.audiovideosample.BuildConfig;

public class MediaAudioEncoder extends MediaEncoder {

    private static final boolean DEBUG = BuildConfig.DEBUG;
    private static final String TAG = MediaAudioEncoder.class.getSimpleName();

    // audio type
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    // 音频采样率，单位Hz，44100Hz是当前唯一能保证在所有设备上工作的采样率；
    // 44.1[KHz] is only setting guaranteed to be available on all devices.
    private static final int SAMPLE_RATE = 44100;
    // 单声道=1 , 双声道=2
    private static final int CHANNEL_COUNT = 1;
    // 比特率
    private static final int BIT_RATE = 64000;
    // 每帧采样数
    public static final int SAMPLES_PER_FRAME = 1024;    // AAC, bytes/frame/channel
    // 每个缓冲区帧数
    public static final int FRAMES_PER_BUFFER = 25;    // AAC, frame/buffer/sec

    private AudioThread mAudioThread = null;

    public MediaAudioEncoder(final MediaMuxerWrapper muxer, final MediaEncoderListener listener) {
        super(muxer, listener);
    }

    @Override
    protected void prepare() throws IOException {
        if (DEBUG) Log.v(TAG, "prepare:");
        mTrackIndex = -1;
        mMuxerStarted = mIsEOS = false;
        // prepare MediaCodec for AAC encoding of audio data from inernal mic.
        final MediaCodecInfo audioCodecInfo = selectAudioCodec();
        if (audioCodecInfo == null) {
            Log.e(TAG, "Unable to find an appropriate codec for " + MIME_TYPE);
            return;
        }
        if (DEBUG) Log.i(TAG, "selected codec: " + audioCodecInfo.getName());

        final MediaFormat audioFormat = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT);
        // 要使用的 AAC 配置文件的键（仅 AAC 音频格式时使用）
        // 常量在 android.media.MediaCodecInfo.CodecProfileLevel 中声明
        // 音频编码中最常用的变量是 MediaCodecInfo.CodecProfileLevel.AACObjectLC
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        // 音频内容的通道组成的键，在音频编码中需要根据硬件支持去有选择性的选择支持范围内的通道号
        // AudioFormat.CHANNEL_IN_MONO 单声道，一个声道进行采样
        // AudioFormat.CHANNEL_IN_STEREO 双声道，两个声道进行采样
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_MONO);
        // 音视频平均比特率，以位/秒为单位(bit/s)的键
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        // 声道数
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, CHANNEL_COUNT);
        // 编解码器中数据缓冲区最大大小的键，以字节(byte)为单位
        // audioFormat.setLong(MediaFormat.KEY_MAX_INPUT_SIZE, inputFile.length());
        // audioFormat.setLong(MediaFormat.KEY_DURATION, (long)durationInMs );
        if (DEBUG) Log.i(TAG, "format: " + audioFormat);

        mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
        // MediaCodec.CONFIGURE_FLAG_ENCODE 表示编码
        mMediaCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mMediaCodec.start();
        if (DEBUG) Log.i(TAG, "prepare finishing");
        if (mListener != null) {
            try {
                mListener.onPrepared(this);
            } catch (final Exception e) {
                Log.e(TAG, "prepare:", e);
            }
        }
    }

    @Override
    protected void startRecording() {
        super.startRecording();
        // create and execute audio capturing thread using internal mic
        if (mAudioThread == null) {
            mAudioThread = new AudioThread();
            mAudioThread.start();
        }
    }

    @Override
    protected void release() {
        mAudioThread = null;
        super.release();
    }

    private static final int[] AUDIO_SOURCES = new int[]{
            MediaRecorder.AudioSource.MIC,                  // 麦克风
            MediaRecorder.AudioSource.DEFAULT,              // 默认音频源
            MediaRecorder.AudioSource.CAMCORDER,            // 摄像机
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // 语音交流
            MediaRecorder.AudioSource.VOICE_RECOGNITION,    // 语音识别
    };

    /**
     * Thread to capture audio data from internal mic as uncompressed 16bit PCM data
     * and write them to the MediaCodec encoder
     */
    private class AudioThread extends Thread {
        @Override
        public void run() {
            // 声音线程的最高级别，优先程度较THREAD_PRIORITY_AUDIO要高。代码中无法设置为该优先级。值为-19。
            // priority 优先级：-20  ------>  +19 ，对应最高优先级------> 最低优先级。
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            int cnt = 0;
            try {
                // 返回成功创建AudioRecord对象所需要的最小缓冲区大小
                final int min_buffer_size = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, // 声道：单声道、双声道等
                        AudioFormat.ENCODING_PCM_16BIT // 音频采样精度，指定采样的数据的格式和每次采样的大小，只支持8位和16位。
                );
                int buffer_size = SAMPLES_PER_FRAME * FRAMES_PER_BUFFER;
                if (buffer_size < min_buffer_size)
                    buffer_size = ((min_buffer_size / SAMPLES_PER_FRAME) + 1) * SAMPLES_PER_FRAME * 2;

                AudioRecord audioRecord = null;
                for (final int source : AUDIO_SOURCES) {
                    try {
                        audioRecord = new AudioRecord(
                                source, // audioSource，音频采集的来源
                                SAMPLE_RATE, // sampleRateInHz，音频采样率
                                AudioFormat.CHANNEL_IN_MONO, // channelConfig，声道：单声道、双声道等
                                AudioFormat.ENCODING_PCM_16BIT, // audioFormat，音频采样精度，指定采样的数据的格式和每次采样的大小，只支持8位和16位。
                                // PCM代表脉冲编码调制，它实际上是原始的音频样本。16位将占用更多的空间和处理能力，但是表示的音频将更接近真实。
                                buffer_size // bufferSizeInBytes，缓冲区大小，用于存放AudioRecord采集到的音频数据
                        );
                        // AudioRecord.STATE_INITIALIZED：初始完毕
                        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED)
                            audioRecord = null;
                    } catch (final Exception e) {
                        audioRecord = null;
                    }
                    if (audioRecord != null) break;
                }
                if (audioRecord != null) {
                    try {
                        if (mIsCapturing) {
                            if (DEBUG) Log.v(TAG, "AudioThread:start audio recording");
                            // 字节缓冲区：系统级的内存分配
                            // https://blog.csdn.net/seebetpro/article/details/49184305
                            final ByteBuffer buf = ByteBuffer.allocateDirect(SAMPLES_PER_FRAME);
                            int readBytes;
                            // 开始录制
                            audioRecord.startRecording();
                            try {
                                while (mIsCapturing && !mRequestStop && !mIsEOS) {
                                    // read audio pcm data from internal mic
                                    buf.clear();
                                    readBytes = audioRecord.read(buf, SAMPLES_PER_FRAME);
                                    if (readBytes > 0) {
                                        // position指针：这个指针是指向当前有效数据的起始位置，
                                        // 在初始化分配内存的时候指向数组的起始位置，后续可以通过position方法进行设置，
                                        // 同时他在很多地方都会发生改变，特别是在读写数据方法get,put的时候，每次读写一次，指针就加一，直到遇到了limit指针，position<=limit；
                                        // 所以可以看到整个数组中只有position-limit之间的数据是有效的，是可以进行读写操作的。
                                        // set audio data to encoder
                                        buf.position(readBytes);
                                        // 调用flip方法来改变状态，才能正确的读到刚刚写入的数据
                                        // flip方法把limit设为当前position，把position设为0，一般在从Buffer读出数据前调用。
                                        buf.flip();
                                        encode(buf, readBytes, getPTSUs());
                                        frameAvailableSoon();
                                        cnt++;
                                    }
                                }
                                frameAvailableSoon();
                            } finally {
                                // 停止录制
                                audioRecord.stop();
                            }
                        }
                    } finally {
                        // 释放资源
                        audioRecord.release();
                    }
                } else {
                    Log.e(TAG, "failed to initialize AudioRecord");
                }
            } catch (final Exception e) {
                Log.e(TAG, "AudioThread#run", e);
            }
            if (cnt == 0) {
                final ByteBuffer buf = ByteBuffer.allocateDirect(SAMPLES_PER_FRAME);
                for (int i = 0; mIsCapturing && (i < 5); i++) {
                    buf.position(SAMPLES_PER_FRAME);
                    buf.flip();
                    try {
                        encode(buf, SAMPLES_PER_FRAME, getPTSUs());
                        frameAvailableSoon();
                    } catch (final Exception e) {
                        break;
                    }
                    synchronized (this) {
                        try {
                            wait(50);
                        } catch (final InterruptedException ignored) {
                        }
                    }
                }
            }
            if (DEBUG) Log.v(TAG, "AudioThread:finished");
        }
    }

    /**
     * select the first codec that match a specific MIME type
     * 选择与特定MIME类型匹配的第一个编解码器
     */
    private static MediaCodecInfo selectAudioCodec() {
        if (DEBUG) Log.v(TAG, "selectAudioCodec:");
        MediaCodecInfo result = null;
        // get the list of available codecs
        // 获取可用编解码器列表
        // 过时：final int numCodecs = MediaCodecList.getCodecCount();
        MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] codecInfoList = mediaCodecList.getCodecInfos();
        LOOP:
        for (final MediaCodecInfo codecInfo : codecInfoList) {
            if (!codecInfo.isEncoder()) {   // skipp decoder
                continue;
            }
            final String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (DEBUG) Log.i(TAG, "supportedType:" + codecInfo.getName() + ",MIME=" + type);
                if (type.equalsIgnoreCase(MIME_TYPE)) {
                    result = codecInfo;
                    break LOOP;
                }
            }
        }
        return result;
    }

}
