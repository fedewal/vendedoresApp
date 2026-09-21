package today.scrappy.vendedores;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.webkit.CookieManager;


import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Manda dónde está el vendedor mientras trabaja.
 *
 * <p>Es un servicio en PRIMER PLANO con notificación permanente. No es una
 * elección de diseño: desde Android 8 un servicio común se muere a los
 * minutos, y desde Android 10 la ubicación en segundo plano sólo llega a un
 * servicio de tipo {@code location}. La notificación además es lo correcto:
 * el vendedor tiene que ver que lo están ubicando.
 *
 * <p>Se autentica con la MISMA cookie de sesión que el WebView: no hay
 * credencial adentro del APK, que es obligatorio si el repo es público.
 * Si la sesión venció, el POST vuelve 302 al login y el punto se descarta
 * hasta que el vendedor vuelva a entrar.
 */
public class UbicacionService extends Service {

    private static final String TAG = "UbicacionService";
    private static final String CANAL = "ubicacion";
    private static final int NOTIFICACION = 1;

    /** Cada cuánto se pide una posición nueva. */
    private static final long CADA_MS = 5 * 60 * 1000L;
    /** Y cuánto se tiene que haber movido para que valga la pena. */
    private static final float MINIMO_METROS = 50f;

    private LocationManager gestor;
    private LocationListener oyente;

    public static void arrancar(Context contexto) {
        Intent i = new Intent(contexto, UbicacionService.class);
        contexto.startForegroundService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        crearCanal();
        startForeground(NOTIFICACION, notificacion());

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Sin permiso no hay nada que hacer; la Activity lo vuelve a
            // pedir y a arrancar el servicio cuando lo tenga.
            stopSelf();
            return;
        }

        gestor = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        oyente = new LocationListener() {
            @Override
            public void onLocationChanged(Location punto) {
                enviar(punto);
            }

            // En API 26 estos tres siguen siendo abstractos en algunos
            // fabricantes: se implementan vacíos para no reventar ahí.
            @Override
            public void onProviderEnabled(String proveedor) { }

            @Override
            public void onProviderDisabled(String proveedor) { }
        };

        pedirA(LocationManager.GPS_PROVIDER);
        // También por red: adentro de un local el GPS no engancha, y una
        // posición aproximada es mejor que ninguna.
        pedirA(LocationManager.NETWORK_PROVIDER);
    }

    private void pedirA(String proveedor) {
        try {
            if (gestor.isProviderEnabled(proveedor)) {
                gestor.requestLocationUpdates(proveedor, CADA_MS, MINIMO_METROS, oyente);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "no se pudo escuchar " + proveedor, e);
        }
    }

    private void enviar(final Location punto) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection con = null;
                try {
                    String base = BuildConfig.BASE_URL;
                    String cookies = CookieManager.getInstance().getCookie(base);
                    if (cookies == null || !cookies.contains("sessionid")) {
                        // Todavía no entró, o la sesión venció. El punto se
                        // pierde a propósito: guardarlo para después sería
                        // acumular ubicaciones de alguien sin sesión.
                        return;
                    }
                    URL url = new URL(base + "/vendedores/ubicacion/");
                    con = (HttpURLConnection) url.openConnection();
                    con.setRequestMethod("POST");
                    con.setConnectTimeout(15000);
                    con.setReadTimeout(15000);
                    con.setDoOutput(true);
                    con.setInstanceFollowRedirects(false);
                    con.setRequestProperty("Content-Type",
                            "application/x-www-form-urlencoded; charset=UTF-8");
                    con.setRequestProperty("Cookie", cookies);
                    String csrf = leerCookie(cookies, "csrftoken");
                    if (csrf != null) {
                        con.setRequestProperty("X-CSRFToken", csrf);
                        // Django compara el Referer en HTTPS.
                        con.setRequestProperty("Referer", base + "/vendedores/");
                    }

                    String cuerpo = "lat=" + punto.getLatitude()
                            + "&lon=" + punto.getLongitude()
                            + "&precision=" + Math.round(punto.getAccuracy())
                            + "&tipo=seguimiento";
                    try (OutputStream salida = con.getOutputStream()) {
                        salida.write(cuerpo.getBytes(StandardCharsets.UTF_8));
                    }
                    int codigo = con.getResponseCode();
                    if (codigo >= 400 || codigo == 302) {
                        Log.w(TAG, "el servidor rechazó la ubicación: " + codigo);
                    }
                } catch (Exception e) {
                    // Sin señal, servidor caído, lo que sea: el próximo
                    // punto llega en cinco minutos. Nada que reintentar.
                    Log.w(TAG, "no se pudo mandar la ubicación", e);
                } finally {
                    if (con != null) {
                        con.disconnect();
                    }
                }
            }
        }).start();
    }

    private static String leerCookie(String cookies, String nombre) {
        for (String parte : cookies.split(";")) {
            String limpia = parte.trim();
            if (limpia.startsWith(nombre + "=")) {
                return limpia.substring(nombre.length() + 1);
            }
        }
        return null;
    }

    private void crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel canal = new NotificationChannel(
                    CANAL, "Ubicación", NotificationManager.IMPORTANCE_LOW);
            canal.setDescription("Avisa que la app está registrando dónde estás.");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(canal);
            }
        }
    }

    private Notification notificacion() {
        return new Notification.Builder(this, CANAL)
                .setContentTitle("Vendedores")
                .setContentText("Registrando tu ubicación mientras trabajás")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Que Android lo vuelva a levantar si lo mata por memoria.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (gestor != null && oyente != null) {
            try {
                gestor.removeUpdates(oyente);
            } catch (SecurityException e) {
                Log.w(TAG, "no se pudo soltar el listener", e);
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
