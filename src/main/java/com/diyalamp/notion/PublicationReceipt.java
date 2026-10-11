package com.diyalamp.notion;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** A durable local guard against repeating a possibly successful remote write. */
public final class PublicationReceipt {
    private PublicationReceipt() {}
    public static void begin(Path path,String reference,String parent,String fingerprint) throws IOException {
        Properties p=new Properties(); p.setProperty("state","PENDING"); p.setProperty("reference",reference);
        p.setProperty("parent",NotionClient.normalizeId(parent)); p.setProperty("fingerprint",fingerprint);
        try(Writer out=Files.newBufferedWriter(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) { p.store(out,"Private publication receipt"); }
    }
    public static void complete(Path path,String pageId) throws IOException {
        Properties p=read(path); p.setProperty("state","PUBLISHED"); p.setProperty("page",NotionClient.normalizeId(pageId));
        Path tmp=Files.createTempFile(path.toAbsolutePath().getParent(),".receipt-",".tmp");
        try {
            try(Writer out=Files.newBufferedWriter(tmp)) { p.store(out,"Private publication receipt"); }
            Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
    public static Properties read(Path path) throws IOException {
        if(Files.size(path)>10_000) throw new IOException("Invalid publication receipt.");
        Properties p=new Properties(); try(Reader in=Files.newBufferedReader(path)) { p.load(in); } return p;
    }
}
