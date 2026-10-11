package com.diyalamp.local;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;

/** A lock is held through network calls and the final local save. */
public final class SessionLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    public SessionLock(Path session) throws IOException {
        Path path=session.toAbsolutePath().resolveSibling(session.getFileName()+".lock");
        channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired=channel.tryLock(); }
        catch(OverlappingFileLockException e) { acquired=null; }
        if(acquired==null) { channel.close(); throw new IOException("This session is open in another process. Finish that command first."); }
        lock=acquired; PrivateFiles.restrict(path,false);
    }
    @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
}
