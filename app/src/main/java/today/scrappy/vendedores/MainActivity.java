package today.scrappy.vendedores;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;


/**
 * El caparazón: muestra las pantallas del módulo, que siguen viviendo en
 * Django.
 *
 * <p>No hay pantallas nativas a propósito. Un cambio en el embudo o en la
 * ficha del lead llega al vendedor recargando, sin publicar un APK nuevo;
 * lo único que la app aporta es lo que el navegador no puede hacer, que es
 * la ubicación en segundo plano (y más adelante subir las grabaciones).
 */
public class MainActivity extends Activity {

    private static final String EMBUDO = BuildConfig.BASE_URL + "/vendedores/";

    private static final int PIDO_UBICACION = 1;
    private static final int PIDO_SEGUNDO_PLANO = 2;

    private WebView web;
    /** El cartel rojo de arriba (ver `revisarSalud`). */
    private TextView cartelSalud;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        // El cartel rojo arriba de la página cuando algo impide que la
        // ubicación llegue (ver `revisarSalud`). Es nativo porque lo que revisa
        // sólo lo sabe el teléfono; lleva a la sección de Salud.
        cartelSalud = new TextView(this);
        cartelSalud.setBackgroundColor(0xFFD63C3C);
        cartelSalud.setTextColor(0xFFFFFFFF);
        cartelSalud.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        cartelSalud.setPadding(pad, pad, pad, pad);
        cartelSalud.setVisibility(View.GONE);
        cartelSalud.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SaludActivity.class));
            }
        });
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.addView(cartelSalud);
        raiz.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(raiz);

        WebSettings ajustes = web.getSettings();
        ajustes.setJavaScriptEnabled(true);
        ajustes.setDomStorageEnabled(true);
        // El módulo es celular-primero y ya viene con su viewport: que el
        // WebView no lo reescale como si fuera una página de escritorio.
        ajustes.setUseWideViewPort(false);
        ajustes.setLoadWithOverviewMode(false);
        ajustes.setSupportZoom(false);
        // La marca en el User-Agent: con ella la página muestra el link a la
        // sección de Salud, que sólo existe adentro de la app.
        ajustes.setUserAgentString(
                ajustes.getUserAgentString() + " VendedoresApp/" + BuildConfig.VERSION_NAME);

        // La sesión de Django vive en una cookie, y tiene que sobrevivir a
        // cerrar la app: si no, el vendedor loguea cada vez que la abre.
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri destino = req.getUrl();
                String esquema = destino.getScheme();
                // `tel:`, `whatsapp:` y `mailto:` no son páginas: si los
                // abriera el WebView, el botón de Llamar de la ficha no
                // haría nada. Van al sistema.
                // `scrappy://salud`: el link de los menús web a la sección
                // nativa de Salud. Va antes que los otros esquemas o se iría
                // afuera como si fuera un `tel:`.
                if ("scrappy".equals(esquema) && "salud".equals(destino.getHost())) {
                    startActivity(new Intent(MainActivity.this, SaludActivity.class));
                    return true;
                }
                if (esquema != null && !esquema.equals("http") && !esquema.equals("https")) {
                    abrirAfuera(destino);
                    return true;
                }
                // Cualquier host que no sea el nuestro tampoco: que un link
                // externo no se coma la app y deje al vendedor sin vuelta.
                String host = destino.getHost();
                if (host != null && !BuildConfig.BASE_URL.contains(host)) {
                    abrirAfuera(destino);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                // El admin es SÓLO la puerta de login. Si el vendedor queda
                // en su índice se encierra: esa página no linkea a
                // /vendedores/ y la app no tiene barra ni botón de inicio.
                // (Encontrado probando la v0.1.0.)
                if (url != null && url.startsWith(BuildConfig.BASE_URL + "/admin/")
                        && !url.contains("/admin/login")) {
                    v.loadUrl(EMBUDO);
                }
            }
        });

        if (savedInstanceState == null) {
            web.loadUrl(EMBUDO);
        } else {
            web.restoreState(savedInstanceState);
        }

        pedirPermisos();
        // Al abrir, y en segundo plano: si hay una versión nueva publicada
        // el vendedor se entera solo, sin que nadie tenga que avisarle.
        Actualizaciones.chequear(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // El permiso pudo concederse recién desde los ajustes (el cartel de
        // Salud manda ahí): arrancar dos veces no hace nada.
        UbicacionService.arrancar(this);
        revisarSalud();
        // Volver a la app después de un rato también mira si hay versión nueva
        // (el propio Actualizaciones decide si ya preguntó hace poco).
        Actualizaciones.chequearSiCorresponde(this);
    }

    /**
     * Muestra el cartel rojo si algo impide que la ubicación llegue, y lo
     * esconde si ya está todo bien. Se revisa en cada vuelta al frente: la
     * persona va a los ajustes, lo arregla, y al volver el cartel no está.
     */
    private void revisarSalud() {
        List<Salud.Item> mal;
        try {
            mal = Salud.criticosFallando(this);
        } catch (Exception e) {
            // El cartel es una ayuda: si revisar falla, la app sigue igual.
            cartelSalud.setVisibility(View.GONE);
            return;
        }
        if (mal.isEmpty()) {
            cartelSalud.setVisibility(View.GONE);
            return;
        }
        // Genérico a propósito: los títulos de los chequeos están en positivo
        // ("Ahorro de batería apagado") y citarlos acá diría lo contrario de lo
        // que pasa. El detalle está en la sección.
        cartelSalud.setText("⚠  Tu ubicación no llega al centro de mando ("
                + mal.size() + (mal.size() == 1 ? " cosa" : " cosas")
                + " para arreglar). Tocá acá.");
        cartelSalud.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Tocar el ícono con la app ya abierta tiene que llevar al embudo.
        // Con `launchMode="singleTask"` Android reanuda esta actividad sin
        // pasar por `onCreate`, así que si no se hace acá no se hace nunca.
        String actual = web.getUrl();
        if (actual == null || !actual.startsWith(EMBUDO)) {
            web.loadUrl(EMBUDO);
        }
    }

    private void abrirAfuera(Uri destino) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, destino));
        } catch (Exception e) {
            // Sin app que lo atienda (un emulador sin discador, por
            // ejemplo) no se hace nada: no vale tirar la pantalla abajo.
        }
    }

    /**
     * Primero las de siempre; la de segundo plano recién cuando esas están
     * dadas, porque Android la rechaza de entrada si se piden juntas.
     */
    private void pedirPermisos() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION},
                    PIDO_UBICACION);
            return;
        }
        pedirSegundoPlanoYArrancar();
    }

    private void pedirSegundoPlanoYArrancar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, PIDO_SEGUNDO_PLANO);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Android no la concede desde un diálogo: hay que ir a ajustes.
            // El servicio arranca igual y sigue funcionando con la app
            // abierta; el seguimiento completo empieza cuando se conceda.
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    PIDO_SEGUNDO_PLANO);
        }
        UbicacionService.arrancar(this);
        pedirBateriaSinLimite();
    }

    /**
     * Pide quedar fuera de la optimización de batería. Sin esto Samsung mata
     * el proceso en segundo plano y el servicio de ubicación no vuelve a
     * arrancar hasta que se abre la app: el 2026-09-26 un teléfono dejó de
     * mandar puntos a las 04:50 y no volvió en todo el día. Se pide sólo si
     * ya hay permiso de ubicación (sin eso no hay seguimiento que cuidar), y
     * se vuelve a pedir en cada apertura hasta que se conceda.
     */
    private void pedirBateriaSinLimite() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        PowerManager energia = (PowerManager) getSystemService(POWER_SERVICE);
        if (energia == null || energia.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            // Algún fabricante sin esa pantalla: el seguimiento anda igual
            // mientras la app no quede dormida.
        }
    }

    @Override
    public void onRequestPermissionsResult(int codigo, String[] permisos,
                                           int[] resultados) {
        super.onRequestPermissionsResult(codigo, permisos, resultados);
        boolean concedido = resultados.length > 0
                && resultados[0] == PackageManager.PERMISSION_GRANTED;
        if (codigo == PIDO_UBICACION && concedido) {
            pedirSegundoPlanoYArrancar();
        } else if (codigo == PIDO_SEGUNDO_PLANO) {
            UbicacionService.arrancar(this);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle estado) {
        super.onSaveInstanceState(estado);
        web.saveState(estado);
    }

    @Override
    public void onBackPressed() {
        // Atrás navega dentro del sitio; sólo cierra la app cuando ya no
        // hay a dónde volver.
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
