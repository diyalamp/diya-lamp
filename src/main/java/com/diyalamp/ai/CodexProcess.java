package com.diyalamp.ai;

import com.diyalamp.local.PrivateFiles;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Private, ephemeral Codex run. Authentication remains owned by the Codex CLI. */
public final class CodexProcess implements CodexReviewer.Runner {
    private final String executable;
    private final Duration timeout;
    public CodexProcess() { this("codex",Duration.ofMinutes(5)); }
    public CodexProcess(String executable,Duration timeout) { this.executable=executable; this.timeout=timeout; }
    @Override public String run(String prompt,String schema,String model) throws IOException,InterruptedException {
        Path directory=Files.createTempDirectory("diyalamp-codex-"); PrivateFiles.restrict(directory,true);
        Process process=null;
        try {
            Path schemaFile=directory.resolve("result.schema.json"), output=directory.resolve("result.json");
            PrivateFiles.create(schemaFile,schema);
            List<String> command=new ArrayList<>(List.of(executable,"exec","--ignore-user-config","--ephemeral","--sandbox","read-only","--skip-git-repo-check","--color","never",
                "--disable","shell_tool","--disable","unified_exec","--disable","apps","--disable","plugins","--disable","hooks","--disable","browser_use","--disable","computer_use",
                "-c","web_search=\"disabled\"","--output-schema",schemaFile.toString(),"--output-last-message",output.toString()));
            if(model!=null) { command.add("--model"); command.add(model); }
            command.add("-");
            try { process=new ProcessBuilder(command).directory(directory.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start(); }
            catch(IOException e) { throw new IOException("Cannot start Codex. Install a current Codex CLI and make codex available on your PATH."); }
            Process running=process;
            long deadline=System.nanoTime()+timeout.toNanos();
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> input=executor.submit(()-> {
                    try(OutputStream stdin=running.getOutputStream()) { stdin.write(prompt.getBytes(StandardCharsets.UTF_8)); }
                    catch(IOException e) { throw new UncheckedIOException(e); }
                });
                try {
                    input.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
                    if(!running.waitFor(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS)) {
                        stop(running); throw new IOException("Codex review timed out. No phase was changed; usage may already have been consumed.");
                    }
                } catch(TimeoutException e) { stop(running); throw new IOException("Codex did not accept the review input before timeout."); }
                catch(ExecutionException e) { stop(running); throw new IOException("Codex could not receive the draft. Check codex login status and retry deliberately."); }
                catch(InterruptedException e) { stop(running); Thread.currentThread().interrupt(); throw e; }
            }
            if(process.exitValue()!=0) throw new IOException("Codex review failed. Check codex login status, your usage limits, and CLI version. No phase was changed.");
            if(!Files.isRegularFile(output) || Files.size(output)>1_000_000) throw new IOException("Codex did not produce a bounded review result. No phase was changed.");
            return Files.readString(output);
        } finally {
            if(process!=null && process.isAlive()) stop(process);
            try(var paths=Files.walk(directory)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static void stop(Process process) {
        try { process.descendants().forEach(ProcessHandle::destroyForcibly); }
        catch(RuntimeException ignored) { /* Some managed hosts prohibit process enumeration. */ }
        process.destroyForcibly();
        try { process.waitFor(1,TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
