package software.solid.fluttervlcplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.util.Base64;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;

import io.flutter.plugin.common.*;
import io.flutter.plugin.platform.PlatformView;
import io.flutter.view.TextureRegistry;

import org.videolan.libvlc.IVLCVout;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.io.File;

class FlutterVideoView implements PlatformView, MethodChannel.MethodCallHandler, MediaPlayer.EventListener {

    // Silences player log output.
    private static final boolean DISABLE_LOG_OUTPUT = true;

    final PluginRegistry.Registrar registrar;
    private final MethodChannel methodChannel;

    private QueuingEventSink eventSink;
    private final EventChannel eventChannel;

    private final Context context;

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private VLCVideoLayout frameLayout;
    private TextureView textureView;
    private TextureView subtitleView;
    private IVLCVout vout;
    private boolean playerDisposed;

    private Boolean subtitleTextureValid=false;
    private Boolean videoTextureValid=false;

    public FlutterVideoView(Context context, PluginRegistry.Registrar _registrar, BinaryMessenger messenger, int id) {
        this.playerDisposed = false;

        this.context = context;
        this.registrar = _registrar;

        eventSink = new QueuingEventSink();
        eventChannel = new EventChannel(messenger, "flutter_video_plugin/getVideoEvents_" + id);

        eventChannel.setStreamHandler(
                new EventChannel.StreamHandler() {
                    @Override
                    public void onListen(Object o, EventChannel.EventSink sink) {
                        eventSink.setDelegate(sink);
                    }

                    @Override
                    public void onCancel(Object o) {
                        eventSink.setDelegate(null);
                    }
                }
        );

        TextureRegistry.SurfaceTextureEntry textureEntry = registrar.textures().createSurfaceTexture();
        TextureRegistry.SurfaceTextureEntry subtitleEntry = registrar.textures().createSurfaceTexture();
        createLayout(context);
        textureView.setSurfaceTexture(textureEntry.surfaceTexture());
        subtitleView.setSurfaceTexture(subtitleEntry.surfaceTexture());

//        subtitleView.setZOrderMediaOverlay(true);
//        subtitleView.getHolder().setFormat(PixelFormat.TRANSLUCENT);

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {

            boolean wasPaused = false;

            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (vout == null) return;

                vout.setVideoSurface(new Surface(textureView.getSurfaceTexture()), null);
                videoTextureValid=true;

                if (subtitleTextureValid && videoTextureValid)
                    vout.attachViews();
                textureView.forceLayout();
                if (wasPaused) {
                    mediaPlayer.play();
                    wasPaused = false;
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (playerDisposed) {
                    if (mediaPlayer != null) {
                        mediaPlayer.stop();
                        mediaPlayer.release();
                        mediaPlayer = null;
                    }
                    return true;
                } else {
                    if (mediaPlayer != null && vout != null) {
                        mediaPlayer.pause();
                        wasPaused = true;
                        vout.detachViews();
                    }
                    return true;
                }
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }

        });

        subtitleView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
                if (vout == null) return;

                vout.setSubtitlesSurface(new Surface(subtitleView.getSurfaceTexture()), null);
                subtitleTextureValid=true;

                if (subtitleTextureValid && videoTextureValid)
                    vout.attachViews();
                subtitleView.forceLayout();

            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {

            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

            }
        });

        methodChannel = new MethodChannel(messenger, "flutter_video_plugin/getVideoView_" + id);
        methodChannel.setMethodCallHandler(this);
    }

    private void createLayout(Context context) {
        frameLayout = new VLCVideoLayout(context);
        textureView = new TextureView(context);
        subtitleView =new TextureView(context);
        frameLayout.addView(textureView);
        frameLayout.addView(subtitleView);
    }

    @Override
    public View getView() {
        return frameLayout;
    }

    @Override
    public void dispose() {
        if (mediaPlayer != null) mediaPlayer.stop();
        if (vout != null) vout.detachViews();
        playerDisposed = true;
    }


    // Suppress WrongThread warnings from IntelliJ / Android Studio, because it looks like the advice
    // is wrong and actually breaks the library.
    @SuppressLint("WrongThread")
    @Override
    public void onMethodCall(MethodCall methodCall, @NonNull MethodChannel.Result result) {
        Boolean isLocal = false;
        long time = 0;
        float rate = (float) 1.0;
        int track = -1;
        String subtitle = "";
        switch (methodCall.method) {
            case "initialize":
                if (frameLayout == null) {
                    createLayout(context);
                }

                ArrayList<String> options = new ArrayList<>();
                options.add("--no-drop-late-frames");
                options.add("--no-skip-frames");

                if (DISABLE_LOG_OUTPUT) {
                    // Silence player log output.
                    options.add("--quiet");
                }

                libVLC = new LibVLC(context, options);
                mediaPlayer = new MediaPlayer(libVLC);
                //mediaPlayer.setVideoTrackEnabled(true);
                mediaPlayer.setEventListener(this);
                vout = mediaPlayer.getVLCVout();
                textureView.forceLayout();
                textureView.setFitsSystemWindows(true);
                vout.setVideoSurface(new Surface(textureView.getSurfaceTexture()), null);
//                vout.setSubtitlesSurface(new Surface(textureView.getSurfaceTexture()), null);
                vout.attachViews();

                String initStreamURL = methodCall.argument("url");
                isLocal = methodCall.argument("isLocal");
                subtitle = methodCall.argument("subtitle");

                Media media = null;
                if (isLocal)
                    media = new Media(libVLC, Uri.fromFile(new File(initStreamURL)));
                else {
                    options.add("--rtsp-tcp");
                    media = new Media(libVLC, Uri.parse(Uri.decode(initStreamURL)));
                }

                mediaPlayer.setMedia(media);
                if (!subtitle.isEmpty())
                    mediaPlayer.addSlave(Media.Slave.Type.Subtitle, subtitle, true);

                result.success(null);
                break;
            case "dispose":
                this.dispose();
                break;
            case "changeURL":
                if (libVLC == null)
                    result.error("VLC_NOT_INITIALIZED", "The player has not yet been initialized.", false);

                mediaPlayer.stop();
                String newURL = methodCall.argument("url");
                isLocal = methodCall.argument("isLocal");
                Media newMedia = null;
                if (isLocal)
                    newMedia = new Media(libVLC, Uri.fromFile(new File(newURL)));
                else
                    newMedia = new Media(libVLC, Uri.parse(Uri.decode(newURL)));
                newMedia.setHWDecoderEnabled(true, true);
                mediaPlayer.setMedia(newMedia);

                result.success(null);
                break;
            case "getSnapshot":
                String imageBytes;
                Map<String, String> response = new HashMap<>();
                if (mediaPlayer.isPlaying()) {
                    Bitmap bitmap = textureView.getBitmap();
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
                    imageBytes = Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT);
                    response.put("snapshot", imageBytes);
                }
                result.success(response);
                break;
            case "setPlaybackState":

                String playbackState = methodCall.argument("playbackState");
                if (playbackState == null) result.success(null);

                switch (playbackState) {
                    case "play":
                        textureView.forceLayout();
                        if (!mediaPlayer.isPlaying())
                            mediaPlayer.play();
                        break;
                    case "pause":
                        if (mediaPlayer.isPlaying())
                            mediaPlayer.pause();
                        break;
                    case "stop":
                        mediaPlayer.stop();
                        break;
                }

                result.success(null);
                break;

            case "setPlaybackSpeed":

                float playbackSpeed = Float.parseFloat((String) methodCall.argument("speed"));
                mediaPlayer.setRate(playbackSpeed);

                result.success(null);
                break;
            case "getPlaybackSpeed":
                rate = mediaPlayer.getRate();
                result.success(rate);
                break;

            case "setTime":
                time = Long.parseLong((String) methodCall.argument("time"));
                mediaPlayer.setTime(time);

                result.success(null);
                break;
            case "getTime":
                time = mediaPlayer.getTime();
                result.success(time);
                break;
            case "getDuration":
                time = mediaPlayer.getLength();
                result.success(time);
                break;
            case "isPlaying":
                result.success(mediaPlayer.isPlaying());
                break;
            case "setSubtitleTrack":
                track = methodCall.argument("track");
                mediaPlayer.setSpuTrack(track);
                break;
            case "getSubtitleTrackCount":
                track = mediaPlayer.getSpuTracksCount();
                result.success(track);
                break;
            case "addSubtitle":
                subtitle = methodCall.argument("subtitle");
                mediaPlayer.addSlave(Media.Slave.Type.Subtitle, subtitle, true);
                break;
        }
    }

    @Override
    public void onEvent(MediaPlayer.Event event) {
        HashMap<String, Object> eventObject = new HashMap<>();

        switch (event.type) {
            case MediaPlayer.Event.Playing:
                // Insert buffering=false event first:
                eventObject.put("name", "buffering");
                eventObject.put("value", false);
                eventSink.success(eventObject.clone());
                eventObject.clear();

                // Now send playing info:
                int height = 0;
                int width = 0;

                Media.VideoTrack currentVideoTrack = (Media.VideoTrack) mediaPlayer.getMedia().getTrack(
                        mediaPlayer.getVideoTrack()
                );
                if (currentVideoTrack != null) {
                    height = currentVideoTrack.height;
                    width = currentVideoTrack.width;
                }

                eventObject.put("name", "playing");
                eventObject.put("value", true);
                eventObject.put("ratio", height > 0 ? (double) width / (double) height : 0D);
                eventObject.put("height", height);
                eventObject.put("width", width);
                eventObject.put("length", mediaPlayer.getLength());
                eventSink.success(eventObject.clone());
                break;
            case MediaPlayer.Event.PositionChanged:
                float pos = event.getPositionChanged();
                eventObject.put("name", "position");
                eventObject.put("value", pos);
                eventSink.success(eventObject.clone());
                eventObject.clear();
                break;
            case MediaPlayer.Event.EndReached:
                mediaPlayer.stop();
                eventObject.put("name", "ended");
                eventSink.success(eventObject);

                eventObject.clear();
                eventObject.put("name", "playing");
                eventObject.put("value", false);
                eventObject.put("reason", "EndReached");
                eventSink.success(eventObject);

            case MediaPlayer.Event.Vout:
                vout.setWindowSize(textureView.getWidth(), textureView.getHeight());
                break;

            case MediaPlayer.Event.TimeChanged:
                eventObject.put("name", "timeChanged");
                eventObject.put("value", mediaPlayer.getTime());
                eventObject.put("speed", mediaPlayer.getRate());
                eventSink.success(eventObject);
                break;

            case MediaPlayer.Event.EncounteredError:
                System.err.println("(flutter_vlc_plugin) A VLC error occurred.");
            case MediaPlayer.Event.Paused:
            case MediaPlayer.Event.Stopped:
                eventObject.put("name", "buffering");
                eventObject.put("value", false);
                eventSink.success(eventObject);

                eventObject.clear();
                eventObject.put("name", "playing");
                eventObject.put("value", false);
                eventSink.success(eventObject);
                break;
        }
    }
}
