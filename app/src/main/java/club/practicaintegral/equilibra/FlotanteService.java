package club.practicaintegral.equilibra;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * Ventana flotante de Focus: el bloque de ahora, su cuenta atrás y sus tareas, encima de todas las apps.
 * La web manda lo que hay que enseñar (AndroidEquilibra.flotante(json)); al tocar una tarea se marca en la web.
 * Necesita el permiso «Mostrar sobre otras aplicaciones».
 */
public class FlotanteService extends Service {
    private static final String CANAL = "flotante";
    private static final int NOTIF_ID = 2;
    private static final int ORO = 0xFFF7C35B, BLANCO = 0xFFF4F0FB, SUAVE = 0xFFB8AED6;
    /** Lo último que mandó la web (JSON con tit, fin y tareas). */
    static String estado = null;
    static FlotanteService activo = null;

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout caja, lista;
    private TextView titulo, cuenta, cuentaTxt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int fin = -1;
    private boolean pedidoCambio;
    private final Runnable reloj = this::tic;

    static boolean puede(Context c) { return Settings.canDrawOverlays(c); }

    static void abrir(Context c) {
        try { c.startForegroundService(new Intent(c, FlotanteService.class)); } catch (RuntimeException ignored) {}
    }

    static void cerrar(Context c) { c.stopService(new Intent(c, FlotanteService.class)); }

    static void nuevoEstado(String json) {
        estado = json;
        FlotanteService s = activo;
        if (s != null) s.handler.post(s::pintar);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CANAL, "Ventana flotante", NotificationManager.IMPORTANCE_MIN));
        PendingIntent abrirApp = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_stat_pasos)
                .setContentTitle("Focus en ventana flotante")
                .setContentText("Toca para abrir Amable Life")
                .setContentIntent(abrirApp)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, n);
        activo = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!puede(this)) { stopSelf(); return START_NOT_STICKY; }
        if (caja == null) crear();
        pintar();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(reloj);
        if (caja != null) try { wm.removeView(caja); } catch (RuntimeException ignored) {}
        caja = null;
        activo = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView texto(String t, float sp, int color, boolean negrita) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (negrita) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private GradientDrawable fondo(int color, float radio, int borde, int colorBorde) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radio));
        if (borde > 0) g.setStroke(dp(borde), colorBorde);
        return g;
    }

    private void crear() {
        wm = getSystemService(WindowManager.class);
        caja = new LinearLayout(this);
        caja.setOrientation(LinearLayout.VERTICAL);
        caja.setBackground(fondo(0xF21D1636, 18, 2, 0xFFE0323A));
        caja.setPadding(dp(12), dp(8), dp(12), dp(12));

        // cabecera: título (se arrastra para mover la ventana) y cruz para cerrarla
        LinearLayout cab = new LinearLayout(this);
        cab.setGravity(Gravity.CENTER_VERTICAL);
        titulo = texto("FOCUS", 12, ORO, true);
        titulo.setSingleLine(true);
        titulo.setEllipsize(TextUtils.TruncateAt.END);
        cab.addView(titulo, new LinearLayout.LayoutParams(0, -2, 1));
        TextView x = texto("✕", 18, SUAVE, false);
        x.setPadding(dp(10), dp(4), dp(2), dp(4));
        x.setOnClickListener(v -> stopSelf());
        cab.addView(x);
        caja.addView(cab);

        LinearLayout zona = new LinearLayout(this);
        zona.setOrientation(LinearLayout.VERTICAL);
        cuenta = texto("", 30, ORO, true);
        cuentaTxt = texto("min para acabar el bloque", 11, ORO, true);
        zona.addView(cuenta);
        zona.addView(cuentaTxt);
        caja.addView(zona);

        lista = new LinearLayout(this);
        lista.setOrientation(LinearLayout.VERTICAL);
        caja.addView(lista);

        View.OnTouchListener mover = new View.OnTouchListener() {
            float x0, y0; int px, py; boolean movido;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x0 = e.getRawX(); y0 = e.getRawY(); px = lp.x; py = lp.y; movido = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(e.getRawX() - x0), dy = Math.round(e.getRawY() - y0);
                        if (Math.abs(dx) + Math.abs(dy) > dp(6)) movido = true;
                        if (movido) { lp.x = px + dx; lp.y = py + dy; try { wm.updateViewLayout(caja, lp); } catch (RuntimeException ignored) {} }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!movido) abrirApp();
                        return true;
                }
                return false;
            }
        };
        titulo.setOnTouchListener(mover);
        zona.setOnTouchListener(mover);

        lp = new WindowManager.LayoutParams(dp(250), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = dp(110);
        wm.addView(caja, lp);
    }

    private void abrirApp() {
        try {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException ignored) {}
    }

    private void pintar() {
        if (caja == null) return;
        lista.removeAllViews();
        try {
            JSONObject o = new JSONObject(estado == null ? "{}" : estado);
            titulo.setText(o.optString("tit", "FOCUS"));
            fin = o.optInt("fin", -1);
            pedidoCambio = false;
            JSONArray t = o.optJSONArray("tareas");
            int n = t == null ? 0 : t.length();
            if (n == 0) {
                TextView v = texto(fin < 0 ? "Los bloques van de 5:30 a 22:00" : "Sin tareas en este bloque", 13, SUAVE, false);
                v.setPadding(0, dp(8), 0, 0);
                lista.addView(v);
            }
            boolean primera = true;
            for (int k = 0; k < n; k++) {
                JSONObject a = t.getJSONObject(k);
                boolean ok = a.optBoolean("ok");
                lista.addView(fila(a.optString("t"), a.optInt("i"), ok, !ok && primera));
                if (!ok) primera = false;
            }
        } catch (JSONException ignored) {}
        tic();
    }

    /** Una tarea: casilla + nombre. Tocarla la marca (o la desmarca) en la web, igual que en Focus. */
    private View fila(String nombre, int i, boolean ok, boolean actual) {
        LinearLayout f = new LinearLayout(this);
        f.setGravity(Gravity.CENTER_VERTICAL);
        f.setPadding(dp(8), dp(8), dp(10), dp(8));
        f.setBackground(fondo(actual ? 0xFF1F9D57 : 0x1AFFFFFF, 12, 0, 0));
        TextView casilla = texto(ok ? "✓" : "", 15, BLANCO, true);
        casilla.setGravity(Gravity.CENTER);
        casilla.setBackground(fondo(ok ? 0xFF3A2F63 : 0x00000000, 7, 2, actual ? BLANCO : 0xFF8FB3FF));
        f.addView(casilla, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView tx = texto(nombre, actual ? 16 : 14, ok ? 0x99F4F0FB : BLANCO, true);
        tx.setMaxLines(2);
        tx.setEllipsize(TextUtils.TruncateAt.END);
        tx.setPadding(dp(10), 0, 0, 0);
        if (ok) tx.setPaintFlags(tx.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        f.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        f.setOnClickListener(v -> MainActivity.js("alternarActAhora(" + i + ")"));
        LinearLayout.LayoutParams m = new LinearLayout.LayoutParams(-1, -2);
        m.topMargin = dp(6);
        f.setLayoutParams(m);
        return f;
    }

    /** Cuenta atrás de lo que queda del bloque; al llegar a cero, la web pasa al bloque siguiente. */
    private void tic() {
        handler.removeCallbacks(reloj);
        if (caja == null) return;
        if (fin < 0) {
            cuenta.setVisibility(View.GONE);
            cuentaTxt.setVisibility(View.GONE);
        } else {
            Calendar c = Calendar.getInstance();
            int q = fin * 60 - (c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND));
            if (q <= 0 && !pedidoCambio) { pedidoCambio = true; MainActivity.js("bloquesTick()"); }
            q = Math.max(0, q);
            cuenta.setText((q / 60) + ":" + String.format("%02d", q % 60));
            cuenta.setVisibility(View.VISIBLE);
            cuentaTxt.setVisibility(View.VISIBLE);
        }
        handler.postDelayed(reloj, 1000);
    }
}
