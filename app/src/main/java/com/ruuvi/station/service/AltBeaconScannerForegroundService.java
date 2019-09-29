package com.ruuvi.station.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import android.util.Log;

import com.ruuvi.station.R;
import com.ruuvi.station.RuuviScannerApplication;
import com.ruuvi.station.feature.StartupActivity;
import com.ruuvi.station.util.Foreground;
import com.ruuvi.station.util.Preferences;
import com.ruuvi.station.util.Utils;

import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.Region;
import org.altbeacon.bluetooth.BluetoothMedic;


public class AltBeaconScannerForegroundService extends Service implements BeaconConsumer {
    private static final String TAG = "AScannerFgService";

    private BeaconManager beaconManager;
    private Region region;
    RuuviRangeNotifier ruuviRangeNotifier;
    BluetoothMedic medic;
    NotificationCompat.Builder notification;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        if (beaconManager != null) {
            updateNotification();
        }
        return Service.START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Starting foreground service");
        beaconManager = BeaconManager.getInstanceForApplication(getApplicationContext());
        Utils.setAltBeaconParsers(beaconManager);
        beaconManager.setBackgroundScanPeriod(5000);

        Foreground.init(getApplication());
        Foreground.get().addListener(listener);

        ruuviRangeNotifier = new RuuviRangeNotifier(getApplicationContext(), "AltBeaconFGScannerService");
        region = new Region("com.ruuvi.station.leRegion", null, null, null);
        startFG();
        beaconManager.bind(this);
        medic = RuuviScannerApplication.setupMedic(getApplicationContext());
        setBackground(); // start in background mode
    }

    private NotificationCompat.Builder setupNotification() {
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        String channelId = "foreground_scanner_channel";
        if (Build.VERSION.SDK_INT >= 26) {
            CharSequence channelName = "RuuviStation foreground scanner";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel notificationChannel = new NotificationChannel(channelId, channelName, importance);
            try {
                notificationManager.createNotificationChannel(notificationChannel);
            } catch (Exception e) {
                Log.e(TAG, "Could not create notification channel");
            }
        }

        Intent notificationIntent = new Intent(this, StartupActivity.class);

        Bitmap bitmap = BitmapFactory.decodeResource(getApplicationContext().getResources(), R.mipmap.ic_launcher);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        String notificationText = getString(R.string.scanner_notification_title);
        notificationText = notificationText.replace("..", " every ");
        int scanInterval = new Preferences(getApplicationContext()).getBackgroundScanInterval();
        int min = scanInterval / 60;
        int sec = scanInterval - min * 60;
        if (min > 0) {
            String minutes = getString(R.string.minutes).toLowerCase();
            if (min == 1) {
                minutes = minutes.substring(0, minutes.length() - 1);
            }
            notificationText += min + " " + minutes + ", ";
        }
        if (sec > 0) {
            String seconds = getString(R.string.seconds).toLowerCase();
            if (sec == 1) {
                seconds = seconds.substring(0, seconds.length() - 1);
            }
            notificationText += sec + " " + seconds;
        }
        else {
            notificationText = notificationText.replace(", ", "");
        }

        notification
                = new NotificationCompat.Builder(getApplicationContext(), channelId)
                .setContentTitle(notificationText)
                .setSmallIcon(R.mipmap.ic_launcher_small)
                .setTicker(this.getString(R.string.scanner_notification_ticker))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(this.getString(R.string.scanner_notification_message)))
                .setContentText(this.getString(R.string.scanner_notification_message))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setLargeIcon(bitmap)
                .setContentIntent(pendingIntent);

        notification.setSmallIcon(R.drawable.ic_ruuvi_bgscan_icon);

        return notification;
    }

    private void startFG() {
        setupNotification();
        //beaconManager.enableForegroundServiceScanning(notification.build(), 1337);
        beaconManager.setEnableScheduledScanJobs(false);
        startForeground(1337, notification.build());
    }

    private void updateNotification() {
        setupNotification();
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            mNotificationManager.notify(1337, notification.build());
        } catch (NullPointerException e) {
            Log.d(TAG, "Could not update notification");
        }
    }

    private void setBackground() {
        int scanInterval = new Preferences(getApplicationContext()).getBackgroundScanInterval() * 1000;
        if (scanInterval != beaconManager.getBackgroundBetweenScanPeriod()) {
            updateNotification();
            beaconManager.setBackgroundBetweenScanPeriod(scanInterval);
            try {
                beaconManager.updateScanPeriods();
            } catch (Exception e) {
                Log.e(TAG, "Could not update scan intervals");
            }
        }
        beaconManager.setBackgroundMode(true);
    }

    Foreground.Listener listener = new Foreground.Listener() {
        public void onBecameForeground() {
            Utils.removeStateFile(getApplicationContext());
            beaconManager.setBackgroundMode(false);
        }

        public void onBecameBackground() {
           setBackground();
        }
    };

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy =======");
        beaconManager.removeRangeNotifier(ruuviRangeNotifier);
        try {
            beaconManager.stopRangingBeaconsInRegion(region);
        } catch (Exception e) {
            Log.d(TAG, "Could not stop ranging region");
        }
        medic = null;
        beaconManager.unbind(this);
        //beaconManager.setEnableScheduledScanJobs(true);
        //beaconManager.disableForegroundServiceScanning();
        beaconManager = null;
        ruuviRangeNotifier = null;
        stopForeground(true);
        if (listener != null) Foreground.get().removeListener(listener);
        ((RuuviScannerApplication)getApplication()).startBackgroundScanning();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onBeaconServiceConnect() {
        Log.d(TAG, "onBeaconServiceConnect");
        //Toast.makeText(getApplicationContext(), "Started scanning (Service)", Toast.LENGTH_SHORT).show();
        ruuviRangeNotifier.gatewayOn = true;
        if (!beaconManager.getRangingNotifiers().contains(ruuviRangeNotifier)) {
            beaconManager.addRangeNotifier(ruuviRangeNotifier);
        }
        try {
            beaconManager.startRangingBeaconsInRegion(region);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not start ranging");
        }
    }
}
