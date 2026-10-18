package com.napreth.set4u.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.*;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.process.ExecOperations;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import javax.inject.Inject;
import javax.tools.ToolProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import java.util.stream.Collectors;

/** Scans class metadata without loading project classes or executing task/spec constructors. */
@CacheableTask
public abstract class DeviceBundle extends DefaultTask {
    private static final String BASE = "com/napreth/set4u/core/device/";
    private static final String MAIN = BASE + "DeviceTaskMain";
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClassDirectories();
    @Classpath public abstract ConfigurableFileCollection getLibraries();
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getAndroidJar();
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getD8Jar();
    @Input public abstract Property<String> getBuildType();
    @Input public abstract Property<String> getBuildVersion();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();
    @Inject protected abstract ExecOperations getExecOperations();

    public static final class Info {
        String name, parent, id, spec;
        int access, version = 1;
        boolean noArgs;
        final Set<String> references = new TreeSet<>();
        byte[] bytes;
    }

    public static Info inspect(byte[] bytes) {
        Info info = new Info();
        info.bytes = bytes;
        ClassReader reader = new ClassReader(bytes);
        info.name = reader.getClassName();
        info.parent = reader.getSuperName();
        info.access = reader.getAccess();
        ClassVisitor metadata = new ClassVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!descriptor.equals("L" + BASE + "DeviceEntry;")) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override public void visit(String name, Object value) {
                        if (name.equals("id")) info.id = (String) value;
                        if (name.equals("protocolVersion")) info.version = (Integer) value;
                        if (name.equals("spec")) info.spec = ((Type) value).getInternalName();
                    }
                };
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (name.equals("<init>") && descriptor.equals("()V") && (access & Opcodes.ACC_PUBLIC) != 0) info.noArgs = true;
                return new MethodVisitor(Opcodes.ASM9) {};
            }
        };
        reader.accept(new ClassRemapper(metadata, new Remapper(Opcodes.ASM9) {
            @Override public String map(String name) { info.references.add(name); return name; }
        }), 0);
        info.references.remove(info.name);
        return info;
    }

    private static boolean inherits(Map<String, Info> classes, Info info, String base) {
        Set<String> seen = new HashSet<>();
        while (info != null && info.parent != null && seen.add(info.name)) {
            if (info.parent.equals(base)) return true;
            info = classes.get(info.parent);
        }
        return false;
    }

    private static void concrete(Info info, String kind) {
        if ((info.access & Opcodes.ACC_PUBLIC) == 0 || (info.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0 || !info.noArgs)
            throw new IllegalArgumentException(kind + " needs a public concrete class with public no-argument constructor: " + info.name);
    }

    public static List<Info> validate(Map<String, Info> classes) {
        List<Info> tasks = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Info info : classes.values()) {
            if (info.id == null) {
                if (inherits(classes, info, BASE + "DeviceTask") && (info.access & Opcodes.ACC_ABSTRACT) == 0)
                    throw new IllegalArgumentException("DeviceTask subclass needs @DeviceEntry: " + info.name);
                continue;
            }
            concrete(info, "Device entry");
            if (!inherits(classes, info, BASE + "DeviceTask")) throw new IllegalArgumentException("Device entry must extend DeviceTask: " + info.name);
            if (!info.id.matches("[A-Za-z0-9_.-]{1,128}") || info.version <= 0 || !ids.add(info.id))
                throw new IllegalArgumentException("Invalid or duplicate device task ID: " + info.id);
            Info spec = classes.get(info.spec);
            if (spec == null || !inherits(classes, spec, BASE + "DeviceTaskSpec")) throw new IllegalArgumentException("Device entry needs a portable DeviceTaskSpec: " + info.name);
            concrete(spec, "Device protocol");
            tasks.add(info);
        }
        tasks.sort(Comparator.comparing(info -> info.id));
        return tasks;
    }

    private static Set<String> closure(Map<String, Info> classes, Set<String> roots) {
        Set<String> selected = new TreeSet<>();
        Deque<String> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (classes.containsKey(name) && selected.add(name)) pending.addAll(classes.get(name).references);
        }
        return selected;
    }

    private static boolean android(String name) { return name.startsWith("android/") || name.startsWith("dalvik/"); }
    private static boolean forbidden(String name) {
        return name.startsWith("java/awt/") || name.startsWith("javax/swing/") || name.startsWith("android/view/")
            || name.startsWith("android/widget/") || name.startsWith("android/app/Activity")
            || name.startsWith("com/napreth/set4u/core/viewmodel/") || name.startsWith("com/napreth/set4u/core/execution/")
            || name.startsWith("com/napreth/set4u/core/bridge/") || name.startsWith("com/napreth/set4u/core/state/")
            || name.startsWith("com/napreth/set4u/core/settings/") || name.startsWith("com/napreth/set4u/core/compatibility/")
            || name.equals("com/napreth/set4u/core/session/CoreSession") || name.startsWith("com/napreth/set4u/core/Core");
    }

    public static Set<String> deviceOnly(Map<String, Info> classes, Set<String> selected, List<Info> tasks) {
        Set<String> stripped = new TreeSet<>();
        tasks.forEach(info -> stripped.add(info.name));
        classes.values().stream().filter(info -> info.name.equals(MAIN) || info.name.startsWith(MAIN + "$"))
            .forEach(info -> stripped.add(info.name));
        for (String name : selected) {
            Info info = classes.get(name);
            for (String ref : info.references) {
                if (forbidden(ref)) throw new IllegalArgumentException("Device dependency crosses host/UI boundary: " + name + " -> " + ref);
                if (android(ref)) stripped.add(name);
            }
        }
        boolean changed;
        do {
            changed = false;
            for (String name : selected) if (classes.get(name).references.stream().anyMatch(stripped::contains)) changed |= stripped.add(name);
        } while (changed);
        for (Info task : tasks) if (stripped.contains(task.spec))
            throw new IllegalArgumentException("Portable DeviceTaskSpec references device code: " + task.spec);
        for (Info info : classes.values()) if (!stripped.contains(info.name)) {
            for (String ref : info.references) if (stripped.contains(ref) || android(ref))
                throw new IllegalArgumentException("Host class references a device implementation: " + info.name + " -> " + ref);
        }
        return stripped;
    }

    @TaskAction public void buildBundle() throws Exception {
        Map<String, Info> classes = new TreeMap<>();
        for (File directory : getClassDirectories()) {
            if (!directory.exists()) continue;
            try (var paths = Files.walk(directory.toPath())) {
                for (Path path : paths.filter(path -> path.toString().endsWith(".class")).collect(Collectors.toList())) {
                    Info info = inspect(Files.readAllBytes(path));
                    if (classes.put(info.name, info) != null) throw new IllegalArgumentException("Duplicate class: " + info.name);
                }
            }
        }
        List<Info> tasks = validate(classes);
        Set<String> roots = tasks.stream().map(info -> info.name).collect(Collectors.toSet());
        roots.add(MAIN);
        roots.add(BASE + "DeviceTaskRegistry");
        tasks.forEach(info -> roots.add(info.spec));
        Set<String> selected = closure(classes, roots);
        Set<String> stripped = deviceOnly(classes, selected, tasks);
        Path output = getOutputDirectory().get().getAsFile().toPath();
        Path generated = Files.createDirectories(output.resolve("generated"));
        Path source = generated.resolve("GeneratedDeviceTasks.java");
        StringBuilder code = new StringBuilder("package com.napreth.set4u.generated;\nimport com.napreth.set4u.core.device.*;\npublic final class GeneratedDeviceTasks {\n@SuppressWarnings({\"rawtypes\",\"unchecked\"}) public static DeviceTaskRegistry create() {\njava.util.List<DeviceTaskRegistration<?,?>> entries = new java.util.ArrayList<>();\n");
        for (Info info : tasks) code.append("entries.add(new DeviceTaskRegistration((DeviceTaskDescriptor)new ")
            .append(info.spec.replace('/', '.').replace('$', '.')).append("().getDescriptor(), (Class)")
            .append(info.name.replace('/', '.').replace('$', '.')).append(".class));\n");
        code.append("return new DeviceTaskRegistry(entries, 1048576);\n}\n}\n");
        Files.writeString(source, code);
        List<String> classpath = new ArrayList<>();
        getClassDirectories().forEach(file -> classpath.add(file.getAbsolutePath()));
        getLibraries().forEach(file -> classpath.add(file.getAbsolutePath()));
        int compilation = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-encoding", "UTF-8", "-classpath", String.join(File.pathSeparator, classpath), "-d", generated.toString(), source.toString());
        if (compilation != 0) throw new IllegalStateException("Generated device registry did not compile");
        Path input = output.resolve("device-input.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input))) {
            for (String name : selected) put(jar, name + ".class", classes.get(name).bytes);
            put(jar, "com/napreth/set4u/generated/GeneratedDeviceTasks.class", Files.readAllBytes(generated.resolve("com/napreth/set4u/generated/GeneratedDeviceTasks.class")));
        }
        Path resources = Files.createDirectories(output.resolve("resources/device"));
        Path bundle = resources.resolve("set4u-device.jar");
        List<String> arguments = new ArrayList<>(List.of(getBuildType().get().equals("release") ? "--release" : "--debug", "--min-api", "23", "--lib", getAndroidJar().get().getAsFile().getAbsolutePath(), "--output", bundle.toString(), input.toString()));
        getLibraries().forEach(file -> arguments.add(file.getAbsolutePath()));
        getExecOperations().javaexec(spec -> {
            spec.getMainClass().set("com.android.tools.r8.D8");
            spec.classpath(getD8Jar());
            spec.args(arguments);
            spec.setMaxHeapSize("1g");
        }).assertNormalExitValue();
        Files.writeString(output.resolve("host-excludes.txt"), stripped.stream().map(name -> name + ".class").collect(Collectors.joining("\n", "", "\n")));
        String entries = tasks.stream().map(info -> "{\"id\":\"" + info.id + "\",\"protocolVersion\":" + info.version + ",\"entry\":\"" + info.name.replace('/', '.') + "\",\"spec\":\"" + info.spec.replace('/', '.') + "\"}").collect(Collectors.joining(","));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(bundle));
        String hash = HexFormat.of().formatHex(digest);
        Files.writeString(resources.resolve("device-tasks.json"), "{\"wireVersion\":1,\"minSdk\":23,\"sha256\":\"" + hash + "\",\"entries\":[" + entries + "]}\n", StandardCharsets.UTF_8);
        Files.writeString(resources.resolve("build-version.txt"), getBuildVersion().get(), StandardCharsets.UTF_8);
        getLogger().lifecycle("Device bundle: {} entries, {} project classes, {} host exclusions", tasks.size(), selected.size(), stripped.size());
    }

    private static void put(JarOutputStream jar, String name, byte[] bytes) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(0);
        jar.putNextEntry(entry); jar.write(bytes); jar.closeEntry();
    }
}
