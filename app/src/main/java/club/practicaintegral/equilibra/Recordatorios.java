package club.practicaintegral.equilibra;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Recordatorios del calendario de Amable Life con la app CERRADA (APK 2.38).
 * La web manda la lista de los próximos días: [{id, at (milisegundos), tit, txt}], y aquí cada uno se deja programado
 * en el reloj de Android (AlarmManager). A su hora sale una notificación con sonido y vibración; tocarla abre la app.
 * Si la app está abierta en pantalla no se notifica: la web ya lo avisa (en pantalla y con voz).
 * Se guardan para volver a programarlos al encender el teléfono o al actualizar la app.
 */
public class Recordatorios extends BroadcastReceiver {
    static final String CANAL = "recordatorios";
    private static final String PREFS = "recordatorios";
    /** La app está en pantalla (MainActivity.onResume / onPause). */
    static volatile boolean appVisible = false;

    /** Programa los recordatorios que manda la web y quita los que ya no están. */
    static void programar(Context c, String json) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        cancelar(c, p.getString("lista", "[]"));
        p.edit().putString("lista", json == null ? "[]" : json).apply();
        poner(c, json);
    }

    /** Al encender el teléfono o al actualizar la app: vuelve a programar los guardados. */
    static void reprogramar(Context c) {
        poner(c, c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lista", "[]"));
    }

    private static void poner(Context c, String json) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long ahora = System.currentTimeMillis();
        try {
            JSONArray a = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.getJSONObject(i);
                long at = r.optLong("at", 0);
                if (at <= ahora) continue;
                PendingIntent pi = intento(c, r.optString("id"), r.optString("tit", "Amable Life"), r.optString("txt", ""));
                boolean exacta = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
                if (exacta) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Exception ignored) {}
    }

    private static void cancelar(Context c, String json) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) am.cancel(intento(c, a.getJSONObject(i).optString("id"), "", ""));
        } catch (Exception ignored) {}
    }

    private static PendingIntent intento(Context c, String id, String tit, String txt) {
        Intent i = new Intent(c, Recordatorios.class).setAction("club.practicaintegral.equilibra.RECORDATORIO." + id);
        i.putExtra("id", id).putExtra("tit", tit).putExtra("txt", txt);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (appVisible) return;   // con la app en pantalla avisa la web
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CANAL, "Recordatorios del calendario", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Los recordatorios de tus tareas y hábitos del calendario");
        ch.enableVibration(true);
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
        nm.createNotificationChannel(ch);
        String id = intent.getStringExtra("id");
        PendingIntent abrir = PendingIntent.getActivity(c, 3,
                new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CANAL)
                .setSmallIcon(R.drawable.ic_stat_pasos)
                .setContentTitle(intent.getStringExtra("tit"))
                .setContentText(intent.getStringExtra("txt"))
                .setStyle(new Notification.BigTextStyle().bigText(intent.getStringExtra("txt")))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(abrir)
                .setAutoCancel(true)
                .build();
        try { nm.notify(id == null ? 7 : id.hashCode(), n); } catch (SecurityException ignored) {}
    }
}
