package com.vot.youtube;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

/**
 * Минимальный нативный MediaSession-прототип фонового воспроизведения.
 *
 * <p>На этом этапе сервис не переносит WebView в отдельный процесс: Activity
 * остаётся владельцем WebView, а сервис отвечает за foreground-состояние,
 * системную карточку и команды управления. Это позволяет отдельно проверить
 * ограничения WebView перед переносом playback-контура в сервис.
 */
public final class PlaybackService extends Service {
    public static final String ACTION_START = "com.vot.youtube.PlaybackService.START";
    public static final String ACTION_STOP = "com.vot.youtube.PlaybackService.STOP";
    public static final String ACTION_PLAY = "com.vot.youtube.PlaybackService.PLAY";
    public static final String ACTION_PAUSE = "com.vot.youtube.PlaybackService.PAUSE";
    public static final String ACTION_NEXT = "com.vot.youtube.PlaybackService.NEXT";
    public static final String ACTION_PREVIOUS = "com.vot.youtube.PlaybackService.PREVIOUS";
    public static final String ACTION_SEEK_BACK = "com.vot.youtube.PlaybackService.SEEK_BACK";
    public static final String ACTION_SEEK_FORWARD = "com.vot.youtube.PlaybackService.SEEK_FORWARD";

    private static final String CHANNEL_ID = "playback";
    private static final int NOTIFICATION_ID = 4201;
    private static final int CONTROL_PLAY = 1;
    private static final int CONTROL_PAUSE = 2;
    private static final int CONTROL_NEXT = 3;
    private static final int CONTROL_PREVIOUS = 4;
    private static final long SEEK_MS = 10000L;
    private static volatile boolean running;
    private static volatile Controller controller;

    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private AudioManager.OnAudioFocusChangeListener legacyFocusListener;
    private boolean playing;
    private long position;
    private long duration;
    private String title = "";
    private String artist = "";
    private String artworkUri = "";

    /** Activity регистрирует WebView-команды, пока Activity находится в памяти. */
    public interface Controller {
        void play();
        void pause();
        void next();
        void previous();
        void seekBy(long deltaMs);
    }

    public static void attach(Controller next) {
        controller = next;
    }

    public static void detach(Controller current) {
        if (controller == current) controller = null;
    }

    public static boolean isRunning() {
        return running;
    }

    public static void setControllerPlaying(boolean value) {
        if (instance != null) instance.setPlaying(value);
    }

    public static void updatePlaybackInfo(String nextTitle, String nextArtist,
                                           long nextPosition, long nextDuration,
                                           boolean paused, String nextArtworkUri) {
        if (instance != null) instance.applyPlaybackInfo(nextTitle, nextArtist,
                nextPosition, nextDuration, paused, nextArtworkUri);
    }

    private static PlaybackService instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        running = true;
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createChannel();
        session = new MediaSession(this, "YouTubeVoTPlayback");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                send(CONTROL_PLAY);
                setPlaying(true);
            }

            @Override
            public void onPause() {
                send(CONTROL_PAUSE);
                setPlaying(false);
            }

            @Override
            public void onStop() {
                send(CONTROL_PAUSE);
                setPlaying(false);
            }

            @Override
            public void onSkipToNext() {
                send(CONTROL_NEXT);
            }

            @Override
            public void onSkipToPrevious() {
                send(CONTROL_PREVIOUS);
            }

            @Override
            public void onSeekTo(long position) {
                Controller c = controller;
                if (c != null) c.seekBy(position - currentPosition());
            }
        });
        session.setActive(true);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(PlaybackState.STATE_NONE, 0, 1.0f)
                .build());
        startForegroundCompat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY.equals(action)) {
            send(CONTROL_PLAY);
            setPlaying(true);
        } else if (ACTION_PAUSE.equals(action)) {
            send(CONTROL_PAUSE);
            setPlaying(false);
        } else if (ACTION_NEXT.equals(action)) {
            send(CONTROL_NEXT);
        } else if (ACTION_PREVIOUS.equals(action)) {
            send(CONTROL_PREVIOUS);
        } else if (ACTION_SEEK_BACK.equals(action)) {
            sendSeek(-SEEK_MS);
        } else if (ACTION_SEEK_FORWARD.equals(action)) {
            sendSeek(SEEK_MS);
        }
        return START_STICKY;
    }

    private void send(int action) {
        Controller c = controller;
        if (c == null) return;
        try {
            switch (action) {
                case CONTROL_PLAY: c.play(); break;
                case CONTROL_PAUSE: c.pause(); break;
                case CONTROL_NEXT: c.next(); break;
                case CONTROL_PREVIOUS: c.previous(); break;
                default: break;
            }
        } catch (Throwable ignored) {
            // Activity могла быть уничтожена; сервис останется в foreground,
            // но следующая команда будет доставлена новому Activity.
        }
    }

    private void sendSeek(long delta) {
        Controller c = controller;
        if (c != null) c.seekBy(delta);
    }

    private long currentPosition() {
        return position;
    }

    private void applyPlaybackInfo(String nextTitle, String nextArtist,
                                   long nextPosition, long nextDuration,
                                   boolean paused, String nextArtworkUri) {
        boolean changed = !nextTitle.equals(title) || !nextArtist.equals(artist)
                || !nextArtworkUri.equals(artworkUri);
        title = nextTitle;
        artist = nextArtist;
        position = Math.max(0L, nextPosition);
        duration = Math.max(0L, nextDuration);
        artworkUri = nextArtworkUri;
        setPlaying(!paused, false);
        if (session != null) {
            MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
            if (!artworkUri.isEmpty()) {
                metadata.putString(MediaMetadata.METADATA_KEY_ART_URI, artworkUri);
            }
            session.setMetadata(metadata.build());
        }
        if (changed) updateNotification();
    }

    private void setPlaying(boolean value) {
        setPlaying(value, true);
    }

    private void setPlaying(boolean value, boolean requestFocus) {
        playing = value;
        if (requestFocus) {
            if (value) requestAudioFocus();
            else abandonAudioFocus();
        }
        if (session != null) {
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                            | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                    .setState(value ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            position, duration > 0 ? 1.0f : 0.0f)
                    .build());
        }
        updateNotification();
    }

    private void requestAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int focusChange) {
                            if (focusChange <= 0) {
                                send(CONTROL_PAUSE);
                                setPlaying(false, false);
                            }
                        }
                    })
                    .build();
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            legacyFocusListener = new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    if (focusChange <= 0) {
                        send(CONTROL_PAUSE);
                        setPlaying(false, false);
                    }
                }
            };
            audioManager.requestAudioFocus(legacyFocusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26 && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            audioFocusRequest = null;
        } else if (legacyFocusListener != null) {
            audioManager.abandonAudioFocus(legacyFocusListener);
            legacyFocusListener = null;
        }
    }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title.isEmpty() ? getString(R.string.playback_notification_title) : title)
                .setContentText(artist.isEmpty() ? getString(R.string.playback_notification_text) : artist)
                .setContentIntent(openPi)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous,
                        getString(R.string.playback_previous), servicePendingIntent(ACTION_PREVIOUS, 1)).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_play, getString(R.string.playback_play),
                        servicePendingIntent(ACTION_PLAY, 2)).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, getString(R.string.playback_pause),
                        servicePendingIntent(ACTION_PAUSE, 3)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next,
                        getString(R.string.playback_next), servicePendingIntent(ACTION_NEXT, 4)).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private PendingIntent servicePendingIntent(String action, int request) {
        return PendingIntent.getService(this, request, new Intent(this, PlaybackService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(channel);
    }

    private int immutableFlag() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    @Override
    public void onDestroy() {
        running = false;
        abandonAudioFocus();
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}
