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

    private void pintar(Intent in) {
        String tit = in.getStringExtra("tit"), txt = in.getStringExtra("txt"), id = in.getStringExtra("id");
        boolean ultima = in.getBooleanExtra("ul", true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(48), dp(24), dp(32));

        col.addView(texto("Amable Life", 22, Color.WHITE, true));
        TextView rec = texto("RECORDATORIO", 15, Color.parseColor("#f7c35b"), true);
        rec.setLetterSpacing(0.15f); rec.setPadding(0, dp(6), 0, dp(36)); col.addView(rec);
        TextView t = texto(tit == null ? "" : tit, 30, Color.WHITE, true); t.setPadding(0, 0, 0, dp(10)); col.addView(t);
        TextView c = texto(txt == null ? "" : txt, 18, Color.parseColor("#e9e4ff"), false); c.setPadding(0, 0, 0, dp(36)); col.addView(c);

        if (ultima) {
            Button ok = boton("Completado", Color.parseColor("#16a34a"), 0);
            ok.setOnClickListener(v -> { Recordatorios.completar(this, getIntent()); Toast.makeText(this, "Tarea completada", Toast.LENGTH_SHORT).show(); finish(); });
            col.addView(ok);
        }

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

        Button ent = boton("Enterado", Color.argb(26, 255, 255, 255), Color.argb(90, 255, 255, 255));
        ent.setOnClickListener(v -> { Recordatorios.quitar(this, id); finish(); });
        col.addView(ent);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        GradientDrawable fondo = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#3d2466"), Color.parseColor("#140f26")});
        sv.setBackground(fondo);
        LinearLayout centro = new LinearLayout(this);
        centro.setGravity(Gravity.CENTER); centro.setOrientation(LinearLayout.VERTICAL);
        centro.addView(col, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        sv.addView(centro, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.MATCH_PARENT));
        setContentView(sv);
    }
}
