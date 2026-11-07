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
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Recordatorios del calendario de Amable Life con la app CERRADA (APK 2.38).
 * La web manda la lista de los próximos días: [{id, at (milisegundos), tit, txt, ev, k, ul}], y aquí cada uno se deja programado
 * en el reloj de Android (AlarmManager). A su hora suena como una alarma (2.39) y sale la ventana a pantalla completa (2.41,
 * AlarmaActivity) con Enterado, Posponer y, en la última alarma de la tarea (ul), Completado.
 * Si la app está abierta en pantalla no se notifica: la web ya lo avisa (con su propia ventana).
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
        long ahora = System.currentTimeMillis();
        try {
            JSONArray a = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.getJSONObject(i);
                if (r.optLong("at", 0) > ahora) programarUno(c, r);
            }
        } catch (Exception ignored) {}
    }

    /** Programa un recordatorio suelto (también los pospuestos, que no van en la lista guardada). */
    static void programarUno(Context c, JSONObject r) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = intento(c, r.optString("id"), r.optString("tit", "Amable Life"), r.optString("txt", ""),
                r.optString("ev", ""), r.optString("k", ""), r.optBoolean("ul", true), r.optInt("dur", 60), r.optBoolean("gr", true));
        long at = r.optLong("at", 0);
        boolean exacta = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (exacta) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    private static void cancelar(Context c, String json) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) am.cancel(intento(c, a.getJSONObject(i).optString("id"), "", "", "", "", true, 60, true));
        } catch (Exception ignored) {}
    }

    /** dur (APK 2.42): segundos que suena la alarma (15, 30, 60); 0 = hasta que la apagues. */
    private static PendingIntent intento(Context c, String id, String tit, String txt, String ev, String k, boolean ul, int dur, boolean gr) {
        Intent i = new Intent(c, Recordatorios.class).setAction("club.practicaintegral.equilibra.RECORDATORIO." + id);
        i.putExtra("id", id).putExtra("tit", tit).putExtra("txt", txt).putExtra("ev", ev).putExtra("k", k).putExtra("ul", ul).putExtra("dur", dur).putExtra("gr", gr);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** APK 2.39 (Gustavo: «tiene que sonar una alarma»): canal nuevo con el sonido de ALARMA del teléfono
     *  (los canales no se pueden cambiar una vez creados; el viejo «recordatorios» ya no se usa). */
    static final String CANAL_ALARMA = "alarmas";
    private static final String PARAR = "club.practicaintegral.equilibra.PARAR.";
    private static final String POSPONER = "club.practicaintegral.equilibra.POSPONER.";
    private static final String COMPLETAR = "club.practicaintegral.equilibra.COMPLETAR.";

    @Override
    public void onReceive(Context c, Intent intent) {
        String act = intent.getAction() == null ? "" : intent.getAction();
        String id = intent.getStringExtra("id");
        if (act.startsWith(PARAR)) { quitar(c, id); return; }
        if (act.startsWith(POSPONER)) { posponer(c, intent, intent.getIntExtra("min", 5)); return; }
        if (act.startsWith(COMPLETAR)) { completar(c, intent); return; }
        if (appVisible) {   // con la app en pantalla avisa la web; los pospuestos los pinta ella también
            if (id != null && id.endsWith("|pos")) {
                try {
                    JSONObject o = new JSONObject().put("ev", intent.getStringExtra("ev")).put("k", intent.getStringExtra("k"))
                            .put("txt", intent.getStringExtra("txt")).put("ul", intent.getBooleanExtra("ul", true));
                    MainActivity.js("window.alarmaDesdeApp && alarmaDesdeApp(" + o + ")");
                } catch (Exception ignored) {}
            }
            return;
        }
        Intent ventana = new Intent(c, AlarmaActivity.class).putExtras(intent)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        boolean servicio = false;
        try {   // 2.44: el servicio pone el aviso y toca el sonido (gradual si lo elegiste)
            Intent sv = new Intent(c, AlarmaService.class).putExtras(intent);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(sv); else c.startService(sv);
            servicio = true;
        } catch (Exception ignored) {}
        if (!servicio) {   // si Android no deja arrancar el servicio: el aviso de antes, con el sonido del canal
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            crearCanal(c);
            Notification n = construir(c, intent, CANAL_ALARMA);
            n.flags |= Notification.FLAG_INSISTENT;
            try { nm.notify(nid(intent.getStringExtra("id")), n); } catch (SecurityException ignored) {}
        }
        // Con el móvil desbloqueado Android solo enseña el aviso de arriba: si la app puede «mostrarse sobre otras apps»
        // (el permiso de la ventana flotante), se abre directamente la ventana grande.
        if (Settings.canDrawOverlays(c)) { try { c.startActivity(ventana); } catch (Exception ignored) {} }
    }

    /** El aviso de la alarma: ventana grande, Enterado, Posponer 5 min y, en la última, Completado. */
    static Notification construir(Context c, Intent intent, String canal) {
        String id = intent.getStringExtra("id");
        Intent ventana = new Intent(c, AlarmaActivity.class).putExtras(intent)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent pantalla = PendingIntent.getActivity(c, ("v" + id).hashCode(), ventana,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(c, canal)
                .setSmallIcon(R.drawable.ic_stat_pasos)
                .setContentTitle(intent.getStringExtra("tit"))
                .setContentText(intent.getStringExtra("txt"))
                .setStyle(new Notification.BigTextStyle().bigText(intent.getStringExtra("txt")))
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(pantalla)
                .setFullScreenIntent(pantalla, true)   // 2.41: con la pantalla apagada o bloqueada, la ventana grande
                .setDeleteIntent(boton(c, PARAR, intent, 0))
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, "Enterado", boton(c, PARAR, intent, 0)).build())
                .addAction(new Notification.Action.Builder(null, "Posponer 5 min", boton(c, POSPONER, intent, 5)).build());
        if (intent.getBooleanExtra("ul", true))
            b.addAction(new Notification.Action.Builder(null, "Completado", boton(c, COMPLETAR, intent, 0)).build());
        int dur = intent.getIntExtra("dur", 60);
        if (dur > 0) b.setTimeoutAfter(dur * 1000L);   // lo que elegiste en «Duración»; 0 = hasta que la apagues
        return b.build();
    }

    /** 2.44: canal del aviso SIN sonido (el sonido lo toca AlarmaService, subiendo poco a poco). */
    static final String CANAL_AVISO = "alarmas_aviso";
    static void crearCanalAviso(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CANAL_AVISO) != null) return;
        NotificationChannel ch = new NotificationChannel(CANAL_AVISO, "Ventana de las alarmas", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("El aviso y la ventana de tus recordatorios (el sonido se elige en «Alarmas del calendario»)");
        ch.setSound(null, null);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 600});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    static int nid(String id) { return id == null ? 7 : id.hashCode(); }

    /** Enterado: se para el sonido y se quita el aviso. */
    static void quitar(Context c, String id) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(nid(id));
        parar();
        try { c.stopService(new Intent(c, AlarmaService.class)); } catch (Exception ignored) {}
    }

    /** Posponer N minutos: se quita y se vuelve a programar (sigue siendo la última si lo era). */
    static void posponer(Context c, Intent orig, int min) {
        String id = orig.getStringExtra("id");
        quitar(c, id);
        String base = id == null ? "x" : id.replaceAll("\\|pos$", "");
        try {
            String txt = orig.getStringExtra("txt");
            if (txt != null && !txt.startsWith("Pospuesto")) txt = "Pospuesto · " + txt;
            programarUno(c, new JSONObject().put("id", base + "|pos").put("at", System.currentTimeMillis() + min * 60000L)
                    .put("tit", orig.getStringExtra("tit")).put("txt", txt).put("ev", orig.getStringExtra("ev"))
                    .put("k", orig.getStringExtra("k")).put("ul", orig.getBooleanExtra("ul", true)).put("dur", orig.getIntExtra("dur", 60)).put("gr", orig.getBooleanExtra("gr", true)));
        } catch (Exception ignored) {}
    }

    /** Completado: se apunta para que la web lo marque como hecho al abrirse (o ahora, si está abierta por detrás). */
    static void completar(Context c, Intent orig) {
        quitar(c, orig.getStringExtra("id"));
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(p.getString("hechos", "[]"));
            a.put(new JSONObject().put("ev", orig.getStringExtra("ev")).put("k", orig.getStringExtra("k")));
            p.edit().putString("hechos", a.toString()).apply();
        } catch (Exception ignored) {}
        MainActivity.js("window.aplicarAlarmas && aplicarAlarmas()");
    }

    /** Lo completado desde la ventana, para la web (y se vacía). */
    static String pendientes(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String s = p.getString("hechos", "[]");
        p.edit().putString("hechos", "[]").apply();
        return s;
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

    /** Con la app en pantalla, la web pide sonar la alarma con el sonido del teléfono (como mucho un minuto).
     *  2.45 (Gustavo: «Probar no funciona en el móvil»): con MediaPlayer en el hilo principal (antes Ringtone desde el
     *  puente de la web), y si el sonido elegido no se puede abrir, el de alarma, el de llamada o el de avisos del teléfono. */
    private static android.media.MediaPlayer mp;
    private static final android.os.Handler manejador = new android.os.Handler(android.os.Looper.getMainLooper());
    static void sonar(Context c, int segundos) { sonar(c, segundos, false); }
    /** gradual (2.44): el volumen sube de casi nada al máximo en 5 segundos. */
    static void sonar(Context c, int segundos, boolean gradual) {
        final Context app = c.getApplicationContext();
        manejador.post(() -> sonarYa(app, segundos, gradual));
    }
    private static synchronized void sonarYa(Context c, int segundos, boolean gradual) {
        parar();
        android.net.Uri[] us = {sonidoElegido(c), RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)};
        for (android.net.Uri u : us) {
            if (u == null) continue;
            android.media.MediaPlayer m = new android.media.MediaPlayer();
            try {
                m.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                m.setDataSource(c, u);
                m.setLooping(true);
                m.prepare();
                float v0 = gradual ? 0.05f : 1f;
                m.setVolume(v0, v0);
                m.start();
                mp = m;
                break;
            } catch (Exception e) { try { m.release(); } catch (Exception ignored) {} }
        }
        if (mp == null) return;
        manejador.removeCallbacksAndMessages(null);
        if (gradual) {
            final android.media.MediaPlayer t = mp;
            for (int i = 1; i <= 20; i++) {
                final float v = 0.05f + 0.95f * i / 20f;
                manejador.postDelayed(() -> { try { if (t == mp) t.setVolume(v, v); } catch (Exception ignored) {} }, i * 250L);
            }
        }
        manejador.postDelayed(Recordatorios::parar, Math.max(3, segundos) * 1000L);
    }
    static synchronized void parar() {
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
    }

    private static PendingIntent boton(Context c, String accion, Intent orig, int min) {
        String id = orig.getStringExtra("id");
        Intent i = new Intent(c, Recordatorios.class).setAction(accion + id);
        i.putExtras(orig).putExtra("min", min);
        return PendingIntent.getBroadcast(c, (accion + id).hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
