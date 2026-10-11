package com.diyalamp.local;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

public final class PrivateFiles {
    private PrivateFiles() {}
    public static void create(Path path,String text) throws IOException {
        Path parent=path.toAbsolutePath().getParent(); Files.createDirectories(parent);
        try {
            Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch(UnsupportedOperationException e) { Files.createFile(path); }
        try(var out=Files.newBufferedWriter(path,StandardOpenOption.WRITE)) { out.write(text); }
    }
    public static void restrict(Path path,boolean directory) throws IOException {
        try { Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"rwx------":"rw-------")); }
        catch(UnsupportedOperationException ignored) { }
    }
}
