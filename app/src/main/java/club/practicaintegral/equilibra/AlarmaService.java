package club.practicaintegral.equilibra;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * APK 2.44 (Gustavo: «que suban de forma gradual en 5 segundos»): con la app cerrada, la alarma la hace este servicio:
 * pone el aviso (canal sin sonido, con la ventana grande) y toca el sonido de alarma elegido en el teléfono,
 * subiendo poco a poco si está marcado «Gradual». Suena lo elegido en «Duración» (0 = hasta que la apagues, como mucho 1 h).
 * Enterado, Posponer o Completado lo paran (Recordatorios.quitar).
 */
public class AlarmaService extends Service {
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        Recordatorios.crearCanalAviso(this);
        Notification n = Recordatorios.construir(this, intent, Recordatorios.CANAL_AVISO);
        int nid = Recordatorios.nid(intent.getStringExtra("id"));
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(nid, n);
        } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        int dur = intent.getIntExtra("dur", 60);
        int seg = dur > 0 ? dur : 3600;
        Recordatorios.sonar(this, seg, intent.getBooleanExtra("gr", true));
        h.removeCallbacksAndMessages(null);
        h.postDelayed(this::stopSelf, seg * 1000L + 500);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        Recordatorios.parar();
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
