import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class GetOpsCount {
    public static void main(String[] args) throws Exception {
        Class<?> clazz = Class.forName("android.app.AppOpsManager");

        try {
            Field field = clazz.getDeclaredField("_NUM_OP");
            field.setAccessible(true);
            int numOp = field.getInt(null);
            System.out.println("_NUM_OP = " + numOp);
            return;
        } catch (NoSuchFieldException e) {
            ;
        }

        try {
            Method method = clazz.getDeclaredMethod("getNumOps");
            method.setAccessible(true);
            int numOps = (int) method.invoke(null);
            System.out.println("getNumOps() = " + numOps);
            return;
        } catch (NoSuchMethodException e) {
            ;
        }

        System.err.println("Neither _NUM_OP nor getNumOps() is available");
    }
}
