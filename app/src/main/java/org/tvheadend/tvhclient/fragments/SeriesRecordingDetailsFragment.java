package org.tvheadend.tvhclient.fragments;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.tvheadend.tvhclient.Constants;
import org.tvheadend.tvhclient.R;
import org.tvheadend.tvhclient.TVHClientApplication;
import org.tvheadend.tvhclient.Utils;
import org.tvheadend.tvhclient.model.SeriesRecording;

@SuppressWarnings("deprecation")
public class SeriesRecordingDetailsFragment extends DialogFragment {

    @SuppressWarnings("unused")
    private final static String TAG = SeriesRecordingDetailsFragment.class.getSimpleName();

    private ActionBarActivity activity;
    private boolean showControls = false;
    private SeriesRecording srec;

    private TextView isEnabled;
    private TextView minDuration;
    private TextView maxDuration;
    private TextView startTime;
    private TextView startWindowTime;
    private TextView daysOfWeek;
    private TextView channelName;
    private TextView nameLabel;
    private TextView name;
    private TextView priority;

    private LinearLayout playerLayout;
    private Button recordRemoveButton;
    private Button recordEditButton;

    private TextView toolbarTitle;
    private View toolbarShadow;
    private TVHClientApplication app;

    public static SeriesRecordingDetailsFragment newInstance(Bundle args) {
        SeriesRecordingDetailsFragment f = new SeriesRecordingDetailsFragment();
        f.setArguments(args);
        return f;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        return dialog;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getDialog() != null) {
            getDialog().getWindow().getAttributes().windowAnimations = R.style.dialog_animation_fade;
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        this.activity = (ActionBarActivity) activity;
        app = (TVHClientApplication) activity.getApplication();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        String srecId = "";
        Bundle bundle = getArguments();
        if (bundle != null) {
            srecId = bundle.getString(Constants.BUNDLE_SERIES_RECORDING_ID);
            showControls = bundle.getBoolean(Constants.BUNDLE_SHOW_CONTROLS, false);
        }

        // Get the recording so we can show its details 
        srec = app.getSeriesRecording(srecId);

        // Initialize all the widgets from the layout
        View v = inflater.inflate(R.layout.series_recording_details_layout, container, false);
        channelName = (TextView) v.findViewById(R.id.channel);
        isEnabled = (TextView) v.findViewById(R.id.is_enabled);
        nameLabel = (TextView) v.findViewById(R.id.name_label);
        name = (TextView) v.findViewById(R.id.name);
        minDuration = (TextView) v.findViewById(R.id.minimum_duration);
        maxDuration = (TextView) v.findViewById(R.id.maximum_duration);
        startTime = (TextView) v.findViewById(R.id.start_after_time);
        startWindowTime = (TextView) v.findViewById(R.id.start_before_time);
        daysOfWeek = (TextView) v.findViewById(R.id.days_of_week);
        priority = (TextView) v.findViewById(R.id.priority);
        toolbarTitle = (TextView) v.findViewById(R.id.toolbar_title);
        toolbarShadow = v.findViewById(R.id.toolbar_shadow);

        // Initialize the player layout
        playerLayout = (LinearLayout) v.findViewById(R.id.player_layout);
        recordRemoveButton = (Button) v.findViewById(R.id.menu_record_remove);
        recordEditButton = (Button) v.findViewById(R.id.menu_record_edit);

        return v;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        
        // If the recording is null exit
        if (srec == null) {
            return;
        }

        toolbarTitle.setVisibility(getDialog() != null ? View.VISIBLE : View.GONE);
        toolbarShadow.setVisibility(getDialog() != null ? View.VISIBLE : View.GONE);
        if (getDialog() != null) {
            toolbarTitle.setText(srec.title);
        }

        // Show the player controls
        if (showControls) {
            addPlayerControlListeners();
            playerLayout.setVisibility(View.VISIBLE);
            recordRemoveButton.setVisibility(View.VISIBLE);
            recordEditButton.setVisibility(app.isUnlocked() ? View.VISIBLE : View.GONE);
        }

        isEnabled.setVisibility(app.getProtocolVersion() >= Constants.MIN_API_VERSION_REC_FIELD_ENABLED ? View.VISIBLE : View.GONE);
        isEnabled.setText(srec.enabled ? R.string.recording_enabled : R.string.recording_disabled);
        channelName.setText(srec.channel != null ? srec.channel.name : getString(R.string.all_channels));

        Utils.setDescription(nameLabel, name, srec.name);
        Utils.setDaysOfWeek(activity, null, daysOfWeek, srec.daysOfWeek);

        String[] priorityItems = getResources().getStringArray(R.array.dvr_priorities);
        if (srec.priority >= 0 && srec.priority < priorityItems.length) {
            priority.setText(priorityItems[(int) (srec.priority)]);
        }
        if (srec.minDuration > 0) {
            // The minimum time is given in seconds, but we want to show it in minutes
            minDuration.setText(getString(R.string.minutes, (int) (srec.minDuration / 60)));
        }
        if (srec.maxDuration > 0) {
            // The maximum time is given in seconds, but we want to show it in minutes
            maxDuration.setText(getString(R.string.minutes, (int) (srec.maxDuration / 60)));
        }
        startTime.setText(Utils.getTimeStringFromValue(activity, srec.start));
        startWindowTime.setText(Utils.getTimeStringFromValue(activity, srec.startWindow));
    }

    /**
     * 
     */
    private void addPlayerControlListeners() {
        recordRemoveButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Utils.confirmRemoveRecording(activity, srec);
                if (getDialog() != null) {
                    getDialog().dismiss();
                }
            }
        });
        recordEditButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                // Create the fragment and show it as a dialog.
                DialogFragment editFragment = SeriesRecordingAddFragment.newInstance();
                Bundle bundle = new Bundle();
                bundle.putString(Constants.BUNDLE_SERIES_RECORDING_ID, srec.id);
                editFragment.setArguments(bundle);
                editFragment.show(activity.getSupportFragmentManager(), "dialog");

                if (getDialog() != null) {
                    getDialog().dismiss();
                }
            }
        });
    }
}
