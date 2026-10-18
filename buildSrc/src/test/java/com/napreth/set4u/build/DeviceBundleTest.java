package com.napreth.set4u.build;

import org.junit.Test;
import org.objectweb.asm.*;
import java.util.*;
import static org.junit.Assert.*;

public class DeviceBundleTest {
    private static final String BASE = "com/napreth/set4u/core/device/";
    private byte[] make(String name, String parent, String id, String dependency) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, parent, null);
        if (id != null) {
            AnnotationVisitor entry = writer.visitAnnotation("L" + BASE + "DeviceEntry;", true);
            entry.visit("id", id); entry.visit("spec", Type.getObjectType("test/Spec")); entry.visitEnd();
        }
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(0, 1); constructor.visitEnd();
        if (dependency != null) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "execute", "()V", null, null);
            method.visitCode(); method.visitTypeInsn(Opcodes.NEW, dependency); method.visitInsn(Opcodes.POP);
            method.visitInsn(Opcodes.RETURN); method.visitMaxs(1, 1); method.visitEnd();
        }
        writer.visitEnd(); return writer.toByteArray();
    }
    private Map<String, DeviceBundle.Info> fixture() {
        Map<String, DeviceBundle.Info> classes = new TreeMap<>();
        add(classes, make("test/Spec", BASE + "DeviceTaskSpec", null, null));
        add(classes, make("test/Task", BASE + "DeviceTask", "test.task", "android/os/Build"));
        return classes;
    }
    private void add(Map<String, DeviceBundle.Info> classes, byte[] bytes) { var info = DeviceBundle.inspect(bytes); classes.put(info.name, info); }
    @Test public void methodInstructionsAreScannedAndOnlyTheDeviceImplementationIsStripped() {
        var classes = fixture();
        var tasks = DeviceBundle.validate(classes);
        assertTrue(classes.get("test/Task").references.contains("android/os/Build"));
        assertEquals(Set.of("test/Task"), DeviceBundle.deviceOnly(classes, classes.keySet(), tasks));
    }
    @Test public void hostReferencesToDeviceImplementationsAreRejected() {
        var classes = fixture();
        add(classes, make("test/Host", "java/lang/Object", null, "test/Task"));
        assertThrows(IllegalArgumentException.class, () -> DeviceBundle.deviceOnly(classes, Set.of("test/Task", "test/Spec"), DeviceBundle.validate(classes)));
    }
    @Test public void duplicateIdsAndNonTaskEntriesAreRejectedWithoutLoadingClasses() {
        var classes = fixture();
        add(classes, make("test/Duplicate", BASE + "DeviceTask", "test.task", null));
        assertThrows(IllegalArgumentException.class, () -> DeviceBundle.validate(classes));
        final var invalid = fixture();
        add(invalid, make("test/FunctionLike", "java/lang/Object", "test.function", null));
        assertThrows(IllegalArgumentException.class, () -> DeviceBundle.validate(invalid));
    }
    @Test public void deviceTasksCannotLoadSessionOrUiClasses() {
        var classes = fixture();
        add(classes, make("test/Task", BASE + "DeviceTask", "test.task", "com/napreth/set4u/core/session/CoreSession"));
        assertThrows(IllegalArgumentException.class, () -> DeviceBundle.deviceOnly(classes, classes.keySet(), DeviceBundle.validate(classes)));
    }
    @Test public void protocolSpecsCannotReferenceAndroidEvenWithoutAHostConsumer() {
        var classes = fixture();
        add(classes, make("test/Spec", BASE + "DeviceTaskSpec", null, "android/os/Build"));
        assertThrows(IllegalArgumentException.class, () -> DeviceBundle.deviceOnly(classes, classes.keySet(), DeviceBundle.validate(classes)));
    }
}
