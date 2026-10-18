import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class GetOpsList {
    public static void main(String[] args) throws Exception {
        Class<?> clazz = Class.forName("android.app.AppOpsManager");
        String[] names;

        try {
            Method method = clazz.getDeclaredMethod("getOpStrs");
            method.setAccessible(true);
            names = (String[]) method.invoke(null);
        } catch (NoSuchMethodException e) {
            Field field = clazz.getDeclaredField("_NUM_OP");
            field.setAccessible(true);
            int count = field.getInt(null);

            Method method = clazz.getDeclaredMethod(
                "opToPublicName", int.class
            );
            method.setAccessible(true);

            names = new String[count];
            for (int i = 0; i < count; i++) {
                names[i] = (String) method.invoke(null, i);
            }
        }

        for (int i = 0; i < names.length; i++) {
            System.out.println(i + "\t" + names[i]);
        }
    }
}
