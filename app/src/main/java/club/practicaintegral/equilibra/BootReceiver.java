package club.practicaintegral.equilibra;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Vuelve a poner el contador en marcha (y los recordatorios del calendario) al encender el teléfono o al actualizar la app. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            StepService.start(context);
        } catch (RuntimeException ignored) {}
        try {
            Recordatorios.reprogramar(context);   // los recordatorios del calendario se pierden al apagar el teléfono
        } catch (RuntimeException ignored) {}
    }
}
