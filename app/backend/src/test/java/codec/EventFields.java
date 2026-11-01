package codec;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Reflection helpers for the D7 field guards (CodecFieldGuardTest, OrderGatewayCopyGuardTest).
 * They see every instance field of an event carrier, so a field added to OrderEvent or
 * ExecutionEvent is picked up by the guards without anyone remembering to list it.
 */
public final class EventFields {

    private EventFields() { }

    /** Every non-static field of {@code type}, in declaration order. */
    public static List<Field> instanceFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                f.setAccessible(true);
                fields.add(f);
            }
        }
        return fields;
    }

    /**
     * Writes a distinct, non-default value into every instance field of {@code target}.
     * Values never equal 0, -1 or null, so they can never be mistaken for a reset value.
     * Two different salts give different values in every field. An unsupported field type
     * fails loudly so a new kind of field forces this helper (and the guards) to be updated.
     */
    public static void fill(Object target, int salt) {
        int i = 0;
        for (Field f : instanceFields(target.getClass())) {
            i++;
            try {
                Class<?> t = f.getType();
                if (t == long.class) {
                    f.setLong(target, salt * 1_000L + i);
                } else if (t == int.class) {
                    f.setInt(target, salt * 1_000 + i);
                } else if (t.isEnum()) {
                    Object[] constants = t.getEnumConstants();
                    f.set(target, constants[(salt + i) % constants.length]);
                } else if (t == byte[].class) {
                    byte[] bytes = (byte[]) f.get(target);
                    for (int b = 0; b < bytes.length; b++) {
                        bytes[b] = (byte) ('A' + (salt * 7 + i + b) % 26);
                    }
                } else {
                    throw new AssertionError("EventFields.fill does not support "
                            + target.getClass().getSimpleName() + "." + f.getName() + " of type " + t
                            + "; extend it and the codec guards");
                }
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** The field's value, with byte arrays copied so later writes cannot change it. */
    public static Object read(Field f, Object owner) {
        try {
            Object v = f.get(owner);
            return (v instanceof byte[] bytes) ? bytes.clone() : v;
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    /** Value equality, by content for byte arrays. */
    public static boolean same(Object a, Object b) {
        if (a instanceof byte[] x && b instanceof byte[] y) return Arrays.equals(x, y);
        return Objects.equals(a, b);
    }

    /** A readable rendering for assertion messages. */
    public static String show(Object v) {
        return (v instanceof byte[] bytes) ? Arrays.toString(bytes) : String.valueOf(v);
    }
}
