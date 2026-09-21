package today.scrappy.vendedores;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

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
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            UbicacionService.arrancar(contexto);
        }
    }
}
