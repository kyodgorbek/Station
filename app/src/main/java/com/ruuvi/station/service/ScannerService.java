package com.ruuvi.station.service;

import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnSuccessListener;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.raizlabs.android.dbflow.sql.language.SQLite;
import com.ruuvi.station.R;
import com.ruuvi.station.feature.StartupActivity;
import com.ruuvi.station.gateway.Http;
import com.ruuvi.station.model.LeScanResult;
import com.ruuvi.station.model.RuuviTag;
import com.ruuvi.station.model.RuuviTag_Table;
import com.ruuvi.station.model.TagSensorReading;
import com.ruuvi.station.util.AlarmChecker;
import com.ruuvi.station.util.BackgroundScanModes;
import com.ruuvi.station.util.Constants;
import com.ruuvi.station.util.Foreground;
import com.ruuvi.station.util.Preferences;
import com.ruuvi.station.util.Utils;


public class ScannerService extends Service {
    private static final String TAG = "ScannerService";

    private boolean scanning;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private List<ScanFilter> scanFilters = new ArrayList<ScanFilter>();
    private ScanSettings scanSettings;
    private Handler handler;
    private Handler bgScanHandler;
    private boolean isForegroundMode = false;
    private boolean foreground = false;
    private int backgroundScanInterval = Constants.DEFAULT_SCAN_INTERVAL;
    private static List<RuuviTag> backgroundTags = new ArrayList<>();
    private static final int SCAN_TIME_MS = 5000;
    private Location tagLocation;
    private PowerManager.WakeLock wakeLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Foreground.init(getApplication());
        Foreground.get().addListener(listener);

        foreground = true;
        scanSettings = new ScanSettings.Builder()
                .setReportDelay(0)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        scanner = bluetoothAdapter.getBluetoothLeScanner();

        if (getForegroundMode()) startFG();
        handler = new Handler();
        handler.post(reStarter);
        bgScanHandler = new Handler();
    }

    private void updateLocation() {
        FusedLocationProviderClient mFusedLocationClient = LocationServices.getFusedLocationProviderClient(getApplicationContext());
        if (ContextCompat.checkSelfPermission(getApplicationContext(), android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mFusedLocationClient.getLastLocation().addOnSuccessListener(new OnSuccessListener<Location>() {
                @Override
                public void onSuccess(Location location) {
                    tagLocation = location;
                }
            });
        }
    }

    private boolean getForegroundMode() {
        Preferences prefs = new Preferences(this);
        int getInterval =  prefs.getBackgroundScanInterval();
        return prefs.getBackgroundScanMode() == BackgroundScanModes.DISABLED && getInterval < 15 * 60;
        //return settings.getBoolean("pref_bgscan", false);
    }

    private Runnable reStarter = new Runnable() {
        @Override
        public void run() {
            stopScan();
            startScan();
            handler.postDelayed(reStarter, 5 * 60 * 1000);
        }
    };

    private Runnable bgLogger = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "Started background scan");
            backgroundTags.clear();
            startScan();
            updateLocation();
            Log.d(TAG, "Scheduling next scan in " + backgroundScanInterval + "s");
            bgScanHandler.postDelayed(bgLogger, backgroundScanInterval * 1000);
            bgScanHandler.postDelayed(bgLoggerDone, SCAN_TIME_MS);
        }
    };

    private Runnable bgLoggerDone = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "Stopping background scan, found " + backgroundTags.size() + " tags");
            stopScan();
            Http.post(backgroundTags, tagLocation, getApplicationContext());

            for (RuuviTag tag: backgroundTags) {
                TagSensorReading reading = new TagSensorReading(tag);
                reading.save();
                AlarmChecker.check(tag, getApplicationContext());
            }
        }
    };

    public void startFG() {
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);


        String channelId = "foreground_scanner_channel";
        if (Build.VERSION.SDK_INT >= 26) {
            CharSequence channelName = "RuuviStation foreground scanner";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel notificationChannel = new NotificationChannel(channelId, channelName, importance);
            try {
                notificationManager.createNotificationChannel(notificationChannel);
            } catch (Exception e) {
                Log.e(TAG, "Could not create notification channel");
            }
        }

        isForegroundMode = true;
        Intent notificationIntent = new Intent(this, StartupActivity.class);

        Bitmap bitmap = BitmapFactory.decodeResource(getApplicationContext().getResources(), R.mipmap.ic_launcher);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        NotificationCompat.Builder notification;
        notification
                = new NotificationCompat.Builder(getApplicationContext(), channelId)
                .setContentTitle(this.getString(R.string.scanner_notification_title))
                .setSmallIcon(R.mipmap.ic_launcher_small)
                .setTicker(this.getString(R.string.scanner_notification_ticker))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(this.getString(R.string.scanner_notification_message)))
                .setContentText(this.getString(R.string.scanner_notification_message))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                .setLargeIcon(bitmap)
                .setContentIntent(pendingIntent);

        notification.setSmallIcon(R.drawable.ic_ruuvi_notification_icon_v1);

        startForeground(1337, notification.build());
    }

    private boolean canScan() {
        return scanner != null;
    }

    public void startScan() {
        if (scanning || !canScan()) return;
        scanning = true;
        try {
            scanner.startScan(Utils.getScanFilters(), scanSettings, nsCallback);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            scanning = false;
            Toast.makeText(getApplicationContext(), "Couldn't start scanning, is bluetooth disabled?", Toast.LENGTH_LONG).show();
        }
    }

    public void stopScan() {
        if (!canScan()) return;
        scanning = false;
        scanner.stopScan(nsCallback);
    }

    private ScanCallback nsCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            foundDevice(result.getDevice(), result.getRssi(), result.getScanRecord().getBytes());
        }
    };

    private void foundDevice(BluetoothDevice device, int rssi, byte[] data) {
        LeScanResult dev = new LeScanResult();
        dev.device = device;
        dev.rssi = rssi;
        dev.scanData = data;

        //Log.d(TAG, "found: " + device.getAddress());
        RuuviTag tag = dev.parse(getApplicationContext());
        if (tag != null) logTag(tag, getApplicationContext(), foreground);
    }

    @Override
    public void onDestroy() {
        stopScan();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    Foreground.Listener listener = new Foreground.Listener() {
        public void onBecameForeground() {
            if (wakeLock != null) {
                try {
                    wakeLock.release();
                    Log.d(TAG, "Released wakelock");
                } catch (Exception e) {
                    Log.e(TAG, "Could not release wakelock");
                }
            }
            foreground = true;
            handler.postDelayed(reStarter, 5 * 60 * 1000);
            if (!isRunning(ScannerService.class))
                startService(new Intent(ScannerService.this, ScannerService.class));
            bgScanHandler.removeCallbacksAndMessages(null);
        }

        public void onBecameBackground() {
            foreground = false;
            handler.removeCallbacksAndMessages(null);
            if (!getForegroundMode()) {
                stopSelf();
                isForegroundMode = false;
            } else {
                Preferences prefs = new Preferences(getApplicationContext());
                if (prefs.getServiceWakelock()) {
                    PowerManager powerManager = (PowerManager) getApplicationContext().getSystemService(POWER_SERVICE);
                    try {
                        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                "ruuviStation:serviceWakelock");
                        wakeLock.acquire();
                        Log.d(TAG, "Acquired wakelock");
                    } catch (Exception e) {
                        Log.e(TAG, "Could not acquire wakelock");
                    }
                } else {
                    wakeLock = null;
                }
                backgroundScanInterval = prefs.getBackgroundScanInterval();
                if (!isForegroundMode) startFG();
                bgScanHandler.postDelayed(bgLogger, backgroundScanInterval * 1000);
            }
        }
    };

    public static Map<String, Long> lastLogged = null;
    public static int LOG_INTERVAL = 5; // seconds

    public static void logTag(RuuviTag ruuviTag, Context context, boolean foreground) {
        RuuviTag dbTag = RuuviTag.get(ruuviTag.id);
        if (dbTag != null) {
            ruuviTag = dbTag.preserveData(ruuviTag);
            ruuviTag.update();
            if (!dbTag.favorite) return;
        } else {
            ruuviTag.updateAt = new Date();
            ruuviTag.save();
            return;
        }

        if (!foreground) {
            if (ruuviTag.favorite && checkForSameTag(backgroundTags, ruuviTag) == -1) {
                backgroundTags.add(ruuviTag);
            }
            return;
        }

        if (lastLogged == null) lastLogged = new HashMap<>();

        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.SECOND, -LOG_INTERVAL);
        long loggingThreshold = calendar.getTime().getTime();
        for (Map.Entry<String, Long> entry : lastLogged.entrySet())
        {
            if (entry.getKey().equals(ruuviTag.id) && entry.getValue() > loggingThreshold) {
                return;
            }
        }

        List<RuuviTag> tags = new ArrayList<>();
        tags.add(ruuviTag);
        Http.post(tags, null, context);

        lastLogged.put(ruuviTag.id, new Date().getTime());
        TagSensorReading reading = new TagSensorReading(ruuviTag);
        reading.save();
        AlarmChecker.check(ruuviTag, context);
    }

    public static boolean Exists(String id) {
        long count = SQLite.selectCountOf()
                .from(RuuviTag.class)
                .where(RuuviTag_Table.id.eq(id))
                .count();
        return count > 0;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopForeground(true);
        stopSelf();
        Foreground.get().removeListener(listener);
    }

    private boolean isRunning(Class<?> serviceClass) {
        ActivityManager mgr = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : mgr.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private static int checkForSameTag(List<RuuviTag> arr, RuuviTag ruuvi) {
        for (int i = 0; i < arr.size(); i++) {
            if (ruuvi.id.equals(arr.get(i).id)) {
                return i;
            }
        }
        return -1;
    }
}
