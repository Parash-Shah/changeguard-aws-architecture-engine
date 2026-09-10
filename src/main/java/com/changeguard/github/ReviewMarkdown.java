package com.changeguard.github;
import com.changeguard.evaluation.ArchitectureReport;
import com.changeguard.findings.Finding;

public final class ReviewMarkdown {
    private ReviewMarkdown() { }
    public static String render(ArchitectureReport report) {
        StringBuilder text = new StringBuilder("## ChangeGuard Architecture Review\n\nScore: **" + report.score() + "/100** — **" + report.status() + "**\n\n");
        text.append("| Pillar | Score | Checks |\n|---|---:|---:|\n");
        report.pillarScores().forEach((pillar, score) -> text.append("| ").append(pillar).append(" | ").append(score.score() == null ? "Not assessed" : score.score()).append(" | ").append(score.evaluated()).append(" |\n"));
        text.append("\n### New risks\n\n");
        report.findings().stream().filter(f -> f.classification() == Finding.Classification.NEW).limit(50)
                .forEach(f -> text.append("- **").append(f.severity()).append("** ").append(escape(f.ruleId())).append(" · ")
                        .append(escape(f.resourceId())).append(": ").append(escape(f.message())).append(f.suppressed() ? " (suppressed)" : "").append("\n"));
        text.append("\nResolved findings: ").append(report.resolvedFindings().size()).append("\n");
        report.gateReasons().forEach(reason -> text.append("\n- ").append(escape(reason)));
        return text.toString();
    }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\r", " ").replace("\n", " ").replace("|", "\\|").replace("`", "\\`").replace("*", "\\*"); }
}
