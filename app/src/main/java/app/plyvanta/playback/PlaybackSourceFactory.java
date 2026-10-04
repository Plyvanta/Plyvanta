package app.plyvanta.playback;

import android.content.Context;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import java.io.IOException;
import java.util.Map;

import app.plyvanta.extractor.OkHttpDownloader;
import app.plyvanta.network.AppNetwork;
import app.plyvanta.offline.EncryptedChunkFile;
import app.plyvanta.offline.EncryptedMediaDataSource;
import app.plyvanta.offline.OfflineMediaRecord;
import app.plyvanta.offline.OfflineMediaStore;

@UnstableApi
public final class PlaybackSourceFactory {
    private final DefaultMediaSourceFactory mediaSourceFactory;

    public PlaybackSourceFactory(Context context) {
        OkHttpDataSource.Factory httpFactory = new OkHttpDataSource.Factory(
                AppNetwork.calls(AppNetwork.Profile.PLAYBACK))
                .setUserAgent(OkHttpDownloader.DESKTOP_USER_AGENT)
                .setDefaultRequestProperties(Map.of(
                        "Accept-Language", "en-US,en;q=0.8"
                ));
        // Online media, manifests, keys and segments must all use the shared HTTP route.
        // A DefaultDataSource wrapper could dispatch other schemes (including UDP) outside it.
        mediaSourceFactory = new DefaultMediaSourceFactory(httpFactory);
    }

    public MediaSource create(ResolvedVideo video) {
        MediaItem videoItem = mediaItem(
                video.getVideoUrl(),
                video.getVideoMimeType(),
                video.getTitle(),
                video.getUploader()
        );
        MediaSource primary = mediaSourceFactory.createMediaSource(videoItem);

        if (video.getSourceType() != ResolvedVideo.SourceType.MERGED
                || video.getAudioUrl() == null) {
            return primary;
        }

        MediaItem audioItem = mediaItem(
                video.getAudioUrl(),
                video.getAudioMimeType(),
                video.getTitle(),
                video.getUploader()
        );
        MediaSource audio = mediaSourceFactory.createMediaSource(audioItem);
        return new MergingMediaSource(primary, audio);
    }

    public MediaSource createOffline(OfflineMediaStore.PlaybackSession session)
            throws IOException {
        OfflineMediaRecord record = session.getRecord();
        EncryptedMediaDataSource.Factory dataSourceFactory =
                new EncryptedMediaDataSource.Factory(session);
        ProgressiveMediaSource.Factory sourceFactory =
                new ProgressiveMediaSource.Factory(dataSourceFactory);

        EncryptedChunkFile.TrackRole videoRole =
                record.getSourceType() == OfflineMediaRecord.SourceType.PROGRESSIVE
                        ? EncryptedChunkFile.TrackRole.PROGRESSIVE
                        : EncryptedChunkFile.TrackRole.VIDEO;
        MediaSource video = sourceFactory.createMediaSource(mediaItem(
                session.uriFor(videoRole),
                record.getVideoMimeType(),
                record.getTitle(),
                record.getUploader()
        ));
        if (record.getSourceType() == OfflineMediaRecord.SourceType.PROGRESSIVE) {
            return video;
        }

        MediaSource audio = sourceFactory.createMediaSource(mediaItem(
                session.uriFor(EncryptedChunkFile.TrackRole.AUDIO),
                record.getAudioMimeType(),
                record.getTitle(),
                record.getUploader()
        ));
        return new MergingMediaSource(video, audio);
    }

    private static MediaItem mediaItem(
            String url,
            String mimeType,
            String title,
            String artist
    ) {
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .build();
        return new MediaItem.Builder()
                .setUri(url)
                .setMimeType(mimeType)
                .setMediaMetadata(metadata)
                .build();
    }
}
