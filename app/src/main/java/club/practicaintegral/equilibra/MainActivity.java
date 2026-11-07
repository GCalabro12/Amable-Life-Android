package club.practicaintegral.equilibra;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.os.Environment;
import android.widget.Toast;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.database.Cursor;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.os.CancellationSignal;
import android.speech.RecognizerIntent;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * App Android de Equilibra: muestra la web de siempre (https://gcalabro12.github.io/Equilibra/)
 * con una diferencia importante: la música de inicio suena sola al abrir (sin tener que tocar la pantalla).
 * La web sabe que está dentro de la app porque existe window.AndroidEquilibra.
 */
public class MainActivity extends Activity {
    private static final String WEB = "https://gcalabro12.github.io/Amable-Life/";   // 2.43: el repositorio pasa de Equilibra a Amable-Life
    private static final String HOST = "gcalabro12.github.io";
    private static final int REQ_DICTAR = 42;
    /** ID de cliente web del proyecto Firebase equilibra-6aff8 (proveedor Google). */
    private static final String CLIENTE_WEB = "682128349771-a175emei7guudhv3gt08ra5vb3bcuuus.apps.googleusercontent.com";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView cargando;
    private boolean cargada;
    /** La app abierta, para que la ventana flotante pueda marcar tareas en la web. */
    private static java.lang.ref.WeakReference<MainActivity> actual = new java.lang.ref.WeakReference<>(null);
    /** Se pidió la ventana flotante pero faltaba el permiso: al volver de Ajustes se abre sola. */
    private boolean quiereFlotante;

    /** Ejecuta código en la web de la app (desde la ventana flotante). */
    static void js(String codigo) {
        MainActivity a = actual.get();
        if (a != null && a.web != null) a.runOnUiThread(() -> a.web.evaluateJavascript(codigo, null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Recordatorios.appVisible = true;
        if (web != null) web.evaluateJavascript("window.aplicarAlarmas && aplicarAlarmas()", null);
        if (quiereFlotante && FlotanteService.puede(this)) {
            quiereFlotante = false;
            FlotanteService.abrir(this);
            web.evaluateJavascript("window.enviarFlotante && enviarFlotante(true)", null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Recordatorios.appVisible = false;
    }

    /** Ya se pidió el permiso de notificaciones para los recordatorios (una vez por apertura). */
    private boolean pedidoAvisos;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        actual = new java.lang.ref.WeakReference<>(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF140F26);

        web = new WebView(this);
        web.setBackgroundColor(0xFF140F26);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        cargando = new TextView(this);
        cargando.setText("Amable Life");
        cargando.setTextColor(0xFFF4F0FB);
        cargando.setTextSize(30);
        cargando.setGravity(Gravity.CENTER);
        cargando.setBackgroundColor(0xFF140F26);
        root.addView(cargando, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        // Que la web no quede debajo de las barras del sistema (en algunos móviles la barra de abajo tapaba el menú)
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(ins.getSystemWindowInsetLeft(), ins.getSystemWindowInsetTop(),
                    ins.getSystemWindowInsetRight(), ins.getSystemWindowInsetBottom());
            return ins;
        });
        root.requestApplyInsets();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // Lo importante: el sonido puede empezar sin que la persona toque la pantalla
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.addJavascriptInterface(new Puente(), "AndroidEquilibra");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                // Versión nueva de la app: se descarga aquí mismo (los navegadores se quedaban trabados al 100 %)
                if (u.getPath() != null && u.getPath().endsWith(".apk")) { descargarApk(u); return true; }
                if (HOST.equals(u.getHost()) || "file".equals(u.getScheme())) return false;
                // Inicio de sesión de Firebase: se queda dentro; el resto de enlaces se abren fuera
                if (u.getHost() != null && u.getHost().endsWith("firebaseapp.com")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (RuntimeException ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                cargada = true;
                cargando.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (!req.isForMainFrame()) return;
                int c = err.getErrorCode();
                if (c == ERROR_HOST_LOOKUP || c == ERROR_CONNECT || c == ERROR_TIMEOUT)
                    view.loadUrl("file:///android_asset/sin-conexion.html");
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler h, SslError e) {
                h.cancel();
                view.loadUrl("file:///android_asset/sin-conexion.html");
            }
        });
        web.loadUrl(WEB);
        handler.postDelayed(() -> { if (!cargada) cargando.setVisibility(View.GONE); }, 15000);
        pedirPermisos();
    }

    /** Descarga el APK con el gestor de descargas de Android; al terminar, se toca la notificación para instalar. */
    private void descargarApk(Uri u) {
        try {
            DownloadManager.Request r = new DownloadManager.Request(u);
            r.setTitle("Amable Life");
            r.setDescription("Nueva versión de Amable Life");
            r.setMimeType("application/vnd.android.package-archive");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Amable Life.apk");
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            idDescarga = dm.enqueue(r);
            escucharDescarga(u);
            Toast.makeText(this, "Descargando Amable Life… Al terminar se abrirá el instalador", Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (RuntimeException ignored) {}
        }
    }

    private long idDescarga = -1;
    private BroadcastReceiver finDescarga;

    /** Al acabar la descarga, abre el instalador directamente (en algunos móviles la notificación no aparece). */
    private void escucharDescarga(Uri origen) {
        if (finDescarga != null) return;
        finDescarga = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent it) {
                long id = it.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != idDescarga) return;
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                int estado = -1;
                try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                    if (c != null && c.moveToFirst()) estado = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                } catch (RuntimeException ignored) {}
                if (estado == DownloadManager.STATUS_SUCCESSFUL) {
                    try {
                        Uri apk = dm.getUriForDownloadedFile(id);
                        Intent inst = new Intent(Intent.ACTION_VIEW);
                        inst.setDataAndType(apk, "application/vnd.android.package-archive");
                        inst.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(inst);
                    } catch (RuntimeException e) {
                        Toast.makeText(MainActivity.this, "Descargada en «Descargas»: abre «Amable Life.apk» para instalarla", Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(MainActivity.this, "No se pudo descargar. Lo abro en el navegador", Toast.LENGTH_LONG).show();
                    try { startActivity(new Intent(Intent.ACTION_VIEW, origen)); } catch (RuntimeException ignored) {}
                }
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(finDescarga, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(finDescarga, f);
    }

    /** Pasos: permiso de «Actividad física» (y avisos). Con permiso, el contador arranca y sigue con la pantalla apagada. */
    private void pedirPermisos() {
        // Si está PC & Body-mind, ella cuenta los pasos: aquí no se pide el permiso y se para el contador propio
        if (StepService.pcInstalada(this)) { StepService.start(this); return; }
        List<String> faltan = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
            faltan.add(Manifest.permission.ACTIVITY_RECOGNITION);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            faltan.add(Manifest.permission.POST_NOTIFICATIONS);
        if (faltan.isEmpty()) StepService.start(this);
        else requestPermissions(faltan.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == 2) return;   // permiso de notificaciones para los recordatorios del calendario
        StepService.start(this);
        web.evaluateJavascript("window.nativeRefresh && window.nativeRefresh()", null);
    }

    private boolean permisoPasos() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    private void alServicio(Intent i) {
        if (!permisoPasos()) return;
        i.setClass(this, StepService.class);
        try { startForegroundService(i); } catch (RuntimeException ignored) {}
    }

    @Override
    public void onBackPressed() {
        // La web decide: cerrar una hoja o volver a Inicio. Si ya está en Inicio, la app se va al fondo.
        web.evaluateJavascript("(function(){try{return window.atrasNativo ? atrasNativo() : false}catch(e){return false}})()", v -> {
            if (!"true".equals(v)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_DICTAR) return;
        String texto = null;
        if (res == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) texto = r.get(0);
        }
        enviarDictado(texto, texto == null ? "No se ha entendido nada. Vuelve a intentarlo." : null);
    }

    /** Entrar con Google dentro de la app: el teléfono enseña sus cuentas de Google y devuelve un token a la web. */
    private void entrarConGoogle() {
        try {
            CredentialManager cm = CredentialManager.create(this);
            GetCredentialRequest req = new GetCredentialRequest.Builder()
                    .addCredentialOption(new GetSignInWithGoogleOption.Builder(CLIENTE_WEB).build())
                    .build();
            cm.getCredentialAsync(this, req, new CancellationSignal(), getMainExecutor(),
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override public void onResult(GetCredentialResponse r) {
                            try {
                                if (r.getCredential() instanceof CustomCredential
                                        && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(r.getCredential().getType())) {
                                    String token = GoogleIdTokenCredential.createFrom(r.getCredential().getData()).getIdToken();
                                    enviarGoogle(token, null);
                                } else enviarGoogle(null, "Google no ha devuelto una cuenta válida.");
                            } catch (RuntimeException e) { enviarGoogle(null, "No se pudo leer la cuenta de Google: " + e.getMessage()); }
                        }
                        @Override public void onError(GetCredentialException e) {
                            if (e instanceof GetCredentialCancellationException) enviarGoogle(null, "");
                            else enviarGoogle(null, "No se pudo entrar con Google (" + e.getType() + "). Usa email y PIN.");
                        }
                    });
        } catch (RuntimeException e) {
            enviarGoogle(null, "No se pudo abrir el inicio de sesión de Google: " + e.getMessage());
        }
    }

    private void enviarGoogle(String token, String error) {
        web.evaluateJavascript("window.nativeGoogle && window.nativeGoogle("
                + (token == null ? "null" : JSONObject.quote(token)) + ","
                + (error == null ? "null" : JSONObject.quote(error)) + ")", null);
    }

    private void enviarDictado(String texto, String error) {
        web.evaluateJavascript("window.nativeDictation && window.nativeDictation("
                + (texto == null ? "null" : JSONObject.quote(texto)) + ","
                + (error == null ? "null" : JSONObject.quote(error)) + ")", null);
    }

    /** Funciones que la web puede llamar con window.AndroidEquilibra.xxx() */
    class Puente {
        /** Dictado con el reconocimiento de voz de Google (la web no puede usar el micrófono dentro de la app). */
        @JavascriptInterface public void dictar() {
            runOnUiThread(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES");
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Dime tu acción");
                try {
                    startActivityForResult(i, REQ_DICTAR);
                } catch (ActivityNotFoundException e) {
                    enviarDictado(null, "Este teléfono no tiene el dictado por voz de Google. Usa el micrófono del teclado.");
                }
            });
        }

        /** Entrar con Google (cuentas del teléfono). Responde con window.nativeGoogle(idToken, error). */
        @JavascriptInterface public void googleLogin() { runOnUiThread(MainActivity.this::entrarConGoogle); }

        @JavascriptInterface public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return "?"; }
        }

        // ---------- Pasos (podómetro del teléfono, también con la pantalla apagada) ----------
        @JavascriptInterface public String getToday() { return StepStore.todayJson(MainActivity.this); }

        @JavascriptInterface public String getHistory() { return StepStore.historyJson(MainActivity.this); }

        @JavascriptInterface public void setConfig(String json) {
            StepStore.setConfig(MainActivity.this, json);
            alServicio(new Intent(StepService.ACTION_REFRESH));
        }

        /** Habla en voz alta con la voz del teléfono (también con la pantalla apagada). */
        @JavascriptInterface public void speak(String text) {
            alServicio(new Intent(StepService.ACTION_SPEAK).putExtra("text", text));
        }

        @JavascriptInterface public boolean permissionOk() { return permisoPasos(); }

        @JavascriptInterface public boolean hasSensor() { return StepService.hasSensor; }

        @JavascriptInterface public boolean batteryOk() {
            PowerManager pm = getSystemService(PowerManager.class);
            return pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        @JavascriptInterface public void openBatterySettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException e) {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            });
        }

        @JavascriptInterface public void openAppSettings() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))));
        }

        @JavascriptInterface public void askPermissions() { runOnUiThread(MainActivity.this::pedirPermisos); }

        /** ¿Está instalada PC & Body-mind? Entonces es ella la que cuenta los pasos. */
        @JavascriptInterface public boolean pcInstalada() { return StepService.pcInstalada(MainActivity.this); }

        // ---------- Ventana flotante de Focus (encima de las demás apps) ----------
        /** Abre la ventana flotante. Si falta el permiso, abre Ajustes para darlo y devuelve false. */
        @JavascriptInterface public boolean abrirFlotante() {
            if (!FlotanteService.puede(MainActivity.this)) {
                quiereFlotante = true;
                runOnUiThread(() -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                    } catch (RuntimeException e) {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                    }
                });
                return false;
            }
            runOnUiThread(() -> FlotanteService.abrir(MainActivity.this));
            return true;
        }

        /** Lo que enseña la ventana flotante: {tit, fin, tareas:[{t, i, ok}]} */
        @JavascriptInterface public void flotante(String json) { FlotanteService.nuevoEstado(json); }

        /** Recordatorios del calendario con la app cerrada (APK 2.38): [{id, at, tit, txt}] de los próximos días. */
        @JavascriptInterface public void programarRecordatorios(String json) {
            Recordatorios.programar(MainActivity.this, json);
            boolean hay = json != null && json.length() > 2;
            if (hay && !pedidoAvisos && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pedidoAvisos = true;
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2));
            }
        }

        /** APK 2.40: abre los ajustes del canal «Alarmas del calendario» para elegir el sonido del teléfono. */
        @JavascriptInterface public void elegirSonidoAlarma() {
            runOnUiThread(() -> {
                Recordatorios.crearCanal(MainActivity.this);
                try {
                    startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                            .putExtra(Settings.EXTRA_CHANNEL_ID, Recordatorios.CANAL_ALARMA));
                } catch (ActivityNotFoundException e) {
                    try { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); }
                    catch (ActivityNotFoundException ignored) {}
                }
            });
        }

        /** Con la app en pantalla: suena la alarma con el sonido elegido en el teléfono (segundos) y se para. */
        @JavascriptInterface public void sonarAlarma(int segundos) { Recordatorios.sonar(MainActivity.this, segundos); }
        @JavascriptInterface public void pararAlarma() { Recordatorios.parar(); }

        /** APK 2.41: posponer desde la ventana de la web; lo programa el teléfono (suena aunque cierres la app). */
        @JavascriptInterface public void posponerAlarma(String json) {
            try { Recordatorios.programarUno(MainActivity.this, new org.json.JSONObject(json)); } catch (Exception ignored) {}
        }
        /** APK 2.41: lo marcado como «Completado» en la ventana del teléfono, para que la web lo apunte (y se vacía). */
        @JavascriptInterface public String pendientesAlarma() { return Recordatorios.pendientes(MainActivity.this); }

        @JavascriptInterface public void cerrarFlotante() { runOnUiThread(() -> FlotanteService.cerrar(MainActivity.this)); }

        @JavascriptInterface public void abrirPC() {
            runOnUiThread(() -> {
                Intent i = getPackageManager().getLaunchIntentForPackage("club.practicaintegral.pasos");
                if (i != null) startActivity(i);
            });
        }
    }
}
