package org.webrtc;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecList;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashSet;

/**
 * A WebRTC-compatible decoder factory that preserves the stock hardware-first
 * + software-fallback behavior while applying Android's realtime codec priority
 * to hardware decoders and enabling the official low-latency MediaCodec mode
 * when the selected decoder explicitly advertises support.
 *
 * <p>This class intentionally lives in org.webrtc because the pinned WebRTC
 * Android SDK keeps AndroidVideoDecoder and MediaCodecWrapper package-private.
 * Keeping the integration at the codec-construction seam avoids reflection and
 * avoids a second decode/render queue.</p>
 */
public final class AarisLowLatencyVideoDecoderFactory implements VideoDecoderFactory {
  private final VideoDecoderFactory hardwareFactory;
  private final VideoDecoderFactory softwareFactory = new SoftwareVideoDecoderFactory();
  private final @Nullable VideoDecoderFactory platformSoftwareFactory;

  public AarisLowLatencyVideoDecoderFactory(@Nullable EglBase.Context eglContext) {
    hardwareFactory = new LowLatencyHardwareVideoDecoderFactory(eglContext);
    platformSoftwareFactory = new PlatformSoftwareVideoDecoderFactory(eglContext);
  }

  @Override
  public @Nullable VideoDecoder createDecoder(VideoCodecInfo codecInfo) {
    VideoDecoder softwareDecoder = softwareFactory.createDecoder(codecInfo);
    VideoDecoder hardwareDecoder = hardwareFactory.createDecoder(codecInfo);

    if (softwareDecoder == null && platformSoftwareFactory != null) {
      softwareDecoder = platformSoftwareFactory.createDecoder(codecInfo);
    }

    if (hardwareDecoder != null && softwareDecoder != null) {
      return new VideoDecoderFallback(
          /* fallback= */ softwareDecoder,
          /* primary= */ hardwareDecoder);
    }
    return hardwareDecoder != null ? hardwareDecoder : softwareDecoder;
  }

  @Override
  public VideoCodecInfo[] getSupportedCodecs() {
    LinkedHashSet<VideoCodecInfo> codecs = new LinkedHashSet<>();
    codecs.addAll(Arrays.asList(softwareFactory.getSupportedCodecs()));
    codecs.addAll(Arrays.asList(hardwareFactory.getSupportedCodecs()));
    if (platformSoftwareFactory != null) {
      codecs.addAll(Arrays.asList(platformSoftwareFactory.getSupportedCodecs()));
    }
    return codecs.toArray(new VideoCodecInfo[0]);
  }

  /**
   * Hardware factory matching WebRTC's normal Android hardware selection, but
   * constructing AndroidVideoDecoder with our capability-gated wrapper factory.
   */
  private static final class LowLatencyHardwareVideoDecoderFactory
      implements VideoDecoderFactory {
    private final @Nullable EglBase.Context sharedContext;
    private final HardwareVideoDecoderFactory stockFactory;

    LowLatencyHardwareVideoDecoderFactory(@Nullable EglBase.Context sharedContext) {
      this.sharedContext = sharedContext;
      this.stockFactory = new HardwareVideoDecoderFactory(sharedContext);
    }

    @Override
    public @Nullable VideoDecoder createDecoder(VideoCodecInfo codecInfo) {
      /*
       * Keep libwebrtc's platform selection authoritative. Android devices may
       * expose multiple hardware decoders for the same MIME type, and simply
       * preferring the first codec that advertises FEATURE_LowLatency can select
       * a different vendor component than WebRTC's compatibility policy chose.
       * A decoder can initialize successfully yet still have poor realtime
       * output cadence, so VideoDecoderFallback cannot rescue that case.
       *
       * Mirror the encoder integration: ask stock WebRTC to select the decoder,
       * then reconstruct only that exact implementation so we can add public
       * MediaCodec realtime hints at configure().
       */
      final VideoDecoder stockDecoder;
      try {
        stockDecoder = stockFactory.createDecoder(codecInfo);
      } catch (RuntimeException stockSelectionFailure) {
        return null;
      }
      if (stockDecoder == null) {
        return null;
      }

      final String codecName = stockDecoder.getImplementationName();
      final MediaCodecInfo mediaCodecInfo = findCodecByName(codecName);
      if (mediaCodecInfo == null) {
        // OEM metadata can be incomplete. The stock decoder remains correct.
        return stockDecoder;
      }

      final VideoCodecMimeType type;
      try {
        type = VideoCodecMimeType.valueOf(codecInfo.getName());
      } catch (IllegalArgumentException unsupported) {
        return stockDecoder;
      }

      try {
        final CodecCapabilities capabilities =
            mediaCodecInfo.getCapabilitiesForType(type.mimeType());
        final Integer colorFormat =
            MediaCodecUtils.selectColorFormat(
                MediaCodecUtils.DECODER_COLOR_FORMATS,
                capabilities);
        if (colorFormat == null) {
          return stockDecoder;
        }

        final boolean lowLatencySupported =
            LowLatencyCapability.isSupported(capabilities);

        return new AndroidVideoDecoder(
            new LowLatencyMediaCodecWrapperFactory(
                type.mimeType(),
                lowLatencySupported),
            codecName,
            type,
            colorFormat,
            sharedContext);
      } catch (RuntimeException incompleteVendorMetadata) {
        Logging.w(
            TAG,
            "Unable to wrap stock decoder "
                + codecName
                + " with realtime hints; using WebRTC stock decoder");
        return stockDecoder;
      }
    }

    @Override
    public VideoCodecInfo[] getSupportedCodecs() {
      return stockFactory.getSupportedCodecs();
    }
  }

  static @Nullable MediaCodecInfo findCodecByName(@Nullable String codecName) {
    if (codecName == null || codecName.isEmpty()) {
      return null;
    }

    try {
      for (MediaCodecInfo info :
          new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
        if (info != null && codecName.equals(info.getName())) {
          return info;
        }
      }
    } catch (RuntimeException unavailableMetadata) {
      // Stock WebRTC decoder remains the fallback when OEM metadata is broken.
    }
    return null;
  }

  private static final class LowLatencyCapability {
    private LowLatencyCapability() {}

    static boolean isSupported(CodecCapabilities capabilities) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return false;
      }
      return Api30.isSupported(capabilities);
    }

    @TargetApi(Build.VERSION_CODES.R)
    private static final class Api30 {
      private Api30() {}

      static boolean isSupported(CodecCapabilities capabilities) {
        try {
          return capabilities.isFeatureSupported(
              CodecCapabilities.FEATURE_LowLatency);
        } catch (RuntimeException vendorFailure) {
          return false;
        }
      }

      static void enable(MediaFormat format) {
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
      }
    }
  }

  /**
   * Apply only public Android resource-planning hints at WebRTC's existing
   * decoder configure seam.
   *
   * <p>Priority 0 is available from API 23 and tells MediaCodec that best-effort
   * throughput is not sufficient for this realtime communication workload. The
   * low-latency flag remains capability-gated because Android exposes it only
   * for decoders that explicitly advertise FEATURE_LowLatency on API 30+.</p>
   */
  static void applyRealtimeHints(
      MediaFormat format,
      int flags,
      boolean lowLatencySupported) {
    if ((flags & MediaCodec.CONFIGURE_FLAG_ENCODE) != 0) {
      return;
    }

    // minSdk is 26. Unlike KEY_LOW_LATENCY, KEY_PRIORITY applies to codec
    // resource planning even on older controller phones.
    format.setInteger(MediaFormat.KEY_PRIORITY, 0);

    if (
        lowLatencySupported &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    ) {
      LowLatencyCapability.Api30.enable(format);
    }
  }

  /**
   * Creates the exact MediaCodec requested by WebRTC. Realtime priority applies
   * to every selected hardware decoder; the low-latency bit is immutable for
   * this decoder instance and is decided from that codec's advertised
   * capability before AndroidVideoDecoder starts.
   */
  private static final class LowLatencyMediaCodecWrapperFactory
      implements MediaCodecWrapperFactory {
    private final String mimeType;
    private final boolean lowLatencySupported;

    LowLatencyMediaCodecWrapperFactory(
        String mimeType,
        boolean lowLatencySupported) {
      this.mimeType = mimeType;
      this.lowLatencySupported = lowLatencySupported;
    }

    @Override
    public MediaCodecWrapper createByCodecName(String name)
        throws IOException {
      return new LowLatencyMediaCodecWrapper(
          MediaCodec.createByCodecName(name),
          mimeType,
          lowLatencySupported);
    }
  }

  private static final class LowLatencyMediaCodecWrapper
      implements MediaCodecWrapper {
    private final MediaCodec codec;
    private final String mimeType;
    private final boolean lowLatencySupported;

    LowLatencyMediaCodecWrapper(
        MediaCodec codec,
        String mimeType,
        boolean lowLatencySupported) {
      this.codec = codec;
      this.mimeType = mimeType;
      this.lowLatencySupported = lowLatencySupported;
    }

    @Override
    public void configure(
        MediaFormat format,
        Surface surface,
        MediaCrypto crypto,
        int flags) {
      applyRealtimeHints(
          format,
          flags,
          lowLatencySupported);

      if ((flags & MediaCodec.CONFIGURE_FLAG_ENCODE) == 0) {
        Logging.d(
            TAG,
            "Android realtime decode priority enabled for "
                + codec.getName()
                + " "
                + mimeType
                + (lowLatencySupported
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? " + low-latency"
                    : ""));
      }

      codec.configure(format, surface, crypto, flags);
    }

    @Override
    public void start() {
      codec.start();
    }

    @Override
    public void flush() {
      codec.flush();
    }

    @Override
    public void stop() {
      codec.stop();
    }

    @Override
    public void release() {
      codec.release();
    }

    @Override
    public int dequeueInputBuffer(long timeoutUs) {
      return codec.dequeueInputBuffer(timeoutUs);
    }

    @Override
    public void queueInputBuffer(
        int index,
        int offset,
        int size,
        long presentationTimeUs,
        int flags) {
      codec.queueInputBuffer(
          index,
          offset,
          size,
          presentationTimeUs,
          flags);
    }

    @Override
    public int dequeueOutputBuffer(
        MediaCodec.BufferInfo info,
        long timeoutUs) {
      return codec.dequeueOutputBuffer(info, timeoutUs);
    }

    @Override
    public void releaseOutputBuffer(int index, boolean render) {
      codec.releaseOutputBuffer(index, render);
    }

    @Override
    public MediaFormat getInputFormat() {
      return codec.getInputFormat();
    }

    @Override
    public MediaFormat getOutputFormat() {
      return codec.getOutputFormat();
    }

    @Override
    public MediaFormat getOutputFormat(int index) {
      return codec.getOutputFormat(index);
    }

    @Override
    public ByteBuffer getInputBuffer(int index) {
      return codec.getInputBuffer(index);
    }

    @Override
    public ByteBuffer getOutputBuffer(int index) {
      return codec.getOutputBuffer(index);
    }

    @Override
    public Surface createInputSurface() {
      return codec.createInputSurface();
    }

    @Override
    public void setParameters(Bundle params) {
      codec.setParameters(params);
    }

    @Override
    public MediaCodecInfo getCodecInfo() {
      return codec.getCodecInfo();
    }
  }

  private static final String TAG = "AarisLowLatencyDecoder";
}
