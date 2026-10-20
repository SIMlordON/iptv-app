package cz.petr.iptv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires a reminder (a notification that opens the channel) and re-arms the alarms after a reboot. */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent in) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(in.getAction())) {
            Reminders.rescheduleAll(c);
            return;
        }
        String key = in.getStringExtra("k");
        String ch = in.getStringExtra("c");
        String title = in.getStringExtra("t");
        long start = in.getLongExtra("s", 0);
        if (key == null) return;
        Reminders.Item gone = new Reminders.Item();
        gone.key = key;
        gone.start = start;
        Reminders.remove(c, gone);

        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel("remind", "Připomínky pořadů", NotificationManager.IMPORTANCE_HIGH));
        Intent open = new Intent(c, MainActivity.class);
        open.putExtra("play", key);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, (key + start).hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, "remind")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Za chvíli: " + title)
                .setContentText(ch + "  " + Prog.hm(start))
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build();
        nm.notify((key + start).hashCode(), n);
    }
}
