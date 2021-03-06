package org.tvheadend.tvhclient.utils;


import android.app.Activity;
import android.app.SearchManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;

import org.tvheadend.tvhclient.DataStorage;
import org.tvheadend.tvhclient.DatabaseHelper;
import org.tvheadend.tvhclient.R;
import org.tvheadend.tvhclient.TVHClientApplication;
import org.tvheadend.tvhclient.activities.DownloadActivity;
import org.tvheadend.tvhclient.activities.PlayActivity;
import org.tvheadend.tvhclient.activities.SearchResultActivity;
import org.tvheadend.tvhclient.adapter.ChannelListSelectionAdapter;
import org.tvheadend.tvhclient.adapter.ChannelTagListAdapter;
import org.tvheadend.tvhclient.adapter.GenreColorDialogAdapter;
import org.tvheadend.tvhclient.htsp.HTSService;
import org.tvheadend.tvhclient.model.Channel;
import org.tvheadend.tvhclient.model.ChannelTag;
import org.tvheadend.tvhclient.model.Connection;
import org.tvheadend.tvhclient.model.GenreColorDialogItem;
import org.tvheadend.tvhclient.model.Profile;
import org.tvheadend.tvhclient.model.Program;
import org.tvheadend.tvhclient.model.Recording;
import org.tvheadend.tvhclient.model.SeriesRecording;
import org.tvheadend.tvhclient.model.TimerRecording;

import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class MenuUtils {
    private final static String TAG = MiscUtils.class.getSimpleName();

    private final int mHtspVersion;
    private final boolean mIsUnlocked;
    private WeakReference<Activity> activity;

    public MenuUtils(Activity activity) {
        this.activity = new WeakReference<>(activity);
        mHtspVersion = DataStorage.getInstance().getProtocolVersion();
        mIsUnlocked = TVHClientApplication.getInstance().isUnlocked();
    }

    /**
     * Prepares a dialog that shows the available genre colors and the names. In
     * here the data for the adapter is created and the dialog prepared which
     * can be shown later.
     */
    public void handleMenuGenreColorSelection() {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        final String[] s = activity.getResources().getStringArray(R.array.pr_content_type0);

        // Fill the list for the adapter
        final List<GenreColorDialogItem> items = new ArrayList<>();
        for (int i = 0; i < s.length; ++i) {
            GenreColorDialogItem genreColor = new GenreColorDialogItem();
            genreColor.color = MiscUtils.getGenreColor(activity, ((i + 1) * 16), 0);
            genreColor.genre = s[i];
            items.add(genreColor);
        }
        new MaterialDialog.Builder(activity)
                .title(R.string.genre_color_list)
                .adapter(new GenreColorDialogAdapter(items), null)
                .show();
    }

    public void handleMenuTimeSelection(int currentSelection, MenuTimeSelectionCallback callback) {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        // TODO show the next 12h, consider overflow into the next day
        // Get the current hour of the day and create
        // a list of the next hours until midnight
        Calendar c = Calendar.getInstance();
        final int hourOfDay = c.get(Calendar.HOUR_OF_DAY);
        String[] times = new String[12];
        times[0] = activity.getString(R.string.current_time);

        // TODO show a locale dependant time representation
        for (int i = 1; i < 12; i++) {
            int hour = (hourOfDay + i);
            times[i] = String.valueOf(hour <= 24 ? hour : hour - 24) + ":00";
        }

        new MaterialDialog.Builder(activity)
                .title(R.string.select_time)
                .items(times)
                .itemsCallbackSingleChoice(currentSelection, (dialog, itemView, which, text) -> {
                    // Convert the selected index into hours in seconds
                    // and reload the data with the given offset
                    if (callback != null) {
                        callback.menuTimeSelected(which);
                    }
                    return true;
                })
                .build()
                .show();

    }

    /**
     * @param selectedTagId
     * @param callback
     */
    public void handleMenuTagsSelection(long selectedTagId, MenuTagSelectionCallback callback) {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }

        // Fill the channel tag adapter with the available channel tags
        List<ChannelTag> channelTagList = new ArrayList<>();
        Map<Integer, ChannelTag> map = DataStorage.getInstance().getTagsFromArray();
        channelTagList.addAll(map.values());

        // Sort the channel tag list before showing it
        Collections.sort(channelTagList, new Comparator<ChannelTag>() {
            @Override
            public int compare(ChannelTag o1, ChannelTag o2) {
                return o1.tagName.compareTo(o2.tagName);
            }
        });

        // Add the default tag (all channels) to the list after it has been sorted
        ChannelTag tag = new ChannelTag();
        tag.tagId = 0;
        tag.tagName = activity.getString(R.string.all_channels);
        channelTagList.add(0, tag);

        final ChannelTagListAdapter channelTagListAdapter = new ChannelTagListAdapter(activity, channelTagList, selectedTagId);
        // Show the dialog that shows all available channel tags. When the
        // user has selected a tag, restart the loader to get the updated channel list
        final MaterialDialog dialog = new MaterialDialog.Builder(activity)
                .title(R.string.tags)
                .adapter(channelTagListAdapter, null)
                .build();
        // Set the callback to handle clicks. This needs to be done after the
        // dialog creation so that the inner method has access to the dialog variable
        channelTagListAdapter.setCallback(which -> {
            if (callback != null) {
                callback.menuTagSelected(which);
            }
            if (dialog != null) {
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    /**
     * @param selectedChannelId
     * @param callback
     */
    public void handleMenuChannelSelection(long selectedChannelId, MenuChannelSelectionCallback callback, boolean showAllChannelsListEntry) {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }

        // Fill the channel tag adapter with the available channel tags
        List<Channel> channelList = new ArrayList<>();
        Map<Integer, Channel> map = DataStorage.getInstance().getChannelsFromArray();
        channelList.addAll(map.values());

        // Sort the channel tag list before showing it
        Collections.sort(channelList, new Comparator<Channel>() {
            @Override
            public int compare(Channel o1, Channel o2) {
                return o1.channelName.compareTo(o2.channelName);
            }
        });

        // Add the default channel (all channels)
        // to the list after it has been sorted
        if (showAllChannelsListEntry) {
            Channel channel = new Channel();
            channel.channelId = 0;
            channel.channelName = activity.getString(R.string.all_channels);
            channelList.add(0, channel);
        }

        final ChannelListSelectionAdapter channelListSelectionAdapter = new ChannelListSelectionAdapter(activity, channelList, selectedChannelId);
        // Show the dialog that shows all available channel tags. When the
        // user has selected a tag, restart the loader to get the updated channel list
        final MaterialDialog dialog = new MaterialDialog.Builder(activity)
                .title(R.string.tags)
                .adapter(channelListSelectionAdapter, null)
                .build();
        // Set the callback to handle clicks. This needs to be done after the
        // dialog creation so that the inner method has access to the dialog variable
        channelListSelectionAdapter.setCallback(which -> {
            if (callback != null) {
                callback.menuChannelSelected(which);
            }
            if (dialog != null) {
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    public void handleMenuDownloadSelection(long recId) {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        Intent intent = new Intent(activity, DownloadActivity.class);
        intent.putExtra("dvrId", recId);
        activity.startActivity(intent);
    }

    public void handleMenuSearchWebSelection(String title) {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        try {
            String url = URLEncoder.encode(title, "utf-8");
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("imdb:///find?s=tt&q=" + url));
            PackageManager packageManager = activity.getPackageManager();
            if (packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) {
                intent.setData(Uri.parse("http://www.imdb.org/find?s=tt&q=" + url));
            }
            activity.startActivity(intent);
        } catch (UnsupportedEncodingException e) {
            // NOP
        }
    }

    public void handleMenuSearchEpgSelection(String title) {
        handleMenuSearchEpgSelection(title, 0);
    }

    public void handleMenuSearchEpgSelection(String title, long channelId) {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        Intent intent = new Intent(activity, SearchResultActivity.class);
        intent.setAction(Intent.ACTION_SEARCH);
        intent.putExtra(SearchManager.QUERY, title);
        if (channelId > 0) {
            intent.putExtra("channelId", channelId);
        }
        activity.startActivity(intent);
    }

    public void handleMenuRecordSelection(long eventId) {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        Log.d(TAG, "handleMenuRecordSelection() called with: eventId = [" + eventId + "]");
        final Intent intent = new Intent(activity, HTSService.class);
        intent.setAction("addDvrEntry");
        intent.putExtra("eventId", eventId);

        final Connection connection = DatabaseHelper.getInstance(activity.getApplicationContext()).getSelectedConnection();
        final Profile profile = DatabaseHelper.getInstance(activity.getApplicationContext()).getProfile(connection.recording_profile_id);
        if (profile != null
                && profile.enabled
                && mHtspVersion >= 16
                && mIsUnlocked) {
            intent.putExtra("configName", profile.name);
        }
        activity.startService(intent);
    }

    public void handleMenuSeriesRecordSelection(String title) {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        final Intent intent = new Intent(activity, HTSService.class);
        intent.setAction("addAutorecEntry");
        intent.putExtra("title", title);

        final Connection connection = DatabaseHelper.getInstance(activity.getApplicationContext()).getSelectedConnection();
        final Profile profile = DatabaseHelper.getInstance(activity.getApplicationContext()).getProfile(connection.recording_profile_id);
        if (profile != null
                && profile.enabled
                && mHtspVersion >= 16
                && mIsUnlocked) {
            intent.putExtra("configName", profile.name);
        }
        activity.startService(intent);
    }

    public void handleMenuPlaySelection(int channelId, int dvrId) {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        Intent intent = new Intent(activity, PlayActivity.class);
        intent.putExtra("channelId", channelId);
        intent.putExtra("dvrId", dvrId);
        activity.startActivity(intent);
    }

    public void handleMenuStopRecordingSelection(long dvrId, String title) {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        // Show a confirmation dialog before stopping the recording
        new MaterialDialog.Builder(activity)
                .title(R.string.record_stop)
                .content(activity.getString(R.string.stop_recording, title))
                .negativeText(R.string.cancel)
                .positiveText(R.string.stop)
                .onPositive((dialog, which) -> {
                    final Intent intent = new Intent(activity, HTSService.class);
                    intent.setAction("stopDvrEntry");
                    intent.putExtra("id", dvrId);
                    activity.startService(intent);
                })
                .show();
    }

    public void handleMenuRemoveRecordingSelection(long dvrId, String title) {
        Log.d(TAG, "handleMenuRemoveRecordingSelection() called with: dvrId = [" + dvrId + "], title = [" + title + "]");
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        Log.d(TAG, "handleMenuRemoveRecordingSelection: ");
        // Show a confirmation dialog before removing the recording
        new MaterialDialog.Builder(activity)
                .title(R.string.record_remove)
                .content(activity.getString(R.string.remove_recording, title))
                .negativeText(R.string.cancel)
                .positiveText(R.string.remove)
                .onPositive((dialog, which) -> {
                    final Intent intent = new Intent(activity, HTSService.class);
                    intent.setAction("deleteDvrEntry");
                    intent.putExtra("id", dvrId);
                    activity.startService(intent);
                })
                .show();
    }

    public void handleMenuCancelRecordingSelection(long dvrId, String title) {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        // Show a confirmation dialog before cancelling the recording
        new MaterialDialog.Builder(activity)
                .title(R.string.record_cancel)
                .content(activity.getString(R.string.cancel_recording, title))
                .negativeText(R.string.discard)
                .positiveText(R.string.cancel)
                .onPositive((dialog, which) -> {
                    final Intent intent = new Intent(activity, HTSService.class);
                    intent.setAction("cancelDvrEntry");
                    intent.putExtra("id", dvrId);
                    activity.startService(intent);
                })
                .show();
    }

    public void handleMenuRemoveSeriesRecordingSelection(String id, String title) {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        // Show a confirmation dialog before removing the recording
        new MaterialDialog.Builder(activity)
                .title(R.string.record_remove)
                .content(activity.getString(R.string.remove_series_recording, title))
                .negativeText(R.string.cancel)
                .positiveText(R.string.remove)
                .onPositive((dialog, which) -> {
                    final Intent intent = new Intent(activity, HTSService.class);
                    intent.setAction("deleteAutorecEntry");
                    intent.putExtra("id", id);
                    activity.startService(intent);
                })
                .show();
    }

    public void handleMenuRemoveTimerRecordingSelection(String id, String title) {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        // Show a confirmation dialog before removing the recording
        new MaterialDialog.Builder(activity)
                .title(R.string.record_remove)
                .content(activity.getString(R.string.remove_timer_recording, title))
                .negativeText(R.string.cancel)
                .positiveText(R.string.remove)
                .onPositive((dialog, which) -> {
                    final Intent intent = new Intent(activity, HTSService.class);
                    intent.setAction("deleteTimerecEntry");
                    intent.putExtra("id", id);
                    activity.startService(intent);
                })
                .show();
    }

    public void handleMenuRemoveAllRecordingsSelection(List<Recording> items) {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        new MaterialDialog.Builder(activity)
                .title(R.string.record_remove_all)
                .content(R.string.confirm_remove_all)
                .positiveText(activity.getString(R.string.remove))
                .negativeText(activity.getString(R.string.cancel))
                .onPositive(new MaterialDialog.SingleButtonCallback() {
                    @Override
                    public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                        new Thread() {
                            public void run() {
                                for (Recording item : items) {
                                    final Intent intent = new Intent(activity, HTSService.class);
                                    intent.putExtra("id", item.id);
                                    if (item.isRecording() || item.isScheduled()) {
                                        intent.setAction("cancelDvrEntry");
                                    } else {
                                        intent.setAction("deleteDvrEntry");
                                    }
                                    activity.startService(intent);
                                    try {
                                        sleep(500);
                                    } catch (InterruptedException e) {
                                        // NOP
                                    }
                                }
                            }
                        }.start();
                    }
                }).show();
    }

    public void handleMenuRemoveAllSeriesRecordingSelection(List<SeriesRecording> items) {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        new MaterialDialog.Builder(activity)
                .title(R.string.record_remove_all)
                .content(R.string.remove_all_recordings)
                .positiveText(activity.getString(R.string.remove))
                .negativeText(activity.getString(R.string.cancel))
                .onPositive(new MaterialDialog.SingleButtonCallback() {
                    @Override
                    public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                        new Thread() {
                            public void run() {
                                for (SeriesRecording item : items) {
                                    final Intent intent = new Intent(activity, HTSService.class);
                                    intent.setAction("deleteAutorecEntry");
                                    intent.putExtra("id", item.id);
                                    activity.startService(intent);
                                    try {
                                        sleep(500);
                                    } catch (InterruptedException e) {
                                        // NOP
                                    }
                                }
                            }
                        }.start();
                    }
                }).show();
    }

    public void handleMenuRemoveAllTimerRecordingSelection(List<TimerRecording> items) {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        new MaterialDialog.Builder(activity)
                .title(R.string.record_remove_all)
                .content(R.string.remove_all_recordings)
                .positiveText(activity.getString(R.string.remove))
                .negativeText(activity.getString(R.string.cancel))
                .onPositive(new MaterialDialog.SingleButtonCallback() {
                    @Override
                    public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                        new Thread() {
                            public void run() {
                                for (TimerRecording item : items) {
                                    final Intent intent = new Intent(activity, HTSService.class);
                                    intent.setAction("deleteTimerecEntry");
                                    intent.putExtra("id", item.id);
                                    activity.startService(intent);
                                    try {
                                        sleep(500);
                                    } catch (InterruptedException e) {
                                        // NOP
                                    }
                                }
                            }
                        }.start();
                    }
                }).show();
    }

    public void handleMenuCustomRecordSelection(final long eventId, final long channelId) {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        DataStorage dataStorage = DataStorage.getInstance();
        String[] dvrConfigList = new String[dataStorage.getDvrConfigs().size()];
        for (int i = 0; i < dataStorage.getDvrConfigs().size(); i++) {
            dvrConfigList[i] = dataStorage.getDvrConfigs().get(i).name;
        }

        // Get the selected recording profile to highlight the
        // correct item in the list of the selection dialog
        int dvrConfigNameValue = 0;
        DatabaseHelper databaseHelper = DatabaseHelper.getInstance(activity.getApplicationContext());
        final Connection conn = databaseHelper.getSelectedConnection();
        final Profile p = databaseHelper.getProfile(conn.recording_profile_id);
        if (p != null) {
            for (int i = 0; i < dvrConfigList.length; i++) {
                if (dvrConfigList[i].equals(p.name)) {
                    dvrConfigNameValue = i;
                    break;
                }
            }
        }

        // Create new variables because the dialog needs them as final
        final String[] dcList = dvrConfigList;

        // Create the dialog to show the available profiles
        new MaterialDialog.Builder(activity)
                .title(R.string.select_dvr_config)
                .items(dvrConfigList)
                .itemsCallbackSingleChoice(dvrConfigNameValue, new MaterialDialog.ListCallbackSingleChoice() {
                    @Override
                    public boolean onSelection(MaterialDialog dialog, View view, int which, CharSequence text) {
                        // Pass over the
                        Intent intent = new Intent(activity, HTSService.class);
                        intent.setAction("addDvrEntry");
                        intent.putExtra("eventId", eventId);
                        intent.putExtra("channelId", channelId);
                        intent.putExtra("configName", dcList[which]);
                        activity.startService(intent);
                        return true;
                    }
                })
                .show();
    }

    public void onPreparePopupMenu(Menu menu, Program program) {
        MenuItem recordOnceMenuItem = menu.findItem(R.id.menu_record_once);
        MenuItem recordOnceCustomProfileMenuItem = menu.findItem(R.id.menu_record_once_custom_profile);
        MenuItem recordSeriesMenuItem = menu.findItem(R.id.menu_record_series);
        MenuItem recordRemoveMenuItem = menu.findItem(R.id.menu_record_remove);
        MenuItem playMenuItem = menu.findItem(R.id.menu_play);

        // Show the play menu item when the current
        // time is between the program start and end time
        long currentTime = new Date().getTime();
        if (currentTime > program.start && currentTime < program.stop) {
            playMenuItem.setVisible(true);
        }

        Recording rec = DataStorage.getInstance().getRecordingFromArray(program.dvrId);
        if (rec == null || !rec.isRecording() && !rec.isScheduled()) {
            recordOnceMenuItem.setVisible(true);
            recordOnceCustomProfileMenuItem.setVisible(mIsUnlocked);
            recordSeriesMenuItem.setVisible(mHtspVersion >= 13);
        } else if (rec.isRecording()) {
            playMenuItem.setVisible(true);
            recordRemoveMenuItem.setTitle(R.string.stop);
            recordRemoveMenuItem.setVisible(true);
        } else if (rec.isScheduled()) {
            recordRemoveMenuItem.setTitle(R.string.cancel);
            recordRemoveMenuItem.setVisible(true);
        } else {
            recordRemoveMenuItem.setTitle(R.string.remove);
            recordRemoveMenuItem.setVisible(true);
        }
    }

    public void handleMenuReconnectSelection() {
        Activity activity = this.activity.get();
        if (activity == null) {
            return;
        }
        new MaterialDialog.Builder(activity)
                .title("Reconnect to server?")
                .content("Do you want to reconnect to the server?\n" +
                        "The application will be restarted and a new initial sync willbe performed.")
                .negativeText(R.string.cancel)
                .positiveText("Reconnect")
                .onPositive((dialog, which) -> {
                    Utils.connect(activity, true);
                })
                .show();
    }
}
