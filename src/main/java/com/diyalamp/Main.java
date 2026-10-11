package com.diyalamp;

import java.nio.file.Files;
import java.nio.file.Path;

/** Local review-packet preparation. Provider and Notion adapters are not implemented yet. */
public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help")) {
            System.out.println("🪔 diyalamp — Defensible writing with AI in Notion.\nUsage: java -jar diya-lamp.jar prepare <draft.txt>\nPrepares a local review packet; does not call AI or connect to Notion.");
            return;
        }
        if (args.length != 2 || !args[0].equals("prepare")) {
            System.err.println("Expected: prepare <draft.txt>");
            System.exit(2);
        }
        String draft = Files.readString(Path.of(args[1]));
        if (draft.isBlank()) {
            System.err.println("The draft must not be empty.");
            System.exit(2);
        }
        System.out.println("# Argument review packet\n\n## Draft\n" + draft
            + "\n## Review checklist\n- Identify the central claim.\n- Separate supplied evidence from assumptions.\n- Consider alternative explanations and the strongest objection.\n- Ask targeted questions where support is missing.\n- Suggest revisions without inventing evidence.\n- Keep changes subject to author approval.");
    }
}
