// SPDX-License-Identifier: Apache-2.0
package com.diyalamp.browser;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Single-writer, local run snapshots. Use a dedicated private directory, outside Git. */
public final class FileBrowserRunStore implements BrowserAgent.Store {
    private static final Gson JSON = new Gson();
    private record Envelope(int version, BrowserAgent.State state) { }
    private final Path directory, snapshot;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private boolean closed;

    public FileBrowserRunStore(Path directory, BrowserAgent.State initial) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(this.directory)) throw new IOException("Run directory must not be a symbolic link.");
        Files.createDirectories(this.directory);
        restrict(this.directory, true);
        snapshot = this.directory.resolve("state.json");
        Path lockFile = this.directory.resolve("run.lock");
        lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock obtained = null;
        try {
            restrict(lockFile, false);
            try { obtained = lockChannel.tryLock(); }
            catch (OverlappingFileLockException conflict) { throw new IOException("Browser run already has a writer."); }
            if (obtained == null) throw new IOException("Browser run already has a writer.");
            lock = obtained;
            if (Files.exists(snapshot, LinkOption.NOFOLLOW_LINKS)) load();
            else save(Objects.requireNonNull(initial));
        } catch (IOException | RuntimeException failure) {
            if (obtained != null) obtained.release();
            lockChannel.close(); throw failure;
        }
    }
    @Override public BrowserAgent.State load() throws IOException {
        ensureOpen();
        if (Files.isSymbolicLink(snapshot) || Files.size(snapshot) > 1_000_000)
            throw new IOException("Invalid browser run snapshot.");
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(snapshot))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement tree = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Invalid browser run snapshot.");
            Envelope envelope = JSON.fromJson(tree, Envelope.class);
            if (envelope == null || envelope.version() != 1 || envelope.state() == null)
                throw new IOException("Unsupported browser run snapshot.");
            return envelope.state();
        } catch (JsonParseException | IllegalArgumentException failure) {
            throw new IOException("Invalid browser run snapshot.");
        }
    }
    @Override public void save(BrowserAgent.State state) throws IOException {
        ensureOpen(); Objects.requireNonNull(state);
        byte[] bytes = JSON.toJson(new Envelope(1, state)).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1_000_000) throw new IOException("Browser run snapshot exceeds the size limit.");
        Path temporary;
        try {
            temporary = Files.createTempFile(directory, ".state-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException unsupported) {
            temporary = Files.createTempFile(directory, ".state-", ".tmp");
        }
        try {
            try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) out.write(buffer);
                out.force(true);
            }
            Files.move(temporary, snapshot, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // An error here can mean the snapshot was replaced. Callers must not blindly replay an action.
            try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) { parent.force(true); }
        } finally { Files.deleteIfExists(temporary); }
    }
    @Override public void close() throws IOException {
        if (!closed) {
            closed = true;
            try { lock.release(); } finally { lockChannel.close(); }
        }
    }
    private void ensureOpen() throws IOException {
        if (closed || !lockChannel.isOpen()) throw new IOException("Browser run store is closed.");
    }
    private static void restrict(Path path, boolean directory) throws IOException {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); }
        catch (UnsupportedOperationException unsupported) {
            throw new IOException("Private browser snapshots require filesystem access controls.");
        }
    }
}
