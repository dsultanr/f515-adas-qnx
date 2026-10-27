package su.dsr.f515.qnx_adas;

import android.os.IBinder;
import android.os.SystemClock;
import java.lang.reflect.Method;

/** Read-only VHAL probe, run by the existing authenticated local root ADB connection.
 * CAR_SPEED does not grant access to F515's vendor SPEED_DISP property.
 */
public final class VhalSpeedBridge {
    public static void main(String[] args) throws Exception {
        Object property = null;
        Method getProperty = null;
        Class<?> valueClass = Class.forName("android.car.hardware.CarPropertyValue");
        Method getValue = valueClass.getMethod("getValue");
        Method getStatus = valueClass.getMethod("getStatus");
        long lastError = -5000;
        while (!System.out.checkError()) {
            try {
                if (property == null) {
                    Object binder = Class.forName("android.os.ServiceManager")
                            .getMethod("getService", String.class).invoke(null, "car_service");
                    Object car = Class.forName("android.car.ICar$Stub")
                            .getMethod("asInterface", IBinder.class).invoke(null, binder);
                    Object propBinder = car.getClass().getMethod("getCarService", String.class)
                            .invoke(car, "property");
                    property = Class.forName("android.car.hardware.property.ICarProperty$Stub")
                            .getMethod("asInterface", IBinder.class).invoke(null, propBinder);
                    getProperty = property.getClass().getMethod("getProperty", int.class, int.class);
                }
                Object value = getProperty.invoke(property, 0x214028c2, 0);
                if (value != null && ((Number) getStatus.invoke(value)).intValue() == 0) {
                    Object raw = getValue.invoke(value);
                    if (raw instanceof Number) {
                        float speed = ((Number) raw).floatValue();
                        if (SpeedReading.valid(speed)) {
                            System.out.println("SPEED " + SystemClock.elapsedRealtime() + " " + speed);
                        }
                    }
                }
            } catch (Throwable t) {
                property = null;
                long now = SystemClock.elapsedRealtime();
                if (now - lastError >= 5000) {
                    while (t.getCause() != null) t = t.getCause();
                    System.out.println("ERROR " + t);
                    lastError = now;
                }
            }
            System.out.flush();
            Thread.sleep(150);
        }
    }
}
