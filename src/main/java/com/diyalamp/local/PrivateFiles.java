package com.diyalamp.local;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

public final class PrivateFiles {
    private PrivateFiles() {}
    public static void create(Path path,String text) throws IOException {
        Path parent=path.toAbsolutePath().getParent(); Files.createDirectories(parent);
        try(var out=Files.newBufferedWriter(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) { out.write(text); }
        restrict(path,false);
    }
    public static void restrict(Path path,boolean directory) throws IOException {
        try { Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"rwx------":"rw-------")); }
        catch(UnsupportedOperationException ignored) { }
    }
}
