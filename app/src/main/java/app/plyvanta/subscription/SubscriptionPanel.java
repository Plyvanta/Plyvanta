package app.plyvanta.subscription;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import app.plyvanta.R;
import app.plyvanta.offline.ContentKeyProtector;
import app.plyvanta.offline.DeviceBoundKeyManager;

/** Native subscription UI. Network lookups and persistent changes run on its worker thread. */
public final class SubscriptionPanel {
    public interface Host {
        void playVideo(String publicVideoUrl);
        String offlineRestriction();
        void onAutoDownloadPreferencesChanged();
        void requestAutomaticDownloads();
        void onFeedChanged();
    }

    private static final long OBSERVE_INTERVAL_MS = 30_000L;
    private static final long REFRESH_INTERVAL_MS = 30 * 60_000L;

    private final Activity activity;
    private final SubscriptionStore store;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<AlertDialog> dialogs = new HashSet<>();
    private final FeedAdapter feedAdapter = new FeedAdapter();
    private TextView summary;
    private TextView status;
    private TextView automaticDetail;
    private Button addButton;
    private Button refreshButton;
    private Button feedButton;
    private Button channelsButton;
    private Button downloadButton;
    private LinearLayout channelRows;
    private AlertDialog channelDialog;
    private AlertDialog feedDialog;
    private TextView feedEmpty;
    private AlertDialog addDialog;
    private Future<?> refreshTask;
    private YouTubeChannelFeedClient refreshClient;
    private AtomicBoolean refreshCancelled;
    private boolean refreshing;
    private boolean mutating;
    private boolean resumed;
    private volatile boolean destroyed;
    private long lastRefreshAt;
    private int statusMessage;
    private int mutationMessage = R.string.subscriptions_saving;

    private final Runnable observer = new Runnable() {
        @Override
        public void run() {
            if (!resumed || destroyed) {
                return;
            }
            render();
            host.onFeedChanged();
            if (System.currentTimeMillis() - lastRefreshAt >= REFRESH_INTERVAL_MS) {
                refresh();
            }
            main.postDelayed(this, OBSERVE_INTERVAL_MS);
        }
    };

    public SubscriptionPanel(Activity activity, SubscriptionStore store, Host host) {
        this.activity = activity;
        this.store = store;
        this.host = host;
    }

    public View buildCard() {
        LinearLayout card = column(14);
        card.setBackgroundResource(R.drawable.bg_card);
        TextView heading = text(R.string.subscriptions_section, 11, R.color.text_secondary);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(heading);
        TextView detail = text(R.string.subscriptions_detail, 13, R.color.text_primary);
        detail.setPadding(0, dp(8), 0, 0);
        card.addView(detail);
        summary = text(0, 13, R.color.text_primary);
        summary.setPadding(0, dp(10), 0, 0);
        card.addView(summary);
        status = text(0, 12, R.color.text_secondary);
        status.setPadding(0, dp(5), 0, 0);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(status);

        LinearLayout navigation = actions();
        feedButton = button(R.string.subscriptions_feed, false);
        feedButton.setOnClickListener(view -> showFeed());
        navigation.addView(feedButton, actionParams());
        channelsButton = button(R.string.subscriptions_channels, false);
        channelsButton.setOnClickListener(view -> showChannels());
        navigation.addView(channelsButton, actionParams());
        card.addView(navigation);

        LinearLayout editActions = actions();
        refreshButton = button(R.string.subscriptions_refresh, false);
        refreshButton.setOnClickListener(view -> refresh());
        editActions.addView(refreshButton, actionParams());
        addButton = button(R.string.subscriptions_add_channel, true);
        addButton.setOnClickListener(view -> showAddChannel());
        editActions.addView(addButton, actionParams());
        card.addView(editActions);

        automaticDetail = text(R.string.subscriptions_auto_detail, 12, R.color.text_secondary);
        automaticDetail.setPadding(0, dp(8), 0, 0);
        card.addView(automaticDetail);
        downloadButton = button(R.string.subscriptions_download_new, false);
        downloadButton.setOnClickListener(view -> host.requestAutomaticDownloads());
        card.addView(downloadButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ));
        render();
        return card;
    }

    public void onResume() {
        if (destroyed) {
            return;
        }
        resumed = true;
        main.removeCallbacks(observer);
        render();
        refresh();
        main.postDelayed(observer, OBSERVE_INTERVAL_MS);
    }

    public void onPause() {
        resumed = false;
        main.removeCallbacks(observer);
        cancelRefresh();
    }

    public void destroy() {
        destroyed = true;
        onPause();
        executor.shutdownNow();
        main.removeCallbacksAndMessages(null);
        for (AlertDialog dialog : new ArrayList<>(dialogs)) {
            dialog.dismiss();
        }
        dialogs.clear();
    }

    public void render() {
        if (destroyed || summary == null) {
            return;
        }
        boolean available = store != null;
        List<ChannelSubscription> channels = available
                ? store.getSubscriptions() : new ArrayList<>();
        int videoCount = available ? store.getFeed().size() : 0;
        int automaticCount = 0;
        for (ChannelSubscription channel : channels) {
            if (channel.isAutoDownload()) {
                automaticCount++;
            }
        }
        summary.setText(!available ? activity.getString(R.string.subscriptions_unavailable)
                : channels.isEmpty() ? activity.getString(R.string.subscriptions_empty)
                : activity.getString(R.string.subscriptions_summary, channels.size(), videoCount));
        if (refreshing) {
            status.setText(R.string.subscriptions_refreshing);
        } else if (mutating) {
            status.setText(mutationMessage);
        } else if (statusMessage != 0) {
            status.setText(statusMessage);
        } else {
            status.setText("");
        }
        status.setVisibility(status.getText().length() == 0 ? View.GONE : View.VISIBLE);
        status.setTextColor(activity.getColor(statusMessage == R.string.subscriptions_refresh_error
                || statusMessage == R.string.subscriptions_refresh_partial
                || statusMessage == R.string.subscriptions_save_error
                || statusMessage == R.string.subscriptions_auto_unavailable
                || statusMessage == R.string.subscriptions_channel_error
                ? R.color.coral : R.color.text_secondary));
        addButton.setEnabled(available && !mutating);
        refreshButton.setEnabled(available && !channels.isEmpty() && !refreshing && !mutating);
        feedButton.setEnabled(available);
        channelsButton.setEnabled(available);
        automaticDetail.setVisibility(automaticCount > 0 ? View.VISIBLE : View.GONE);
        String restriction = host.offlineRestriction();
        automaticDetail.setText(restriction == null
                ? activity.getString(R.string.subscriptions_auto_detail) : restriction);
        downloadButton.setVisibility(automaticCount > 0 ? View.VISIBLE : View.GONE);
        downloadButton.setEnabled(available && !mutating && restriction == null);
        if (channelDialog != null && channelDialog.isShowing()) {
            renderChannels();
        }
        if (feedDialog != null && feedDialog.isShowing()) {
            feedEmpty.setText(channels.isEmpty() ? R.string.subscriptions_channel_list_empty
                    : R.string.subscriptions_feed_empty);
            feedAdapter.update();
        }
    }

    private void refresh() {
        if (destroyed || store == null || refreshing || mutating
                || store.getSubscriptions().isEmpty()) {
            return;
        }
        refreshing = true;
        statusMessage = 0;
        lastRefreshAt = System.currentTimeMillis();
        AtomicBoolean cancelled = new AtomicBoolean();
        YouTubeChannelFeedClient client = new YouTubeChannelFeedClient();
        refreshCancelled = cancelled;
        refreshClient = client;
        render();
        refreshTask = executor.submit(() -> {
            SubscriptionRefreshCoordinator.Result result =
                    new SubscriptionRefreshCoordinator(store, client).refresh(cancelled::get);
            post(() -> {
                if (cancelled.get() || refreshCancelled != cancelled) {
                    return;
                }
                refreshing = false;
                refreshTask = null;
                refreshClient = null;
                refreshCancelled = null;
                if (!result.isCancelled()) {
                    statusMessage = result.getFailedChannelIds().isEmpty()
                            ? R.string.subscriptions_refreshed
                            : result.getSuccessfulChannelCount() > 0
                            ? R.string.subscriptions_refresh_partial
                            : R.string.subscriptions_refresh_error;
                }
                render();
                host.onFeedChanged();
            });
        });
    }

    private void cancelRefresh() {
        if (refreshCancelled != null) {
            refreshCancelled.set(true);
        }
        if (refreshClient != null) {
            refreshClient.cancel();
        }
        if (refreshTask != null) {
            refreshTask.cancel(true);
        }
        refreshTask = null;
        refreshClient = null;
        refreshCancelled = null;
        refreshing = false;
    }

    private void showAddChannel() {
        if (store == null || destroyed || mutating) {
            return;
        }
        LinearLayout content = column(20);
        TextView label = text(R.string.subscriptions_channel_input_label, 11,
                R.color.text_secondary);
        content.addView(label);
        EditText input = new EditText(activity);
        input.setId(View.generateViewId());
        label.setLabelFor(input.getId());
        input.setHint(R.string.subscriptions_channel_hint);
        input.setTextColor(activity.getColor(R.color.text_primary));
        input.setHintTextColor(activity.getColor(R.color.text_secondary));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2_048)});
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        );
        inputParams.topMargin = dp(8);
        content.addView(input, inputParams);
        CheckBox automatic = autoCheckbox(false);
        content.addView(automatic);
        String restriction = host.offlineRestriction();
        automatic.setEnabled(restriction == null);
        content.addView(text(restriction == null
                ? activity.getString(R.string.subscriptions_auto_detail) : restriction,
                12, R.color.text_secondary));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.subscriptions_add_channel)
                .setView(content)
                .setNegativeButton(R.string.not_now, null)
                .setPositiveButton(R.string.subscriptions_subscribe, null)
                .create();
        addDialog = dialog;
        show(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) {
                input.setError(activity.getString(R.string.subscriptions_invalid_channel));
                return;
            }
            long subscribedAtMs = System.currentTimeMillis();
            boolean autoDownload = automatic.isChecked() && host.offlineRestriction() == null;
            Runnable subscribe = () -> {
                dialog.dismiss();
                subscribe(value, subscribedAtMs, autoDownload);
            };
            if (autoDownload) {
                confirmAutomaticDownloads(subscribe);
            } else {
                subscribe.run();
            }
        });
    }

    private void subscribe(String input, long subscribedAtMs, boolean autoDownload) {
        if (destroyed || store == null || mutating) {
            return;
        }
        cancelRefresh();
        mutating = true;
        mutationMessage = R.string.subscriptions_resolving;
        statusMessage = 0;
        render();
        executor.submit(() -> {
            int errorMessage = R.string.subscriptions_channel_error;
            try {
                YouTubeChannelResolver.ResolvedChannel channel =
                        new YouTubeChannelResolver().resolve(input);
                if (destroyed || Thread.currentThread().isInterrupted()) {
                    return;
                }
                boolean duplicate = false;
                for (ChannelSubscription existing : store.getSubscriptions()) {
                    if (existing.getChannelId().equals(channel.getChannelId())) {
                        duplicate = true;
                        break;
                    }
                }
                if (autoDownload && !duplicate) {
                    errorMessage = R.string.subscriptions_auto_unavailable;
                    new DeviceBoundKeyManager(activity.getApplicationContext())
                            .prepareBackgroundDownloads();
                    if (destroyed || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                }
                errorMessage = R.string.subscriptions_save_error;
                store.subscribe(channel.getChannelId(), channel.getName(), subscribedAtMs,
                        autoDownload);
                boolean wasDuplicate = duplicate;
                post(() -> {
                    mutating = false;
                    Toast.makeText(activity, activity.getString(wasDuplicate
                            ? R.string.subscriptions_duplicate : R.string.subscriptions_saved,
                            channel.getName()), Toast.LENGTH_LONG).show();
                    changed();
                    refresh();
                });
            } catch (Exception failure) {
                int message = errorMessage;
                post(() -> {
                    mutating = false;
                    statusMessage = message;
                    render();
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showChannels() {
        if (store == null || destroyed) {
            return;
        }
        channelRows = column(16);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(channelRows);
        channelDialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.subscriptions_channels)
                .setView(scroll)
                .setPositiveButton(R.string.subscriptions_close, null)
                .create();
        renderChannels();
        show(channelDialog);
    }

    private void renderChannels() {
        if (channelRows == null || store == null) {
            return;
        }
        channelRows.removeAllViews();
        List<ChannelSubscription> channels = store.getSubscriptions();
        if (channels.isEmpty()) {
            channelRows.addView(text(R.string.subscriptions_channel_list_empty, 14,
                    R.color.text_secondary));
        }
        String restriction = host.offlineRestriction();
        for (ChannelSubscription channel : channels) {
            LinearLayout row = column(12);
            row.setBackgroundResource(R.drawable.bg_card);
            TextView name = text(channel.getName(), 16, R.color.text_primary);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.addView(name);
            row.addView(text(activity.getString(R.string.subscriptions_channel_since,
                    date(channel.getSubscribedAtMs())), 12, R.color.text_secondary));
            CheckBox automatic = autoCheckbox(channel.isAutoDownload());
            automatic.setEnabled(!mutating && (channel.isAutoDownload() || restriction == null));
            automatic.setOnClickListener(view -> {
                boolean enable = automatic.isChecked();
                automatic.setChecked(channel.isAutoDownload());
                Runnable save = () -> mutate(() -> {
                    if (enable) {
                        new DeviceBoundKeyManager(activity.getApplicationContext())
                                .prepareBackgroundDownloads();
                        if (destroyed || Thread.currentThread().isInterrupted()) {
                            return;
                        }
                    }
                    store.setAutoDownload(channel.getChannelId(), enable);
                }, enable ? R.string.subscriptions_auto_unavailable
                        : R.string.subscriptions_save_error);
                if (enable) {
                    confirmAutomaticDownloads(save);
                } else {
                    save.run();
                }
            });
            row.addView(automatic);
            if (restriction != null) {
                row.addView(text(restriction, 12, R.color.text_secondary));
            }
            Button unsubscribe = button(R.string.subscriptions_unsubscribe, false);
            unsubscribe.setEnabled(!mutating);
            unsubscribe.setOnClickListener(view -> confirmUnsubscribe(channel));
            row.addView(unsubscribe, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.bottomMargin = dp(10);
            channelRows.addView(row, params);
        }
    }

    private void confirmAutomaticDownloads(Runnable confirmed) {
        String restriction = host.offlineRestriction();
        if (restriction != null) {
            Toast.makeText(activity, restriction, Toast.LENGTH_LONG).show();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.subscriptions_auto_rights_title)
                .setMessage(activity.getString(R.string.offline_rights_message) + "\n\n"
                        + activity.getString(R.string.subscriptions_auto_rights_message))
                .setNegativeButton(R.string.not_now, null)
                .setPositiveButton(R.string.subscriptions_auto_rights_confirm,
                        (ignored, which) -> confirmed.run())
                .create();
        show(dialog);
    }

    private void confirmUnsubscribe(ChannelSubscription channel) {
        show(new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.subscriptions_unsubscribe_title,
                        channel.getName()))
                .setMessage(R.string.subscriptions_unsubscribe_message)
                .setNegativeButton(R.string.not_now, null)
                .setPositiveButton(R.string.subscriptions_unsubscribe,
                        (dialog, which) -> mutate(() -> store.unsubscribe(channel.getChannelId()),
                                R.string.subscriptions_save_error))
                .create());
    }

    private interface Change {
        void save() throws Exception;
    }

    private void mutate(Change change, int failureMessage) {
        if (destroyed || mutating || store == null) {
            return;
        }
        cancelRefresh();
        mutating = true;
        mutationMessage = R.string.subscriptions_saving;
        statusMessage = 0;
        render();
        executor.submit(() -> {
            try {
                change.save();
                post(() -> {
                    mutating = false;
                    changed();
                });
            } catch (Exception failure) {
                int message = failure instanceof ContentKeyProtector.KeyProtectionException
                        ? failureMessage : R.string.subscriptions_save_error;
                post(() -> {
                    mutating = false;
                    statusMessage = message;
                    render();
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void changed() {
        render();
        host.onAutoDownloadPreferencesChanged();
        host.onFeedChanged();
    }

    private void showFeed() {
        if (store == null || destroyed) {
            return;
        }
        LinearLayout content = column(0);
        ListView list = new ListView(activity);
        list.setDividerHeight(dp(8));
        list.setAdapter(feedAdapter);
        TextView empty = text(store.getSubscriptions().isEmpty()
                ? R.string.subscriptions_channel_list_empty : R.string.subscriptions_feed_empty,
                14, R.color.text_secondary);
        feedEmpty = empty;
        empty.setPadding(dp(20), dp(16), dp(20), dp(16));
        content.addView(empty);
        content.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        list.setEmptyView(empty);
        feedAdapter.update();
        feedDialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.subscriptions_feed)
                .setView(content)
                .setPositiveButton(R.string.subscriptions_close, null)
                .create();
        show(feedDialog);
    }

    private final class FeedAdapter extends BaseAdapter {
        private List<SubscriptionVideo> videos = new ArrayList<>();
        private final Map<String, String> channelNames = new HashMap<>();

        void update() {
            if (store == null) {
                return;
            }
            videos = store.getFeed();
            channelNames.clear();
            for (ChannelSubscription channel : store.getSubscriptions()) {
                channelNames.put(channel.getChannelId(), channel.getName());
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return videos.size();
        }

        @Override
        public SubscriptionVideo getItem(int position) {
            return videos.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            TextView title;
            TextView metadata;
            Button play;
            if (recycled == null) {
                row = column(14);
                title = text(0, 15, R.color.text_primary);
                title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                row.addView(title);
                metadata = text(0, 12, R.color.text_secondary);
                metadata.setPadding(0, dp(6), 0, dp(4));
                row.addView(metadata);
                play = button(R.string.play, false);
                row.addView(play, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
                ));
            } else {
                row = (LinearLayout) recycled;
                title = (TextView) row.getChildAt(0);
                metadata = (TextView) row.getChildAt(1);
                play = (Button) row.getChildAt(2);
            }
            SubscriptionVideo video = getItem(position);
            title.setText(video.getTitle());
            metadata.setText(activity.getString(R.string.subscriptions_feed_metadata,
                    channelNames.getOrDefault(video.getChannelId(), video.getChannelId()),
                    date(video.getPublishedAtMs())));
            play.setContentDescription(activity.getString(R.string.play) + " " + video.getTitle());
            play.setOnClickListener(view -> {
                if (feedDialog != null) {
                    feedDialog.dismiss();
                }
                host.playVideo(video.getVideoUrl());
            });
            return row;
        }
    }

    private void post(Runnable action) {
        if (!destroyed) {
            main.post(() -> {
                if (!destroyed && !activity.isFinishing()) {
                    action.run();
                }
            });
        }
    }

    private void show(AlertDialog dialog) {
        if (destroyed || activity.isFinishing()) {
            return;
        }
        dialogs.add(dialog);
        dialog.setOnDismissListener(ignored -> {
            dialogs.remove(dialog);
            if (dialog == addDialog) {
                addDialog = null;
            }
            if (dialog == channelDialog) {
                channelDialog = null;
                channelRows = null;
            }
            if (dialog == feedDialog) {
                feedDialog = null;
                feedEmpty = null;
            }
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(activity.getColor(R.color.coral));
    }

    private CheckBox autoCheckbox(boolean checked) {
        CheckBox checkbox = new CheckBox(activity);
        checkbox.setText(R.string.subscriptions_auto_download);
        checkbox.setTextSize(14);
        checkbox.setTextColor(activity.getColor(R.color.text_primary));
        checkbox.setButtonTintList(ColorStateList.valueOf(activity.getColor(R.color.mint)));
        checkbox.setChecked(checked);
        checkbox.setPadding(0, dp(8), 0, dp(4));
        return checkbox;
    }

    private LinearLayout column(int padding) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        return layout;
    }

    private LinearLayout actions() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);
        return row;
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private Button button(int resource, boolean primary) {
        Button button = new Button(activity);
        button.setText(resource);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setTextColor(activity.getColor(primary ? R.color.ink : R.color.coral));
        if (primary) {
            button.setBackgroundResource(R.drawable.bg_primary_button);
        } else {
            button.setBackgroundColor(Color.TRANSPARENT);
        }
        return button;
    }

    private TextView text(int resource, int size, int color) {
        return text(resource == 0 ? "" : activity.getString(resource), size, color);
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(activity.getColor(color));
        text.setLineSpacing(0, 1.08f);
        return text;
    }

    private String date(long timestamp) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(timestamp));
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
