package com.diyalamp.workflow;

import java.util.List;
import java.util.Objects;

/** An inspectable review result. Questions stop progression until context is supplied and reviewed. */
public record PhaseResult(Phase phase, String output, List<String> questions) {
    public PhaseResult {
        Objects.requireNonNull(phase, "Phase is required.");
        if (output == null || output.isBlank() || output.length() > 100_000)
            throw new IllegalArgumentException("Phase output must contain 1–100,000 characters.");
        questions = List.copyOf(Objects.requireNonNull(questions, "Questions are required."));
        if (questions.size() > 50 || questions.stream().anyMatch(q -> q.isBlank() || q.length() > 2_000))
            throw new IllegalArgumentException("Supply at most 50 nonblank questions, each at most 2,000 characters.");
    }
}
