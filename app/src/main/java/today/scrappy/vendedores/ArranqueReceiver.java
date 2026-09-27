package today.scrappy.vendedores;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Vuelve a levantar el seguimiento cuando se reinicia el telefono.
 *
 * Sin esto, un reinicio de madrugada deja al vendedor sin registrar hasta
 * que alguien abre la app -- y nadie se entera, porque no falla nada
 * visible.
 */
public class ArranqueReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context contexto, Intent intent) {
        // Reinicio del teléfono o actualización de la app: en los dos casos
        // Android dejó el servicio muerto y nadie lo va a abrir para revivirlo.
        String accion = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(accion)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(accion)) {
            // Al arrancar el teléfono la app no está a la vista, y Android sólo deja
            // correr el seguimiento con "todo el tiempo" (Android 10+). Con
            // "mientras se usa" se espera a que se abra la app.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    || contexto.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                UbicacionService.arrancar(contexto);
            }
        }
    }
}
