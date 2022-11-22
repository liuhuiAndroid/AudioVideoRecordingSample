package com.serenegiant.encoder;

import java.io.IOException;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.opengl.EGLContext;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.audiovideosample.BuildConfig;
import com.serenegiant.glutilsOld.RenderHandler;

/**
 * https://zhuanlan.zhihu.com/p/33050820
 */
public class MediaVideoEncoder extends MediaEncoder {

    private static final boolean DEBUG = BuildConfig.DEBUG;
    private static final String TAG = MediaVideoEncoder.class.getSimpleName();

    // MediaFormat.MIMETYPE_VIDEO_AVC（H.264）
    // MediaFormat.MIMETYPE_VIDEO_HEVC（H.265）
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    // parameters for recording
    // 帧率
    private static final int FRAME_RATE = 25;
    private static final float BPP = 0.25f;

    private final int mWidth;
    private final int mHeight;
    private RenderHandler mRenderHandler;
    private Surface mSurface;

    public MediaVideoEncoder(final MediaMuxerWrapper muxer, final MediaEncoderListener listener, final int width, final int height) {
        super(muxer, listener);
        if (DEBUG) Log.i(TAG, "MediaVideoEncoder: ");
        mWidth = width;
        mHeight = height;
        mRenderHandler = RenderHandler.createHandler(TAG);
    }

/*
    public boolean frameAvailableSoon(final float[] tex_matrix) {
        boolean result;
        if (result = super.frameAvailableSoon())
            mRenderHandler.draw(tex_matrix);
        return result;
    }
*/

    public void frameAvailableSoon(final float[] tex_matrix, final float[] mvp_matrix) {
        if (super.frameAvailableSoon())
            mRenderHandler.draw(tex_matrix, mvp_matrix);
    }

    @Override
    public boolean frameAvailableSoon() {
        boolean result = super.frameAvailableSoon();
        if (result)
            mRenderHandler.draw(null);
        return result;
    }

    @Override
    protected void prepare() throws IOException {
        if (DEBUG) Log.i(TAG, "prepare: ");
        mTrackIndex = -1;
        mMuxerStarted = mIsEOS = false;

        final MediaCodecInfo videoCodecInfo = selectVideoCodec();
        if (videoCodecInfo == null) {
            Log.e(TAG, "Unable to find an appropriate codec for " + MIME_TYPE);
            return;
        }
        if (DEBUG) Log.i(TAG, "selected codec: " + videoCodecInfo.getName());

        final MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);
        // 指定编码器颜色格式
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);    // API >= 18
        // 指定比特率
        // 比特率：简单来说，码率就是指单位时间内传送的数据量，一般以秒为单位，如：128 kbps，
        // 表示每秒通过网络传送的数据量为 128k bit.
        // 码率：与比特率是一个概念，从技术的角度来讲，比特率显得更专业一些。码率越大，体积越大。
        format.setInteger(MediaFormat.KEY_BIT_RATE, calcBitRate());
        // 指定帧率
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        // 指定关键帧时间间隔，一般设置为每秒关键帧
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);
        if (DEBUG) Log.i(TAG, "format: " + format);

        mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
        // MediaCodec.CONFIGURE_FLAG_ENCODE 表示编码
        mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // get Surface for encoder input
        // this method only can call between #configure and #start
        // 获取输入Surface
        mSurface = mMediaCodec.createInputSurface();    // API >= 18
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

    public void setEglContext(final EGLContext shared_context, final int tex_id) {
        mRenderHandler.setEglContext(shared_context, tex_id, mSurface, true);
    }

    @Override
    protected void release() {
        if (DEBUG) Log.i(TAG, "release:");
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        if (mRenderHandler != null) {
            mRenderHandler.release();
            mRenderHandler = null;
        }
        super.release();
    }

    private int calcBitRate() {
        final int bitrate = (int) (BPP * FRAME_RATE * mWidth * mHeight);
        Log.i(TAG, String.format("bitrate=%5.2f[Mbps]", bitrate / 1024f / 1024f));
        return bitrate;
    }

    /**
     * select the first codec that match a specific MIME type
     *
     * @return null if no codec matched
     */
    protected static MediaCodecInfo selectVideoCodec() {
        if (DEBUG) Log.v(TAG, "selectVideoCodec:");
        // get the list of available codecs
        // 获取可用编解码器列表
        // 过时：final int numCodecs = MediaCodecList.getCodecCount();
        MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] codecInfoList = mediaCodecList.getCodecInfos();
        for (MediaCodecInfo codecInfo : codecInfoList) {
            if (!codecInfo.isEncoder()) {    // skipp decoder
                continue;
            }
            // select first codec that match a specific MIME type and color format
            final String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(MIME_TYPE)) {
                    if (DEBUG) Log.i(TAG, "codec:" + codecInfo.getName() + ",MIME=" + type);
                    final int format = selectColorFormat(codecInfo);
                    if (format > 0) {
                        return codecInfo;
                    }
                }
            }
        }
        return null;
    }

    /**
     * select color format available on specific codec and we can use.
     *
     * @return 0 if no colorFormat is matched
     */
    protected static int selectColorFormat(final MediaCodecInfo codecInfo) {
        if (DEBUG) Log.i(TAG, "selectColorFormat: ");
        int result = 0;
        final MediaCodecInfo.CodecCapabilities caps;
        try {
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
            // 查询机器上的MediaCodec实现具体支持哪些YUV格式作为输入格式
            caps = codecInfo.getCapabilitiesForType(MIME_TYPE);
        } finally {
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
        }
        int colorFormat;
        for (int i = 0; i < caps.colorFormats.length; i++) {
            colorFormat = caps.colorFormats[i];
            if (isRecognizedVideoFormat(colorFormat)) {
                result = colorFormat;
                break;
            }
        }
        if (result == 0)
            Log.e(TAG, "couldn't find a good color format for " + codecInfo.getName() + " / " + MIME_TYPE);
        return result;
    }

    /**
     * color formats that we can use in this class
     */
    protected static int[] recognizedFormats;

    static {
        recognizedFormats = new int[]{
//        	MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
//        	MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
//        	MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        };
    }

    private static boolean isRecognizedVideoFormat(final int colorFormat) {
        if (DEBUG) Log.i(TAG, "isRecognizedVideoFormat:colorFormat=" + colorFormat);
        final int n = recognizedFormats != null ? recognizedFormats.length : 0;
        for (int i = 0; i < n; i++) {
            if (recognizedFormats[i] == colorFormat) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void signalEndOfInputStream() {
        if (DEBUG) Log.d(TAG, "sending EOS to encoder");
        // 发出流结束的信号。在此调用之后，输入Surface将立即停止向编解码器submit数据。
        mMediaCodec.signalEndOfInputStream();    // API >= 18
        mIsEOS = true;
    }

}
