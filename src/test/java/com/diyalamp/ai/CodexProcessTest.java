package com.diyalamp.ai;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public final class CodexProcessTest {
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("fake-codex-"); Path executable=dir.resolve("codex stub");
        try {
            Files.writeString(executable,"""
                #!/bin/sh
                set -eu
                output=''
                seen_ephemeral=no
                seen_readonly=no
                while [ "$#" -gt 0 ]; do
                  case "$1" in
                    --output-last-message) shift; output="$1" ;;
                    --ephemeral) seen_ephemeral=yes ;;
                    read-only) seen_readonly=yes ;;
                  esac
                  shift
                done
                [ "$seen_ephemeral" = yes ] && [ "$seen_readonly" = yes ]
                input=$(cat)
                [ "$input" = 'Synthetic prompt' ]
                printf '{"phase":"ARGUMENT_MAP","output":"Synthetic review","questions":[]}' > "$output"
                """); executable.toFile().setExecutable(true);
            String result=new CodexProcess(executable.toString(),Duration.ofSeconds(5)).run("Synthetic prompt","{}",null);
            if(!result.contains("Synthetic review")) throw new AssertionError();
            Files.writeString(executable,"#!/bin/sh\nexit 1\n");
            reject(new CodexProcess(executable.toString(),Duration.ofSeconds(5)));
            Files.writeString(executable,"#!/bin/sh\nexec sleep 10\n");
            reject(new CodexProcess(executable.toString(),Duration.ofMillis(100)));
            reject(new CodexProcess(dir.resolve("missing").toString(),Duration.ofSeconds(1)));
            System.out.println("PASS: Codex subprocess stdin, structured file output, ephemeral/read-only flags, nonzero exit, timeout, and missing CLI.");
        } finally { try(var paths=Files.walk(dir)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); } }
    }
    private static void reject(CodexProcess runner) throws Exception { try { runner.run("Synthetic prompt","{}",null); throw new AssertionError(); } catch(IOException expected) { } }
}
