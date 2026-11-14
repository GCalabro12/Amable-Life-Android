package club.practicaintegral.equilibra;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Ventana de alarma a pantalla completa (APK 2.41, Gustavo): «Amable Life · Recordatorio», la tarea y cuándo es, y
 * Enterado (para el sonido), Posponer (5, 15 o 30 min, 1, 2 o 3 h) y Completado, que solo sale en la última alarma de la tarea.
 * Sale encima de la pantalla de bloqueo. El sonido es el de la notificación (insistente) y se para con cualquier botón.
 */
public class AlarmaActivity extends Activity {
    private static final int[] MINUTOS = {5, 15, 30, 60, 120, 180};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        pintar(getIntent());
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
    }

    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); pintar(i); }

    @Override
    public void onBackPressed() { Recordatorios.quitar(this, getIntent().getStringExtra("id")); finish(); }

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private TextView texto(String t, float sp, int color, boolean negrita) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextSize(sp); v.setTextColor(color); v.setGravity(Gravity.CENTER);
        if (negrita) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private Button boton(String t, int fondo, int borde) {
        Button b = new Button(this);
        b.setText(t); b.setAllCaps(false); b.setTextSize(19); b.setTextColor(Color.WHITE); b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable(); g.setColor(fondo); g.setCornerRadius(dp(16));
        if (borde != 0) g.setStroke(dp(1.5f), borde);
        b.setBackground(g); b.setPadding(dp(12), dp(16), dp(12), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10); b.setLayoutParams(lp);
        return b;
    }

    /** 2.49 (Gustavo, igual que la web): «Falta 1 hora para», «Faltan 5 minutos para», «Es la hora de»… */
    private static String largoMin(long m) {
        if (m < 60) return m + (m == 1 ? " minuto" : " minutos");
        if (m < 1440) { long h = m / 60, r = m % 60; return h + (h == 1 ? " hora" : " horas") + (r > 0 ? " y " + r + " min" : ""); }
        long d = Math.round(m / 1440.0); return d + (d == 1 ? " día" : " días");
    }
    static String faltaTxt(long ini, boolean todo) {
        if (ini <= 0) return "";
        if (todo) {
            java.util.Calendar hoy = java.util.Calendar.getInstance();
            hoy.set(java.util.Calendar.HOUR_OF_DAY, 0); hoy.set(java.util.Calendar.MINUTE, 0); hoy.set(java.util.Calendar.SECOND, 0); hoy.set(java.util.Calendar.MILLISECOND, 0);
            long n = Math.round((ini - hoy.getTimeInMillis()) / 86400000.0);
            return n <= 0 ? "Hoy toca" : n == 7 ? "Falta 1 semana para" : (n == 1 ? "Falta " : "Faltan ") + largoMin(n * 1440) + " para";
        }
        long m = (long) Math.ceil((ini - System.currentTimeMillis()) / 60000.0);
        if (m <= 0 && m >= -1) return "Es la hora de";
        if (m < -1) return "Empezó hace " + largoMin(-m);
        String t = largoMin(m);
        return (t.startsWith("1 ") ? "Falta " : "Faltan ") + t + " para";
    }

    /** el color de la tarea mezclado con el morado oscuro, como el degradado de la web */
    private static int mezcla(int a, int b, float p) {
        return Color.rgb((int) (Color.red(a) * p + Color.red(b) * (1 - p)), (int) (Color.green(a) * p + Color.green(b) * (1 - p)), (int) (Color.blue(a) * p + Color.blue(b) * (1 - p)));
    }

    private void pintar(Intent in) {
        String tit = in.getStringExtra("tit"), txt = in.getStringExtra("txt"), id = in.getStringExtra("id");
        boolean ultima = in.getBooleanExtra("ul", true);
        long ini = 0; boolean todo = false; String cuando = "", colTarea = "", entTxt = "", img = "", okt = "Tarea completada";
        try {
            org.json.JSONObject x = new org.json.JSONObject(in.getStringExtra("extra") == null ? "{}" : in.getStringExtra("extra"));
            ini = x.optLong("ini", 0); todo = x.optBoolean("todo", false); cuando = x.optString("cuando", ""); colTarea = x.optString("col", ""); entTxt = x.optString("ent", "");
            img = x.optString("img", ""); okt = x.optString("okt", "Tarea completada");   // 2.60: prácticas (imagen, «Práctica terminada», fondo verde) y tareas (fondo rojo)
        } catch (Exception ignored) {}
        String falta = faltaTxt(ini, todo);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(48), dp(24), dp(32));

        // 2.49 (Gustavo): arriba el logotipo y «Amable Life», «RECORDATORIO», la campana sonando, en amarillo lo que falta,
        // la tarea y «Tarea para realizar a las 17:30»
        LinearLayout marca = new LinearLayout(this);
        marca.setOrientation(LinearLayout.HORIZONTAL); marca.setGravity(Gravity.CENTER);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher_foreground);
        logo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER); logo.setScaleX(1.45f); logo.setScaleY(1.45f);   // el girasol ocupa el centro del icono adaptable
        LinearLayout.LayoutParams llogo = new LinearLayout.LayoutParams(dp(40), dp(40)); llogo.rightMargin = dp(8);
        marca.addView(logo, llogo);
        marca.addView(texto("Amable Life", 22, Color.WHITE, true));
        col.addView(marca);
        TextView rec = texto("RECORDATORIO", 15, Color.parseColor("#f7c35b"), true);
        rec.setLetterSpacing(0.15f); rec.setPadding(0, dp(4), 0, dp(20)); col.addView(rec);

        android.widget.ImageView campana = new android.widget.ImageView(this);
        campana.setImageResource(R.drawable.ic_campana);
        boolean practica = !img.isEmpty();
        col.addView(campana, new LinearLayout.LayoutParams(dp(practica ? 40 : 68), dp(practica ? 40 : 68)));
        if (practica) {   // 2.60 (Gustavo): la imagen de la práctica, redonda y con borde blanco, debajo de la campana
            android.widget.ImageView foto = new android.widget.ImageView(this);
            foto.setImageResource("meta".equals(img) ? R.drawable.meta_planta : R.mipmap.ic_launcher_foreground);
            foto.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            GradientDrawable redondo = new GradientDrawable(); redondo.setShape(GradientDrawable.OVAL); redondo.setColor(Color.WHITE); redondo.setStroke(dp(4), Color.WHITE);
            foto.setBackground(redondo); foto.setClipToOutline(true); foto.setPadding(dp(4), dp(4), dp(4), dp(4));
            LinearLayout.LayoutParams lf = new LinearLayout.LayoutParams(dp(132), dp(132)); lf.topMargin = dp(6);
            col.addView(foto, lf);
        }
        campana.setPivotX(dp(practica ? 20 : 34)); campana.setPivotY(dp(4));
        android.animation.ObjectAnimator mueve = android.animation.ObjectAnimator.ofFloat(campana, "rotation", -14f, 14f);
        mueve.setDuration(500); mueve.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        mueve.setRepeatMode(android.animation.ValueAnimator.REVERSE); mueve.start();

        if (!falta.isEmpty()) {
            TextView f = texto(falta, 21, Color.parseColor("#f7c35b"), true); f.setPadding(0, dp(14), 0, dp(4)); col.addView(f);
        }
        TextView t = texto(tit == null ? "" : tit, 30, Color.WHITE, true); t.setPadding(0, falta.isEmpty() ? dp(14) : 0, 0, dp(10)); col.addView(t);
        TextView c = texto(cuando.isEmpty() ? (txt == null ? "" : txt) : cuando, 18, Color.parseColor("#e9e4ff"), false); c.setPadding(0, 0, 0, dp(32)); col.addView(c);

        // 2.48 (Gustavo): Enterado arriba, Posponer en medio y «Tarea completada» abajo
        // 2.53 (Gustavo): Enterado en verde, que es lo que más se pulsa; «Tarea completada» discreto y solo en las de 5 min antes o menos (lo decide la web en «ul»)
        // 2.54 (Gustavo): con 5 min o menos (sale «Tarea completada») el verde va en «Tarea completada»; si no, en «Enterado»
        Button ent = ultima ? boton("Enterado", Color.argb(26, 255, 255, 255), Color.argb(90, 255, 255, 255)) : boton("Enterado", Color.parseColor(practica ? "#ffffff" : "#16a34a"), 0);
        if (practica && !ultima) ent.setTextColor(Color.parseColor("#15803d"));   // 2.60: sobre el verde, el botón verde pasa a blanco
        ent.setOnClickListener(v -> { Recordatorios.quitar(this, id); finish(); });
        col.addView(ent);
        // 2.50 (Gustavo): en pequeño, qué quita Enterado («Solo quita este aviso de 1 hora antes»)
        if (!entTxt.isEmpty()) { TextView et = texto(entTxt, 13, Color.parseColor("#cfc6ec"), false); et.setPadding(0, dp(2), 0, dp(6)); col.addView(et); }

        Button pos = boton("Posponer", Color.parseColor("#8b5cf6"), 0);
        GridLayout rej = new GridLayout(this);
        rej.setColumnCount(3); rej.setVisibility(View.GONE);
        for (int m : MINUTOS) {
            Button o = new Button(this);
            o.setText(m < 60 ? m + " min" : (m / 60) + " h"); o.setAllCaps(false); o.setTextSize(17); o.setTextColor(Color.WHITE);
            GradientDrawable g = new GradientDrawable(); g.setColor(Color.parseColor("#3a2a6e")); g.setCornerRadius(dp(12));
            g.setStroke(dp(1.5f), Color.parseColor("#8b5cf6")); o.setBackground(g);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0; lp.setMargins(dp(4), dp(8), dp(4), 0); o.setLayoutParams(lp);
            o.setOnClickListener(v -> {
                Recordatorios.posponer(this, getIntent(), m);
                Toast.makeText(this, "Te lo vuelvo a recordar en " + (m < 60 ? m + " minutos" : m == 60 ? "1 hora" : (m / 60) + " horas"), Toast.LENGTH_SHORT).show();
                finish();
            });
            rej.addView(o);
        }
        pos.setOnClickListener(v -> rej.setVisibility(rej.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        col.addView(pos);
        col.addView(rej, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (ultima) {
            Button ok = boton(okt, Color.parseColor(practica ? "#ffffff" : "#16a34a"), 0);
            if (practica) ok.setTextColor(Color.parseColor("#15803d"));
            final String okFin = okt;
            ok.setOnClickListener(v -> { Recordatorios.completar(this, getIntent()); Toast.makeText(this, okFin, Toast.LENGTH_SHORT).show(); finish(); });
            col.addView(ok);
        }

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        int arriba = Color.parseColor("#3d2466");
        // 2.60: el color llega verde (práctica) o rojo (tarea) y se ve más fuerte
        try { if (!colTarea.isEmpty()) arriba = mezcla(Color.parseColor(colTarea), Color.parseColor("#140f26"), 0.78f); } catch (Exception ignored) {}
        GradientDrawable fondo = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{mezcla(arriba, Color.parseColor("#140f26"), 0.8f), arriba, Color.parseColor("#140f26"), Color.parseColor("#140f26")});
        sv.setBackground(fondo);
        LinearLayout centro = new LinearLayout(this);
        centro.setGravity(Gravity.CENTER); centro.setOrientation(LinearLayout.VERTICAL);
        centro.addView(col, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        sv.addView(centro, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.MATCH_PARENT));
        setContentView(sv);
    }
}
