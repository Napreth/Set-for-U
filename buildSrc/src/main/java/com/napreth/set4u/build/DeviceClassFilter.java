package com.napreth.set4u.build;

import org.gradle.api.file.FileTreeElement;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** A named spec avoids capturing a Gradle Kotlin script in the configuration cache. */
public final class DeviceClassFilter implements Spec<FileTreeElement>, Serializable {
    private final Provider<RegularFile> exclusions;
    private transient Set<String> paths;
    public DeviceClassFilter(Provider<RegularFile> exclusions) { this.exclusions = exclusions; }
    @Override public boolean isSatisfiedBy(FileTreeElement element) {
        if (paths == null) try { paths = new HashSet<>(Files.readAllLines(exclusions.get().getAsFile().toPath())); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
        return paths.contains(element.getPath());
    }
}
