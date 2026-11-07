package com.example.smartgassafety;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/** Leak-alert notifications, shared by the user page and the admin page. */
public class NotificationHelper {

    private static final String CHANNEL_ID = "leak_alerts";

    /** Call once in onCreate of any screen that shows alerts. */
    public static void setup(Activity activity) {
        createChannel(activity);
        askPermission(activity);
    }

    /**
     * Shows a leak notification.
     * id           - keeps alerts from different users separate
     * screenToOpen - the screen that opens when the notification is tapped
     */
    @SuppressLint("MissingPermission")
    public static void sendLeakAlert(Context context, int id, String message, Class<?> screenToOpen) {
        if (!hasPermission(context)) return; // user denied notifications

        Intent openScreen = new Intent(context, screenToOpen);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, id, openScreen, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("⚠️ Gas Leak Detected")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        NotificationManagerCompat.from(context).notify(id, builder.build());
    }

    // Channels are required on Android 8+
    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Gas Leak Alerts", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Alerts when a gas leak is detected");
            context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    // Android 13+ asks the user for permission to show notifications
    private static void askPermission(Activity activity) {
        if (!hasPermission(activity)) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    // Older Android versions don't need the permission
    private static boolean hasPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }
}