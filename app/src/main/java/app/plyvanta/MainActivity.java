package app.plyvanta;

import android.Manifest;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.lifecycle.Lifecycle;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import app.plyvanta.network.AppNetwork;
import app.plyvanta.offline.ContentKeyProtector;
import app.plyvanta.offline.OfflineDownloadEligibility;
import app.plyvanta.offline.OfflineDownloadManager;
import app.plyvanta.offline.OfflineMediaRecord;
import app.plyvanta.offline.OfflineMediaStore;
import app.plyvanta.offline.OfflineSecurityPolicy;
import app.plyvanta.offline.PlaybackProtection;
import app.plyvanta.playback.NewPipePlaylistResolver;
import app.plyvanta.playback.NewPipeVideoResolver;
import app.plyvanta.playback.PlaybackSessionState;
import app.plyvanta.playback.PlaybackSourceFactory;
import app.plyvanta.playback.PlaylistEntry;
import app.plyvanta.playback.PlaylistQueue;
import app.plyvanta.playback.ResolvedPlaylist;
import app.plyvanta.playback.ResolvedVideo;
import app.plyvanta.playback.SponsorSkipController;
import app.plyvanta.settings.PreferenceStore;
import app.plyvanta.sponsor.SponsorBlockClient;
import app.plyvanta.sponsor.SponsorSegment;
import app.plyvanta.support.DiagnosticReport;
import app.plyvanta.subscription.ChannelSubscription;
import app.plyvanta.subscription.SubscriptionDownloadScheduler;
import app.plyvanta.subscription.SubscriptionPanel;
import app.plyvanta.subscription.SubscriptionStore;
import app.plyvanta.update.UpdateChecker;
import app.plyvanta.update.UpdateDownloadVerification;
import app.plyvanta.update.UpdateNotificationManager;
import app.plyvanta.update.UpdatePreferences;
import app.plyvanta.update.UpdateRelease;
import app.plyvanta.update.UpdateScheduler;
import app.plyvanta.util.YouTubeUrlParser;

@UnstableApi
public final class MainActivity extends ComponentActivity {
    private static final String STATE_URL = "active_url";
    private static final String STATE_SOURCE_URL = "source_url";
    private static final String STATE_CURRENT_VIDEO_ID = "current_video_id";
    private static final String STATE_PLAYLIST_INDEX = "playlist_index";
    private static final String STATE_POSITION = "playback_position";
    private static final String STATE_PLAY_WHEN_READY = "play_when_ready";
    private static final String STATE_BUG_REPORT_STEP = "bug_report_step";
    private static final String STATE_BUG_REPORT_DRAFT = "bug_report_draft";
    private static final String STATE_BUG_REPORT_DIAGNOSTICS = "bug_report_diagnostics";
    private static final String STATE_BUG_REPORT_VIDEO = "bug_report_video";
    private static final String STATE_BUG_REPORT_FROM_ERROR = "bug_report_from_error";
    private static final String STATE_BUG_REPORT_PREVIEW = "bug_report_preview";
    private static final String STATE_BUG_REPORT_DETAIL_KEYS = "bug_report_detail_keys";
    private static final String STATE_BUG_REPORT_DETAIL_VALUES = "bug_report_detail_values";
    private static final String STATE_BUG_REPORT_VIDEO_URL = "bug_report_video_url";
    private static final String BUG_REPORT_URL =
            "https://github.com/Plyvanta/Plyvanta/issues/new";
    private static final int MAX_GITHUB_PREFILL_URI_CHARS = 6_000;
    private static final int BUG_REPORT_CLOSED = 0;
    private static final int BUG_REPORT_EDITING = 1;
    private static final int BUG_REPORT_REVIEWING = 2;
    private static final int REQUEST_UPDATE_NOTIFICATIONS = 1001;
    private static final int REQUEST_OFFLINE_CREDENTIAL = 1002;

    private enum PlaybackStartReason {
        USER,
        PLAYLIST_ITEM,
        RETRY,
        RESTORE
    }

    private enum PendingOfflineAction {
        NONE,
        DOWNLOAD_CURRENT,
        SHOW_LIBRARY,
        PLAY_ITEM
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService videoResolverExecutor =
            Executors.newSingleThreadExecutor();
    private final ExecutorService playlistResolverExecutor =
            Executors.newSingleThreadExecutor();
    private final ExecutorService updateCheckExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService offlineExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService torCheckExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger torSettingsGeneration = new AtomicInteger();
    private final AtomicInteger loadGeneration = new AtomicInteger();
    private final AtomicInteger playlistLoadGeneration = new AtomicInteger();
    private final AtomicInteger offlineForegroundGeneration =
            new AtomicInteger();
    private final AtomicLong updateDownloadVerificationGeneration = new AtomicLong();
    private final PlaybackSessionState playbackSessionState =
            new PlaybackSessionState();
    private final ManualUpdateCheckController manualUpdateCheckController =
            new ManualUpdateCheckController();

    private FrameLayout root;
    private LinearLayout appContent;
    private LinearLayout topBar;
    private LinearLayout body;
    private FrameLayout playerContainer;
    private PlayerView playerView;
    private ImageButton fullscreenButton;
    private EditText linkInput;
    private TextView titleText;
    private TextView uploaderText;
    private TextView playbackStatus;
    private TextView protectionStatus;
    private TextView torRoutingBanner;
    private LinearLayout playlistCard;
    private TextView playlistNameText;
    private TextView playlistPositionText;
    private Button playlistPreviousButton;
    private Button playlistNextButton;
    private LinearLayout errorCard;
    private TextView errorText;
    private ProgressBar loadingIndicator;
    private LinearLayout skipNotice;
    private TextView skipNoticeText;
    private Button offlineSaveButton;
    private Button offlineLibraryButton;
    private TextView offlineStatusText;
    private ProgressBar offlineProgress;

    private ExoPlayer player;
    private PlaybackSourceFactory playbackSourceFactory;
    private SponsorSkipController skipController;
    private SponsorBlockClient sponsorBlockClient;
    private PreferenceStore preferenceStore;
    private UpdatePreferences updatePreferences;
    private OfflineSecurityPolicy offlineSecurityPolicy;
    private OfflineMediaStore offlineMediaStore;
    private OfflineDownloadManager offlineDownloadManager;
    private SubscriptionStore subscriptionStore;
    private SubscriptionPanel subscriptionPanel;
    private final NewPipeVideoResolver videoResolver = new NewPipeVideoResolver();
    private final NewPipePlaylistResolver playlistResolver =
            new NewPipePlaylistResolver();

    private ResolvedVideo activeVideo;
    private String activeUrl;
    private String sourceUrl;
    private PlaylistQueue playlistQueue;
    private Future<?> activeVideoResolveTask;
    private Future<?> activePlaylistResolveTask;
    private Future<?> activeOfflineTask;
    private OfflineDownloadManager.Cancellation offlineDownloadCancellation;
    private OfflineMediaStore.PlaybackSession offlinePlaybackSession;
    private OfflineMediaRecord activeOfflineRecord;
    private PendingOfflineAction pendingOfflineAction = PendingOfflineAction.NONE;
    private UUID pendingOfflineItemId;
    private ResolvedVideo pendingOfflineVideo;
    private boolean awaitingOfflineCredential;
    private boolean offlineCredentialApproved;
    private boolean offlineDownloadInProgress;
    private String offlineInitializationFailure;
    private boolean fullscreen;
    private boolean retriedAfterPlaybackFailure;
    private int handledEndGeneration = -1;
    private int preparedPlaybackGeneration = -1;
    private long pendingSeekMs = PlaybackSessionState.NO_PENDING_SEEK;
    private String pendingRestoreVideoId;
    private int pendingRestorePlaylistIndex = -1;
    private boolean selectedMediaPrepared;
    private String lastFailureStage;
    private String lastFailureType;
    private String sponsorLookupStatus = "Not requested";
    private int bugReportStep = BUG_REPORT_CLOSED;
    private String bugReportDraft = "";
    private boolean bugReportIncludesDiagnostics;
    private boolean bugReportIncludesVideo;
    private boolean bugReportFromError;
    private String bugReportPreview;
    private LinkedHashMap<String, String> bugReportTechnicalSnapshot;
    private String bugReportVideoUrlSnapshot;
    private EditText activeBugReportDescription;
    private CheckBox activeBugReportDiagnostics;
    private CheckBox activeBugReportVideo;
    private SponsorSegment lastSkippedSegment;
    private ViewGroup originalPlayerParent;
    private int originalPlayerIndex;
    private ViewGroup.LayoutParams originalPlayerLayoutParams;
    private boolean updateNotificationsWereAllowed;
    private AlertDialog activeSettingsDialog;
    private TextView activeUpdateCheckDetail;
    private Button activeUpdateCheckAction;
    private volatile boolean destroyed;
    private boolean observedTorEnabled;
    private int observedTorPort;
    private final Runnable networkRouteListener =
            () -> mainHandler.post(this::synchronizeNetworkRoute);

    private final Runnable hideSkipNotice = () -> {
        if (skipNotice != null) {
            skipNotice.animate()
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction(() -> skipNotice.setVisibility(View.GONE))
                    .start();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();

        preferenceStore = new PreferenceStore(this);
        observedTorEnabled = AppNetwork.isTorEnabled();
        observedTorPort = AppNetwork.torPort();
        updatePreferences = new UpdatePreferences(this);
        updateNotificationsWereAllowed =
                UpdateNotificationManager.notificationsAllowed(this);
        sponsorBlockClient = new SponsorBlockClient();
        initializeOfflineMedia();
        initializeSubscriptions();
        buildPlayer();
        setContentView(buildUi());
        applySystemInsets();
        configureBackNavigation();

        boolean handledUpdateIntent = handleUpdateIntent(getIntent());
        if (savedInstanceState != null) {
            String savedSourceUrl = savedInstanceState.getString(
                    STATE_SOURCE_URL,
                    savedInstanceState.getString(STATE_URL)
            );
            String savedVideoId = savedInstanceState.getString(
                    STATE_CURRENT_VIDEO_ID
            );
            int savedPlaylistIndex = savedInstanceState.getInt(
                    STATE_PLAYLIST_INDEX,
                    -1
            );
            pendingSeekMs = PlaybackSessionState.restoredPosition(
                    savedInstanceState.getLong(
                            STATE_POSITION,
                            PlaybackSessionState.NO_PENDING_SEEK
                    )
            );
            playbackSessionState.requestPlayWhenReady(savedInstanceState.getBoolean(
                    STATE_PLAY_WHEN_READY,
                    true
            ));
            YouTubeUrlParser.PlayableLink savedLink =
                    YouTubeUrlParser.parsePlayable(savedSourceUrl);
            if (savedLink != null) {
                pendingRestoreVideoId = savedVideoId == null
                        ? savedLink.getVideoId()
                        : savedVideoId;
                pendingRestorePlaylistIndex = savedPlaylistIndex;
                linkInput.setText(savedLink.getCanonicalUrl());
                startPlayable(
                        savedLink,
                        savedPlaylistIndex,
                        savedVideoId,
                        PlaybackStartReason.RESTORE
                );
            }
        } else if (!handledUpdateIntent && !handleIntent(getIntent())) {
            updateProtectionText();
        }
        restoreBugReport(savedInstanceState);
        mainHandler.post(() -> maybeExplainUpdateNotifications(getIntent()));
        AppNetwork.addRouteChangeListener(networkRouteListener);
    }

    private void configureBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (fullscreen) {
                    setFullscreen(false);
                } else {
                    finishAfterTransition();
                }
            }
        });
    }

    private void configureWindow() {
        PlaybackProtection.protectActivity(this);
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(getColor(R.color.ink));
    }

    private void initializeOfflineMedia() {
        offlineSecurityPolicy = new OfflineSecurityPolicy();
        try {
            offlineMediaStore = OfflineMediaStore.forApplication(this);
            offlineMediaStore.cleanupPartials();
            offlineDownloadManager = new OfflineDownloadManager(offlineMediaStore);
        } catch (Exception exception) {
            offlineMediaStore = null;
            offlineDownloadManager = null;
            offlineInitializationFailure = exception.getClass().getSimpleName();
        }
    }

    private void initializeSubscriptions() {
        try {
            subscriptionStore = SubscriptionStore.forApplication(this);
        } catch (IOException exception) {
            subscriptionStore = null;
        }
        subscriptionPanel = new SubscriptionPanel(this, subscriptionStore,
                new SubscriptionPanel.Host() {
                    @Override
                    public void playVideo(String publicVideoUrl) {
                        YouTubeUrlParser.PlayableLink link =
                                YouTubeUrlParser.parsePlayable(publicVideoUrl);
                        if (link != null) {
                            linkInput.setText(link.getCanonicalUrl());
                            startPlayable(link, -1, null, PlaybackStartReason.USER);
                        }
                    }

                    @Override
                    public String offlineRestriction() {
                        OfflineSecurityPolicy.Decision decision = offlineSecurityDecision();
                        if (!decision.isAllowed()) {
                            return decision.getMessage();
                        }
                        return offlineMediaStore == null
                                ? getString(R.string.offline_integrity_error) : null;
                    }

                    @Override
                    public void onAutoDownloadPreferencesChanged() {
                        SubscriptionDownloadScheduler.cancelIfIdle(MainActivity.this);
                        SubscriptionDownloadScheduler.schedulePending(MainActivity.this);
                    }

                    @Override
                    public void requestAutomaticDownloads() {
                        SubscriptionDownloadScheduler.schedulePending(MainActivity.this);
                        Toast.makeText(MainActivity.this,
                                R.string.subscription_downloads_scheduled,
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onFeedChanged() {
                        SubscriptionDownloadScheduler.schedulePending(MainActivity.this);
                    }
                });
    }

    private void buildPlayer() {
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_NONE)
                        .build(),
                true
        );
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    loadingIndicator.setVisibility(View.GONE);
                    playbackStatus.setText(statusForReadyVideo());
                    if (PlaybackSessionState.hasPendingSeek(pendingSeekMs)) {
                        player.seekTo(pendingSeekMs);
                        pendingSeekMs = PlaybackSessionState.NO_PENDING_SEEK;
                    }
                    player.setPlayWhenReady(
                            playbackSessionState.shouldPlayWhenReady()
                    );
                } else if (playbackState == Player.STATE_BUFFERING) {
                    playbackStatus.setText("Buffering…");
                } else if (playbackState == Player.STATE_ENDED) {
                    handlePlaybackEnded();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                playerView.setKeepScreenOn(isPlaying);
            }

            @Override
            public void onPlayWhenReadyChanged(
                    boolean playWhenReady,
                    int reason
            ) {
                playbackSessionState.onPlayerPlayWhenReadyChanged(playWhenReady);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                recordFailure(
                        "playback",
                        "Media3 code " + error.errorCode + " / " + deepestType(error)
                );
                if (activeOfflineRecord != null) {
                    clearActiveOfflinePlayback(false);
                    showError(getString(R.string.offline_integrity_error));
                    refreshOfflineControls();
                    return;
                }
                if (!retriedAfterPlaybackFailure && activeUrl != null) {
                    retriedAfterPlaybackFailure = true;
                    pendingSeekMs = Math.max(0, player.getCurrentPosition());
                    playbackStatus.setText("Refreshing the stream…");
                    startPlayback(activeUrl, PlaybackStartReason.RETRY);
                    return;
                }
                showError(withPlaylistContinuation(humanPlaybackError(error)));
            }
        });
        playbackSourceFactory = new PlaybackSourceFactory(this);
        skipController = new SponsorSkipController(player, this::onSponsorSkipped);
    }

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.ink));
        PlaybackProtection.protectSensitiveView(root);

        appContent = new LinearLayout(this);
        appContent.setOrientation(LinearLayout.VERTICAL);
        root.addView(appContent, matchParent());

        topBar = buildTopBar();
        appContent.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
        ));

        torRoutingBanner = text(getString(R.string.tor_routing_banner), 12,
                getColor(R.color.coral));
        torRoutingBanner.setPadding(dp(18), 0, dp(18), dp(8));
        torRoutingBanner.setOnClickListener(view -> showSettings());
        appContent.addView(torRoutingBanner, matchParentWrap());
        refreshTorBanner();

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(14), dp(18), dp(32));
        scrollView.addView(body, matchParentWrap());
        appContent.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        playerContainer = buildPlayerContainer();
        body.addView(playerContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(216)
        ));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(2), dp(18), dp(2), dp(16));
        titleText = text(
                getString(R.string.play_prompt),
                22,
                getColor(R.color.text_primary)
        );
        titleText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleText.setMaxLines(2);
        uploaderText = text(
                getString(R.string.play_prompt_detail),
                14,
                getColor(R.color.text_secondary)
        );
        uploaderText.setPadding(0, dp(6), 0, 0);
        playbackStatus = text(getString(R.string.ready), 13, getColor(R.color.mint));
        playbackStatus.setPadding(0, dp(8), 0, 0);
        playbackStatus.setAccessibilityLiveRegion(
                View.ACCESSIBILITY_LIVE_REGION_POLITE
        );
        info.addView(titleText);
        info.addView(uploaderText);
        info.addView(playbackStatus);
        body.addView(info);

        playlistCard = buildPlaylistCard();
        body.addView(playlistCard, spacedCardParams());
        body.addView(buildInputCard(), spacedCardParams());
        body.addView(subscriptionPanel.buildCard(), spacedCardParams());
        body.addView(buildOfflineCard(), spacedCardParams());
        body.addView(buildProtectionCard(), spacedCardParams());

        errorCard = new LinearLayout(this);
        errorCard.setOrientation(LinearLayout.VERTICAL);
        errorCard.setBackgroundResource(R.drawable.bg_card);
        errorCard.setPadding(dp(16), dp(14), dp(10), dp(8));
        errorCard.setVisibility(View.GONE);
        errorText = text("", 14, Color.rgb(255, 165, 150));
        errorText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        errorCard.addView(errorText);
        Button reportIssue = textButton(getString(R.string.report_this_issue));
        reportIssue.setOnClickListener(view -> showBugReport(true, "", false, false));
        LinearLayout.LayoutParams reportParams = new LinearLayout.LayoutParams(wrap(), dp(42));
        reportParams.gravity = Gravity.END;
        errorCard.addView(reportIssue, reportParams);
        body.addView(errorCard, spacedCardParams());

        loadingIndicator = new ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleLarge
        );
        loadingIndicator.setIndeterminateTintList(
                android.content.res.ColorStateList.valueOf(getColor(R.color.coral))
        );
        loadingIndicator.setVisibility(View.GONE);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(
                dp(48),
                dp(48),
                Gravity.CENTER
        );
        root.addView(loadingIndicator, loadingParams);

        skipNotice = buildSkipNotice();
        FrameLayout.LayoutParams noticeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        noticeParams.setMargins(dp(16), 0, dp(16), dp(22));
        root.addView(skipNotice, noticeParams);

        return root;
    }

    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), 0, dp(10), 0);
        bar.setBackgroundColor(getColor(R.color.ink));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_shield_play);
        logo.setContentDescription(getString(R.string.app_name));
        bar.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(10), 0, 0, 0);
        TextView name = text(getString(R.string.app_name), 19, getColor(R.color.text_primary));
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView tagline = text(
                getString(R.string.app_tagline),
                10,
                getColor(R.color.text_secondary)
        );
        brand.addView(name);
        brand.addView(tagline);
        bar.addView(brand, new LinearLayout.LayoutParams(0, wrap(), 1f));

        protectionStatus = text("PROTECTED", 11, getColor(R.color.mint));
        protectionStatus.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        protectionStatus.setBackgroundResource(R.drawable.bg_status);
        protectionStatus.setGravity(Gravity.CENTER);
        protectionStatus.setPadding(dp(12), dp(7), dp(12), dp(7));
        bar.addView(protectionStatus);

        ImageButton settings = iconButton(R.drawable.ic_settings, getString(R.string.settings));
        settings.setOnClickListener(view -> showSettings());
        bar.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return bar;
    }

    private FrameLayout buildPlayerContainer() {
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setControllerAutoShow(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setContentDescription(getString(R.string.video_player));
        PlaybackProtection.protectVideoSurface(playerView);
        playerView.setPlayer(player);
        container.addView(playerView, matchParent());

        fullscreenButton = iconButton(
                R.drawable.ic_fullscreen,
                getString(R.string.fullscreen)
        );
        fullscreenButton.setBackgroundColor(0x88000000);
        fullscreenButton.setOnClickListener(view -> setFullscreen(!fullscreen));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(46),
                dp(46),
                Gravity.TOP | Gravity.END
        );
        params.setMargins(0, dp(8), dp(8), 0);
        container.addView(fullscreenButton, params);
        return container;
    }

    private LinearLayout buildInputCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackgroundResource(R.drawable.bg_card);

        TextView label = text(
                getString(R.string.play_video_or_playlist),
                11,
                getColor(R.color.text_secondary)
        );
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(dp(2), 0, 0, dp(10));
        card.addView(label);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        linkInput = new EditText(this);
        linkInput.setId(View.generateViewId());
        label.setLabelFor(linkInput.getId());
        linkInput.setHint(R.string.paste_link_hint);
        linkInput.setHintTextColor(getColor(R.color.text_secondary));
        linkInput.setTextColor(getColor(R.color.text_primary));
        linkInput.setTextSize(14);
        linkInput.setSingleLine(true);
        linkInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        linkInput.setPadding(dp(14), 0, dp(10), 0);
        linkInput.setBackgroundResource(R.drawable.bg_input);
        linkInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                playInput();
                return true;
            }
            return false;
        });
        row.addView(linkInput, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Button play = primaryButton(getString(R.string.play));
        play.setOnClickListener(view -> playInput());
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(82), dp(52));
        playParams.setMargins(dp(10), 0, 0, 0);
        row.addView(play, playParams);
        card.addView(row);

        Button paste = textButton(getString(R.string.paste));
        paste.setOnClickListener(view -> pasteClipboard());
        LinearLayout.LayoutParams pasteParams = new LinearLayout.LayoutParams(wrap(), dp(42));
        pasteParams.gravity = Gravity.END;
        card.addView(paste, pasteParams);
        return card;
    }

    private LinearLayout buildOfflineCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(14), dp(14));
        card.setBackgroundResource(R.drawable.bg_card);

        TextView heading = text(
                getString(R.string.offline_section),
                11,
                getColor(R.color.text_secondary)
        );
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(heading);

        TextView detail = text(
                getString(R.string.offline_detail),
                13,
                getColor(R.color.text_primary)
        );
        detail.setPadding(0, dp(8), 0, 0);
        card.addView(detail);

        offlineStatusText = text(
                getString(R.string.offline_waiting_for_video),
                12,
                getColor(R.color.text_secondary)
        );
        offlineStatusText.setPadding(0, dp(8), 0, 0);
        offlineStatusText.setAccessibilityLiveRegion(
                View.ACCESSIBILITY_LIVE_REGION_POLITE
        );
        card.addView(offlineStatusText);

        offlineProgress = new ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
        );
        offlineProgress.setMax(100);
        offlineProgress.setProgressTintList(
                android.content.res.ColorStateList.valueOf(getColor(R.color.coral))
        );
        offlineProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(8)
        );
        progressParams.setMargins(0, dp(10), 0, dp(4));
        card.addView(offlineProgress, progressParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        offlineLibraryButton = textButton(getString(R.string.offline_downloads));
        offlineLibraryButton.setOnClickListener(
                view -> requestOfflineCredential(
                        PendingOfflineAction.SHOW_LIBRARY,
                        null
                )
        );
        actions.addView(
                offlineLibraryButton,
                new LinearLayout.LayoutParams(wrap(), dp(46))
        );

        offlineSaveButton = primaryButton(getString(R.string.save_offline));
        offlineSaveButton.setOnClickListener(view -> promptOfflineSave());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                wrap(),
                dp(46)
        );
        saveParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(offlineSaveButton, saveParams);
        card.addView(actions);

        refreshOfflineControls();
        return card;
    }

    private LinearLayout buildPlaylistCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(12), dp(10));
        card.setBackgroundResource(R.drawable.bg_card);
        card.setVisibility(View.GONE);

        TextView label = text(
                getString(R.string.playlist),
                11,
                getColor(R.color.text_secondary)
        );
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(label);

        playlistNameText = text("", 17, getColor(R.color.text_primary));
        playlistNameText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        playlistNameText.setPadding(0, dp(7), 0, 0);
        playlistNameText.setMaxLines(2);
        card.addView(playlistNameText);

        playlistPositionText = text("", 13, getColor(R.color.text_secondary));
        playlistPositionText.setPadding(0, dp(5), 0, 0);
        playlistPositionText.setAccessibilityLiveRegion(
                View.ACCESSIBILITY_LIVE_REGION_POLITE
        );
        card.addView(playlistPositionText);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        controls.setPadding(0, dp(3), 0, 0);

        playlistPreviousButton = textButton(getString(R.string.previous_video));
        playlistPreviousButton.setOnClickListener(view -> playPreviousPlaylistItem());
        controls.addView(
                playlistPreviousButton,
                new LinearLayout.LayoutParams(wrap(), dp(48))
        );

        playlistNextButton = textButton(getString(R.string.next_video));
        playlistNextButton.setOnClickListener(view -> playNextPlaylistItem());
        controls.addView(
                playlistNextButton,
                new LinearLayout.LayoutParams(wrap(), dp(48))
        );

        card.addView(controls);
        return card;
    }

    private LinearLayout buildProtectionCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.TOP);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackgroundResource(R.drawable.bg_card);

        ImageView shield = new ImageView(this);
        shield.setImageResource(R.drawable.ic_shield_play);
        card.addView(shield, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(14), 0, 0, 0);
        TextView heading = text(
                getString(R.string.protection_active),
                17,
                getColor(R.color.text_primary)
        );
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView direct = text(
                "✓  " + getString(R.string.direct_stream_detail),
                13,
                getColor(R.color.text_secondary)
        );
        direct.setPadding(0, dp(8), 0, 0);
        TextView sponsor = text(
                "✓  " + getString(R.string.sponsor_detail),
                13,
                getColor(R.color.text_secondary)
        );
        sponsor.setPadding(0, dp(5), 0, 0);
        copy.addView(heading);
        copy.addView(direct);
        copy.addView(sponsor);
        card.addView(copy, new LinearLayout.LayoutParams(0, wrap(), 1f));
        return card;
    }

    private LinearLayout buildSkipNotice() {
        LinearLayout notice = new LinearLayout(this);
        notice.setOrientation(LinearLayout.HORIZONTAL);
        notice.setGravity(Gravity.CENTER_VERTICAL);
        notice.setPadding(dp(16), dp(10), dp(8), dp(10));
        notice.setBackgroundResource(R.drawable.bg_card);
        notice.setVisibility(View.GONE);

        skipNoticeText = text("Sponsor skipped", 14, getColor(R.color.text_primary));
        notice.addView(skipNoticeText, new LinearLayout.LayoutParams(0, wrap(), 1f));
        Button undo = textButton(getString(R.string.undo));
        undo.setTextColor(getColor(R.color.mint));
        undo.setOnClickListener(view -> undoSponsorSkip());
        notice.addView(undo, new LinearLayout.LayoutParams(wrap(), dp(44)));
        return notice;
    }

    private OfflineSecurityPolicy.Decision offlineSecurityDecision() {
        if (offlineSecurityPolicy == null) {
            return OfflineSecurityPolicy.Decision.forReason(
                    OfflineSecurityPolicy.Reason.STRONGBOX_REQUIRED
            );
        }
        return offlineSecurityPolicy.evaluate(this);
    }

    private void refreshOfflineControls() {
        if (offlineSaveButton == null
                || offlineLibraryButton == null
                || offlineStatusText == null
                || offlineDownloadInProgress) {
            return;
        }

        OfflineSecurityPolicy.Decision security = offlineSecurityDecision();
        boolean storeReady = offlineMediaStore != null
                && offlineDownloadManager != null
                && offlineInitializationFailure == null;
        boolean secure = storeReady && security.isAllowed();
        offlineLibraryButton.setEnabled(secure);

        if (!storeReady) {
            offlineSaveButton.setEnabled(false);
            offlineStatusText.setText(getString(
                    R.string.offline_security_unavailable,
                    "private vault initialization failed"
            ));
            offlineStatusText.setTextColor(getColor(R.color.coral));
            return;
        }
        if (!security.isAllowed()) {
            offlineSaveButton.setEnabled(false);
            offlineStatusText.setText(getString(
                    R.string.offline_security_unavailable,
                    security.getMessage()
            ));
            offlineStatusText.setTextColor(getColor(R.color.coral));
            return;
        }
        if (activeOfflineRecord != null && offlinePlaybackSession != null) {
            offlineSaveButton.setEnabled(false);
            offlineStatusText.setText(R.string.offline_playback_ready);
            offlineStatusText.setTextColor(getColor(R.color.mint));
            return;
        }
        if (activeVideo == null) {
            offlineSaveButton.setEnabled(false);
            offlineStatusText.setText(R.string.offline_waiting_for_video);
            offlineStatusText.setTextColor(getColor(R.color.text_secondary));
            return;
        }

        OfflineDownloadEligibility.Decision eligibility =
                OfflineDownloadEligibility.evaluate(activeVideo);
        offlineSaveButton.setEnabled(eligibility.isAllowed());
        if (eligibility.isAllowed()) {
            offlineStatusText.setText(R.string.offline_ready_to_save);
            offlineStatusText.setTextColor(getColor(R.color.mint));
        } else {
            offlineStatusText.setText(
                    eligibility.getReason()
                            == OfflineDownloadEligibility.Reason
                            .LIVE_OR_SEGMENTED_STREAM
                            ? R.string.offline_segmented_unsupported
                            : R.string.offline_download_failed
            );
            offlineStatusText.setTextColor(getColor(R.color.coral));
        }
    }

    private void promptOfflineSave() {
        if (activeVideo == null
                || !OfflineDownloadEligibility.evaluate(activeVideo).isAllowed()
                || offlineDownloadInProgress) {
            refreshOfflineControls();
            return;
        }
        ResolvedVideo offeredVideo = activeVideo;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.offline_rights_title)
                .setMessage(R.string.offline_rights_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        R.string.offline_confirm_save,
                        (ignoredDialog, ignoredWhich) -> {
                            if (activeVideo != offeredVideo) {
                                Toast.makeText(
                                        this,
                                        R.string.offline_waiting_for_video,
                                        Toast.LENGTH_LONG
                                ).show();
                                return;
                            }
                            requestOfflineCredential(
                                    PendingOfflineAction.DOWNLOAD_CURRENT,
                                    null
                            );
                        }
                )
                .create();
        dialog.setOnShowListener(ignored -> dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(getColor(R.color.coral)));
        dialog.show();
    }

    private void requestOfflineCredential(
            PendingOfflineAction action,
            UUID itemId
    ) {
        if (action == PendingOfflineAction.NONE || !isOfflineForeground()) {
            return;
        }
        OfflineSecurityPolicy.Decision security = offlineSecurityDecision();
        if (offlineMediaStore == null || !security.isAllowed()) {
            showOfflineUnavailable(
                    offlineMediaStore == null
                            ? "Private vault initialization failed."
                            : security.getMessage()
            );
            return;
        }

        KeyguardManager keyguardManager =
                (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        Intent credentialIntent = keyguardManager == null
                ? null
                : keyguardManager.createConfirmDeviceCredentialIntent(
                        getString(R.string.offline_auth_title),
                        getString(R.string.offline_auth_message)
                );
        if (credentialIntent == null) {
            Toast.makeText(
                    this,
                    R.string.offline_auth_unavailable,
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        pendingOfflineAction = action;
        pendingOfflineItemId = itemId;
        pendingOfflineVideo = action == PendingOfflineAction.DOWNLOAD_CURRENT
                ? activeVideo
                : null;
        if (action == PendingOfflineAction.DOWNLOAD_CURRENT
                && pendingOfflineVideo == null) {
            clearPendingOfflineCredential();
            return;
        }
        awaitingOfflineCredential = true;
        offlineCredentialApproved = false;
        try {
            startActivityForResult(
                    credentialIntent,
                    REQUEST_OFFLINE_CREDENTIAL
            );
        } catch (RuntimeException exception) {
            clearPendingOfflineCredential();
            Toast.makeText(
                    this,
                    R.string.offline_auth_unavailable,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void performApprovedOfflineActionIfResumed() {
        if (!offlineCredentialApproved
                || pendingOfflineAction == PendingOfflineAction.NONE
                || !isOfflineForeground()) {
            return;
        }
        OfflineSecurityPolicy.Decision security = offlineSecurityDecision();
        if (offlineMediaStore == null || !security.isAllowed()) {
            clearPendingOfflineCredential();
            showOfflineUnavailable(
                    offlineMediaStore == null
                            ? "Private vault initialization failed."
                            : security.getMessage()
            );
            return;
        }
        offlineCredentialApproved = false;
        performPendingOfflineAction();
    }

    private void performPendingOfflineAction() {
        PendingOfflineAction action = pendingOfflineAction;
        UUID itemId = pendingOfflineItemId;
        ResolvedVideo video = pendingOfflineVideo;
        pendingOfflineAction = PendingOfflineAction.NONE;
        pendingOfflineItemId = null;
        pendingOfflineVideo = null;
        switch (action) {
            case DOWNLOAD_CURRENT:
                startOfflineDownload(video);
                break;
            case SHOW_LIBRARY:
                loadOfflineLibrary();
                break;
            case PLAY_ITEM:
                if (itemId != null) {
                    openOfflineItem(itemId);
                }
                break;
            case NONE:
            default:
                break;
        }
    }

    private void clearPendingOfflineCredential() {
        awaitingOfflineCredential = false;
        offlineCredentialApproved = false;
        pendingOfflineAction = PendingOfflineAction.NONE;
        pendingOfflineItemId = null;
        pendingOfflineVideo = null;
    }

    private void startOfflineDownload(ResolvedVideo video) {
        if (offlineDownloadInProgress
                || offlineDownloadManager == null
                || video == null
                || activeVideo != video
                || !isOfflineForeground()
                || !offlineSecurityDecision().isAllowed()
                || !OfflineDownloadEligibility.evaluate(video).isAllowed()) {
            refreshOfflineControls();
            return;
        }

        int foregroundGeneration = offlineForegroundGeneration.get();
        OfflineDownloadManager.Cancellation cancellation =
                new OfflineDownloadManager.Cancellation();
        offlineDownloadCancellation = cancellation;
        offlineDownloadInProgress = true;
        offlineProgress.setProgress(0);
        offlineProgress.setVisibility(View.VISIBLE);
        offlineSaveButton.setText(R.string.offline_cancel);
        offlineSaveButton.setEnabled(true);
        offlineSaveButton.setOnClickListener(view -> cancelOfflineDownload());
        offlineLibraryButton.setEnabled(false);
        offlineStatusText.setText(getString(
                R.string.offline_downloading,
                formatOfflineBytes(0L)
        ));
        offlineStatusText.setTextColor(getColor(R.color.text_primary));

        activeOfflineTask = offlineExecutor.submit(() -> {
            OfflineMediaRecord record = null;
            Throwable failure = null;
            boolean cancelled = false;
            try {
                record = offlineDownloadManager.download(
                        video,
                        cancellation,
                        (track, downloaded, total) -> mainHandler.post(
                                () -> updateOfflineDownloadProgress(
                                        foregroundGeneration,
                                        cancellation,
                                        track,
                                        downloaded,
                                        total
                                )
                        )
                );
            } catch (OfflineDownloadManager.DownloadCancelledException exception) {
                cancelled = true;
            } catch (Throwable exception) {
                failure = exception;
            }
            OfflineMediaRecord completedRecord = record;
            Throwable completedFailure = failure;
            boolean wasCancelled = cancelled;
            mainHandler.post(() -> finishOfflineDownload(
                    cancellation,
                    completedRecord,
                    completedFailure,
                    wasCancelled,
                    foregroundGeneration
            ));
        });
    }

    private void updateOfflineDownloadProgress(
            int foregroundGeneration,
            OfflineDownloadManager.Cancellation cancellation,
            OfflineDownloadManager.Track track,
            long downloaded,
            long total
    ) {
        if (destroyed
                || cancellation != offlineDownloadCancellation
                || !offlineDownloadInProgress
                || !isOfflineForeground(foregroundGeneration)) {
            return;
        }
        int percent = total <= 0L
                ? 0
                : (int) Math.min(
                        100L,
                        Math.round((downloaded * 100.0d) / total)
                );
        offlineProgress.setProgress(percent);
        String trackName = switch (track) {
            case PROGRESSIVE -> "video";
            case VIDEO -> "video track";
            case AUDIO -> "audio track";
        };
        offlineStatusText.setText(getString(
                R.string.offline_downloading_track,
                trackName,
                formatOfflineBytes(downloaded)
        ));
    }

    private void cancelOfflineDownload() {
        OfflineDownloadManager.Cancellation cancellation =
                offlineDownloadCancellation;
        if (cancellation == null) {
            return;
        }
        cancellation.cancel();
        if (offlineSaveButton != null) {
            offlineSaveButton.setEnabled(false);
        }
    }

    private void finishOfflineDownload(
            OfflineDownloadManager.Cancellation cancellation,
            OfflineMediaRecord record,
            Throwable failure,
            boolean cancelled,
            int foregroundGeneration
    ) {
        if (cancellation != offlineDownloadCancellation) {
            return;
        }
        offlineDownloadCancellation = null;
        activeOfflineTask = null;
        offlineDownloadInProgress = false;
        if (!isOfflineForeground(foregroundGeneration)) {
            return;
        }

        offlineProgress.setVisibility(View.GONE);
        offlineSaveButton.setText(R.string.save_offline);
        offlineSaveButton.setOnClickListener(view -> promptOfflineSave());
        offlineLibraryButton.setEnabled(offlineSecurityDecision().isAllowed());
        offlineSaveButton.setEnabled(
                activeVideo != null
                        && OfflineDownloadEligibility.evaluate(activeVideo).isAllowed()
                        && offlineSecurityDecision().isAllowed()
        );
        if (record != null) {
            offlineStatusText.setText(R.string.offline_download_complete);
            offlineStatusText.setTextColor(getColor(R.color.mint));
            return;
        }
        if (cancelled || cancellation.isCancelled()) {
            offlineStatusText.setText(R.string.offline_download_cancelled);
            offlineStatusText.setTextColor(getColor(R.color.text_secondary));
            return;
        }

        recordFailure(
                "offline download",
                failure == null ? "Unknown" : deepestType(failure)
        );
        offlineStatusText.setText(R.string.offline_download_failed);
        offlineStatusText.setTextColor(getColor(R.color.coral));
    }

    private String formatOfflineBytes(long bytes) {
        double kibibytes = Math.max(0L, bytes) / 1024.0d;
        if (kibibytes < 1024.0d) {
            return getString(R.string.offline_bytes_kb, kibibytes);
        }
        double mebibytes = kibibytes / 1024.0d;
        if (mebibytes < 1024.0d) {
            return getString(R.string.offline_bytes_mb, mebibytes);
        }
        return getString(R.string.offline_bytes_gb, mebibytes / 1024.0d);
    }

    private void loadOfflineLibrary() {
        if (offlineMediaStore == null
                || offlineDownloadInProgress
                || !isOfflineForeground()
                || !offlineSecurityDecision().isAllowed()) {
            return;
        }
        int foregroundGeneration = offlineForegroundGeneration.get();
        offlineStatusText.setText(R.string.offline_library_loading);
        offlineStatusText.setTextColor(getColor(R.color.text_secondary));
        offlineLibraryButton.setEnabled(false);
        activeOfflineTask = offlineExecutor.submit(() -> {
            OfflineMediaStore.ListResult result = null;
            Throwable failure = null;
            try {
                result = offlineMediaStore.list(
                        () -> isOfflineAccessCurrent(foregroundGeneration)
                );
            } catch (Throwable exception) {
                failure = exception;
            }
            OfflineMediaStore.ListResult completedResult = result;
            Throwable completedFailure = failure;
            mainHandler.post(() -> {
                if (!isOfflineForeground(foregroundGeneration)) {
                    return;
                }
                activeOfflineTask = null;
                offlineLibraryButton.setEnabled(
                        offlineSecurityDecision().isAllowed()
                );
                if (completedResult == null) {
                    showOfflineOperationFailure(completedFailure);
                    return;
                }
                showOfflineLibrary(completedResult);
                refreshOfflineControls();
            });
        });
    }

    private void showOfflineLibrary(OfflineMediaStore.ListResult result) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView policy = text(
                getString(R.string.offline_library_policy),
                12,
                getColor(R.color.text_secondary)
        );
        policy.setPadding(0, 0, 0, dp(8));
        content.addView(policy);

        if (result.getRecords().isEmpty()) {
            TextView empty = text(
                    getString(R.string.offline_library_empty),
                    14,
                    getColor(R.color.text_secondary)
            );
            empty.setPadding(0, dp(8), 0, dp(8));
            content.addView(empty);
        }
        if (result.getCorruptCount() > 0) {
            TextView corrupt = text(
                    getString(
                            R.string.offline_library_corrupt,
                            result.getCorruptCount()
                    ),
                    12,
                    getColor(R.color.coral)
            );
            corrupt.setPadding(0, dp(8), 0, dp(8));
            content.addView(corrupt);
        }

        AlertDialog[] dialogHolder = new AlertDialog[1];
        for (OfflineMediaRecord record : result.getRecords()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));

            TextView itemTitle = text(
                    record.getTitle(),
                    15,
                    getColor(R.color.text_primary)
            );
            itemTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            itemTitle.setMaxLines(2);
            row.addView(itemTitle);

            long storedPlaintextBytes = record.getVideoPlaintextLength()
                    + record.getAudioPlaintextLength();
            String quality = record.getSelectedHeight() > 0
                    ? record.getSelectedHeight() + "p • "
                    : "";
            String uploader = record.getUploader().isEmpty()
                    ? getString(R.string.offline_unknown_uploader)
                    : record.getUploader();
            TextView itemDetail = text(
                    uploader + " • " + quality
                            + formatOfflineBytes(storedPlaintextBytes),
                    12,
                    getColor(R.color.text_secondary)
            );
            itemDetail.setPadding(0, dp(4), 0, 0);
            row.addView(itemDetail);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.END);
            Button delete = textButton(getString(R.string.offline_delete));
            delete.setOnClickListener(view -> {
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                confirmDeleteOfflineItem(record);
            });
            actions.addView(delete, new LinearLayout.LayoutParams(wrap(), dp(44)));
            Button play = primaryButton(getString(R.string.offline_play));
            play.setOnClickListener(view -> {
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                requestOfflineCredential(
                        PendingOfflineAction.PLAY_ITEM,
                        record.getItemId()
                );
            });
            LinearLayout.LayoutParams playParams =
                    new LinearLayout.LayoutParams(wrap(), dp(44));
            playParams.setMargins(dp(8), 0, 0, 0);
            actions.addView(play, playParams);
            row.addView(actions);
            content.addView(row);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, matchParentWrap());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.offline_library_title)
                .setView(scroll)
                .setNeutralButton(
                        R.string.offline_reset,
                        (ignoredDialog, ignoredWhich) ->
                                confirmResetOfflineVault()
                )
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialogHolder[0] = dialog;
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(getColor(R.color.coral));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setTextColor(getColor(R.color.text_secondary));
        });
        dialog.show();
    }

    private void confirmDeleteOfflineItem(OfflineMediaRecord record) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.offline_delete_title)
                .setMessage(R.string.offline_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        R.string.offline_delete_confirm,
                        (ignoredDialog, ignoredWhich) ->
                                deleteOfflineItem(record.getItemId())
                )
                .create();
        dialog.setOnShowListener(ignored -> dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(getColor(R.color.coral)));
        dialog.show();
    }

    private void deleteOfflineItem(UUID itemId) {
        if (activeOfflineRecord != null
                && activeOfflineRecord.getItemId().equals(itemId)) {
            clearActiveOfflinePlayback(false);
        }
        offlineExecutor.execute(() -> {
            boolean deleted = false;
            try {
                deleted = offlineMediaStore != null
                        && offlineMediaStore.delete(itemId);
            } catch (Exception ignored) {
                // The user-facing result is intentionally generic.
            }
            boolean completed = deleted;
            mainHandler.post(() -> {
                if (!destroyed) {
                    Toast.makeText(
                            this,
                            completed
                                    ? R.string.offline_delete_complete
                                    : R.string.offline_delete_failed,
                            Toast.LENGTH_LONG
                    ).show();
                    refreshOfflineControls();
                }
            });
        });
    }

    private void confirmResetOfflineVault() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.offline_reset_title)
                .setMessage(R.string.offline_reset_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        R.string.offline_reset_confirm,
                        (ignoredDialog, ignoredWhich) -> resetOfflineVault()
                )
                .create();
        dialog.setOnShowListener(ignored -> dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(getColor(R.color.coral)));
        dialog.show();
    }

    private void resetOfflineVault() {
        cancelOfflineDownload();
        clearActiveOfflinePlayback(false);
        offlineExecutor.execute(() -> {
            boolean reset = false;
            try {
                SubscriptionDownloadScheduler.runWithDownloadsPaused(this, () -> {
                    if (subscriptionStore != null) {
                        synchronized (subscriptionStore) {
                            for (ChannelSubscription channel : subscriptionStore.getSubscriptions()) {
                                subscriptionStore.setAutoDownload(channel.getChannelId(), false);
                            }
                            if (offlineMediaStore != null) {
                                offlineMediaStore.reset();
                            }
                        }
                    } else if (offlineMediaStore != null) {
                        offlineMediaStore.reset();
                    }
                });
                reset = offlineMediaStore != null;
            } catch (Exception ignored) {
                // The user-facing result is intentionally generic.
            }
            boolean completed = reset;
            mainHandler.post(() -> {
                if (!destroyed) {
                    Toast.makeText(
                            this,
                            completed
                                    ? R.string.offline_reset_complete
                                    : R.string.offline_delete_failed,
                            Toast.LENGTH_LONG
                    ).show();
                    refreshOfflineControls();
                    subscriptionPanel.render();
                }
            });
        });
    }

    private void showOfflineUnavailable(String reason) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.offline_library_title)
                .setMessage(getString(
                        R.string.offline_security_unavailable,
                        reason
                ))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(
                        R.string.offline_reset,
                        (ignoredDialog, ignoredWhich) ->
                                confirmResetOfflineVault()
                )
                .create();
        dialog.setOnShowListener(ignored -> dialog
                .getButton(AlertDialog.BUTTON_NEUTRAL)
                .setTextColor(getColor(R.color.coral)));
        dialog.show();
    }

    private void showOfflineOperationFailure(Throwable failure) {
        recordFailure(
                "offline storage",
                failure == null ? "Unknown" : deepestType(failure)
        );
        offlineStatusText.setText(R.string.offline_integrity_error);
        offlineStatusText.setTextColor(getColor(R.color.coral));
        showOfflineUnavailable(getString(R.string.offline_integrity_error));
    }

    private void openOfflineItem(UUID itemId) {
        if (offlineMediaStore == null
                || !isOfflineForeground()
                || !offlineSecurityDecision().isAllowed()) {
            return;
        }
        int foregroundGeneration = offlineForegroundGeneration.get();
        offlineStatusText.setText(R.string.offline_playback_preparing);
        offlineStatusText.setTextColor(getColor(R.color.text_secondary));
        activeOfflineTask = offlineExecutor.submit(() -> {
            OfflineMediaStore.PlaybackSession session = null;
            Throwable failure = null;
            try {
                session = offlineMediaStore.open(
                        itemId,
                        () -> isOfflineAccessCurrent(foregroundGeneration)
                );
            } catch (Throwable exception) {
                failure = exception;
            }
            OfflineMediaStore.PlaybackSession openedSession = session;
            Throwable completedFailure = failure;
            mainHandler.post(() -> {
                if (!isOfflineForeground(foregroundGeneration)) {
                    if (openedSession != null) {
                        openedSession.close();
                    }
                    return;
                }
                activeOfflineTask = null;
                if (openedSession == null) {
                    showOfflineOperationFailure(completedFailure);
                    return;
                }
                beginOfflinePlayback(openedSession);
            });
        });
    }

    private void beginOfflinePlayback(
            OfflineMediaStore.PlaybackSession session
    ) {
        if (!isOfflineForeground()
                || !offlineSecurityDecision().isAllowed()) {
            session.close();
            return;
        }
        OfflineMediaRecord record = session.getRecord();
        int generation = loadGeneration.incrementAndGet();
        playlistLoadGeneration.incrementAndGet();
        cancelTask(activeVideoResolveTask);
        cancelTask(activePlaylistResolveTask);
        activeVideoResolveTask = null;
        activePlaylistResolveTask = null;

        clearActiveOfflinePlayback(false);
        player.stop();
        player.clearMediaItems();
        playlistQueue = null;
        playlistCard.setVisibility(View.GONE);
        activeUrl = null;
        sourceUrl = null;
        activeVideo = null;
        activeOfflineRecord = record;
        offlinePlaybackSession = session;
        selectedMediaPrepared = false;
        handledEndGeneration = -1;
        preparedPlaybackGeneration = generation;
        pendingSeekMs = PlaybackSessionState.NO_PENDING_SEEK;
        pendingRestoreVideoId = null;
        pendingRestorePlaylistIndex = -1;
        playbackSessionState.requestPlayWhenReady(true);
        retriedAfterPlaybackFailure = false;
        clearFailure();
        errorCard.setVisibility(View.GONE);
        loadingIndicator.setVisibility(View.VISIBLE);
        titleText.setText(record.getTitle());
        if (record.getUploader().isEmpty()) {
            uploaderText.setText(R.string.offline_unknown_uploader);
        } else {
            uploaderText.setText(record.getUploader());
        }
        playbackStatus.setText(R.string.offline_playback_preparing);
        resetItemProtection();
        fetchSponsorSegments(record.getVideoId(), generation);

        try {
            MediaSource source = playbackSourceFactory.createOffline(session);
            player.setMediaSource(source);
            selectedMediaPrepared = true;
            player.setPlayWhenReady(playbackSessionState.shouldPlayWhenReady());
            player.prepare();
            offlineStatusText.setText(R.string.offline_playback_ready);
            offlineStatusText.setTextColor(getColor(R.color.mint));
        } catch (Exception exception) {
            recordFailure("offline playback", deepestType(exception));
            clearActiveOfflinePlayback(false);
            showError(getString(R.string.offline_integrity_error));
        }
        refreshOfflineControls();
    }

    private void clearActiveOfflinePlayback(boolean locked) {
        OfflineMediaStore.PlaybackSession session = offlinePlaybackSession;
        if (session == null && activeOfflineRecord == null) {
            return;
        }
        if (session != null) {
            player.stop();
            player.clearMediaItems();
            session.close();
        }
        offlinePlaybackSession = null;
        selectedMediaPrepared = false;
        preparedPlaybackGeneration = -1;
        if (locked && activeOfflineRecord != null) {
            if (offlineStatusText != null) {
                offlineStatusText.setText(R.string.offline_playback_locked);
                offlineStatusText.setTextColor(getColor(R.color.coral));
            }
            if (playbackStatus != null) {
                playbackStatus.setText(R.string.offline_playback_locked);
            }
        } else {
            activeOfflineRecord = null;
        }
    }

    private void playInput() {
        YouTubeUrlParser.PlayableLink link =
                YouTubeUrlParser.parsePlayable(linkInput.getText().toString());
        if (link == null) {
            recordFailure("link validation", "Invalid or unsupported YouTube link");
            showError(getString(R.string.invalid_link));
            return;
        }
        hideKeyboard();
        linkInput.setText(link.getCanonicalUrl());
        startPlayable(link, -1, null, PlaybackStartReason.USER);
    }

    private void pasteClipboard() {
        ClipboardManager manager =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData data = manager.getPrimaryClip();
        if (data == null || data.getItemCount() == 0) {
            Toast.makeText(this, "Clipboard is empty.", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence text = data.getItemAt(0).coerceToText(this);
        if (text != null) {
            linkInput.setText(text.toString());
            linkInput.setSelection(linkInput.length());
        }
    }

    private void startPlayable(
            YouTubeUrlParser.PlayableLink link,
            int requestedPlaylistIndex,
            String requestedVideoId,
            PlaybackStartReason reason
    ) {
        cancelOfflineDownload();
        clearPendingOfflineCredential();
        sourceUrl = link.getCanonicalUrl();
        if (link.isPlaylist()) {
            String preferredVideoId = requestedVideoId == null
                    ? link.getVideoId()
                    : requestedVideoId;
            int preferredIndex = PlaybackSessionState.anchoredPlaylistIndex(
                    requestedPlaylistIndex,
                    link.getPlaylistIndex(),
                    preferredVideoId
            );
            startPlaylist(link, preferredIndex, preferredVideoId, reason);
            return;
        }

        playlistLoadGeneration.incrementAndGet();
        cancelTask(activePlaylistResolveTask);
        activePlaylistResolveTask = null;
        playlistQueue = null;
        playlistCard.setVisibility(View.GONE);
        startPlayback(link.getCanonicalUrl(), reason);
    }

    private void startPlaylist(
            YouTubeUrlParser.PlayableLink link,
            int preferredIndex,
            String preferredVideoId,
            PlaybackStartReason reason
    ) {
        cancelOfflineDownload();
        clearPendingOfflineCredential();
        int playlistGeneration = playlistLoadGeneration.incrementAndGet();
        int invalidatedVideoGeneration = loadGeneration.incrementAndGet();
        handledEndGeneration = invalidatedVideoGeneration;
        cancelTask(activePlaylistResolveTask);
        cancelTask(activeVideoResolveTask);
        activePlaylistResolveTask = null;
        activeVideoResolveTask = null;

        clearActiveOfflinePlayback(false);
        playlistQueue = null;
        activeUrl = null;
        activeVideo = null;
        refreshOfflineControls();
        selectedMediaPrepared = false;
        retriedAfterPlaybackFailure = false;
        player.stop();
        resetItemProtection();
        if (reason == PlaybackStartReason.RESTORE) {
            pendingRestorePlaylistIndex = preferredIndex;
        } else {
            pendingSeekMs = PlaybackSessionState.NO_PENDING_SEEK;
            pendingRestoreVideoId = null;
            pendingRestorePlaylistIndex = -1;
            playbackSessionState.requestPlayWhenReady(true);
        }

        clearFailure();
        errorCard.setVisibility(View.GONE);
        loadingIndicator.setVisibility(View.VISIBLE);
        playbackStatus.setText(getString(R.string.loading_playlist));
        titleText.setText(getString(R.string.playlist_loading_name));
        uploaderText.setText(getString(R.string.playlist_loading_detail));
        playlistCard.setVisibility(View.VISIBLE);
        playlistNameText.setText(getString(R.string.playlist_loading_name));
        playlistPositionText.setText(getString(R.string.playlist_loading_detail));
        setPlaylistButtonEnabled(playlistPreviousButton, false);
        setPlaylistButtonEnabled(playlistNextButton, false);

        // Keep the seed video for generated mixes; some mix IDs cannot be
        // resolved from a playlist-only URL.
        String playlistUrl = link.getCanonicalUrl();
        activePlaylistResolveTask = playlistResolverExecutor.submit(() -> {
            try {
                ResolvedPlaylist resolved = playlistResolver.resolve(playlistUrl);
                runOnUiThread(() -> {
                    if (playlistGeneration != playlistLoadGeneration.get()
                            || activityUnavailable()) {
                        return;
                    }
                    playlistQueue = new PlaylistQueue(
                            resolved,
                            preferredIndex,
                            preferredVideoId
                    );
                    renderPlaylistQueue();
                    startCurrentPlaylistItem(
                            reason == PlaybackStartReason.RESTORE
                                    ? PlaybackStartReason.RESTORE
                                    : PlaybackStartReason.PLAYLIST_ITEM
                    );
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (playlistGeneration == playlistLoadGeneration.get()
                            && !activityUnavailable()) {
                        recordFailure("playlist resolution", deepestType(error));
                        showError(humanPlaylistError(error));
                        playlistPositionText.setText("");
                    }
                });
            }
        });
    }

    private void startCurrentPlaylistItem(PlaybackStartReason reason) {
        if (playlistQueue == null) {
            return;
        }
        PlaylistEntry entry = playlistQueue.current();
        renderPlaylistQueue();
        titleText.setText(entry.getTitle());
        uploaderText.setText(
                entry.getUploader().isEmpty() ? "YouTube" : entry.getUploader()
        );
        startPlayback(entry.getCanonicalUrl(), reason);
    }

    private void startPlayback(String canonicalUrl, PlaybackStartReason reason) {
        cancelOfflineDownload();
        clearPendingOfflineCredential();
        String videoId = YouTubeUrlParser.extractVideoId(canonicalUrl);
        if (videoId == null) {
            recordFailure("link validation", "Invalid or unsupported YouTube link");
            showError(getString(R.string.invalid_link));
            return;
        }

        clearActiveOfflinePlayback(false);
        boolean retry = reason == PlaybackStartReason.RETRY;
        if (reason == PlaybackStartReason.RESTORE) {
            pendingSeekMs = PlaybackSessionState.seekForRestoredVideo(
                    pendingSeekMs,
                    pendingRestoreVideoId,
                    videoId
            );
            pendingRestoreVideoId = null;
            pendingRestorePlaylistIndex = -1;
        } else if (!retry) {
            pendingSeekMs = PlaybackSessionState.NO_PENDING_SEEK;
            pendingRestoreVideoId = null;
            pendingRestorePlaylistIndex = -1;
            playbackSessionState.requestPlayWhenReady(true);
        }
        if (!retry) {
            clearFailure();
            sponsorLookupStatus = "Loading";
        }
        int generation = loadGeneration.incrementAndGet();
        handledEndGeneration = -1;
        preparedPlaybackGeneration = -1;
        cancelTask(activeVideoResolveTask);
        activeVideoResolveTask = null;
        activeUrl = canonicalUrl;
        activeVideo = null;
        refreshOfflineControls();
        selectedMediaPrepared = false;
        errorCard.setVisibility(View.GONE);
        loadingIndicator.setVisibility(View.VISIBLE);
        playbackStatus.setText(retry ? "Refreshing the stream…" : getString(R.string.loading_video));
        if (!retry) {
            retriedAfterPlaybackFailure = false;
            player.stop();
            resetItemProtection();
        }

        fetchSponsorSegments(videoId, generation);
        int maxHeight = preferenceStore.maxHeight();
        activeVideoResolveTask = videoResolverExecutor.submit(() -> {
            try {
                ResolvedVideo resolved = videoResolver.resolve(canonicalUrl, maxHeight);
                runOnUiThread(() -> {
                    if (generation != loadGeneration.get() || activityUnavailable()) {
                        return;
                    }
                    beginResolvedPlayback(resolved, generation);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (generation == loadGeneration.get() && !activityUnavailable()) {
                        recordFailure("video resolution", deepestType(error));
                        showError(withPlaylistContinuation(humanResolveError(error)));
                    }
                });
            }
        });
    }

    private void fetchSponsorSegments(String videoId, int generation) {
        List<String> categories = preferenceStore.enabledSponsorCategories();
        if (categories.isEmpty()) {
            sponsorLookupStatus = "Disabled in settings";
            skipController.setSegments(List.of());
            updateProtectionText();
            return;
        }

        sponsorLookupStatus = "Loading";
        CompletableFuture<List<SponsorSegment>> future =
                sponsorBlockClient.getSegments(videoId, categories);
        future.whenComplete((segments, error) -> runOnUiThread(() -> {
            if (generation != loadGeneration.get() || activityUnavailable()) {
                return;
            }
            if (error == null && segments != null) {
                sponsorLookupStatus = segments.isEmpty()
                        ? "Completed; no matching segments"
                        : "Completed; " + segments.size() + " segment(s)";
                skipController.setSegments(segments);
                protectionStatus.setText(
                        segments.isEmpty() ? "AD-FREE" : "AD-FREE • SPONSORS"
                );
            } else {
                sponsorLookupStatus = "Failed";
                protectionStatus.setText("AD-FREE");
            }
        }));
    }

    private void beginResolvedPlayback(ResolvedVideo resolved, int generation) {
        activeVideo = resolved;
        refreshOfflineControls();
        preparedPlaybackGeneration = generation;
        titleText.setText(resolved.getTitle());
        uploaderText.setText(
                resolved.getUploader().isEmpty() ? "YouTube" : resolved.getUploader()
        );
        playbackStatus.setText("Preparing ad-free playback…");
        MediaSource source = playbackSourceFactory.create(resolved);
        player.setMediaSource(source);
        selectedMediaPrepared = true;
        player.setPlayWhenReady(playbackSessionState.shouldPlayWhenReady());
        player.prepare();
    }

    private void handlePlaybackEnded() {
        int generation = loadGeneration.get();
        if (preparedPlaybackGeneration != generation
                || handledEndGeneration == generation) {
            return;
        }
        handledEndGeneration = generation;

        if (activeOfflineRecord != null) {
            OfflineMediaStore.PlaybackSession session = offlinePlaybackSession;
            player.clearMediaItems();
            if (session != null) {
                session.close();
            }
            offlinePlaybackSession = null;
            selectedMediaPrepared = false;
            preparedPlaybackGeneration = -1;
            playbackStatus.setText("Finished");
            offlineStatusText.setText(R.string.offline_download_complete);
            offlineStatusText.setTextColor(getColor(R.color.mint));
            offlineSaveButton.setEnabled(false);
            offlineLibraryButton.setEnabled(
                    offlineSecurityDecision().isAllowed()
            );
            return;
        }

        if (playlistQueue != null && playlistQueue.next()) {
            startCurrentPlaylistItem(PlaybackStartReason.PLAYLIST_ITEM);
            return;
        }
        playbackStatus.setText(
                playlistQueue == null
                        ? "Finished"
                        : getString(
                                playlistQueue.getPlaylist().isComplete()
                                        ? R.string.playlist_finished
                                        : R.string.playlist_loaded_items_finished
                        )
        );
        renderPlaylistQueue();
    }

    private void playNextPlaylistItem() {
        if (playlistQueue != null && playlistQueue.next()) {
            startCurrentPlaylistItem(PlaybackStartReason.PLAYLIST_ITEM);
        }
    }

    private void playPreviousPlaylistItem() {
        if (playlistQueue != null && playlistQueue.previous()) {
            startCurrentPlaylistItem(PlaybackStartReason.PLAYLIST_ITEM);
        }
    }

    private void renderPlaylistQueue() {
        if (playlistQueue == null) {
            return;
        }
        ResolvedPlaylist resolved = playlistQueue.getPlaylist();
        playlistCard.setVisibility(View.VISIBLE);
        playlistNameText.setText(resolved.getTitle());

        int position = playlistQueue.position() + 1;
        int size = playlistQueue.size();
        int positionString;
        if (resolved.isMix()) {
            positionString = R.string.playlist_mix_position;
        } else if (!resolved.isComplete()) {
            positionString = R.string.playlist_position_partial;
        } else {
            positionString = R.string.playlist_position;
        }
        playlistPositionText.setText(getString(positionString, position, size));
        setPlaylistButtonEnabled(
                playlistPreviousButton,
                playlistQueue.hasPrevious()
        );
        setPlaylistButtonEnabled(playlistNextButton, playlistQueue.hasNext());
    }

    private static void setPlaylistButtonEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.45f);
    }

    private void resetItemProtection() {
        lastSkippedSegment = null;
        mainHandler.removeCallbacks(hideSkipNotice);
        if (skipNotice != null) {
            skipNotice.animate().cancel();
            skipNotice.setVisibility(View.GONE);
        }
        skipController.setSegments(List.of());
        sponsorLookupStatus = "Loading";
        updateProtectionText();
    }

    private static void cancelTask(Future<?> task) {
        if (task != null) {
            task.cancel(true);
        }
    }

    private boolean isOfflineForeground() {
        return isOfflineForeground(offlineForegroundGeneration.get());
    }

    private boolean isOfflineForeground(int expectedGeneration) {
        return isOfflineAccessCurrent(expectedGeneration)
                && !activityUnavailable()
                && getLifecycle()
                        .getCurrentState()
                        .isAtLeast(Lifecycle.State.RESUMED);
    }

    private boolean isOfflineAccessCurrent(int expectedGeneration) {
        return expectedGeneration == offlineForegroundGeneration.get()
                && !destroyed;
    }

    private void revokeOfflineForegroundAccess() {
        offlineForegroundGeneration.incrementAndGet();
        OfflineDownloadManager.Cancellation cancellation =
                offlineDownloadCancellation;
        if (cancellation != null) {
            cancellation.cancel();
        }
        cancelTask(activeOfflineTask);
        activeOfflineTask = null;
        offlineDownloadCancellation = null;
        offlineDownloadInProgress = false;
        if (offlineMediaStore != null) {
            offlineMediaStore.revokeAllPlayback();
        }
        clearActiveOfflinePlayback(true);

        if (offlineProgress != null) {
            offlineProgress.setVisibility(View.GONE);
            offlineProgress.setProgress(0);
        }
        if (offlineSaveButton != null) {
            offlineSaveButton.setText(R.string.save_offline);
            offlineSaveButton.setOnClickListener(view -> promptOfflineSave());
            offlineSaveButton.setEnabled(false);
        }
        if (offlineLibraryButton != null) {
            offlineLibraryButton.setEnabled(false);
        }
    }

    private boolean activityUnavailable() {
        return destroyed || isFinishing() || isDestroyed();
    }

    private String withPlaylistContinuation(String message) {
        if (playlistQueue == null || !playlistQueue.hasNext()) {
            return message;
        }
        return message + "\n\n"
                + getString(R.string.playlist_continue_after_error);
    }

    private void onSponsorSkipped(SponsorSegment segment) {
        lastSkippedSegment = segment;
        long seconds = Math.max(
                1,
                Math.round(segment.getEndSeconds() - segment.getStartSeconds())
        );
        String category = friendlyCategory(segment.getCategory());
        skipNoticeText.setText(
                String.format(Locale.US, "Skipped %s • %ds", category, seconds)
        );
        skipNotice.animate().cancel();
        skipNotice.setAlpha(0f);
        skipNotice.setVisibility(View.VISIBLE);
        skipNotice.animate().alpha(1f).setDuration(160).start();
        mainHandler.removeCallbacks(hideSkipNotice);
        mainHandler.postDelayed(hideSkipNotice, 5_000);
    }

    private void undoSponsorSkip() {
        if (lastSkippedSegment == null) {
            return;
        }
        skipController.suppressTemporarily(lastSkippedSegment, 20_000);
        player.seekTo(lastSkippedSegment.getStartMillis());
        player.play();
        mainHandler.removeCallbacks(hideSkipNotice);
        hideSkipNotice.run();
    }

    private void showSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), dp(8));
        scroll.addView(content, matchParentWrap());

        TextView supportLabel = text(
                getString(R.string.help_and_support),
                11,
                getColor(R.color.text_secondary)
        );
        supportLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        supportLabel.setPadding(0, 0, 0, dp(3));
        content.addView(supportLabel);
        Button reportBug = textButton(getString(R.string.start_report));
        View reportBugRow = settingAction(
                getString(R.string.report_a_bug),
                getString(R.string.report_a_bug_detail),
                reportBug
        );
        reportBugRow.setFocusable(false);
        reportBugRow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(reportBugRow);
        TextView version = text(
                getString(R.string.app_version, installedVersion()),
                12,
                getColor(R.color.text_secondary)
        );
        version.setPadding(0, 0, 0, dp(8));
        content.addView(version);

        addTorSettings(content);

        TextView updatesLabel = text(
                getString(R.string.updates),
                11,
                getColor(R.color.text_secondary)
        );
        updatesLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        updatesLabel.setPadding(0, dp(16), 0, dp(3));
        content.addView(updatesLabel);

        Button manageNotifications = textButton(getString(R.string.manage));
        View notificationSettingsRow = settingAction(
                getString(R.string.update_notifications),
                getString(
                        UpdateNotificationManager.notificationsAllowed(this)
                                ? R.string.update_notifications_allowed
                                : R.string.update_notifications_off
                ),
                manageNotifications
        );
        content.addView(notificationSettingsRow);

        TextView updateCheckDetail = text(
                getString(R.string.check_for_updates_detail),
                12,
                getColor(R.color.text_secondary)
        );
        Button checkForUpdates = textButton(getString(R.string.check_now));
        View checkForUpdatesRow = settingAction(
                getString(R.string.check_for_updates_now),
                updateCheckDetail,
                checkForUpdates
        );
        content.addView(checkForUpdatesRow);

        UpdateRelease availableUpdate = availableUpdate();
        manualUpdateCheckController.resetFeedbackWhenUpdateIsAvailable(
                availableUpdate != null
        );
        Button downloadUpdate = null;
        View availableUpdateRow = null;
        if (availableUpdate != null) {
            downloadUpdate = textButton(getString(R.string.download));
            availableUpdateRow = settingAction(
                    getString(R.string.update_available),
                    getString(
                            R.string.update_available_detail,
                            availableUpdate.getVersionName()
                    ),
                    downloadUpdate
            );
            content.addView(availableUpdateRow);
        }

        TextView offlineUseLabel = text(
                getString(R.string.offline_use_section),
                11,
                getColor(R.color.text_secondary)
        );
        offlineUseLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        offlineUseLabel.setPadding(0, dp(20), 0, dp(3));
        content.addView(offlineUseLabel);
        TextView offlineUsePolicy = text(
                getString(R.string.offline_use_policy),
                12,
                getColor(R.color.text_secondary)
        );
        offlineUsePolicy.setPadding(0, 0, 0, dp(8));
        content.addView(offlineUsePolicy);

        TextView section = text("SKIP CATEGORIES", 11, getColor(R.color.text_secondary));
        section.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        section.setPadding(0, dp(20), 0, 0);
        content.addView(section);
        content.addView(settingSwitch(
                "Paid sponsors",
                "Crowdsourced paid-promotion segments",
                preferenceStore.skipSponsor(),
                preferenceStore::setSkipSponsor
        ));
        content.addView(settingSwitch(
                "Self promotion",
                "Creator merchandise and unpaid promotion",
                preferenceStore.skipSelfPromo(),
                preferenceStore::setSkipSelfPromo
        ));
        content.addView(settingSwitch(
                "Like and subscribe reminders",
                "Calls to like, subscribe, or comment",
                preferenceStore.skipInteraction(),
                preferenceStore::setSkipInteraction
        ));
        content.addView(settingSwitch(
                "Intros",
                "Intro animations and intermissions",
                preferenceStore.skipIntro(),
                preferenceStore::setSkipIntro
        ));
        content.addView(settingSwitch(
                "Outros",
                "End cards and credits",
                preferenceStore.skipOutro(),
                preferenceStore::setSkipOutro
        ));

        TextView qualityLabel = text("MAX VIDEO QUALITY", 11, getColor(R.color.text_secondary));
        qualityLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        qualityLabel.setPadding(0, dp(20), 0, dp(6));
        content.addView(qualityLabel);

        RadioGroup quality = new RadioGroup(this);
        int[] heights = {360, 720, 1080, 2160};
        for (int height : heights) {
            RadioButton choice = new RadioButton(this);
            choice.setText(height + "p");
            choice.setTextColor(getColor(R.color.text_primary));
            choice.setButtonTintList(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.coral)
            ));
            choice.setId(height);
            choice.setChecked(preferenceStore.maxHeight() == height);
            quality.addView(choice);
        }
        quality.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId > 0) {
                preferenceStore.setMaxHeight(checkedId);
            }
        });
        content.addView(quality);

        TextView about = text(
                "Uses SponsorBlock data licensed under CC BY-NC-SA 4.0 from "
                        + "https://sponsor.ajay.app/.\n\n"
                        + "Playback is powered by NewPipe Extractor under GPL-3.0. "
                        + "Plyvanta is not affiliated with YouTube or SponsorBlock. "
                        + "This unofficial player may need updates when YouTube changes.",
                12,
                getColor(R.color.text_secondary)
        );
        about.setPadding(0, dp(20), 0, dp(8));
        android.text.util.Linkify.addLinks(about, android.text.util.Linkify.WEB_URLS);
        android.text.SpannableString linkedAbout = new android.text.SpannableString(about.getText());
        for (android.text.style.URLSpan link : linkedAbout.getSpans(
                0, linkedAbout.length(), android.text.style.URLSpan.class)) {
            int start = linkedAbout.getSpanStart(link);
            int end = linkedAbout.getSpanEnd(link);
            linkedAbout.removeSpan(link);
            linkedAbout.setSpan(new android.text.style.ClickableSpan() {
                @Override
                public void onClick(View view) {
                    if (!openInBrowser(link.getURL())) {
                        Toast.makeText(MainActivity.this, R.string.no_app_for_update,
                                Toast.LENGTH_LONG).show();
                    }
                }
            }, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        about.setText(linkedAbout);
        about.setMovementMethod(LinkMovementMethod.getInstance());
        content.addView(about);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Plyvanta settings")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .create();
        activeSettingsDialog = dialog;
        activeUpdateCheckDetail = updateCheckDetail;
        activeUpdateCheckAction = checkForUpdates;
        renderManualUpdateCheckState();
        View.OnClickListener openBugReport = view -> {
            dialog.dismiss();
            showBugReport(false, "", false, false);
        };
        reportBug.setOnClickListener(openBugReport);
        reportBugRow.setOnClickListener(openBugReport);
        View.OnClickListener manageUpdateNotifications =
                view -> openUpdateNotificationSettings();
        manageNotifications.setOnClickListener(manageUpdateNotifications);
        notificationSettingsRow.setOnClickListener(manageUpdateNotifications);
        View.OnClickListener runUpdateCheck = view -> startManualUpdateCheck();
        checkForUpdates.setOnClickListener(runUpdateCheck);
        checkForUpdatesRow.setOnClickListener(runUpdateCheck);
        if (availableUpdate != null) {
            UpdateRelease offeredRelease = availableUpdate;
            View.OnClickListener openAvailableUpdate = view -> {
                dialog.dismiss();
                showUpdateDialog(offeredRelease);
            };
            downloadUpdate.setOnClickListener(openAvailableUpdate);
            availableUpdateRow.setOnClickListener(openAvailableUpdate);
        }
        dialog.setOnDismissListener(ignored -> {
            if (activeSettingsDialog == dialog) {
                activeSettingsDialog = null;
                activeUpdateCheckDetail = null;
                activeUpdateCheckAction = null;
            }
            refreshSponsorPreferences();
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(getColor(R.color.coral));
        });
        dialog.show();
    }

    private void refreshTorBanner() {
        if (torRoutingBanner != null) {
            torRoutingBanner.setVisibility(AppNetwork.isTorEnabled() ? View.VISIBLE : View.GONE);
        }
    }

    private void addTorSettings(LinearLayout content) {
        TextView section = text(getString(R.string.tor_section), 11,
                getColor(R.color.text_secondary));
        section.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        section.setPadding(0, dp(16), 0, dp(3));
        content.addView(section);

        TextView state = text("", 12, getColor(R.color.text_secondary));
        Button check = textButton(getString(R.string.check_now));
        TextView portDetail = text("", 12, getColor(R.color.text_secondary));
        Runnable render = () -> {
            state.setText(AppNetwork.isTorEnabled()
                    ? R.string.tor_enabled_unchecked : R.string.tor_disabled);
            check.setEnabled(AppNetwork.isTorEnabled());
            check.setText(R.string.check_now);
            portDetail.setText(getString(R.string.tor_port_detail, AppNetwork.torPort()));
            refreshTorBanner();
        };
        content.addView(settingSwitch(
                getString(R.string.tor_use), getString(R.string.tor_use_detail),
                AppNetwork.isTorEnabled(), enabled -> {
                    applyTorSetting(enabled, AppNetwork.torPort());
                    render.run();
                }
        ));

        Button orbot = textButton(getString(R.string.tor_open_orbot));
        View orbotRow = settingAction(getString(R.string.tor_orbot),
                getString(R.string.tor_orbot_detail), orbot);
        View.OnClickListener openOrbot = view -> openOrbot();
        orbot.setOnClickListener(openOrbot);
        orbotRow.setOnClickListener(openOrbot);
        content.addView(orbotRow);

        View checkRow = settingAction(getString(R.string.tor_check), state, check);
        View.OnClickListener checkConnection = view -> {
            if (!AppNetwork.isTorEnabled() || !check.isEnabled()) {
                return;
            }
            int generation = torSettingsGeneration.incrementAndGet();
            check.setEnabled(false);
            check.setText(R.string.tor_checking);
            state.setText(R.string.tor_checking_detail);
            torCheckExecutor.execute(() -> {
                int result;
                try {
                    result = AppNetwork.checkTorConnection()
                            ? R.string.tor_verified : R.string.tor_not_detected;
                } catch (Exception failure) {
                    result = R.string.tor_check_failed;
                }
                int resultMessage = result;
                mainHandler.post(() -> {
                    if (activityUnavailable() || generation != torSettingsGeneration.get()) {
                        return;
                    }
                    state.setText(resultMessage);
                    check.setText(R.string.check_now);
                    check.setEnabled(AppNetwork.isTorEnabled());
                });
            });
        };
        check.setOnClickListener(checkConnection);
        checkRow.setOnClickListener(checkConnection);
        content.addView(checkRow);

        Button port = textButton(getString(R.string.tor_change_port));
        View portRow = settingAction(getString(R.string.tor_port), portDetail, port);
        View.OnClickListener changePort = view -> showTorPortDialog(render);
        port.setOnClickListener(changePort);
        portRow.setOnClickListener(changePort);
        content.addView(portRow);

        TextView limits = text(getString(R.string.tor_privacy_detail), 12,
                getColor(R.color.text_secondary));
        limits.setPadding(0, dp(6), 0, dp(8));
        content.addView(limits);
        render.run();
    }

    private void applyTorSetting(boolean enabled, int port) {
        if (enabled == AppNetwork.isTorEnabled() && port == AppNetwork.torPort()) {
            return;
        }
        invalidateForNetworkRouteChange();
        try {
            AppNetwork.setTorEnabled(enabled, port);
            Toast.makeText(this, activeOfflineRecord == null
                    ? R.string.tor_route_changed : R.string.tor_route_changed_offline,
                    Toast.LENGTH_LONG).show();
        } catch (RuntimeException failure) {
            Toast.makeText(this, R.string.tor_setting_failed, Toast.LENGTH_LONG).show();
            if (activeSettingsDialog != null) {
                activeSettingsDialog.dismiss();
                mainHandler.post(this::showSettings);
            }
        }
        rebindOfflineNetworkRoute();
        observedTorEnabled = AppNetwork.isTorEnabled();
        observedTorPort = AppNetwork.torPort();
        refreshOfflineControls();
        refreshTorBanner();
    }

    private void synchronizeNetworkRoute() {
        if (activityUnavailable()) {
            return;
        }
        if (observedTorEnabled != AppNetwork.isTorEnabled()
                || observedTorPort != AppNetwork.torPort()) {
            invalidateForNetworkRouteChange();
            rebindOfflineNetworkRoute();
            observedTorEnabled = AppNetwork.isTorEnabled();
            observedTorPort = AppNetwork.torPort();
            if (activeSettingsDialog != null) {
                activeSettingsDialog.dismiss();
            }
            refreshOfflineControls();
        }
        refreshTorBanner();
    }

    private void invalidateForNetworkRouteChange() {
        torSettingsGeneration.incrementAndGet();
        updateDownloadVerificationGeneration.incrementAndGet();
        // Invalidate resolver callbacks before cancelling sockets so they cannot
        // restart playback with metadata obtained under the previous route.
        loadGeneration.incrementAndGet();
        playlistLoadGeneration.incrementAndGet();
        cancelTask(activeVideoResolveTask);
        cancelTask(activePlaylistResolveTask);
        activeVideoResolveTask = null;
        activePlaylistResolveTask = null;
        cancelOfflineDownload();
        clearPendingOfflineCredential();
        if (activeOfflineRecord == null) {
            activeVideo = null;
            activeUrl = null;
            sourceUrl = null;
            playlistQueue = null;
            playlistCard.setVisibility(View.GONE);
            selectedMediaPrepared = false;
            preparedPlaybackGeneration = -1;
            pendingSeekMs = PlaybackSessionState.NO_PENDING_SEEK;
            pendingRestoreVideoId = null;
            pendingRestorePlaylistIndex = -1;
            playbackSessionState.requestPlayWhenReady(false);
            player.stop();
            player.clearMediaItems();
            loadingIndicator.setVisibility(View.GONE);
            errorCard.setVisibility(View.GONE);
            playbackStatus.setText(R.string.tor_route_changed);
            resetItemProtection();
        }
    }

    private void rebindOfflineNetworkRoute() {
        // A failed preference write also invalidated the old callbacks. Keep an
        // existing offline session bound to the current generation in either case.
        if (activeOfflineRecord != null && offlinePlaybackSession != null) {
            preparedPlaybackGeneration = loadGeneration.get();
            handledEndGeneration = -1;
            resetItemProtection();
            fetchSponsorSegments(activeOfflineRecord.getVideoId(), loadGeneration.get());
        }
    }

    private void showTorPortDialog(Runnable render) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setContentDescription(getString(R.string.tor_port));
        input.setText(Integer.toString(AppNetwork.torPort()));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.tor_port)
                .setMessage(R.string.tor_port_message)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.tor_save_port, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    int port;
                    try {
                        port = Integer.parseInt(input.getText().toString().trim());
                        if (port < 1 || port > 65535) {
                            throw new NumberFormatException();
                        }
                    } catch (NumberFormatException invalidPort) {
                        input.setError(getString(R.string.tor_invalid_port));
                        return;
                    }
                    applyTorSetting(AppNetwork.isTorEnabled(), port);
                    render.run();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void openOrbot() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("org.torproject.android");
        if (launch != null && startExternalActivity(launch)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.tor_get_orbot)
                .setMessage(R.string.tor_get_orbot_detail)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.tor_get_orbot, (dialog, which) -> {
                    if (!openInBrowserDirect("https://orbot.app/en/download/")) {
                        Toast.makeText(this, R.string.no_app_for_update, Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void startManualUpdateCheck() {
        if (destroyed || !manualUpdateCheckController.start()) {
            return;
        }
        renderManualUpdateCheckState();
        Context applicationContext = getApplicationContext();
        updateCheckExecutor.execute(() -> {
            UpdateChecker.Result result;
            try {
                result = new UpdateChecker(applicationContext).check();
            } catch (RuntimeException unexpectedFailure) {
                result = null;
            }
            UpdateChecker.Result completedResult = result;
            mainHandler.post(() -> finishManualUpdateCheck(completedResult));
        });
    }

    private void finishManualUpdateCheck(UpdateChecker.Result result) {
        if (destroyed || isFinishing() || isDestroyed()) {
            return;
        }
        UpdateChecker.Status status = result == null ? null : result.getStatus();
        boolean updateAvailable =
                result != null && result.getAvailableRelease() != null;
        ManualUpdateCheckController.Completion completion =
                manualUpdateCheckController.complete(status, updateAvailable);
        if (completion == ManualUpdateCheckController.Completion.SHOW_UPDATE) {
            AlertDialog settingsDialog = activeSettingsDialog;
            if (settingsDialog != null && settingsDialog.isShowing()) {
                settingsDialog.dismiss();
            }
            showAvailableUpdate();
            return;
        }

        ManualUpdateCheckController.State feedbackState =
                manualUpdateCheckController.getState();
        boolean settingsVisible =
                activeSettingsDialog != null && activeSettingsDialog.isShowing();
        renderManualUpdateCheckState();
        if (!settingsVisible) {
            int feedbackMessage;
            if (feedbackState == ManualUpdateCheckController.State.UP_TO_DATE) {
                feedbackMessage = R.string.update_check_up_to_date;
            } else if (feedbackState
                    == ManualUpdateCheckController.State.UNVERIFIED) {
                feedbackMessage = R.string.update_check_unverified;
            } else {
                feedbackMessage = R.string.update_check_error;
            }
            Toast.makeText(
                    this,
                    feedbackMessage,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void renderManualUpdateCheckState() {
        if (activeUpdateCheckDetail == null || activeUpdateCheckAction == null) {
            return;
        }
        switch (manualUpdateCheckController.getState()) {
            case CHECKING:
                activeUpdateCheckDetail.setText(R.string.update_check_checking);
                activeUpdateCheckDetail.setTextColor(getColor(R.color.text_secondary));
                activeUpdateCheckAction.setText(R.string.checking);
                activeUpdateCheckAction.setEnabled(false);
                break;
            case UP_TO_DATE:
                activeUpdateCheckDetail.setText(R.string.update_check_up_to_date);
                activeUpdateCheckDetail.setTextColor(getColor(R.color.mint));
                activeUpdateCheckAction.setText(R.string.check_again);
                activeUpdateCheckAction.setEnabled(true);
                break;
            case UNVERIFIED:
                activeUpdateCheckDetail.setText(R.string.update_check_unverified);
                activeUpdateCheckDetail.setTextColor(getColor(R.color.coral));
                activeUpdateCheckAction.setText(R.string.try_again);
                activeUpdateCheckAction.setEnabled(true);
                break;
            case ERROR:
                activeUpdateCheckDetail.setText(R.string.update_check_error);
                activeUpdateCheckDetail.setTextColor(getColor(R.color.coral));
                activeUpdateCheckAction.setText(R.string.try_again);
                activeUpdateCheckAction.setEnabled(true);
                break;
            case IDLE:
            default:
                activeUpdateCheckDetail.setText(R.string.check_for_updates_detail);
                activeUpdateCheckDetail.setTextColor(getColor(R.color.text_secondary));
                activeUpdateCheckAction.setText(R.string.check_now);
                activeUpdateCheckAction.setEnabled(true);
                break;
        }
    }

    private View settingSwitch(
            String title,
            String detail,
            boolean checked,
            java.util.function.Consumer<Boolean> setter
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 15, getColor(R.color.text_primary));
        TextView detailView = text(detail, 12, getColor(R.color.text_secondary));
        copy.addView(titleView);
        copy.addView(detailView);
        row.addView(copy, new LinearLayout.LayoutParams(0, wrap(), 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setContentDescription(title);
        toggle.setButtonTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.coral)
        ));
        toggle.setOnCheckedChangeListener((button, value) -> setter.accept(value));
        row.addView(toggle);
        return row;
    }

    private View settingAction(String title, String detail, Button action) {
        TextView detailView = text(detail, 12, getColor(R.color.text_secondary));
        return settingAction(title, detailView, action);
    }

    private View settingAction(String title, TextView detailView, Button action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 15, getColor(R.color.text_primary));
        copy.addView(titleView);
        copy.addView(detailView);
        row.addView(copy, new LinearLayout.LayoutParams(0, wrap(), 1f));
        row.addView(action, new LinearLayout.LayoutParams(wrap(), dp(44)));
        return row;
    }

    private void showBugReport(
            boolean fromError,
            String draft,
            boolean includeDiagnosticsInitially,
            boolean includeVideoInitially
    ) {
        if (bugReportStep == BUG_REPORT_CLOSED) {
            bugReportTechnicalSnapshot = new LinkedHashMap<>(collectTechnicalDetails());
            bugReportVideoUrlSnapshot = activeUrl;
        } else if (bugReportTechnicalSnapshot == null) {
            bugReportTechnicalSnapshot = new LinkedHashMap<>(collectTechnicalDetails());
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), dp(8));
        scroll.addView(content, matchParentWrap());

        TextView intro = text(
                getString(R.string.bug_report_intro),
                14,
                getColor(R.color.text_secondary)
        );
        intro.setPadding(0, 0, 0, dp(12));
        content.addView(intro);

        EditText description = new EditText(this);
        description.setHint(R.string.bug_description_hint);
        description.setHintTextColor(getColor(R.color.text_secondary));
        description.setTextColor(getColor(R.color.text_primary));
        description.setTextSize(14);
        description.setGravity(Gravity.TOP | Gravity.START);
        description.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
        );
        description.setSingleLine(false);
        description.setMinLines(5);
        description.setMaxLines(10);
        description.setPadding(dp(14), dp(12), dp(14), dp(12));
        description.setBackgroundResource(R.drawable.bg_input);
        description.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(DiagnosticReport.MAX_DESCRIPTION_CHARS)
        });
        String initialDraft = draft;
        if (initialDraft.isBlank() && fromError && lastFailureStage != null) {
            initialDraft = "Plyvanta showed an error during "
                    + lastFailureStage
                    + ".\n\nSteps to reproduce:\n1. ";
        }
        bugReportStep = BUG_REPORT_EDITING;
        bugReportFromError = fromError;
        bugReportDraft = initialDraft;
        bugReportIncludesDiagnostics = includeDiagnosticsInitially;
        bugReportIncludesVideo = includeVideoInitially;
        bugReportPreview = null;
        activeBugReportDescription = description;
        description.setText(initialDraft);
        description.setSelection(description.length());
        content.addView(description, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        CheckBox includeDiagnostics = reportCheckBox(
                getString(R.string.include_diagnostics),
                includeDiagnosticsInitially
        );
        activeBugReportDiagnostics = includeDiagnostics;
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        checkParams.setMargins(0, dp(14), 0, 0);
        content.addView(includeDiagnostics, checkParams);
        TextView diagnosticsDetail = text(
                getString(R.string.include_diagnostics_detail),
                12,
                getColor(R.color.text_secondary)
        );
        diagnosticsDetail.setPadding(dp(34), 0, 0, dp(6));
        content.addView(diagnosticsDetail);

        CheckBox includeVideo = null;
        if (bugReportVideoUrlSnapshot != null) {
            includeVideo = reportCheckBox(
                    getString(R.string.include_video_link),
                    includeVideoInitially
            );
            content.addView(includeVideo);
            TextView videoDetail = text(
                    getString(R.string.include_video_link_detail),
                    12,
                    getColor(R.color.text_secondary)
            );
            videoDetail.setPadding(dp(34), 0, 0, dp(6));
            content.addView(videoDetail);
        }
        activeBugReportVideo = includeVideo;

        TextView consent = text(
                getString(R.string.bug_report_consent),
                12,
                getColor(R.color.mint)
        );
        consent.setPadding(0, dp(12), 0, dp(4));
        content.addView(consent);

        CheckBox finalIncludeVideo = includeVideo;
        boolean[] movingToPreview = {false};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.report_a_bug)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.review_report, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.coral));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(getColor(R.color.text_secondary));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String userDescription = description.getText().toString().trim();
                if (userDescription.isEmpty()) {
                    description.setError(getString(R.string.describe_problem_first));
                    description.requestFocus();
                    return;
                }

                boolean includeTechnicalDetails = includeDiagnostics.isChecked();
                boolean includeCurrentVideo =
                        finalIncludeVideo != null && finalIncludeVideo.isChecked();
                bugReportDraft = userDescription;
                bugReportIncludesDiagnostics = includeTechnicalDetails;
                bugReportIncludesVideo = includeCurrentVideo;
                Map<String, String> technicalDetails = includeTechnicalDetails
                        ? new LinkedHashMap<>(bugReportTechnicalSnapshot)
                        : Map.of();
                String report = DiagnosticReport.format(
                        userDescription,
                        technicalDetails,
                        includeCurrentVideo ? bugReportVideoUrlSnapshot : null
                );
                bugReportPreview = report;
                bugReportStep = BUG_REPORT_REVIEWING;
                movingToPreview[0] = true;
                dialog.dismiss();
                showBugReportPreview(
                        fromError,
                        userDescription,
                        includeTechnicalDetails,
                        includeCurrentVideo,
                        report
                );
            });
        });
        dialog.setOnDismissListener(ignored -> {
            activeBugReportDescription = null;
            activeBugReportDiagnostics = null;
            activeBugReportVideo = null;
            if (!movingToPreview[0]) {
                clearBugReportState();
            }
        });
        dialog.show();
    }

    private CheckBox reportCheckBox(String label, boolean checked) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(label);
        checkBox.setTextColor(getColor(R.color.text_primary));
        checkBox.setTextSize(14);
        checkBox.setChecked(checked);
        checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.coral)
        ));
        return checkBox;
    }

    private void showBugReportPreview(
            boolean fromError,
            String description,
            boolean includedDiagnostics,
            boolean includedVideo,
            String report
    ) {
        bugReportStep = BUG_REPORT_REVIEWING;
        bugReportFromError = fromError;
        bugReportDraft = description;
        bugReportIncludesDiagnostics = includedDiagnostics;
        bugReportIncludesVideo = includedVideo;
        bugReportPreview = report;

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), dp(8));
        scroll.addView(content, matchParentWrap());

        TextView notice = text(
                getString(R.string.bug_report_public_notice),
                13,
                getColor(R.color.text_secondary)
        );
        notice.setPadding(0, 0, 0, dp(12));
        content.addView(notice);

        String title = DiagnosticReport.suggestedTitle(description);
        TextView titleLabel = text(
                getString(R.string.issue_title_label),
                11,
                getColor(R.color.text_secondary)
        );
        titleLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(titleLabel);
        TextView titlePreview = text(title, 14, getColor(R.color.text_primary));
        titlePreview.setTextIsSelectable(true);
        titlePreview.setPadding(0, dp(4), 0, dp(12));
        content.addView(titlePreview);

        TextView preview = text(report, 12, getColor(R.color.text_primary));
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextIsSelectable(true);
        preview.setPadding(dp(14), dp(12), dp(14), dp(12));
        preview.setBackgroundResource(R.drawable.bg_input);
        content.addView(preview);

        boolean[] movingToEditor = {false};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.review_report)
                .setView(scroll)
                .setNegativeButton(R.string.edit_report, null)
                .setNeutralButton(R.string.share_report, null)
                .setPositiveButton(R.string.open_github, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button openGitHub = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            openGitHub.setTextColor(getColor(R.color.coral));
            openGitHub.setOnClickListener(view -> openGitHubIssue(title, report));
            Button share = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            share.setTextColor(getColor(R.color.mint));
            share.setOnClickListener(view -> shareBugReport(title, report));
            Button edit = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            edit.setTextColor(getColor(R.color.text_secondary));
            edit.setOnClickListener(view -> {
                movingToEditor[0] = true;
                bugReportStep = BUG_REPORT_EDITING;
                dialog.dismiss();
                showBugReport(
                        fromError,
                        description,
                        includedDiagnostics,
                        includedVideo
                );
            });
        });
        dialog.setOnDismissListener(ignored -> {
            if (!movingToEditor[0]) {
                clearBugReportState();
            }
        });
        dialog.show();
    }

    private void openGitHubIssue(String title, String report) {
        if (AppNetwork.isTorEnabled()) {
            confirmExternalTorAction(() -> openGitHubIssueDirect(title, report));
        } else {
            openGitHubIssueDirect(title, report);
        }
    }

    private void openGitHubIssueDirect(String title, String report) {
        Uri issueUri = Uri.parse(BUG_REPORT_URL)
                .buildUpon()
                .appendQueryParameter("title", title)
                .appendQueryParameter("body", report)
                .build();
        if (issueUri.toString().length() > MAX_GITHUB_PREFILL_URI_CHARS) {
            Toast.makeText(
                    this,
                    R.string.long_report_share_fallback,
                    Toast.LENGTH_LONG
            ).show();
            shareBugReportDirect(title, report);
            return;
        }
        Intent openGitHub = new Intent(Intent.ACTION_VIEW, issueUri);
        Intent chooser = Intent.createChooser(
                openGitHub,
                getString(R.string.open_github_with)
        );
        try {
            startActivity(chooser);
        } catch (ActivityNotFoundException error) {
            shareBugReportDirect(title, report);
        }
    }

    private void shareBugReport(String title, String report) {
        if (AppNetwork.isTorEnabled()) {
            confirmExternalTorAction(() -> shareBugReportDirect(title, report));
        } else {
            shareBugReportDirect(title, report);
        }
    }

    private void shareBugReportDirect(String title, String report) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, title)
                .putExtra(Intent.EXTRA_TEXT, report);
        Intent chooser = Intent.createChooser(
                share,
                getString(R.string.bug_report_share_title)
        );
        chooser.putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                new ComponentName[]{getComponentName()}
        );
        try {
            startActivity(chooser);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(
                    this,
                    R.string.no_app_for_bug_report,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private Map<String, String> collectTechnicalDetails() {
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put("App version", installedVersion());
        details.put("Package", getPackageName());
        details.put(
                "Build",
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                        ? "Debug"
                        : "Release"
        );
        details.put(
                "Android",
                Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
        );
        details.put("Device", deviceName());
        details.put(
                "Locale",
                getResources().getConfiguration().getLocales().get(0).toLanguageTag()
        );
        details.put("Orientation", orientationName());
        details.put("Quality limit", preferenceStore.maxHeight() + "p");
        details.put("Sponsor categories", enabledSponsorCategories());
        details.put("Sponsor lookup", sponsorLookupStatus);
        details.put("Player state", playerStateName());
        details.put("Resolved stream", resolvedStreamName());
        details.put("Playback retry attempted", retriedAfterPlaybackFailure ? "Yes" : "No");
        details.put(
                "Last failure",
                lastFailureStage == null
                        ? "None recorded this session"
                        : lastFailureStage + " / " + lastFailureType
        );
        return details;
    }

    private String installedVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : info.versionCode;
            String versionName = info.versionName == null ? "Unknown" : info.versionName;
            return versionName + " (" + versionCode + ")";
        } catch (PackageManager.NameNotFoundException error) {
            return "Unknown";
        }
    }

    private static String deviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (model.toLowerCase(Locale.US).startsWith(manufacturer.toLowerCase(Locale.US))) {
            return model.isEmpty() ? "Unknown" : model;
        }
        String combined = (manufacturer + " " + model).trim();
        return combined.isEmpty() ? "Unknown" : combined;
    }

    private String enabledSponsorCategories() {
        List<String> categories = preferenceStore.enabledSponsorCategories();
        if (categories.isEmpty()) {
            return "None";
        }
        StringBuilder label = new StringBuilder();
        for (String category : categories) {
            if (label.length() > 0) {
                label.append(", ");
            }
            label.append(friendlyCategory(category));
        }
        return label.toString();
    }

    private String orientationName() {
        return switch (getResources().getConfiguration().orientation) {
            case Configuration.ORIENTATION_LANDSCAPE -> "Landscape";
            case Configuration.ORIENTATION_PORTRAIT -> "Portrait";
            case Configuration.ORIENTATION_SQUARE -> "Square";
            case Configuration.ORIENTATION_UNDEFINED -> "Unknown";
            default -> "Unknown";
        };
    }

    private String playerStateName() {
        String state = switch (player.getPlaybackState()) {
            case Player.STATE_IDLE -> "Idle";
            case Player.STATE_BUFFERING -> "Buffering";
            case Player.STATE_READY -> "Ready";
            case Player.STATE_ENDED -> "Ended";
            default -> "Unknown";
        };
        if (player.isPlaying()) {
            return state + " / playing";
        }
        if (player.getPlayWhenReady()) {
            return state + " / waiting";
        }
        return state + " / paused";
    }

    private String resolvedStreamName() {
        if (activeOfflineRecord != null) {
            int height = activeOfflineRecord.getSelectedHeight();
            String quality = height > 0 ? " / " + height + "p" : "";
            return "device-locked offline / "
                    + activeOfflineRecord.getSourceType().name()
                    .toLowerCase(Locale.US)
                    + quality;
        }
        if (activeVideo == null) {
            return activeUrl == null ? "None" : "Not resolved";
        }
        int height = activeVideo.getSelectedHeight();
        String quality = height > 0 ? " / " + height + "p" : "";
        return activeVideo.getSourceType().name().toLowerCase(Locale.US) + quality;
    }

    private void recordFailure(String stage, String type) {
        lastFailureStage = stage;
        lastFailureType = type;
    }

    private void clearFailure() {
        lastFailureStage = null;
        lastFailureType = null;
    }

    private void restoreBugReport(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }
        int savedStep = savedInstanceState.getInt(
                STATE_BUG_REPORT_STEP,
                BUG_REPORT_CLOSED
        );
        if (savedStep != BUG_REPORT_EDITING && savedStep != BUG_REPORT_REVIEWING) {
            return;
        }

        String savedDraft = savedInstanceState.getString(STATE_BUG_REPORT_DRAFT, "");
        boolean savedDiagnostics =
                savedInstanceState.getBoolean(STATE_BUG_REPORT_DIAGNOSTICS, false);
        boolean savedVideo = savedInstanceState.getBoolean(STATE_BUG_REPORT_VIDEO, false);
        boolean savedFromError =
                savedInstanceState.getBoolean(STATE_BUG_REPORT_FROM_ERROR, false);
        String savedPreview = savedInstanceState.getString(STATE_BUG_REPORT_PREVIEW);
        String savedVideoUrl = savedInstanceState.getString(STATE_BUG_REPORT_VIDEO_URL);
        ArrayList<String> detailKeys =
                savedInstanceState.getStringArrayList(STATE_BUG_REPORT_DETAIL_KEYS);
        ArrayList<String> detailValues =
                savedInstanceState.getStringArrayList(STATE_BUG_REPORT_DETAIL_VALUES);
        LinkedHashMap<String, String> savedTechnicalSnapshot = new LinkedHashMap<>();
        if (detailKeys != null && detailValues != null) {
            int detailCount = Math.min(detailKeys.size(), detailValues.size());
            for (int index = 0; index < detailCount; index++) {
                savedTechnicalSnapshot.put(detailKeys.get(index), detailValues.get(index));
            }
        }
        bugReportStep = savedStep;
        bugReportDraft = savedDraft;
        bugReportIncludesDiagnostics = savedDiagnostics;
        bugReportIncludesVideo = savedVideo;
        bugReportFromError = savedFromError;
        bugReportPreview = savedPreview;
        bugReportVideoUrlSnapshot = savedVideoUrl;
        bugReportTechnicalSnapshot = savedTechnicalSnapshot.isEmpty()
                ? null
                : savedTechnicalSnapshot;
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (savedStep == BUG_REPORT_REVIEWING && savedPreview != null) {
                showBugReportPreview(
                        savedFromError,
                        savedDraft,
                        savedDiagnostics,
                        savedVideo,
                        savedPreview
                );
            } else {
                showBugReport(
                        savedFromError,
                        savedDraft,
                        savedDiagnostics,
                        savedVideo
                );
            }
        });
    }

    private void captureBugReportEditorState() {
        if (bugReportStep != BUG_REPORT_EDITING) {
            return;
        }
        if (activeBugReportDescription != null) {
            bugReportDraft = activeBugReportDescription.getText().toString();
        }
        if (activeBugReportDiagnostics != null) {
            bugReportIncludesDiagnostics = activeBugReportDiagnostics.isChecked();
        }
        bugReportIncludesVideo =
                activeBugReportVideo != null && activeBugReportVideo.isChecked();
    }

    private void clearBugReportState() {
        bugReportStep = BUG_REPORT_CLOSED;
        bugReportDraft = "";
        bugReportIncludesDiagnostics = false;
        bugReportIncludesVideo = false;
        bugReportFromError = false;
        bugReportPreview = null;
        bugReportTechnicalSnapshot = null;
        bugReportVideoUrlSnapshot = null;
        activeBugReportDescription = null;
        activeBugReportDiagnostics = null;
        activeBugReportVideo = null;
    }

    private void refreshSponsorPreferences() {
        updateProtectionText();
        if (activeVideo != null) {
            fetchSponsorSegments(activeVideo.getVideoId(), loadGeneration.get());
        }
    }

    private boolean handleUpdateIntent(Intent intent) {
        if (intent == null
                || !UpdateNotificationManager.ACTION_SHOW_UPDATE.equals(intent.getAction())) {
            return false;
        }
        if (!UpdateNotificationManager.isTrustedTapIntent(this, intent)) {
            return false;
        }
        // MainActivity is exported for YouTube links. The private token authenticates this
        // navigation request, and the download URL is still read only from private state.
        intent.setAction(null);
        mainHandler.post(this::showAvailableUpdate);
        return true;
    }

    private void showAvailableUpdate() {
        UpdateRelease release = availableUpdate();
        if (release != null) {
            showUpdateDialog(release);
        }
    }

    private void showUpdateDialog(UpdateRelease requestedRelease) {
        UpdateRelease release = availableUpdate();
        if (release == null) {
            return;
        }
        if (!release.equals(requestedRelease)) {
            mainHandler.post(() -> showUpdateDialog(release));
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(
                        R.string.update_dialog_title,
                        release.getVersionName()
                ))
                .setMessage(R.string.update_dialog_message)
                .setNegativeButton(R.string.later, null)
                .setPositiveButton(
                        R.string.download_update,
                        (ignoredDialog, ignoredWhich) ->
                                confirmAndOpenUpdateDownload(release)
                )
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(getColor(R.color.coral));
        });
        dialog.show();
    }

    private void confirmAndOpenUpdateDownload(UpdateRelease displayedRelease) {
        UpdateRelease currentRelease = availableUpdate();
        if (currentRelease == null) {
            return;
        }
        if (!currentRelease.equals(displayedRelease)) {
            mainHandler.post(() -> showUpdateDialog(currentRelease));
            return;
        }
        Toast.makeText(
                this,
                R.string.update_download_verifying,
                Toast.LENGTH_SHORT
        ).show();
        Context applicationContext = getApplicationContext();
        long verificationGeneration =
                updateDownloadVerificationGeneration.incrementAndGet();
        updateCheckExecutor.execute(() -> {
            UpdateChecker.Result result;
            try {
                result = new UpdateChecker(applicationContext).check();
            } catch (RuntimeException unexpectedFailure) {
                result = null;
            }
            UpdateChecker.Result completedResult = result;
            mainHandler.post(() -> finishUpdateDownloadVerification(
                    displayedRelease,
                    completedResult,
                    verificationGeneration
            ));
        });
    }

    private void finishUpdateDownloadVerification(
            UpdateRelease displayedRelease,
            UpdateChecker.Result result,
            long verificationGeneration
    ) {
        boolean resumed = getLifecycle()
                .getCurrentState()
                .isAtLeast(Lifecycle.State.RESUMED);
        if (destroyed
                || isFinishing()
                || isDestroyed()
                || !UpdateDownloadVerification.canApplyResult(
                        verificationGeneration,
                        updateDownloadVerificationGeneration.get(),
                        resumed
                )) {
            return;
        }
        UpdateChecker.Status status = result == null ? null : result.getStatus();
        UpdateRelease checkedRelease =
                result == null ? null : result.getCheckedRelease();
        UpdateDownloadVerification.Action action = UpdateDownloadVerification.decide(
                status,
                displayedRelease,
                checkedRelease
        );
        switch (action) {
            case OPEN_VERIFIED_RELEASE:
                openUpdateDownload(checkedRelease);
                break;
            case SHOW_REFRESHED_RELEASE:
                showUpdateDialog(checkedRelease);
                break;
            case SHOW_ERROR:
            default:
                Toast.makeText(
                        this,
                        status == UpdateChecker.Status.UNVERIFIED_RELEASE
                                ? R.string.update_check_unverified
                                : R.string.update_download_verification_error,
                        Toast.LENGTH_LONG
                ).show();
                break;
        }
    }

    private void openUpdateDownload(UpdateRelease release) {
        // Start from the HTML release page so download confirmation and retry stay
        // inside a browser instead of competing with handlers for the raw APK URL.
        if (!openInBrowser(release.getReleaseUrl())) {
            Toast.makeText(this, R.string.no_app_for_update, Toast.LENGTH_LONG).show();
        }
    }

    private boolean openInBrowser(String url) {
        if (AppNetwork.isTorEnabled()) {
            confirmExternalTorAction(() -> {
                if (!openInBrowserDirect(url)) {
                    Toast.makeText(this, R.string.no_app_for_update, Toast.LENGTH_LONG).show();
                }
            });
            return true;
        }
        return openInBrowserDirect(url);
    }

    private void confirmExternalTorAction(Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.tor_external_title)
                .setMessage(R.string.tor_external_detail)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.tor_external_continue, (dialog, which) -> action.run())
                .show();
    }

    private boolean openInBrowserDirect(String url) {
        Uri uri = Uri.parse(url);
        Intent browserIntent = Intent.makeMainSelectorActivity(
                Intent.ACTION_MAIN,
                Intent.CATEGORY_APP_BROWSER
        );
        browserIntent.setData(uri);
        if (startExternalActivity(browserIntent)) {
            return true;
        }
        Intent webFallback = new Intent(Intent.ACTION_VIEW, uri);
        webFallback.addCategory(Intent.CATEGORY_BROWSABLE);
        return startExternalActivity(webFallback);
    }

    private boolean startExternalActivity(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException noHandler) {
            return false;
        }
    }

    private UpdateRelease availableUpdate() {
        UpdateRelease release = updatePreferences.availableRelease();
        if (release != null && !release.isNewerThan(installedVersionCode())) {
            updatePreferences.clearAvailableRelease();
            UpdateNotificationManager.cancel(this);
            return null;
        }
        return release;
    }

    private long installedVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : info.versionCode;
        } catch (PackageManager.NameNotFoundException missingOwnPackage) {
            return Long.MAX_VALUE;
        }
    }

    private void maybeExplainUpdateNotifications(Intent launchIntent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || launchIntent == null
                || !Intent.ACTION_MAIN.equals(launchIntent.getAction())
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
                || updatePreferences.permissionExplanationShown()
                || isFinishing()
                || isDestroyed()) {
            return;
        }

        updatePreferences.markPermissionExplanationShown();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_message)
                .setNegativeButton(R.string.not_now, null)
                .setPositiveButton(R.string.allow, (ignoredDialog, ignoredWhich) -> {
                    updatePreferences.markPermissionRequestAttempted();
                    requestPermissions(
                            new String[]{Manifest.permission.POST_NOTIFICATIONS},
                            REQUEST_UPDATE_NOTIFICATIONS
                    );
                })
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(getColor(R.color.coral));
        });
        dialog.show();
    }

    private void openUpdateNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && !updatePreferences.permissionRequestAttempted()) {
            updatePreferences.markPermissionRequestAttempted();
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_UPDATE_NOTIFICATIONS
            );
            return;
        }

        Intent intent;
        if (UpdateNotificationManager.notificationsAllowed(this)) {
            intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(
                            Settings.EXTRA_CHANNEL_ID,
                            UpdateNotificationManager.CHANNEL_ID
                    );
        } else {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException noNotificationSettings) {
            Intent fallback = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(fallback);
        }
    }

    private boolean handleIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String candidate = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            candidate = intent.getDataString();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            candidate = intent.getStringExtra(Intent.EXTRA_TEXT);
        }
        if (candidate == null) {
            return false;
        }
        YouTubeUrlParser.PlayableLink link =
                YouTubeUrlParser.parsePlayable(candidate);
        if (link == null) {
            recordFailure("link validation", "Invalid or unsupported YouTube link");
            showError(getString(R.string.invalid_link));
            return true;
        }
        linkInput.setText(link.getCanonicalUrl());
        startPlayable(link, -1, null, PlaybackStartReason.USER);
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!handleUpdateIntent(intent)) {
            handleIntent(intent);
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OFFLINE_CREDENTIAL) {
            return;
        }
        awaitingOfflineCredential = false;
        if (resultCode == RESULT_OK) {
            if (pendingOfflineAction == PendingOfflineAction.NONE) {
                clearPendingOfflineCredential();
                return;
            }
            offlineCredentialApproved = true;
            mainHandler.post(this::performApprovedOfflineActionIfResumed);
            return;
        }
        clearPendingOfflineCredential();
        Toast.makeText(
                this,
                R.string.offline_auth_cancelled,
                Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_UPDATE_NOTIFICATIONS) {
            return;
        }
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            UpdateScheduler.checkNow(this);
        } else {
            Toast.makeText(
                    this,
                    R.string.update_notifications_denied,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        synchronizeNetworkRoute();
        boolean notificationsAllowed =
                UpdateNotificationManager.notificationsAllowed(this);
        if (notificationsAllowed && !updateNotificationsWereAllowed) {
            UpdateScheduler.checkNow(this);
        }
        updateNotificationsWereAllowed = notificationsAllowed;
        refreshOfflineControls();
        mainHandler.post(this::performApprovedOfflineActionIfResumed);
        subscriptionPanel.onResume();
    }

    @Override
    protected void onStart() {
        super.onStart();
        playbackSessionState.onStart();
        player.setPlayWhenReady(playbackSessionState.shouldPlayWhenReady());
        skipController.start();
    }

    @Override
    protected void onPause() {
        subscriptionPanel.onPause();
        boolean credentialPromptInFlight = awaitingOfflineCredential;
        revokeOfflineForegroundAccess();
        if (!credentialPromptInFlight) {
            clearPendingOfflineCredential();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        updateDownloadVerificationGeneration.incrementAndGet();
        boolean credentialPromptInFlight = awaitingOfflineCredential;
        revokeOfflineForegroundAccess();
        if (!credentialPromptInFlight) {
            clearPendingOfflineCredential();
        }
        skipController.stop();
        playbackSessionState.onStop();
        player.setPlayWhenReady(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        subscriptionPanel.destroy();
        AppNetwork.removeRouteChangeListener(networkRouteListener);
        revokeOfflineForegroundAccess();
        clearPendingOfflineCredential();
        loadGeneration.incrementAndGet();
        playlistLoadGeneration.incrementAndGet();
        mainHandler.removeCallbacksAndMessages(null);
        cancelTask(activeVideoResolveTask);
        cancelTask(activePlaylistResolveTask);
        videoResolverExecutor.shutdownNow();
        playlistResolverExecutor.shutdownNow();
        updateCheckExecutor.shutdownNow();
        offlineExecutor.shutdownNow();
        torCheckExecutor.shutdownNow();
        torSettingsGeneration.incrementAndGet();
        activeSettingsDialog = null;
        activeUpdateCheckDetail = null;
        activeUpdateCheckAction = null;
        playerView.setPlayer(null);
        player.release();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        captureBugReportEditorState();
        outState.putString(STATE_URL, activeUrl);
        outState.putString(
                STATE_SOURCE_URL,
                sourceUrl == null ? activeUrl : sourceUrl
        );
        String currentVideoId = activeVideo == null
                ? YouTubeUrlParser.extractVideoId(activeUrl)
                : activeVideo.getVideoId();
        if (currentVideoId == null) {
            currentVideoId = pendingRestoreVideoId;
        }
        outState.putString(STATE_CURRENT_VIDEO_ID, currentVideoId);
        outState.putInt(
                STATE_PLAYLIST_INDEX,
                playlistQueue == null
                        ? pendingRestorePlaylistIndex
                        : playlistQueue.position()
        );
        outState.putLong(
                STATE_POSITION,
                PlaybackSessionState.positionToSave(
                        selectedMediaPrepared,
                        pendingSeekMs,
                        player.getCurrentPosition()
                )
        );
        outState.putBoolean(
                STATE_PLAY_WHEN_READY,
                playbackSessionState.intendedPlayWhenReady()
        );
        outState.putInt(STATE_BUG_REPORT_STEP, bugReportStep);
        if (bugReportStep != BUG_REPORT_CLOSED) {
            outState.putString(STATE_BUG_REPORT_DRAFT, bugReportDraft);
            outState.putBoolean(
                    STATE_BUG_REPORT_DIAGNOSTICS,
                    bugReportIncludesDiagnostics
            );
            outState.putBoolean(STATE_BUG_REPORT_VIDEO, bugReportIncludesVideo);
            outState.putBoolean(STATE_BUG_REPORT_FROM_ERROR, bugReportFromError);
            outState.putString(STATE_BUG_REPORT_PREVIEW, bugReportPreview);
            outState.putString(STATE_BUG_REPORT_VIDEO_URL, bugReportVideoUrlSnapshot);
            if (bugReportTechnicalSnapshot != null) {
                outState.putStringArrayList(
                        STATE_BUG_REPORT_DETAIL_KEYS,
                        new ArrayList<>(bugReportTechnicalSnapshot.keySet())
                );
                outState.putStringArrayList(
                        STATE_BUG_REPORT_DETAIL_VALUES,
                        new ArrayList<>(bugReportTechnicalSnapshot.values())
                );
            }
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (fullscreen && newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            setFullscreen(false);
        }
    }

    private void setFullscreen(boolean enabled) {
        if (fullscreen == enabled) {
            return;
        }
        fullscreen = enabled;
        if (enabled) {
            originalPlayerParent = (ViewGroup) playerContainer.getParent();
            originalPlayerIndex = originalPlayerParent.indexOfChild(playerContainer);
            originalPlayerLayoutParams = playerContainer.getLayoutParams();
            originalPlayerParent.removeView(playerContainer);
            appContent.setVisibility(View.GONE);
            root.addView(playerContainer, matchParent());
            fullscreenButton.setContentDescription(getString(R.string.exit_fullscreen));
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            hideSystemBars();
        } else {
            ((ViewGroup) playerContainer.getParent()).removeView(playerContainer);
            originalPlayerParent.addView(
                    playerContainer,
                    originalPlayerIndex,
                    originalPlayerLayoutParams
            );
            appContent.setVisibility(View.VISIBLE);
            fullscreenButton.setContentDescription(getString(R.string.fullscreen));
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            showSystemBars();
        }
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    private void showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private void applySystemInsets() {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars =
                        insets.getInsets(WindowInsets.Type.systemBars());
                topBar.setPadding(dp(18), bars.top, dp(10), 0);
                ViewGroup.LayoutParams topParams = topBar.getLayoutParams();
                topParams.height = dp(58) + bars.top;
                topBar.setLayoutParams(topParams);
                appContent.setPadding(0, 0, 0, bars.bottom);
            }
            return insets;
        });
        root.requestApplyInsets();
    }

    private void showError(String message) {
        loadingIndicator.setVisibility(View.GONE);
        playbackStatus.setText("Unable to play");
        errorText.setText(message);
        errorCard.setVisibility(View.VISIBLE);
        errorCard.setFocusable(true);
        errorCard.requestFocus();
        errorCard.announceForAccessibility(message);
    }

    private String statusForReadyVideo() {
        if (activeOfflineRecord != null) {
            int offlineHeight = activeOfflineRecord.getSelectedHeight();
            return offlineHeight > 0
                    ? getString(R.string.offline_playback_ready)
                            + " • " + offlineHeight + "p"
                    : getString(R.string.offline_playback_ready);
        }
        if (activeVideo == null) {
            return "Ad-free playback";
        }
        int height = activeVideo.getSelectedHeight();
        return height > 0
                ? "Ad-free • " + height + "p"
                : "Ad-free playback";
    }

    private void updateProtectionText() {
        protectionStatus.setText(
                preferenceStore.enabledSponsorCategories().isEmpty()
                        ? "AD-FREE"
                        : "AD-FREE • SPONSORS"
        );
    }

    private String humanPlaylistError(Throwable error) {
        if (AppNetwork.isTorEnabled()) {
            return getString(R.string.tor_playback_help) + "\n\n" + deepestMessage(error);
        }
        String message = deepestMessage(error);
        String lower = message.toLowerCase(Locale.US);
        if (lower.contains("no playable")
                || lower.contains("empty playlist")
                || lower.contains("does not contain")) {
            return getString(R.string.playlist_no_playable_videos);
        }
        if (lower.contains("private")
                || lower.contains("sign in")
                || lower.contains("account")) {
            return "This playlist is private or requires a YouTube account. "
                    + "Plyvanta supports public playlists without signing in.";
        }
        return "Plyvanta could not load this playlist. YouTube sometimes changes its "
                + "playlist format; try again or update the app.\n\n"
                + message;
    }

    private String humanResolveError(Throwable error) {
        if (AppNetwork.isTorEnabled()) {
            return getString(R.string.tor_playback_help) + "\n\n" + deepestMessage(error);
        }
        String message = deepestMessage(error);
        String lower = message.toLowerCase(Locale.US);
        if (lower.contains("age") || lower.contains("sign in")) {
            return "This video requires sign-in or age verification, which Plyvanta does not "
                    + "store or request.";
        }
        if (lower.contains("private") || lower.contains("members")) {
            return "This video is private or members-only.";
        }
        if (lower.contains("region") || lower.contains("country")) {
            return "This video is not available in your region.";
        }
        return "Plyvanta could not resolve this video. YouTube sometimes changes its playback "
                + "format; try again or update the app.\n\n" + message;
    }

    private String humanPlaybackError(PlaybackException error) {
        if (AppNetwork.isTorEnabled()) {
            return getString(R.string.tor_playback_help) + "\n\n" + deepestMessage(error);
        }
        String detail = deepestMessage(error);
        return getString(R.string.playback_error)
                + " The stream may have expired or be restricted.\n\n"
                + detail;
    }

    private static String deepestMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    private static String deepestType(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    private static String friendlyCategory(String category) {
        return switch (category) {
            case "selfpromo" -> "self-promotion";
            case "interaction" -> "subscribe reminder";
            case "intro" -> "intro";
            case "outro" -> "outro";
            default -> "sponsor";
        };
    }

    private void hideKeyboard() {
        InputMethodManager manager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        manager.hideSoftInputFromWindow(linkInput.getWindowToken(), 0);
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.08f);
        return view;
    }

    private ImageButton iconButton(int icon, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setContentDescription(description);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        return button;
    }

    private Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(getColor(R.color.ink));
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.bg_primary_button);
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }

    private Button textButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(getColor(R.color.coral));
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setMinHeight(0);
        button.setMinWidth(0);
        return button;
    }

    private LinearLayout.LayoutParams spacedCardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(8), 0, dp(8));
        return params;
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
    }

    private FrameLayout.LayoutParams matchParentWrap() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private static int wrap() {
        return ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
