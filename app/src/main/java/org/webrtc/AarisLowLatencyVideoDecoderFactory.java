package org.webrtc;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.VideoCapabilities;
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
       * Keep libwebrtc's decoder selection authoritative. Multi-decoder Android
       * devices can expose several components for the same MIME type; preferring
       * an arbitrary component only because it advertises FEATURE_LowLatency can
       * silently replace WebRTC's compatibility-tested choice. A decoder may
       * initialize successfully yet deliver poor cadence, so software fallback
       * does not necessarily rescue that failure mode.
       *
       * Ask stock WebRTC to choose first, then reconstruct only that exact
       * implementation so Aaris can add public realtime MediaCodec hints.
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
        // Broken/incomplete OEM metadata must not replace a working stock path.
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
        VideoCapabilities videoCapabilities;
        try {
          videoCapabilities = capabilities.getVideoCapabilities();
        } catch (RuntimeException incompleteVendorMetadata) {
          videoCapabilities = null;
        }

        return new AndroidVideoDecoder(
            new LowLatencyMediaCodecWrapperFactory(
                type.mimeType(),
                lowLatencySupported,
                videoCapabilities),
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
      // The already-selected stock decoder remains the safe fallback.
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
   * <p>Stock AndroidVideoDecoder configures only MIME + dimensions, so priority
   * 0 has no frame-rate performance target to plan around. When the selected
   * hardware decoder publishes a measured/guaranteed throughput for the exact
   * decoded geometry, add KEY_OPERATING_RATE as that target. Unknown or broken
   * OEM capability metadata leaves the current priority-only path unchanged.
   *
   * <p>The low-latency flag remains separately capability-gated because Android
   * exposes it only for decoders that advertise FEATURE_LowLatency on API 30+.</p>
   */
  static void applyRealtimeHints(
      MediaFormat format,
      int flags,
      boolean lowLatencySupported,
      int operatingRateFps) {
    if ((flags & MediaCodec.CONFIGURE_FLAG_ENCODE) != 0) {
      return;
    }

    // minSdk is 26. Priority 0 marks this as realtime codec work.
    format.setInteger(MediaFormat.KEY_PRIORITY, 0);

    if (operatingRateFps > 0) {
      format.setInteger(MediaFormat.KEY_OPERATING_RATE, operatingRateFps);
    }

    if (
        lowLatencySupported &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    ) {
      LowLatencyCapability.Api30.enable(format);
    }
  }

  /**
   * Highest decoder rate Android itself says this component can sustain for the
   * exact geometry. Performance points are guarantees on API 29+; older devices
   * use the measured achievable-rate range. Never invent capability when OEM
   * metadata is absent.
   */
  static int realtimeOperatingRate(
      @Nullable VideoCapabilities videoCapabilities,
      MediaFormat format) {
    if (videoCapabilities == null) {
      return 0;
    }

    final int width;
    final int height;
    try {
      width = format.getInteger(MediaFormat.KEY_WIDTH);
      height = format.getInteger(MediaFormat.KEY_HEIGHT);
    } catch (RuntimeException missingGeometry) {
      return 0;
    }
    if (width <= 0 || height <= 0) {
      return 0;
    }

    try {
      if (!videoCapabilities.isSizeSupported(width, height)) {
        return 0;
      }
    } catch (RuntimeException incompleteMetadata) {
      return 0;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      Integer guaranteed =
          Api29.guaranteedOperatingRate(
              videoCapabilities,
              width,
              height);
      if (guaranteed != null) {
        return guaranteed;
      }
    }

    try {
      android.util.Range<Double> achievable =
          videoCapabilities.getAchievableFrameRatesFor(width, height);
      if (achievable == null) {
        return 0;
      }
      Double upper = achievable.getUpper();
      return boundedOperatingRate(
          upper == null ? Double.NaN : upper.doubleValue());
    } catch (RuntimeException incompleteMetadata) {
      return 0;
    }
  }

  static int boundedOperatingRate(double upperFps) {
    if (!Double.isFinite(upperFps) || upperFps < MIN_REALTIME_DECODER_FPS) {
      return 0;
    }
    return Math.min(
        MAX_REALTIME_DECODER_FPS,
        (int) Math.floor(upperFps + 1e-6));
  }

  @TargetApi(Build.VERSION_CODES.Q)
  private static final class Api29 {
    private Api29() {}

    static @Nullable Integer guaranteedOperatingRate(
        VideoCapabilities videoCapabilities,
        int width,
        int height) {
      final java.util.List<VideoCapabilities.PerformancePoint> points;
      try {
        points = videoCapabilities.getSupportedPerformancePoints();
      } catch (RuntimeException vendorFailure) {
        return null;
      }
      if (points == null || points.isEmpty()) {
        return null;
      }

      for (int fps = MAX_REALTIME_DECODER_FPS;
          fps >= MIN_REALTIME_DECODER_FPS;
          fps--) {
        VideoCapabilities.PerformancePoint requested =
            new VideoCapabilities.PerformancePoint(width, height, fps);
        for (VideoCapabilities.PerformancePoint point : points) {
          if (point != null && point.covers(requested)) {
            return fps;
          }
        }
      }
      // A non-empty performance-point table is explicit capability evidence:
      // do not override it with a less-authoritative achievable-rate estimate.
      return 0;
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
    private final @Nullable VideoCapabilities videoCapabilities;

    LowLatencyMediaCodecWrapperFactory(
        String mimeType,
        boolean lowLatencySupported,
        @Nullable VideoCapabilities videoCapabilities) {
      this.mimeType = mimeType;
      this.lowLatencySupported = lowLatencySupported;
      this.videoCapabilities = videoCapabilities;
    }

    @Override
    public MediaCodecWrapper createByCodecName(String name)
        throws IOException {
      return new LowLatencyMediaCodecWrapper(
          MediaCodec.createByCodecName(name),
          mimeType,
          lowLatencySupported,
          videoCapabilities);
    }
  }

  private static final class LowLatencyMediaCodecWrapper
      implements MediaCodecWrapper {
    private final MediaCodec codec;
    private final String mimeType;
    private final boolean lowLatencySupported;
    private final @Nullable VideoCapabilities videoCapabilities;

    LowLatencyMediaCodecWrapper(
        MediaCodec codec,
        String mimeType,
        boolean lowLatencySupported,
        @Nullable VideoCapabilities videoCapabilities) {
      this.codec = codec;
      this.mimeType = mimeType;
      this.lowLatencySupported = lowLatencySupported;
      this.videoCapabilities = videoCapabilities;
    }

    @Override
    public void configure(
        MediaFormat format,
        Surface surface,
        MediaCrypto crypto,
        int flags) {
      int operatingRateFps =
          (flags & MediaCodec.CONFIGURE_FLAG_ENCODE) == 0
              ? realtimeOperatingRate(videoCapabilities, format)
              : 0;
      applyRealtimeHints(
          format,
          flags,
          lowLatencySupported,
          operatingRateFps);

      if ((flags & MediaCodec.CONFIGURE_FLAG_ENCODE) == 0) {
        Logging.d(
            TAG,
            "Android realtime decode priority enabled for "
                + codec.getName()
                + " "
                + mimeType
                + (operatingRateFps > 0
                    ? " operating-rate=" + operatingRateFps + "fps"
                    : "")
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

  private static final int MIN_REALTIME_DECODER_FPS = 15;
  private static final int MAX_REALTIME_DECODER_FPS = 60;
  private static final String TAG = "AarisLowLatencyDecoder";
}
