package org.tvheadend.tvhclient.fragments;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.tvheadend.tvhclient.Constants;
import org.tvheadend.tvhclient.DatabaseHelper;
import org.tvheadend.tvhclient.R;
import org.tvheadend.tvhclient.TVHClientApplication;
import org.tvheadend.tvhclient.htsp.HTSService;
import org.tvheadend.tvhclient.interfaces.ActionBarInterface;
import org.tvheadend.tvhclient.interfaces.HTSListener;
import org.tvheadend.tvhclient.model.Connection;
import org.tvheadend.tvhclient.model.DiscSpace;
import org.tvheadend.tvhclient.model.Recording;
import org.tvheadend.tvhclient.model.Subscription;
import org.tvheadend.tvhclient.model.SystemTime;

import java.util.List;
import java.util.Map;

public class StatusFragment extends Fragment implements HTSListener {

    @SuppressWarnings("unused")
    private final static String TAG = StatusFragment.class.getSimpleName();

    private Activity activity;
    private ActionBarInterface actionBarInterface;

    private LinearLayout additionalInformationLayout;

	// This information is always available
    private TextView connection;
    private TextView status;
    private TextView channels;
	private TextView currentlyRec;
	private TextView completedRec;
	private TextView upcomingRec;
	private TextView failedRec;
    private TextView freediscspace;
    private TextView totaldiscspace;
    private TextView serverApiVersion;

    // This information depends on the server capabilities
	private TextView seriesRecLabel;
	private TextView seriesRec;
	private TextView timerRecLabel;
    private TextView timerRec;
    private String connectionStatus = "";

    private TVHClientApplication app;
    private DatabaseHelper dbh;

	@Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        // If the view group does not exist, the fragment would not be shown. So
        // we can return anyway.
        if (container == null) {
            return null;
        }

        Bundle bundle = getArguments();
        if (bundle != null) {
            connectionStatus = bundle.getString(Constants.BUNDLE_CONNECTION_STATUS);
        }

        View v = inflater.inflate(R.layout.status_fragment_layout, container, false);
        connection = (TextView) v.findViewById(R.id.connection);
        status = (TextView) v.findViewById(R.id.status);
        additionalInformationLayout = (LinearLayout) v.findViewById(R.id.additional_information_layout);
        channels = (TextView) v.findViewById(R.id.channels);
        currentlyRec = (TextView) v.findViewById(R.id.currently_recording);
        completedRec = (TextView) v.findViewById(R.id.completed_recordings);
        upcomingRec = (TextView) v.findViewById(R.id.upcoming_recordings);
        failedRec = (TextView) v.findViewById(R.id.failed_recordings);
        freediscspace = (TextView) v.findViewById(R.id.free_discspace);
        totaldiscspace = (TextView) v.findViewById(R.id.total_discspace);
        seriesRecLabel = (TextView) v.findViewById(R.id.series_recording_label);
        seriesRec = (TextView) v.findViewById(R.id.series_recordings);
        timerRecLabel = (TextView) v.findViewById(R.id.timer_recording_label);
        timerRec = (TextView) v.findViewById(R.id.timer_recordings);
        serverApiVersion = (TextView) v.findViewById(R.id.server_api_version);

        return v;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        this.activity = activity;
        app = (TVHClientApplication) activity.getApplication();
        dbh = DatabaseHelper.getInstance(activity);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        if (activity instanceof ActionBarInterface) {
            actionBarInterface = (ActionBarInterface) activity;
        }
        if (actionBarInterface != null) {
            actionBarInterface.setActionBarTitle(getString(R.string.status));
            actionBarInterface.setActionBarSubtitle("");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        app.addListener(this);

        // Upon resume show the actual status. If stuff is loading hide certain
        // information, otherwise show the connection status and the cause of
        // possible connection problems. 
        additionalInformationLayout.setVisibility(View.GONE);
        if (app.isLoading()) {
            onMessage(Constants.ACTION_LOADING, true);
        } else {
            onMessage(connectionStatus, false);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        app.removeListener(this);
    }

    @Override
    public void onDetach() {
        actionBarInterface = null;
        super.onDetach();
    }

	@Override
	public void onMessage(final String action, final Object obj) {
        switch (action) {
            case Constants.ACTION_CONNECTION_STATE_OK:
                activity.runOnUiThread(new Runnable() {
                    public void run() {
                        connectionStatus = action;
                        showCompleteStatus();
                    }
                });
                break;
            case Constants.ACTION_CONNECTION_STATE_UNKNOWN:
            case Constants.ACTION_CONNECTION_STATE_SERVER_DOWN:
            case Constants.ACTION_CONNECTION_STATE_LOST:
            case Constants.ACTION_CONNECTION_STATE_TIMEOUT:
            case Constants.ACTION_CONNECTION_STATE_REFUSED:
            case Constants.ACTION_CONNECTION_STATE_AUTH:
            case Constants.ACTION_CONNECTION_STATE_NO_CONNECTION:
            case Constants.ACTION_CONNECTION_STATE_NO_NETWORK:
                activity.runOnUiThread(new Runnable() {
                    public void run() {
                        connectionStatus = action;

                        if (actionBarInterface != null) {
                            actionBarInterface.setActionBarTitle(getString(R.string.status));
                            actionBarInterface.setActionBarSubtitle("");
                        }

                        // Hide the additional status information because the
                        // connection to the server is not OK
                        additionalInformationLayout.setVisibility(View.GONE);
                        showConnectionName();
                        showConnectionStatus();
                    }
                });
                break;
            case Constants.ACTION_LOADING:
                activity.runOnUiThread(new Runnable() {
                    public void run() {
                        boolean loading = (Boolean) obj;
                        if (actionBarInterface != null) {
                            actionBarInterface.setActionBarTitle(getString(R.string.status));
                            actionBarInterface.setActionBarSubtitle((loading ? getString(R.string.updating) : ""));
                        }

                        // Show that data is being loaded from the server and hide
                        // the additional information, because this information is
                        // outdated. If the data has been loaded from the server,
                        // show the additional status layout again and display the
                        // available information.
                        if (loading) {
                            status.setText(R.string.loading);
                            additionalInformationLayout.setVisibility(View.GONE);
                            showConnectionName();

                            freediscspace.setText(R.string.loading);
                            totaldiscspace.setText(R.string.loading);
                        } else {
                            showCompleteStatus();
                        }
                    }
                });
                break;
            case Constants.ACTION_DISC_SPACE:
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isAdded()) {
                            showDiscSpace();
                        }
                    }
                });
                break;
        }
	}

    /**
     * Displays all available status information. This is the case
     * when the loading is done and the connection is fine
     */
    private void showCompleteStatus() {
        // The connection to the server is fine again, therefore
        // show the additional information again
        additionalInformationLayout.setVisibility(View.VISIBLE);
        showConnectionName();
        showConnectionStatus();
        showRecordingStatus();
        showSubscriptionStatus();
        showDiscSpace();

        // Show the number of available channels
        final String text = app.getChannels().size() + " " + getString(R.string.available);
        channels.setText(text);
    }

    /**
     * Displays some subscription details of the available subscriptions
     */
    private void showSubscriptionStatus() {
        app.log(TAG, "showSubscriptionStatus");
        List<Subscription> subscriptions = app.getSubscriptions();
        for (Subscription s : subscriptions) {
            if (s != null) {
                app.log(TAG, "subscription status " + s.status);

            }
        }
    }

    /**
	 * Shows the name and address of a connection, otherwise shows an
     * information that no connection is selected or available. 
	 */
    private void showConnectionName() {
	    // Get the currently selected connection
        boolean noConnectionsDefined = false;
        Connection conn = null;
        if (dbh != null) {
            noConnectionsDefined = dbh.getConnections().isEmpty();
            conn = dbh.getSelectedConnection();
        }

        // Show the details about the current connection or an information that
        // none is selected or available
        if (conn == null) {
            if (noConnectionsDefined) {
                connection.setText(R.string.no_connection_available_advice);
            } else {
                connection.setText(R.string.no_connection_active_advice);
            }
        } else {
            String text = conn.name + " (" + conn.address + ")";
            connection.setText(text);
        }
	}

    /**
     * Shows the current connection status is displayed, this can be
     * authorization, timeouts or other errors.
     */
    private void showConnectionStatus() {
        // Show a textual description about the connection state
        switch (connectionStatus) {
            case Constants.ACTION_CONNECTION_STATE_OK:
                status.setText(R.string.ready);
                break;
            case Constants.ACTION_CONNECTION_STATE_SERVER_DOWN:
                status.setText(R.string.err_connect);
                break;
            case Constants.ACTION_CONNECTION_STATE_LOST:
                status.setText(R.string.err_con_lost);
                break;
            case Constants.ACTION_CONNECTION_STATE_TIMEOUT:
                status.setText(R.string.err_con_timeout);
                break;
            case Constants.ACTION_CONNECTION_STATE_REFUSED:
            case Constants.ACTION_CONNECTION_STATE_AUTH:
                status.setText(R.string.err_auth);
                break;
            case Constants.ACTION_CONNECTION_STATE_NO_NETWORK:
                status.setText(R.string.err_no_network);
                break;
            case Constants.ACTION_CONNECTION_STATE_NO_CONNECTION:
                status.setText(R.string.no_connection_available);
                break;
            default:
                status.setText(R.string.unknown);
                break;
        }
    }

    /**
     * Shows the available and total disc space either in MB or GB to avoid
     * showing large numbers. This depends on the size of the value.
     */
    private void showDiscSpace() {
        DiscSpace ds = app.getDiscSpace();
        if (ds == null) {
            freediscspace.setText(R.string.unknown);
            totaldiscspace.setText(R.string.unknown);
            return;
        }

        try {
            // Get the disc space values and convert them to megabytes
            long free = Long.valueOf(ds.freediskspace) / 1000000;
            long total = Long.valueOf(ds.totaldiskspace) / 1000000;

            String freeDiscSpace;
            String totalDiscSpace;

            // Show the free amount of disc space as GB or MB
            if (free > 1000) {
                freeDiscSpace = (free / 1000) + " GB " + getString(R.string.available);
            } else {
                freeDiscSpace = free + " MB " + getString(R.string.available);
            }
            // Show the total amount of disc space as GB or MB
            if (total > 1000) {
                totalDiscSpace = (total / 1000) + " GB " + getString(R.string.total);
            } else {
                totalDiscSpace = total + " MB " + getString(R.string.total);
            }
            freediscspace.setText(freeDiscSpace);
            totaldiscspace.setText(totalDiscSpace);
        } catch (Exception e) {
            freediscspace.setText(R.string.unknown);
            totaldiscspace.setText(R.string.unknown);
        }
    }

    /**
     * Shows the program that is currently being recorded and the summary about
     * the available, scheduled and failed recordings.
     */
    private void showRecordingStatus() {
        String currentRecText = "";

        // Get the programs that are currently being recorded
        for (Recording rec : app.getRecordings()) {
            if (rec.isRecording()) {
                currentRecText += getString(R.string.currently_recording) + ": " + rec.title;
                if (rec.channel != null) {
                    currentRecText += " (" + getString(R.string.channel) + " " + rec.channel.name + ")\n";
                }
            }
        }

        // Show which programs are being recorded
        currentlyRec.setText(currentRecText.length() > 0 ? currentRecText
                : getString(R.string.nothing));

        final int completedRecCount = app.getRecordingsByType(
                Constants.RECORDING_TYPE_COMPLETED).size();
        final int scheduledRecCount = app.getRecordingsByType(
                Constants.RECORDING_TYPE_SCHEDULED).size();
        final int failedRecCount = app.getRecordingsByType(
                Constants.RECORDING_TYPE_FAILED).size();

        // Show how many different recordings are available
        completedRec.setText(getResources().getQuantityString(
                R.plurals.completed_recordings, completedRecCount,
                completedRecCount));
        upcomingRec.setText(getResources().getQuantityString(
                R.plurals.upcoming_recordings, scheduledRecCount,
                scheduledRecCount));
        failedRec.setText(getResources().getQuantityString(
                R.plurals.failed_recordings, failedRecCount, failedRecCount));

        // Show how many series recordings are available
        if (app.getProtocolVersion() < Constants.MIN_API_VERSION_SERIES_RECORDINGS) {
            seriesRecLabel.setVisibility(View.GONE);
            seriesRec.setVisibility(View.GONE);
        } else {
            String text = app.getSeriesRecordings().size() + " " + getString(R.string.available);
            seriesRec.setText(text);
        }

        // Show how many timer recordings are available if the server supports
        // it and the application is unlocked
        if (app.getProtocolVersion() < Constants.MIN_API_VERSION_SERIES_RECORDINGS || !app.isUnlocked()) {
            timerRecLabel.setVisibility(View.GONE);
            timerRec.setVisibility(View.GONE);
        } else {
            String text = app.getTimerRecordings().size() + " " + getString(R.string.available);
            timerRec.setText(text);
        }

        String version = String.valueOf(app.getProtocolVersion())
                + "   (" + getString(R.string.server) + ": "
                + app.getServerName() + " " + app.getServerVersion() + ")";
        serverApiVersion.setText(version);
    }
}
