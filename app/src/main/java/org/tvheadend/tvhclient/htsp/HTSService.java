package org.tvheadend.tvhclient.htsp;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;

import org.tvheadend.tvhclient.Constants;
import org.tvheadend.tvhclient.DataStorage;
import org.tvheadend.tvhclient.Logger;
import org.tvheadend.tvhclient.R;
import org.tvheadend.tvhclient.TVHClientApplication;
import org.tvheadend.tvhclient.interfaces.HTSConnectionListener;
import org.tvheadend.tvhclient.interfaces.HTSResponseHandler;
import org.tvheadend.tvhclient.model.Channel;
import org.tvheadend.tvhclient.model.ChannelTag;
import org.tvheadend.tvhclient.model.DiscSpace;
import org.tvheadend.tvhclient.model.DvrCutpoint;
import org.tvheadend.tvhclient.model.HttpTicket;
import org.tvheadend.tvhclient.model.Packet;
import org.tvheadend.tvhclient.model.Profiles;
import org.tvheadend.tvhclient.model.Program;
import org.tvheadend.tvhclient.model.Recording;
import org.tvheadend.tvhclient.model.SeriesInfo;
import org.tvheadend.tvhclient.model.SeriesRecording;
import org.tvheadend.tvhclient.model.SourceInfo;
import org.tvheadend.tvhclient.model.Stream;
import org.tvheadend.tvhclient.model.Subscription;
import org.tvheadend.tvhclient.model.SystemTime;
import org.tvheadend.tvhclient.model.TimerRecording;
import org.tvheadend.tvhclient.utils.Utils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HTSService extends Service implements HTSConnectionListener {

    private static final String TAG = HTSService.class.getSimpleName();
    
    private ScheduledExecutorService execService;
    private HTSConnection connection;
    private PackageInfo packInfo;
    private DataStorage dataStorage;
    private Logger logger;

    private class LocalBinder extends Binder {
        HTSService getService() {
            return HTSService.this;
        }
    }

    @Override
    public void onCreate() {
        execService = Executors.newScheduledThreadPool(10);
        dataStorage = DataStorage.getInstance();
        logger = Logger.getInstance();

        try {
            packInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
        } catch (NameNotFoundException ex) {
            // NOP
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent.getAction();

        if (action.equals(Constants.ACTION_CONNECT)) {
            logger.log(TAG, "onStartCommand: Connection to server requested");

            boolean force = intent.getBooleanExtra("force", false);
            final String hostname = intent.getStringExtra("hostname");
            final int port = intent.getIntExtra("port", 9982);
            final String username = intent.getStringExtra("username");
            final String password = intent.getStringExtra("password");

            if (connection != null && force) {
                logger.log(TAG, "onStartCommand: Closing existing connection");
                connection.close();
                dataStorage.clearAll();
            }
            if (connection == null || !connection.isConnected()) {
                logger.log(TAG, "onStartCommand: Connecting to server");
                dataStorage.setLoading(true);
                connection = new HTSConnection(TVHClientApplication.getInstance(), this, packInfo.packageName, packInfo.versionName);

                // Since this is blocking, spawn to a new thread
                execService.execute(new Runnable() {
                    public void run() {
                        connection.open(hostname, port, TVHClientApplication.getInstance().isConnected());
                        connection.authenticate(username, password);
                    }
                });
            }

        } else if (connection == null || !connection.isConnected()) {
            logger.log(TAG, "onStartCommand: No connection to perform " + action);

        } else if (action.equals(Constants.ACTION_DISCONNECT)) {
            logger.log(TAG, "onStartCommand: Closing connection to server");
            connection.close();

        } else if (action.equals("getEvent")) {
            getEvent(intent.getLongExtra("eventId", 0));

        } else if (action.equals("getEvents")) {
            final Channel ch = dataStorage.getChannel(intent.getLongExtra("channelId", 0));
            getEvents(ch, intent.getLongExtra("eventId", 0), intent.getIntExtra("count", 10));

        } else if (action.equals("addDvrEntry")) {
            addDvrEntry(intent);

        } else if (action.equals("updateDvrEntry")) {
            updateDvrEntry(intent);

        } else if (action.equals("deleteDvrEntry")) {
            deleteDvrEntry(intent.getLongExtra("id", 0));

        } else if (action.equals("cancelDvrEntry")) {
            cancelDvrEntry(intent.getLongExtra("id", 0));

        } else if (action.equals("stopDvrEntry")) {
            stopDvrEntry(intent.getLongExtra("id", 0));

        } else if (action.equals("addTimerecEntry")) {
            addTimerRecEntry(intent);

        } else if (action.equals("updateTimerecEntry")) {
            updateTimerRecEntry(intent);

        } else if (action.equals("deleteTimerecEntry")) {
            deleteTimerRecEntry(intent.getStringExtra("id"));

        } else if (action.equals("epgQuery")) {
            epgQuery(intent);

        } else if (action.equals("subscribe")) {
            subscribe(intent.getLongExtra("channelId", 0),
                    intent.getLongExtra("subscriptionId", 0),
                    intent.getIntExtra("maxWidth", 0),
                    intent.getIntExtra("maxHeight", 0),
                    intent.getStringExtra("audioCodec"),
                    intent.getStringExtra("videoCodec"));

        } else if (action.equals("unsubscribe")) {
            unsubscribe(intent.getLongExtra("subscriptionId", 0));

        } else if (action.equals("feedback")) {
            feedback(intent.getLongExtra("subscriptionId", 0), intent.getIntExtra("speed", 0));

        } else if (action.equals("getTicket")) {
            Channel ch = dataStorage.getChannel(intent.getLongExtra("channelId", 0));
            Recording rec = dataStorage.getRecording(intent.getLongExtra("dvrId", 0));
            if (ch != null) {
                getTicket(ch);
            } else if (rec != null) {
                getTicket(rec);
            }

        } else if (action.equals("getDiskSpace")) {
        	getDiscSpace();

        } else if (action.equals("getDvrConfigs")) {
            getDvrConfigs();
            
        } else if (action.equals("getProfiles")) {
            getProfiles();
            
        } else if (action.equals("getChannel")) {
            Channel ch = dataStorage.getChannel(intent.getLongExtra("channelId", 0));
            if (ch != null) {
                getChannel(ch);
            }
            
        } else if (action.equals("subscriptionFilterStream")) {
            subscriptionFilterStream();
            
        } else if (action.equals("getDvrCutpoints")) {
            Recording rec = dataStorage.getRecording(intent.getLongExtra("dvrId", 0));
            if (rec != null) {
                getDvrCutpoints(rec);
            }
            
        } else if (action.equals("addAutorecEntry")) {
            addAutorecEntry(intent);

        } else if (action.equals("updateAutorecEntry")) {
            updateAutorecEntry(intent);

        } else if (action.equals("deleteAutorecEntry")) {
            String id = intent.getStringExtra("id");
            deleteAutorecEntry(id);

        } else if (action.equals("getSysTime")) {
            getSystemTime();

        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        execService.shutdown();
        if (connection != null) {
            connection.close();
        }
    }

    public void onError(final String error) {
        dataStorage.setLoading(false);
        dataStorage.setConnectionState(error);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
    private final IBinder mBinder = new LocalBinder();

    private void onTagAdd(HTSMessage msg) {
        dataStorage.addTagToArray(HTSUtils.convertMessageToChannelTagModel(msg));

        ChannelTag tag = new ChannelTag();
        tag.id = msg.getLong("tagId");
        tag.name = msg.getString("tagName", null);
        tag.icon = msg.getString("tagIcon", null);
        dataStorage.addChannelTag(tag);
        if (tag.icon != null) {
            getChannelTagIcon(tag);
        }
    }

    private void onTagUpdate(HTSMessage msg) {
        dataStorage.updateTagInArray(HTSUtils.convertMessageToChannelTagModel(msg));

        ChannelTag tag = dataStorage.getChannelTag(msg.getLong("tagId"));
        if (tag == null) {
            return;
        }

        tag.name = msg.getString("tagName", tag.name);
        String icon = msg.getString("tagIcon", tag.icon);
        if (icon == null) {
            tag.icon = null;
            tag.iconBitmap = null;
        } else if (!icon.equals(tag.icon)) {
            tag.icon = icon;
            getChannelTagIcon(tag);
        }
    }

    private void onTagDelete(HTSMessage msg) {
        dataStorage.removeTagFromArray(msg.getInt("tagId"));
        dataStorage.removeChannelTag(msg.getLong("tagId"));
    }

    private void onChannelAdd(HTSMessage msg) {
        dataStorage.addChannelToArray(HTSUtils.convertMessageToChannelModel(msg));

        final Channel ch = new Channel();
        ch.id = msg.getLong("channelId");
        ch.name = msg.getString("channelName", null);
        ch.number = msg.getInt("channelNumber", 0);

        // The default values will be set in case a server with a htsp API
        // version 12 or lower is used
        ch.numberMinor = msg.getInt("channelNumberMinor", 0);

        ch.icon = msg.getString("channelIcon", null);
        ch.tags = msg.getIntList("tags", ch.tags);

        if (ch.number == 0) {
            ch.number = (int) (ch.id + 25000);
        }

        dataStorage.addChannel(ch);
        if (ch.icon != null) {
            getChannelIcon(ch);
        }
        long currEventId = msg.getLong("eventId", 0);
        long nextEventId = msg.getLong("nextEventId", 0);

        ch.isTransmitting = (currEventId != 0);

        if (currEventId > 0) {
            getEvents(ch, currEventId, Constants.PREF_PROGRAMS_TO_LOAD);
        } else if (nextEventId > 0) {
            getEvents(ch, nextEventId, Constants.PREF_PROGRAMS_TO_LOAD);
        }
    }

    private void onChannelUpdate(HTSMessage msg) {
        dataStorage.updateChannelInArray(HTSUtils.convertMessageToChannelModel(msg));

        final Channel ch = dataStorage.getChannel(msg.getLong("channelId"));
        if (ch == null) {
            return;
        }

        ch.name = msg.getString("channelName", ch.name);
        ch.number = msg.getInt("channelNumber", ch.number);

        // The default values will be set in case a server with a htsp API
        // version 12 or lower is used
        ch.numberMinor = msg.getInt("channelNumberMinor", 0);

        String icon = msg.getString("channelIcon", ch.icon);
        ch.tags = msg.getIntList("tags", ch.tags);

        if (icon == null) {
            ch.icon = null;
            ch.iconBitmap = null;
        } else if (!icon.equals(ch.icon)) {
            ch.icon = icon;
            getChannelIcon(ch);
        }
        // Remove programs that have ended
        long currEventId = msg.getLong("eventId", 0);
        long nextEventId = msg.getLong("nextEventId", 0);

        ch.isTransmitting = currEventId != 0;

        Iterator<Program> it = ch.epg.iterator();
        ArrayList<Program> tmp = new ArrayList<>();

        while (it.hasNext() && currEventId > 0) {
            Program p = it.next();
            if (p.id != currEventId) {
                tmp.add(p);
            } else {
                break;
            }
        }
        ch.epg.removeAll(tmp);

        for (Program p : tmp) {
            dataStorage.removeProgram(p);
        }

        final long eventId = currEventId != 0 ? currEventId : nextEventId;
        if (eventId > 0 && ch.epg.size() < 2) {
            execService.schedule(new Runnable() {
                public void run() {
                    getEvents(ch, eventId, 5);
                }
            }, 30, TimeUnit.SECONDS);
        } else {
            dataStorage.updateChannel(ch);
        }
    }

    private void onChannelDelete(HTSMessage msg) {
        dataStorage.removeChannelFromArray(msg.getInt("channelId"));
        dataStorage.removeChannel(msg.getLong("channelId"));
    }

    private void onDvrEntryAdd(HTSMessage msg) {
        dataStorage.addRecordingToArray(HTSUtils.convertMessageToRecordingModel(msg));

        Recording rec = new Recording();
        rec.id = msg.getLong("id");
        rec.description = msg.getString("description");
        rec.summary = msg.getString("summary");
        rec.error = msg.getString("error");
        rec.start = msg.getDate("start");
        rec.state = msg.getString("state");
        rec.stop = msg.getDate("stop");
        rec.title = msg.getString("title");
        rec.subtitle = msg.getString("subtitle");
        rec.enabled = msg.getLong("enabled", 1) != 0;

        rec.channel = dataStorage.getChannel(msg.getLong("channel", 0));
        if (rec.channel != null) {
            rec.channel.recordings.add(rec);
        }

        // Not all fields can be set with default values, so check if the server
        // provides a supported HTSP API version. These entries are available
        // only on version 13 and higher
        if (connection.getProtocolVersion() >= Constants.MIN_API_VERSION_SERIES_RECORDINGS) {
            rec.eventId = msg.getLong("eventId", 0);
            rec.autorecId = msg.getString("autorecId");
            rec.startExtra = msg.getLong("startExtra", 0);
            rec.stopExtra = msg.getLong("stopExtra", 0);
            rec.retention = msg.getLong("retention");
            rec.priority = msg.getLong("priority");
            rec.contentType = msg.getLong("contentType", -1);
        }

        // Not all fields can be set with default values, so check if the server
        // provides a supported HTSP API version. These entries are available
        // only on version 17 and higher
        if (connection.getProtocolVersion() >= Constants.MIN_API_VERSION_TIMER_RECORDINGS) {
            rec.timerecId = msg.getString("timerecId");
        }

        rec.episode = msg.getString("episode", null);
        rec.comment = msg.getString("comment", null);
        rec.subscriptionError = msg.getString("subscriptionError", null);
        rec.streamErrors = msg.getLong("streamErrors", 0);
        rec.dataErrors = msg.getLong("dataErrors", 0);
        rec.dataSize = msg.getLong("dataSize", 0);

        if (rec.channel != null && rec.channel.epg != null) {
            for (Program p : rec.channel.epg) {
                if (p != null 
                        && p.title.equals(rec.title)
                        && p.start.getTime() == rec.start.getTime()
                        && p.stop.getTime() == rec.stop.getTime()) {
                    p.recording = rec;
                    break;
                }
            }
        }

        rec.owner = msg.getString("owner", null);
        rec.creator = msg.getString("creator", null);
        rec.path = msg.getString("path", null);
        rec.files = msg.getString("files", null);

        dataStorage.addRecording(rec);
    }

    private void onDvrEntryUpdate(HTSMessage msg) {
        dataStorage.updateRecordingInArray(HTSUtils.convertMessageToRecordingModel(msg));

        Recording rec = dataStorage.getRecording(msg.getLong("id"));
        if (rec == null) {
            return;
        }

        rec.description = msg.getString("description", rec.description);
        rec.summary = msg.getString("summary", rec.summary);
        rec.error = msg.getString("error", rec.error);
        rec.start = msg.getDate("start");
        rec.state = msg.getString("state", rec.state);
        rec.stop = msg.getDate("stop");
        rec.title = msg.getString("title", rec.title);
        rec.subtitle = msg.getString("subtitle", rec.subtitle);
        rec.enabled = msg.getLong("enabled", 1) != 0;

        // Not all fields can be set with default values, so check if the server
        // provides a supported HTSP API version. These entries are available
        // only on version 13 and higher
        if (connection.getProtocolVersion() >= Constants.MIN_API_VERSION_SERIES_RECORDINGS) {
            rec.eventId = msg.getLong("eventId", 0);
            rec.autorecId = msg.getString("autorecId");
            rec.startExtra = msg.getLong("startExtra", 0);
            rec.stopExtra = msg.getLong("stopExtra", 0);
            rec.retention = msg.getLong("retention");
            rec.priority = msg.getLong("priority");
            rec.contentType = msg.getLong("contentType", -1);
        }

        // Not all fields can be set with default values, so check if the server
        // provides a supported HTSP API version. These entries are available
        // only on version 17 and higher
        if (connection.getProtocolVersion() >= Constants.MIN_API_VERSION_TIMER_RECORDINGS) {
            rec.timerecId = msg.getString("timerecId");
        }

        rec.episode = msg.getString("episode", null);
        rec.comment = msg.getString("comment", null);
        rec.subscriptionError = msg.getString("subscriptionError", null);
        rec.streamErrors = msg.getLong("streamErrors", 0);
        rec.dataErrors = msg.getLong("dataErrors", 0);
        rec.dataSize = msg.getLong("dataSize", 0);
        rec.owner = msg.getString("owner", rec.owner);
        rec.creator = msg.getString("creator", rec.creator);
        rec.path = msg.getString("path", rec.path);
        rec.files = msg.getString("files", rec.files);

        dataStorage.updateRecording(rec);
    }

    private void onDvrEntryDelete(HTSMessage msg) {
        dataStorage.removeRecordingFromArray(msg.getInt("id"));

        Recording rec = dataStorage.getRecording(msg.getLong("id"));

        if (rec == null || rec.channel == null) {
            return;
        }

        rec.channel.recordings.remove(rec);
        for (Program p : rec.channel.epg) {
            if (p.recording == rec) {
                p.recording = null;
                dataStorage.updateProgram(p);
                break;
            }
        }
        dataStorage.removeRecording(rec);
    }

    private void onTimerRecEntryAdd(HTSMessage msg) {
        dataStorage.addTimerRecordingToArray(HTSUtils.convertMessageToTimerRecordingModel(msg));

        TimerRecording rec = new TimerRecording();
        rec.id = msg.getString("id", "");
        
        rec.daysOfWeek = msg.getLong("daysOfWeek", 0);
        rec.retention = msg.getLong("retention", 0);
        rec.priority = msg.getLong("priority", 0);
        rec.start = msg.getLong("start", 0);
        rec.stop = msg.getLong("stop", 0);
        rec.title = msg.getString("title");
        rec.name = msg.getString("name");
        rec.directory = msg.getString("directory");
        rec.channel = dataStorage.getChannel(msg.getLong("channel", 0));

        // The enabled flag was added in HTSP API version 18. The support for
        // timer recordings are available since version 17.
        if (connection.getProtocolVersion() >= Constants.MIN_API_VERSION_REC_FIELD_ENABLED) {
            rec.enabled = msg.getLong("enabled", 0) != 0;
        }
        dataStorage.addTimerRecording(rec);
    }

    private void onTimerRecEntryUpdate(HTSMessage msg) {
        dataStorage.updateTimerRecordingInArray(HTSUtils.convertMessageToTimerRecordingModel(msg));

        TimerRecording rec = dataStorage.getTimerRecording(msg.getString("id"));
        if (rec == null) {
            return;
        }

        rec.daysOfWeek = msg.getLong("daysOfWeek", rec.daysOfWeek);
        rec.retention = msg.getLong("retention", rec.retention);
        rec.priority = msg.getLong("priority", rec.priority);
        rec.start = msg.getLong("start", rec.start);
        rec.stop = msg.getLong("stop", rec.stop);
        rec.title = msg.getString("title", rec.title);
        rec.name = msg.getString("name", rec.name);
        rec.directory = msg.getString("directory", rec.name);
        rec.channel = dataStorage.getChannel(msg.getLong("channel", 0));

        // The enabled flag was added in HTSP API version 18. The support for
        // timer recordings are available since version 17.
        if (connection.getProtocolVersion() >= Constants.MIN_API_VERSION_REC_FIELD_ENABLED) {
            rec.enabled = msg.getLong("enabled", 0) != 0;
        }
        dataStorage.updateTimerRecording(rec);
    }

    private void onTimerRecEntryDelete(HTSMessage msg) {
        dataStorage.removeTimerRecordingFromArray(msg.getString("id"));
        TimerRecording rec = dataStorage.getTimerRecording(msg.getString("id"));

        if (rec == null || rec.channel == null) {
            return;
        }

        rec.channel = null;
        dataStorage.removeTimerRecording(rec);
    }

    private void onInitialSyncCompleted() {
        logger.log(TAG, "onInitialSyncCompleted() called");
        dataStorage.setLoading(false);
        dataStorage.setConnectionState(Constants.ACTION_CONNECTION_STATE_OK);
        dataStorage.setProtocolVersion(connection.getProtocolVersion());
        dataStorage.setServerName(connection.getServerName());
        dataStorage.setServerVersion(connection.getServerVersion());
        dataStorage.setWebRoot(connection.getWebRoot());

        // Get some additional information after the initial loading has been finished
        getDiscSpace();
        getSystemTime();
    }

    private void onSubscriptionStart(HTSMessage msg) {
        Subscription subscription = dataStorage.getSubscription(msg.getLong("subscriptionId"));
        if (subscription == null) {
            return;
        }

        for (Object obj : msg.getList("streams")) {
            Stream s = new Stream();
            HTSMessage sub = (HTSMessage) obj;
            s.index = sub.getInt("index");
            s.type = sub.getString("type");
            s.language = sub.getString("language");
            s.width = sub.getInt("width", 0);
            s.height = sub.getInt("height", 0);
            s.duration = sub.getInt("duration", 0);
            s.aspectNum = sub.getInt("aspect_num", 0);
            s.aspectDen = sub.getInt("aspect_den", 0);
            s.autioType = sub.getInt("autio_type", 0);
            s.channels = sub.getInt("channels", 0);
            s.rate = sub.getInt("rate", 0);
            subscription.streams.add(s);
        }

        if (msg.containsField("sourceinfo")) {
            Object obj = msg.get("sourceinfo");
            HTSMessage sub = (HTSMessage) obj;
            SourceInfo si = new SourceInfo();
            si.adapter = sub.getString("adapter");
            si.mux = sub.getString("mux");
            si.network = sub.getString("network");
            si.provider = sub.getString("provider");
            si.service = sub.getString("service");
            subscription.sourceInfo = si;
        }
    }

    private void onSubscriptionStatus(HTSMessage msg) {
        Subscription s = dataStorage.getSubscription(msg.getLong("subscriptionId"));
        if (s == null) {
            return;
        }
        String status = msg.getString("status", null);
        if (s.status == null ? status != null : !s.status.equals(status)) {
            s.status = status;
            dataStorage.updateSubscription(s);
        }
    }

    private void onSubscriptionStop(HTSMessage msg) {
        Subscription s = dataStorage.getSubscription(msg.getLong("subscriptionId"));
        if (s == null) {
            return;
        }
        String status = msg.getString("status", null);
        if (s.status == null ? status != null : !s.status.equals(status)) {
            s.status = status;
            dataStorage.updateSubscription(s);
        }
        dataStorage.removeSubscription(s);
    }

    private void onSubscriptionGrace(HTSMessage msg) {
        Subscription s = dataStorage.getSubscription(msg.getLong("subscriptionId"));
        if (s == null) {
            return;
        }
        long gt = msg.getLong("graceTimeout", 0);
        if (s.graceTimeout != gt) {
            s.graceTimeout = gt;
            dataStorage.updateSubscription(s);
        }
    }

    private void onSubscriptionSignalStatus(HTSMessage msg) {
        Subscription s = dataStorage.getSubscription(msg.getLong("subscriptionId"));
        if (s == null) {
            return;
        }
        s.feStatus = msg.getString("feStatus");
        s.feSNR = msg.getInt("feSNR", 0);
        s.feSignal = msg.getInt("feSignal", 0);
        s.feBER = msg.getInt("feBER", 0);
        s.feUNC = msg.getInt("feUNC", 0);
    }

    private void onMuxPacket(HTSMessage msg) {
        Subscription sub = dataStorage.getSubscription(msg.getLong("subscriptionId"));
        if (sub == null) {
            return;
        }

        Packet packet = new Packet();
        packet.dts = msg.getLong("dts", 0);
        packet.pts = msg.getLong("pts", 0);
        packet.duration = msg.getLong("duration");
        packet.frametype = msg.getInt("frametype");
        packet.payload = msg.getByteArray("payload");

        for (Stream st : sub.streams) {
            if (st.index == msg.getInt("stream")) {
                packet.stream = st;
            }
        }
        packet.subscription = sub;
        dataStorage.broadcastPacket(packet);
    }

    private void onQueueStatus(HTSMessage msg) {
        Subscription sub = dataStorage.getSubscription(msg.getLong("subscriptionId"));
        if (sub == null) {
            return;
        }
        if (msg.containsField("delay")) {
            BigInteger delay = msg.getBigInteger("delay");
            delay = delay.divide(BigInteger.valueOf((1000)));
            sub.delay = delay.longValue();
        }
        sub.droppedBFrames = msg.getLong("Bdrops", sub.droppedBFrames);
        sub.droppedIFrames = msg.getLong("Idrops", sub.droppedIFrames);
        sub.droppedPFrames = msg.getLong("Pdrops", sub.droppedPFrames);
        sub.packetCount = msg.getLong("packets", sub.packetCount);
        sub.queSize = msg.getLong("bytes", sub.queSize);

        dataStorage.updateSubscription(sub);
    }

    private void onAutorecEntryDelete(HTSMessage msg) {
        dataStorage.removeSeriesRecordingFromArray(msg.getString("id"));
        String id = msg.getString("id");
        if (id == null) {
            return;
        }
        // Remove the series recording from the list and also update all
        // recordings by removing the series id
        dataStorage.removeSeriesRecording(id);
        for (Recording rec : dataStorage.getRecordings()) {
            if (rec.autorecId != null && rec.autorecId.equals(id)) {
                rec.autorecId = null;
            }
        }
    }

    private void onAutorecEntryUpdate(HTSMessage msg) {
        dataStorage.updateSeriesRecordingInArray(HTSUtils.convertMessageToSeriesRecordingModel(msg));

        SeriesRecording srec = dataStorage.getSeriesRecording(msg.getString("id"));
        if (srec == null) {
            return;
        }
        srec.enabled = msg.getLong("enabled", 0) != 0;
        srec.maxDuration = msg.getLong("maxDuration");
        srec.minDuration = msg.getLong("minDuration");
        srec.retention = msg.getLong("retention");
        srec.daysOfWeek = msg.getLong("daysOfWeek");
        srec.approxTime = msg.getLong("approxTime", -1);
        srec.priority = msg.getLong("priority");
        srec.start = msg.getLong("start", -1);
        srec.startWindow = msg.getLong("startWindow", -1);
        srec.startExtra = msg.getLong("startExtra", 0);
        srec.stopExtra = msg.getLong("stopExtra", 0);
        srec.dupDetect = msg.getLong("dupDetect", 0);
        srec.title = msg.getString("title", srec.title);
        srec.name = msg.getString("name", srec.name);
        srec.directory = msg.getString("directory", srec.directory);
        srec.fulltext = msg.getString("fulltext", srec.fulltext);

        dataStorage.updateSeriesRecording(srec);
    }

    private void onAutorecEntryAdd(HTSMessage msg) {
        dataStorage.addSeriesRecordingToArray(HTSUtils.convertMessageToSeriesRecordingModel(msg));

        SeriesRecording srec = new SeriesRecording();
        srec.id = msg.getString("id");
        srec.enabled = msg.getLong("enabled", 0) != 0;
        srec.maxDuration = msg.getLong("maxDuration");
        srec.minDuration = msg.getLong("minDuration");
        srec.retention = msg.getLong("retention");
        srec.daysOfWeek = msg.getLong("daysOfWeek");
        srec.approxTime = msg.getLong("approxTime", -1);
        srec.priority = msg.getLong("priority");
        srec.start = msg.getLong("start", -1);
        srec.startWindow = msg.getLong("startWindow", -1);
        srec.startExtra = msg.getLong("startExtra", 0);
        srec.stopExtra = msg.getLong("stopExtra", 0);
        srec.dupDetect = msg.getLong("dupDetect", 0);
        srec.title = msg.getString("title");
        srec.name = msg.getString("name");
        srec.directory = msg.getString("directory");
        srec.channel = dataStorage.getChannel(msg.getLong("channel", 0));
        srec.fulltext = msg.getString("fulltext");

        dataStorage.addSeriesRecording(srec);
    }

    public void onMessage(HTSMessage msg) {
        logger.log(TAG, "onMessage() called with: msg = [" + msg.getMethod() + "]");
        String method = msg.getMethod();

        switch (method) {
            case "tagAdd":
                onTagAdd(msg);
                break;
            case "tagUpdate":
                onTagUpdate(msg);
                break;
            case "tagDelete":
                onTagDelete(msg);
                break;
            case "channelAdd":
                onChannelAdd(msg);
                break;
            case "channelUpdate":
                onChannelUpdate(msg);
                break;
            case "channelDelete":
                onChannelDelete(msg);
                break;
            case "initialSyncCompleted":
                onInitialSyncCompleted();
                break;
            case "dvrEntryAdd":
                onDvrEntryAdd(msg);
                break;
            case "dvrEntryUpdate":
                onDvrEntryUpdate(msg);
                break;
            case "dvrEntryDelete":
                onDvrEntryDelete(msg);
                break;
            case "timerecEntryAdd":
                onTimerRecEntryAdd(msg);
                break;
            case "timerecEntryUpdate":
                onTimerRecEntryUpdate(msg);
                break;
            case "timerecEntryDelete":
                onTimerRecEntryDelete(msg);
                break;
            case "subscriptionStart":
                onSubscriptionStart(msg);
                break;
            case "subscriptionStatus":
                onSubscriptionStatus(msg);
                break;
            case "subscriptionStop":
                onSubscriptionStop(msg);
                break;
            case "subscriptionGrace":
                onSubscriptionGrace(msg);
                break;
            case "muxpkt":
                onMuxPacket(msg);
                break;
            case "queueStatus":
                onQueueStatus(msg);
                break;
            case "autorecEntryAdd":
                onAutorecEntryAdd(msg);
                break;
            case "autorecEntryUpdate":
                onAutorecEntryUpdate(msg);
                break;
            case "autorecEntryDelete":
                onAutorecEntryDelete(msg);
                break;
            case "signalStatus":
                onSubscriptionSignalStatus(msg);
                break;
            case "eventAdd":
                onEventAdd(msg);
                break;
            case "eventUpdate":
                onEventUpdate(msg);
                break;
            case "eventDelete":
                onEventDelete(msg);
                break;
            default:
                break;
        }
    }

    private String hashString(String s) {
        try {
            MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            digest.update(s.getBytes());
            byte messageDigest[] = digest.digest();

            StringBuilder hexString = new StringBuilder();
            for (byte aMessageDigest : messageDigest) {
                hexString.append(Integer.toHexString(0xFF & aMessageDigest));
            }
            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            // NOP
        }

        return "";
    }

    private void cacheImage(String url, File f) throws IOException {
        InputStream is;
        
        if (url.startsWith("http")) {
        	is = new BufferedInputStream(new URL(url).openStream());
        } else if (connection.getProtocolVersion() > 9) {
        	is = new HTSFileInputStream(connection, url);
        } else {
            return;
        }
        
        OutputStream os = new FileOutputStream(f);

        float scale = getResources().getDisplayMetrics().density;
        int width = (int) (64 * scale);
        int height = (int) (64 * scale);

        // Set the options for a bitmap and decode an input stream into a bitmap
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(is, null, o);
        is.close();

        if (url.startsWith("http")) {
            is = new BufferedInputStream(new URL(url).openStream());
        } else if (connection.getProtocolVersion() > 9) {
            is = new HTSFileInputStream(connection, url);
        }

        // Set the sample size of the image. This is the number of pixels in
        // either dimension that correspond to a single pixel in the decoded
        // bitmap. For example, inSampleSize == 4 returns an image that is 1/4
        // the width/height of the original, and 1/16 the number of pixels.
        int ratio = Math.max(o.outWidth / width, o.outHeight / height);
        int sampleSize = Integer.highestOneBit((int) Math.floor(ratio));
        o = new BitmapFactory.Options();
        o.inSampleSize = sampleSize;

        // Now decode an input stream into a bitmap and compress it.
        Bitmap bitmap = BitmapFactory.decodeStream(is, null, o);
        if (bitmap != null) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        os.close();
        is.close();
    }

    private Bitmap getIcon(final String url) throws IOException {

        // When no channel icon shall be shown return null instead of the icon.
        // The icon will not be shown anyway, so returning null will drastically
        // reduce memory consumption.
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        Boolean showIcons = prefs.getBoolean("showIconPref", true);
        if (!showIcons) {
            return null; 
        }

        if (url == null || url.length() == 0) {
            return null;
        }

        File dir = getCacheDir();
        File f = new File(dir, hashString(url) + ".png");

        if (!f.exists()) {
            cacheImage(url, f);
        }

        return BitmapFactory.decodeFile(f.toString());
    }

    private void getChannelIcon(final Channel ch) {
        execService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ch.iconBitmap = getIcon(ch.icon);
                    dataStorage.updateChannel(ch);
                } catch (Throwable ex) {
                    logger.log(TAG, "run: Could not load channel icon. " + ex.getLocalizedMessage());
                }
            }
        });
    }

    private void getChannelTagIcon(final ChannelTag tag) {
        execService.execute(new Runnable() {
            public void run() {
                try {
                    tag.iconBitmap = getIcon(tag.icon);
                    dataStorage.updateChannelTag(tag);
                } catch (Throwable ex) {
                    logger.log(TAG, "run: Could not load tag icon. " + ex.getLocalizedMessage());
                }
            }
        });
    }

    private void getEvents(final Channel ch, final long eventId, int cnt) {
        if (ch == null) {
            return;
        }

        HTSMessage request = new HTSMessage();
        request.setMethod("getEvents");
        request.putField("eventId", eventId);
        request.putField("numFollowing", cnt);
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                if (!response.containsKey("events")) {
                    return;
                }

                for (Object obj : response.getList("events")) {
                    HTSMessage sub = (HTSMessage) obj;
                    Program p = getProgramFromEventMessage(sub);
                    p.channel = ch;

                    if (ch.epg.add(p)) {
                        dataStorage.addProgram(p);
                    }
                }
                dataStorage.updateChannel(ch);
            }
        });
    }

    private Program getProgramFromEventMessage(HTSMessage sub) {
        Program p = new Program();
        p.id = sub.getLong("eventId", 0);
        p.nextId = sub.getLong("nextEventId", 0);
        p.description = sub.getString("description");
        p.summary = sub.getString("summary");
        p.subtitle = sub.getString("subtitle");
        p.recording = dataStorage.getRecording(sub.getLong("dvrId", 0));
        p.contentType = sub.getInt("contentType", -1);
        p.title = sub.getString("title");
        p.start = sub.getDate("start");
        p.stop = sub.getDate("stop");
        p.seriesInfo = buildSeriesInfo(sub);
        p.starRating = sub.getInt("starRating", -1);
        p.image = sub.getString("image");
        return p;
    }

    private void getEvent(long eventId) {
        HTSMessage request = new HTSMessage();
        request.setMethod("getEvent");
        request.putField("eventId", eventId);

        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                Channel ch = dataStorage.getChannel(response.getLong("channelId"));
                Program p = getProgramFromEventMessage(response);
                p.channel = ch;

                if (ch.epg.add(p)) {
                    dataStorage.addProgram(p);
                    dataStorage.updateChannel(ch);
                }
            }
        });
    }

    private SeriesInfo buildSeriesInfo(HTSMessage msg) {
        SeriesInfo info = new SeriesInfo();
        info.episodeCount = msg.getInt("episodeCount", 0);
        info.episodeNumber = msg.getInt("episodeNumber", 0);
        info.onScreen = msg.getString("episodeOnscreen");
        info.partCount = msg.getInt("partCount", 0);
        info.partNumber = msg.getInt("partNumber", 0);
        info.seasonCount = msg.getInt("seasonCount", 0);
        info.seasonNumber = msg.getInt("seasonNumber", 0);
        info.serieslinkId = msg.getInt("serieslinkId", 0);
        return info;
    }
	
    private void epgQuery(final Intent intent) {

        final Channel ch = dataStorage.getChannel(intent.getLongExtra("channelId", 0));
        final String query = intent.getStringExtra("query");
        final long tagId = intent.getLongExtra("tagId", 0);

        HTSMessage request = new HTSMessage();
        request.setMethod("epgQuery");
        request.putField("query", query);

        // The default values will be set in case a server with a htsp API
        // version 12 or lower is used
        request.putField("minduration", 0);
        request.putField("maxduration", Integer.MAX_VALUE);

        if (ch != null && ch.id > 0) {
            request.putField("channelId", ch.id);
        }
        if (tagId > 0) {
            request.putField("tagId", tagId);
        }
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                if (!response.containsKey("eventIds")) {
                    return;
                }
                for (Long id : response.getLongList("eventIds")) {
                    getEvent(id);
                }
            }
        });
    }

    /**
     * Cancels a regular recording from the server with the given id.
     * If it was successful a positive message is shown
     * otherwise a negative one.
     *
     * @param id The id of the regular recording that shall be stopped
     */
    private void cancelDvrEntry(final long id) {
        HTSMessage request = new HTSMessage();
        request.setMethod("cancelDvrEntry");
        request.putField("id", id);
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                boolean success = response.getInt("success", 0) == 1;
                if (!success) {
                    dataStorage.showMessage(getString(R.string.error_removing_recording,
                                response.getString("error", "")));
                } else {
                    dataStorage.showMessage(getString(R.string.success_removing_recording));
                }
            }
        });
    }

    /**
     * Stops a regular recording from the server with the given id. If the
     * stopping was not successful then the fallback method to cancel a
     * recording is used. If it was successful a positive message is shown
     * otherwise a negative one.
     *
     * @param id The id of the regular recording that shall be stopped
     */
    private void stopDvrEntry(final long id) {
        HTSMessage request = new HTSMessage();
        request.setMethod("stopDvrEntry");
        request.putField("id", id);
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                boolean success = response.getInt("success", 0) == 1;
                if (!success) {
                    cancelDvrEntry(id);
                } else {
                    dataStorage.showMessage(getString(R.string.success_removing_recording));
                }
            }
        });
    }

    /**
     * Updates a regular recording on the server with the given id and values.
     * If the update was successful a positive message is shown otherwise a
     * negative one.
     *
     * @param intent Contains the intent with the (un)changed parameters of the regular
     *               recording
     */
    private void updateDvrEntry(final Intent intent) {

        final long id = intent.getLongExtra("id", 0);
        final long channelId = intent.getLongExtra("channelId", 0);
        final long start = intent.getLongExtra("start", 0);
        final long stop = intent.getLongExtra("stop", 0);
        final long retention = intent.getLongExtra("retention", 0);
        final long priority = intent.getLongExtra("priority", 2);
        final long enabled = intent.getLongExtra("enabled", 1);
        final long startExtra = intent.getLongExtra("startExtra", 0);
        final long stopExtra = intent.getLongExtra("stopExtra", 0);
        final String title = intent.getStringExtra("title");
        final String subtitle = intent.getStringExtra("subtitle");
        final String description = intent.getStringExtra("description");
        final String configName = intent.getStringExtra("configName");
        final boolean isRecording = intent.getBooleanExtra("isRecording", false);

        HTSMessage request = new HTSMessage();
        request.setMethod("updateDvrEntry");
        request.putField("id", id);
        request.putField("stop", stop);
        request.putField("stopExtra", stopExtra);

        // Only add these fields when the recording is only scheduled and not
        // being recorded
        if (!isRecording) {
            request.putField("start", start);
            request.putField("retention", retention);
            request.putField("priority", priority);
            request.putField("startExtra", startExtra);

            // Do not add these fields if not supported by the server. Updating
            // these strings was fixed in server version v4.1-111-g807b9c8
            if (title != null && dataStorage.getProtocolVersion() >= Constants.MIN_API_VERSION_REC_FIELD_TITLE) {
                request.putField("title", title);
            }
            if (subtitle != null && dataStorage.getProtocolVersion() >= Constants.MIN_API_VERSION_REC_FIELD_SUBTITLE) {
                request.putField("subtitle", subtitle);
            }
            if (description != null && dataStorage.getProtocolVersion() >= Constants.MIN_API_VERSION_REC_FIELD_DESCRIPTION) {
                request.putField("description", description);
            }
            if (channelId != 0 && dataStorage.getProtocolVersion() >= Constants.MIN_API_VERSION_REC_FIELD_UPDATE_CHANNEL) {
                request.putField("channelId", channelId);
            }
            if (configName != null) {
                request.putField("configName", configName);
            }
            // Enabled flag (Added in version 23)
            request.putField("enabled", enabled);
        }

        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                boolean success = response.getInt("success", 0) == 1;
                if (!success) {
                    dataStorage.showMessage(getString(R.string.error_updating_recording,
                            response.getString("error", "")));
                } else {
                    dataStorage.showMessage(getString(R.string.success_updating_recording));

                    // Force a reconnect. This is a workaround because no
                    // onDvrUpdate event is sent
                    Utils.connect(TVHClientApplication.getInstance().getApplicationContext(), true);
                }
            }
        });
    }

    /**
     * Deletes a regular recording from the server with the given id. If the
     * removal was successful a positive message is shown otherwise a negative
     * one.
     * 
     * @param id
     *            The id of the regular recording that shall be deleted
     */
    private void deleteDvrEntry(long id) {
        HTSMessage request = new HTSMessage();
        request.setMethod("deleteDvrEntry");
        request.putField("id", id);
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                boolean success = response.getInt("success", 0) == 1;
                if (!success) {
                    dataStorage.showMessage(getString(R.string.error_removing_recording,
                            response.getString("error", "")));
                } else {
                    dataStorage.showMessage(getString(R.string.success_removing_recording));
                }
            }
        });
    }

    /**
     * Adds a regular recording to the server with the given values. If the
     * adding was successful a positive message is shown otherwise a negative
     * one.
     *
     * @param intent The intent with the parameters of the recording that shall be added
     */
    private void addDvrEntry(final Intent intent) {

        final long eventId = intent.getLongExtra("eventId", 0);
        final long channelId = intent.getLongExtra("channelId", 0);
        final long start = intent.getLongExtra("start", 0);
        final long stop = intent.getLongExtra("stop", 0);
        final long retention = intent.getLongExtra("retention", 0);
        final String creator = intent.getStringExtra("creator");
        final long priority = intent.getLongExtra("priority", 2);
        final long enabled = intent.getLongExtra("enabled", 1);
        final long startExtra = intent.getLongExtra("startExtra", 0);
        final long stopExtra = intent.getLongExtra("stopExtra", 0);
        final String title = intent.getStringExtra("title");
        final String subtitle = intent.getStringExtra("subtitle");
        final String description = intent.getStringExtra("description");
        final String configName = intent.getStringExtra("configName");

        HTSMessage request = new HTSMessage();
        request.setMethod("addDvrEntry");

        // If the eventId is set then an existing program from the program guide
        // shall be recorded. The server will then ignore the other fields
        // automatically.
        request.putField("eventId", eventId);        
        request.putField("channelId", channelId);
        request.putField("start", start);
        request.putField("stop", stop);
        request.putField("startExtra", startExtra);
        request.putField("stopExtra", stopExtra);
        request.putField("retention", retention);
        request.putField("priority", priority);

        // Enabled flag (Added in version 23)
        request.putField("enabled", enabled);

        if (title != null) {
            request.putField("title", title);
        }
        if (subtitle != null) {
            request.putField("subtitle", subtitle);
        }
        if (description != null) {
            request.putField("description", description);
        }
        if (creator != null) {
            request.putField("creator", creator);
        }
        if (configName != null) {
            request.putField("configName", configName);
        }

        // Get the channel from the id to update the program list of the channel
        // when the recording was added.
        final Channel ch = dataStorage.getChannel(channelId);

        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                boolean success = response.getInt("success", 0) == 1;
                if (success && ch != null) {
                    for (Program p : ch.epg) {
                        if (p.id == eventId) {
                            p.recording = dataStorage.getRecording(response.getLong("id", 0));
                            dataStorage.updateProgram(p);
                            break;
                        }
                    }
                }

                if (!success) {
                    dataStorage.showMessage(getString(R.string.error_adding_recording,
                            response.getString("error", "")));
                } else {
                    dataStorage.showMessage(getString(R.string.success_adding_recording));
                }
            }
        });
    }

    /**
     * Updates a manual recording to the server with the given values. If the
     * update was successful a positive message is shown otherwise a negative
     * one.
     * 
     * @param intent
     *            Contains the parameters of the manual recording that shall be
     *            updated
     */
    private void updateTimerRecEntry(final Intent intent) {

        final String id = intent.getStringExtra("id");
        final long channelId = intent.getLongExtra("channelId", 0);
        final long start = intent.getLongExtra("start", 0);
        final long stop = intent.getLongExtra("stop", 0);
        final long retention = intent.getLongExtra("retention", 0);
        final long priority = intent.getLongExtra("priority", 2);
        final long daysOfWeek = intent.getLongExtra("daysOfWeek", 0);
        final long enabled = intent.getLongExtra("enabled", 1);
        final String directory = intent.getStringExtra("directory");
        final String title = intent.getStringExtra("title");
        final String name = intent.getStringExtra("name");
        final String configName = intent.getStringExtra("configName");

        HTSMessage request = new HTSMessage();
        request.setMethod("updateTimerecEntry");
        request.putField("id", id);
        request.putField("title", title);
        request.putField("name", name);
        request.putField("start", start);
        request.putField("stop", stop);
        request.putField("channelId", channelId);
        request.putField("retention", retention);
        request.putField("daysOfWeek", daysOfWeek);
        request.putField("priority", priority);
        request.putField("enabled", enabled);
        request.putField("directory", directory);

        if (configName != null) {
            request.putField("configName", configName);
        }

        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                boolean success = response.getInt("success", 0) == 1;
                if (!success) {
                    dataStorage.showMessage(getString(R.string.error_adding_recording,
                            response.getString("error", "")));
                } else {
                    dataStorage.showMessage(getString(R.string.success_adding_recording));
                }
            }
        });
    }

    /**
     * Deletes a manual recording from the server with the given id. If the
     * removal was successful a positive message is shown otherwise a negative
     * one.
     * 
     * @param id
     *            The id of the manual recording that shall be deleted
     */
    private void deleteTimerRecEntry(String id) {
        HTSMessage request = new HTSMessage();
        request.setMethod("deleteTimerecEntry");
        request.putField("id", id);
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                boolean success = response.getInt("success", 0) == 1;
                if (!success) {
                    dataStorage.showMessage(getString(R.string.error_removing_recording,
                            response.getString("error", "")));
                } else {
                    dataStorage.showMessage(getString(R.string.success_removing_recording));
                }
            }
        });
    }

    /**
     * Adds a manual recording to the server with the given values. If the
     * adding was successful a positive message is shown otherwise a negative
     * one.
     * 
     * @param intent
     *            Contains the parameters of the manual recording that shall be
     *            added
     */
    private void addTimerRecEntry(final Intent intent) {

        final long channelId = intent.getLongExtra("channelId", 0);
        final long start = intent.getLongExtra("start", 0);
        final long stop = intent.getLongExtra("stop", 0);
        final long retention = intent.getLongExtra("retention", 0);
        final long priority = intent.getLongExtra("priority", 2);
        final long daysOfWeek = intent.getLongExtra("daysOfWeek", 0);
        final long enabled = intent.getLongExtra("enabled", 1);
        final String directory = intent.getStringExtra("directory");
        final String title = intent.getStringExtra("title");
        final String name = intent.getStringExtra("name");
        final String configName = intent.getStringExtra("configName");

        HTSMessage request = new HTSMessage();
        request.setMethod("addTimerecEntry");
        request.putField("title", title);
        request.putField("name", name);
        request.putField("start", start);
        request.putField("stop", stop);
        request.putField("channelId", channelId);
        request.putField("retention", retention);
        request.putField("daysOfWeek", daysOfWeek);
        request.putField("priority", priority);
        request.putField("enabled", enabled);
        request.putField("directory", directory);

        if (configName != null) {
            request.putField("configName", configName);
        }

        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                boolean success = response.getInt("success", 0) == 1;
                if (!success) {
                    dataStorage.showMessage(getString(R.string.error_adding_recording,
                            response.getString("error", "")));
                } else {
                    dataStorage.showMessage(getString(R.string.success_adding_recording));
                }
            }
        });
    }

    private void subscribe(long channelId, long subscriptionId, int maxWidth, int maxHeight, String aCodec, String vCodec) {
        Subscription subscription = new Subscription();
        subscription.id = subscriptionId;
        subscription.status = "Subscribing";

        dataStorage.addSubscription(subscription);

        HTSMessage request = new HTSMessage();
        request.setMethod("subscribe");
        request.putField("channelId", channelId);
        request.putField("maxWidth", maxWidth);
        request.putField("maxHeight", maxHeight);
        request.putField("audioCodec", aCodec);
        request.putField("videoCodec", vCodec);
        request.putField("subscriptionId", subscriptionId);
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                //NOP
            }
        });
    }

    private void unsubscribe(long subscriptionId) {
        dataStorage.removeSubscription(subscriptionId);

        HTSMessage request = new HTSMessage();
        request.setMethod("unsubscribe");
        request.putField("subscriptionId", subscriptionId);
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                //NOP
            }
        });
    }

    private void feedback(long subscriptionId, int speed) {
        HTSMessage request = new HTSMessage();
        request.setMethod("feedback");
        request.putField("subscriptionId", subscriptionId);
        request.putField("speed", speed);
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                //NOP
            }
        });
    }

    private void getTicket(Channel ch) {
        HTSMessage request = new HTSMessage();
        request.setMethod("getTicket");
        request.putField("channelId", ch.id);
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                String path = response.getString("path", null);
                String ticket = response.getString("ticket", null);
                String webroot = connection.getWebRoot();
                
                if (path != null && ticket != null) {
                    dataStorage.addTicket(new HttpTicket(webroot + path, ticket));
                }
            }
        });
    }

    private void getTicket(Recording rec) {
        HTSMessage request = new HTSMessage();
        request.setMethod("getTicket");
        request.putField("dvrId", rec.id);
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                String path = response.getString("path", null);
                String ticket = response.getString("ticket", null);

                if (path != null && ticket != null) {
                    dataStorage.addTicket(new HttpTicket(path, ticket));
                }
            }
        });
    }
    
    private void getDiscSpace() {
        HTSMessage request = new HTSMessage();
        request.setMethod("getDiskSpace");
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                DiscSpace ds = new DiscSpace();
                ds.freediskspace = response.getString("freediskspace", null);
                ds.totaldiskspace = response.getString("totaldiskspace", null);
                dataStorage.addDiscSpace(ds);
            }
        });
    }

    private void getSystemTime() {
        HTSMessage request = new HTSMessage();
        request.setMethod("getSysTime");
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                SystemTime st = new SystemTime();
                st.time = response.getString("time", null);
                st.timezone = response.getString("timezone", null);
                st.gmtoffset = response.getString("gmtoffset", null);
                dataStorage.addSystemTime(st);
            }
        });
    }

    private void getDvrConfigs() {
        HTSMessage request = new HTSMessage();
        request.setMethod("getDvrConfigs");
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                if (!response.containsKey("dvrconfigs")) {
                    return;
                }
                List<Profiles> pList = new ArrayList<>();
                for (Object obj : response.getList("dvrconfigs")) {
                    HTSMessage sub = (HTSMessage) obj;

                    Profiles p = new Profiles();
                    p.uuid = sub.getString("uuid");
                    p.name = sub.getString("name");
                    if (p.name.length() == 0) {
                        p.name = Constants.REC_PROFILE_DEFAULT;
                    }
                    p.comment = sub.getString("comment");
                    pList.add(p);
                }
                dataStorage.addDvrConfigs(pList);
            }
        });
    }

    /**
     * Updates a series recording to the server with the given values. If the
     * update was successful a positive message is shown otherwise a negative
     * one.
     * 
     * @param intent
     *            Contains the parameters of the series recording that shall be
     *            updated
     */
    private void updateAutorecEntry(final Intent intent) {

        final String id = intent.getStringExtra("id");
        final String title = intent.getStringExtra("title");
        final String name = intent.getStringExtra("name");
        final long channelId = intent.getLongExtra("channelId", 0);
        final long maxDuration = intent.getLongExtra("maxDuration", 0);
        final long minDuration = intent.getLongExtra("minDuration", 0);
        final long retention = intent.getLongExtra("retention", 0);
        final long daysOfWeek = intent.getLongExtra("daysOfWeek", 127);
        final long priority = intent.getLongExtra("priority", 2);
        final long enabled = intent.getLongExtra("enabled", 1);
        final String directory = intent.getStringExtra("directory");
        final long startExtra = intent.getLongExtra("startExtra", 0);
        final long stopExtra = intent.getLongExtra("stopExtra", 0);
        final long dupDetect = intent.getLongExtra("dupDetect", 0);
        final long start = intent.getLongExtra("start", -1);
        final long startWindow = intent.getLongExtra("startWindow", -1);
        final String configName = intent.getStringExtra("configName");

        HTSMessage request = new HTSMessage();
        request.setMethod("updateAutorecEntry");
        request.putField("id", id);
        request.putField("title", title);
        request.putField("name", name);

        // Don't add the channel id if none was given. Assume the user wants to
        // record on all channels 
        if (channelId > 0) {
            request.putField("channelId", channelId);
        }

        // Minimal duration in seconds (0 = Any)
        request.putField("minDuration", minDuration);
        // Maximal duration in seconds (0 = Any)
        request.putField("maxDuration", maxDuration);
        request.putField("retention", retention);
        request.putField("daysOfWeek", daysOfWeek);
        request.putField("priority", priority);
        request.putField("startExtra", startExtra);
        request.putField("stopExtra", stopExtra);

        // Minutes from midnight (up to 24*60) for the start of the time
        // window (including) (Added in version 18). Do not send the value
        // if the default of -1 (no time specified) was set
        if (start >= 0) {
            request.putField("start", start);
        }
        // Minutes from midnight (up to 24*60) for the end of the time
        // window (including, cross-noon allowed) (Added in version 18). Do
        // not send the value if the default of -1 (no time specified) was set
        if (startWindow >= 0) {
            request.putField("startWindow", startWindow);
        }
        // Minutes from midnight (up to 24*60) (window +- 15 minutes)
        // (Obsoleted from version 18). Do not send the value if the default
        // of -1 (no time specified) was set
        if (start >= 0) {
            request.putField("approxTime", start);
        }

        request.putField("enabled", enabled);
        request.putField("directory", directory);
        request.putField("dupDetect", dupDetect);

        if (configName != null) {
            request.putField("configName", configName);
        }

        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                boolean success = response.getInt("success", 0) == 1;
                if (!success) {
                    dataStorage.showMessage(getString(R.string.error_adding_recording,
                            response.getString("error", "")));
                } else {
                    dataStorage.showMessage(getString(R.string.success_adding_recording));
                }
            }
        });
    }

    /**
     * Deletes a series recording from the server with the given id. If the
     * removal was successful a positive message is shown otherwise a negative
     * one.
     *
     * @param id The id of the series recording that shall be deleted
     */
    private void deleteAutorecEntry(final String id) {
        HTSMessage request = new HTSMessage();
        request.setMethod("deleteAutorecEntry");
        request.putField("id", id);
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                boolean success = response.getInt("success", 0) == 1;
                if (!success) {
                    dataStorage.showMessage(getString(R.string.error_removing_recording,
                            response.getString("error", "")));
                } else {
                    dataStorage.showMessage(getString(R.string.success_removing_recording));
                }
            }
        });
    }

    /**
     * Adds a series recording to the server with the given values. If the
     * adding was successful a positive message is shown otherwise a negative
     * one.
     *
     * @param intent Contains the parameters of the series recording that shall be
     *               added
     */
    private void addAutorecEntry(final Intent intent) {

        final String title = intent.getStringExtra("title");
        final String fulltext = intent.getStringExtra("fulltext");
        final String name = intent.getStringExtra("name");
        final long channelId = intent.getLongExtra("channelId", 0);
        final long maxDuration = intent.getLongExtra("maxDuration", 0);
        final long minDuration = intent.getLongExtra("minDuration", 0);
        final long retention = intent.getLongExtra("retention", 0);
        final long daysOfWeek = intent.getLongExtra("daysOfWeek", 127);
        final long priority = intent.getLongExtra("priority", 2);
        final long enabled = intent.getLongExtra("enabled", 1);
        final String directory = intent.getStringExtra("directory");
        final long startExtra = intent.getLongExtra("startExtra", 0);
        final long stopExtra = intent.getLongExtra("stopExtra", 0);
        final long dupDetect = intent.getLongExtra("dupDetect", 0);
        final long start = intent.getLongExtra("start", -1);
        final long startWindow = intent.getLongExtra("startWindow", -1);
        final String configName = intent.getStringExtra("configName");

        HTSMessage request = new HTSMessage();
        request.setMethod("addAutorecEntry");
        request.putField("title", title);
        request.putField("name", name);

        // Don't add the channel id if none was given. Assume the user wants to
        // record on all channels 
        if (channelId > 0) {
            request.putField("channelId", channelId);
        }

        // Minimal duration in seconds (0 = Any)
        request.putField("minDuration", minDuration);
        // Maximal duration in seconds (0 = Any)
        request.putField("maxDuration", maxDuration);
        request.putField("retention", retention);
        request.putField("daysOfWeek", daysOfWeek);
        request.putField("priority", priority);
        request.putField("startExtra", startExtra);
        request.putField("stopExtra", stopExtra);

        // Minutes from midnight (up to 24*60) for the start of the time
        // window (including) (Added in version 18). Do not send the value
        // if the default of -1 (no time specified) was set
        if (start >= 0) {
            request.putField("start", start);
        }
        // Minutes from midnight (up to 24*60) for the end of the time
        // window (including, cross-noon allowed) (Added in version 18). Do
        // not send the value if the default of -1 (no time specified) was set
        if (startWindow >= 0) {
            request.putField("startWindow", startWindow);
        }
        // Minutes from midnight (up to 24*60) (window +- 15 minutes)
        // (Obsoleted from version 18). Do not send the value if the default
        // of -1 (no time specified) was set
        if (start >= 0) {
            request.putField("approxTime", start);
        }

        request.putField("enabled", enabled);
        request.putField("directory", directory);
        request.putField("dupDetect", dupDetect);

        if (configName != null) {
            request.putField("configName", configName);
        }
        if (fulltext != null) {
            request.putField("fulltext", fulltext);
        }

        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                @SuppressWarnings("unused")
                boolean success = response.getInt("success", 0) == 1;
                if (!success) {
                    dataStorage.showMessage(getString(R.string.error_adding_recording,
                            response.getString("error", "")));
                } else {
                    dataStorage.showMessage(getString(R.string.success_adding_recording));
                }
            }
        });
    }

    private void getDvrCutpoints(final Recording rec) {

        HTSMessage request = new HTSMessage();
        request.setMethod("getDvrCutpoints");
        request.putField("id", rec.id);
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                if (!response.containsKey("cutpoints")) {
                    return;
                }
                // Clear all saved cut points before adding new ones.
                rec.dvrCutPoints.clear();

                for (Object obj : response.getList("cutpoints")) {
                    DvrCutpoint dc = new DvrCutpoint();
                    HTSMessage sub = (HTSMessage) obj;
                    dc.start = sub.getInt("start");
                    dc.end = sub.getInt("end");
                    dc.type = sub.getInt("type");
                    rec.dvrCutPoints.add(dc);
                }
            }
        });
    }

    private void subscriptionFilterStream() {
        // NOP
    }

    private void getChannel(final Channel ch) {
        HTSMessage request = new HTSMessage();
        request.setMethod("getChannel");
        request.putField("channelId", ch.id);
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                // NOP
            }
        });
    }

    private void getProfiles() {
        HTSMessage request = new HTSMessage();
        request.setMethod("getProfiles");
        connection.sendMessage(request, new HTSResponseHandler() {
            public void handleResponse(HTSMessage response) {
                if (!response.containsKey("profiles")) {
                    return;
                }
                List<Profiles> pList = new ArrayList<>();
                for (Object obj : response.getList("profiles")) {
                    HTSMessage sub = (HTSMessage) obj;

                    Profiles p = new Profiles();
                    p.uuid = sub.getString("uuid");
                    p.name = sub.getString("name");
                    p.comment = sub.getString("comment");
                    pList.add(p);
                }
                dataStorage.addProfiles(pList);
            }
        });
    }

    private void onEventAdd(HTSMessage msg) {
        dataStorage.addProgramToArray(HTSUtils.convertMessageToProgramModel(msg));
    }

    private void onEventUpdate(HTSMessage msg) {
        dataStorage.updateProgramInArray(HTSUtils.convertMessageToProgramModel(msg));
    }

    private void onEventDelete(HTSMessage msg) {
        dataStorage.removeProgramFromArray(msg.getInt("eventId"));
    }
}
