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

    /** APK 2.39 (Gustavo: «tiene que sonar una alarma»): canal nuevo con el sonido de ALARMA del teléfono
     *  (los canales no se pueden cambiar una vez creados; el viejo «recordatorios» ya no se usa). */
    static final String CANAL_ALARMA = "alarmas";
    private static final String PARAR = "club.practicaintegral.equilibra.PARAR.";
    private static final String POSPONER = "club.practicaintegral.equilibra.POSPONER.";

    @Override
    public void onReceive(Context c, Intent intent) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String act = intent.getAction() == null ? "" : intent.getAction();
        String id = intent.getStringExtra("id");
        int nid = id == null ? 7 : id.hashCode();
        if (act.startsWith(PARAR)) { nm.cancel(nid); return; }
        if (act.startsWith(POSPONER)) {   // vuelve a sonar dentro de 5 minutos
            nm.cancel(nid);
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                PendingIntent pi = intento(c, id + ".pos", intent.getStringExtra("tit"), "Pospuesto · " + intent.getStringExtra("txt"));
                long at = System.currentTimeMillis() + 5 * 60000L;
                boolean exacta = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
                if (exacta) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
            return;
        }
        if (appVisible) return;   // con la app en pantalla suena la alarma de la web
        crearCanal(c);
        PendingIntent abrir = PendingIntent.getActivity(c, 3,
                new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(c, CANAL_ALARMA)
                .setSmallIcon(R.drawable.ic_stat_pasos)
                .setContentTitle(intent.getStringExtra("tit"))
                .setContentText(intent.getStringExtra("txt"))
                .setStyle(new Notification.BigTextStyle().bigText(intent.getStringExtra("txt")))
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(abrir)
                .setDeleteIntent(boton(c, PARAR, intent))
                .setAutoCancel(true)
                .setTimeoutAfter(60000)   // suena como mucho un minuto
                .addAction(new Notification.Action.Builder(null, "Parar", boton(c, PARAR, intent)).build())
                .addAction(new Notification.Action.Builder(null, "Posponer 5 min", boton(c, POSPONER, intent)).build());
        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT;   // el sonido se repite hasta que la paras, la abres o pasa el minuto
        try { nm.notify(nid, n); } catch (SecurityException ignored) {}
    }

    /** Crea (si no está) el canal de las alarmas, con el sonido de alarma del teléfono. Después la persona puede cambiar el sonido
     *  en los ajustes del canal (APK 2.40: botón «Elegir el sonido de la alarma» en Ajustes de la app). */
    static void crearCanal(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CANAL_ALARMA) != null) return;
        android.net.Uri sonido = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (sonido == null) sonido = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        if (sonido == null) sonido = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        NotificationChannel ch = new NotificationChannel(CANAL_ALARMA, "Alarmas del calendario", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Suenan como una alarma a la hora de tus recordatorios");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 600});
        ch.setSound(sonido, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    /** El sonido elegido para las alarmas (el del canal; si no hay, el de alarma del teléfono). */
    static android.net.Uri sonidoElegido(Context c) {
        crearCanal(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = nm == null ? null : nm.getNotificationChannel(CANAL_ALARMA);
        android.net.Uri u = ch == null ? null : ch.getSound();
        return u != null ? u : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
    }

    /** Con la app en pantalla, la web pide sonar la alarma con el sonido del teléfono (como mucho un minuto). */
    private static android.media.Ringtone tono;
    private static final android.os.Handler manejador = new android.os.Handler(android.os.Looper.getMainLooper());
    static synchronized void sonar(Context c, int segundos) {
        parar();
        try {
            tono = RingtoneManager.getRingtone(c.getApplicationContext(), sonidoElegido(c));
            if (tono == null) return;
            tono.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            if (Build.VERSION.SDK_INT >= 28) tono.setLooping(true);
            tono.play();
            manejador.removeCallbacksAndMessages(null);
            manejador.postDelayed(Recordatorios::parar, Math.max(3, segundos) * 1000L);
        } catch (Exception ignored) {}
    }
    static synchronized void parar() {
        try { if (tono != null) tono.stop(); } catch (Exception ignored) {}
        tono = null;
    }

    private static PendingIntent boton(Context c, String accion, Intent orig) {
        String id = orig.getStringExtra("id");
        Intent i = new Intent(c, Recordatorios.class).setAction(accion + id);
        i.putExtra("id", id).putExtra("tit", orig.getStringExtra("tit")).putExtra("txt", orig.getStringExtra("txt"));
        return PendingIntent.getBroadcast(c, (accion + id).hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
