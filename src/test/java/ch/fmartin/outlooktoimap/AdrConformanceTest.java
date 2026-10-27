package ch.fmartin.outlooktoimap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Without this test a decision record that leaves the MADR 4 shape AGENTS.md describes goes unnoticed, see docs/adr/0006-record-decisions-as-madr-4.md. */
class AdrConformanceTest {

    private static final Path ADR_DIRECTORY = Path.of("docs", "adr");
    private static final String TEMPLATE = "adr-template.md";

    private static final Pattern FILE_NAME = Pattern.compile("^(\\d{4})-[a-z0-9]+(?:-[a-z0-9]+)*\\.md$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern SUPERSEDED = Pattern.compile("^superseded by \\[ADR \\d{4}]\\(([^)]+\\.md)\\)$");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^]]+)]\\([^)]+\\)");
    private static final Pattern VAGUE_LINK_TEXT = Pattern.compile("(?i)#\\d+|https?://\\S+|(?:click )?here|(?:this )?link|this");
    private static final Pattern BARE_REFERENCE = Pattern.compile("(?<![\\w/])#\\d+\\b");
    private static final Pattern BARE_GITHUB_URL = Pattern.compile("https?://github\\.com/[^\\s)]+/(?:issues|pull)/\\d+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*}");
    // Code such as ${{ github.token }} or ${session.rootDirectory} may hold braces; only prose can hold a placeholder.
    private static final Pattern INLINE_CODE = Pattern.compile("(`+).+?\\1");
    private static final Pattern CHOSEN_OPTION = Pattern.compile("Chosen option: \"([^\"]+)\"");
    private static final char EM_DASH = '\u2014';

    private static final List<String> FRONT_MATTER_KEYS = List.of("status", "date", "decision-makers", "consulted", "informed");
    // The MADR template marks these optional, so an empty list says nobody was consulted or informed.
    private static final List<String> MAY_BE_EMPTY = List.of("consulted", "informed");
    private static final List<String> REQUIRED_HEADINGS = List.of(
            "## Context and Problem Statement",
            "## Decision Drivers",
            "## Considered Options",
            "## Decision Outcome",
            "### Consequences",
            "### Confirmation",
            "## Pros and Cons of the Options");
    private static final String MORE_INFORMATION = "## More Information";

    private static final ObjectMapper YAML = YAMLMapper.builder().build();

    @Test
    void repositoryRecordsConform() {
        assertThat(ADR_DIRECTORY.resolve(TEMPLATE)).isRegularFile();
        assertThat(violations(ADR_DIRECTORY)).isEmpty();
    }

    @Test
    void sampleRecordPassesAndTemplateIsSkipped(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve(TEMPLATE), "{placeholder only}\n");
        Files.writeString(directory.resolve("0001-keep-the-sample.md"), VALID);

        assertThat(violations(directory)).isEmpty();
    }

    @Test
    void reportsAMissingFrontMatterKey(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("informed: [Future maintainers]\n", ""), "key informed");
    }

    @Test
    void reportsABlankFrontMatterKey(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("decision-makers: [martinfrancois]", "decision-makers: []"), "key decision-makers");
    }

    @Test
    void acceptsAnEmptyConsultedList(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("0001-keep-the-sample.md"), VALID.replace("consulted: [Maintainers]", "consulted: []"));

        assertThat(violations(directory)).isEmpty();
    }

    @Test
    void reportsAPlaceholderInTheFrontMatter(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("decision-makers: [martinfrancois]", "decision-makers: {list everyone involved in the decision}"),
                "front matter key decision-makers still holds a template placeholder");
    }

    @Test
    void reportsAnUnknownStatus(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("status: accepted", "status: proposed"), "status accepted");
    }

    @Test
    void reportsASupersedingRecordThatDoesNotExist(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("status: accepted", "status: superseded by [ADR 0002](0002-missing.md)"),
                "superseding record does not exist");
    }

    @Test
    void acceptsASupersedingRecordThatExists(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("0001-keep-the-sample.md"),
                VALID.replace("status: accepted", "status: superseded by [ADR 0002](0002-drop-the-sample.md)"));
        Files.writeString(directory.resolve("0002-drop-the-sample.md"), VALID);

        assertThat(violations(directory)).isEmpty();
    }

    @Test
    void reportsAMalformedDate(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("date: 2026-10-26", "date: October 2026"), "YYYY-MM-DD");
    }

    @Test
    void reportsAMissingHeading(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("### Confirmation\n\nRun the sample test.\n\n", ""), "### Confirmation");
    }

    @Test
    void reportsAHeadingOutOfOrder(@TempDir Path directory) throws IOException {
        String swapped = VALID.replace("## Decision Drivers\n\n* A sample driver.\n\n", "")
                .replace("## Pros and Cons of the Options", "## Decision Drivers\n\n* A sample driver.\n\n## Pros and Cons of the Options");
        assertViolation(directory, swapped, "out of order");
    }

    @Test
    void reportsATemplatePlaceholder(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("\"Keep the sample\", because it is the sample.", "\"{title of option 1}\", because {justification}."),
                "template placeholder");
    }

    @Test
    void acceptsAMavenExpression(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("0001-keep-the-sample.md"),
                VALID.replace("Run the sample test.", "Run with `--global-settings=${session.rootDirectory}/.mvn/settings.xml`."));

        assertThat(violations(directory)).isEmpty();
    }

    @Test
    void acceptsBracesInInlineCode(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("0001-keep-the-sample.md"),
                VALID.replace("Run the sample test.", "Run it with `${{ github.token }}` and an empty `{}`."));

        assertThat(violations(directory)).isEmpty();
    }

    @Test
    void ignoresHeadingsAndBracesInAFencedCodeBlock(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("0001-keep-the-sample.md"),
                VALID.replace("Run the sample test.\n", "Run the sample test:\n\n```bash\n# {not a title}\n./mvnw verify\n```\n"));

        assertThat(violations(directory)).isEmpty();
    }

    @Test
    void reportsAnEmptyConfirmation(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("### Confirmation\n\nRun the sample test.\n", "### Confirmation\n"),
                "section ### Confirmation is empty");
    }

    @Test
    void reportsASectionThatHoldsOnlyAComment(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("* A sample driver.", "<!-- drivers go here -->"), "section ## Decision Drivers is empty");
    }

    @Test
    void reportsAnOutcomeWithoutAChosenOption(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("Chosen option: \"Keep the sample\", because it is the sample.", "We keep the sample."),
                "must state the chosen option");
    }

    @Test
    void reportsAChosenOptionThatIsNotAConsideredOption(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("Chosen option: \"Keep the sample\"", "Chosen option: \"Keep a sample\""),
                "chosen option \"Keep a sample\" is not one of the Considered Options");
    }

    @Test
    void acceptsAChosenOptionWrappedOverTwoLines(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("0001-keep-the-sample.md"), VALID
                .replace("* Keep the sample\n", "* Keep the sample,\n  for good\n")
                .replace("Chosen option: \"Keep the sample\"", "Chosen option: \"Keep the sample,\nfor good\""));

        assertThat(violations(directory)).isEmpty();
    }

    @Test
    void reportsAnOptionThatOpensWithABullet(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("Keeping means the sample file stays in the repository.\n\n", ""),
                "must open with a sentence");
    }

    @Test
    void reportsABareIssueNumber(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("A sample driver.", "Asked for in #1."), "not #1");
    }

    @Test
    void reportsABareGitHubUrl(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("A sample driver.", "See https://github.com/example/project/pull/2."), "bare URL");
    }

    @Test
    void reportsAnIssueNumberAsLinkText(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("[GitHub issue 1]", "[#1]"), "link text must say what it points to, not [#1]");
    }

    @Test
    void reportsAUrlAsLinkText(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("[GitHub issue 1]", "[https://github.com/example/project/issues/1]"),
                "link text must say what it points to");
    }

    @Test
    void reportsHereAsLinkText(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("[GitHub issue 1]", "[here]"), "link text must say what it points to, not [here]");
    }

    @Test
    void reportsAnEmDash(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("A sample driver.", "A sample driver \u2014 with a dash."), "em dash");
    }

    @Test
    void reportsANumberUsedTwice(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("0001-keep-the-sample.md"), VALID);
        Files.writeString(directory.resolve("0001-keep-another-sample.md"), VALID);

        assertThat(violations(directory)).anyMatch(v -> v.contains("ADR number 0001 is used by several files"));
    }

    @Test
    void reportsABadFileName(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("1-Keep Sample.md"), VALID);

        assertThat(violations(directory)).anyMatch(v -> v.contains("expected a file name like"));
    }

    private static void assertViolation(Path directory, String record, String fragment) throws IOException {
        Files.writeString(directory.resolve("0001-keep-the-sample.md"), record);

        assertThat(violations(directory)).anyMatch(v -> v.contains(fragment));
    }

    static List<String> violations(Path directory) {
        List<String> violations = new ArrayList<>();
        List<Path> records = records(directory);
        Map<String, List<String>> byNumber = new TreeMap<>();
        for (Path record : records) {
            String name = record.getFileName().toString();
            Matcher matcher = FILE_NAME.matcher(name);
            if (matcher.matches()) {
                byNumber.computeIfAbsent(matcher.group(1), number -> new ArrayList<>()).add(name);
            } else {
                violations.add(name + ": expected a file name like 0001-short-title.md");
            }
        }
        byNumber.forEach((number, names) -> {
            if (names.size() > 1) {
                violations.add("ADR number " + number + " is used by several files: " + String.join(", ", names));
            }
        });
        for (Path record : records) {
            checkRecord(record, new Findings(record.getFileName().toString(), violations));
        }
        return violations;
    }

    private static List<Path> records(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".md"))
                    .filter(file -> !file.getFileName().toString().equals(TEMPLATE))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void checkRecord(Path record, Findings findings) {
        List<String> lines;
        try {
            lines = Files.readAllLines(record);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int end = lines.isEmpty() || !lines.getFirst().equals("---") ? -1 : lines.subList(1, lines.size()).indexOf("---") + 1;
        if (end <= 0) {
            findings.add("expected YAML front matter between two --- lines at the top");
            return;
        }
        Map<String, Object> metadata;
        try {
            metadata = YAML.readValue(String.join("\n", lines.subList(1, end)), new TypeReference<>() {});
        } catch (JacksonException e) {
            findings.add("front matter is not valid YAML: " + e.getOriginalMessage());
            return;
        }
        List<String> body = withoutCodeBlocks(lines.subList(end + 1, lines.size()));
        checkMetadata(findings, metadata == null ? Map.of() : metadata, record.getParent());
        checkHeadings(findings, body);
        checkSections(findings, body);
        checkDecisionOutcome(findings, body);
        checkOptions(findings, body);
        checkPlaceholders(findings, body);
        checkReferences(findings, lines);
        checkDashes(findings, lines);
    }

    private static void checkMetadata(Findings findings, Map<String, Object> metadata, Path directory) {
        for (String key : FRONT_MATTER_KEYS) {
            Object value = metadata.get(key);
            boolean emptyList = value instanceof List<?> list && list.isEmpty();
            if (value == null || value.toString().isBlank() || emptyList && !MAY_BE_EMPTY.contains(key)) {
                findings.add("expected filled front matter key " + key);
            } else if (PLACEHOLDER.matcher(value.toString()).find()) {
                findings.add("front matter key " + key + " still holds a template placeholder");
            }
        }
        if (metadata.get("status") instanceof String status && !status.equals("accepted")) {
            Matcher superseded = SUPERSEDED.matcher(status);
            if (!superseded.matches()) {
                findings.add("expected status accepted or a superseded-by link, found " + status);
            } else if (!Files.isRegularFile(directory.resolve(superseded.group(1)))) {
                findings.add("superseding record does not exist: " + superseded.group(1));
            }
        }
        Object date = metadata.get("date");
        if (date != null && !DATE.matcher(date.toString()).matches()) {
            findings.add("expected date as YYYY-MM-DD, found " + date);
        }
    }

    private static void checkHeadings(Findings findings, List<String> body) {
        long titles = body.stream().filter(line -> line.startsWith("# ")).count();
        if (titles != 1) {
            findings.add("expected exactly one title heading, found " + titles);
        }
        int previous = -1;
        for (String heading : REQUIRED_HEADINGS) {
            int index = body.indexOf(heading);
            if (index < 0) {
                findings.add("expected heading " + heading);
                continue;
            }
            if (index <= previous) {
                findings.add("heading is out of order: " + heading);
            }
            previous = Math.max(previous, index);
        }
        int moreInformation = body.indexOf(MORE_INFORMATION);
        if (moreInformation >= 0 && moreInformation <= previous) {
            findings.add("heading is out of order: " + MORE_INFORMATION);
        }
    }

    private static void checkSections(Findings findings, List<String> body) {
        for (String heading : REQUIRED_HEADINGS) {
            if (body.contains(heading) && !hasText(section(body, heading, depth(heading)))) {
                findings.add("section " + heading + " is empty");
            }
        }
    }

    private static void checkDecisionOutcome(Findings findings, List<String> body) {
        String outcome = String.join(" ", section(body, "## Decision Outcome", 3)).replaceAll("\\s+", " ");
        Matcher chosen = CHOSEN_OPTION.matcher(outcome);
        if (!chosen.find()) {
            findings.add("Decision Outcome must state the chosen option as Chosen option: \"...\"");
        } else if (!bullets(section(body, "## Considered Options", 2)).contains(chosen.group(1))) {
            findings.add("chosen option \"" + chosen.group(1) + "\" is not one of the Considered Options");
        }
    }

    private static void checkOptions(Findings findings, List<String> body) {
        List<String> prosAndCons = section(body, "## Pros and Cons of the Options", 2);
        List<String> options = prosAndCons.stream().filter(line -> line.startsWith("### ")).toList();
        if (options.isEmpty()) {
            findings.add("Pros and Cons of the Options has no option subsection");
        }
        for (String option : options) {
            String first = section(prosAndCons, option, 3).stream()
                    .map(String::strip)
                    .filter(AdrConformanceTest::isText)
                    .findFirst()
                    .orElse("");
            if (first.isEmpty() || first.startsWith("* ") || first.startsWith("- ")) {
                findings.add("option '" + option.substring(4) + "' must open with a sentence saying what it is");
            }
        }
    }

    private static void checkPlaceholders(Findings findings, List<String> body) {
        for (int i = 0; i < body.size(); i++) {
            if (PLACEHOLDER.matcher(INLINE_CODE.matcher(body.get(i)).replaceAll("")).find()) {
                findings.add("body line " + (i + 1) + " still holds a template placeholder");
                return;
            }
        }
    }

    private static void checkReferences(Findings findings, List<String> lines) {
        String text = String.join("\n", lines);
        Matcher link = MARKDOWN_LINK.matcher(text);
        while (link.find()) {
            if (VAGUE_LINK_TEXT.matcher(link.group(1).strip()).matches()) {
                findings.add("link text must say what it points to, not [" + link.group(1) + "]");
            }
        }
        String unlinked = MARKDOWN_LINK.matcher(text).replaceAll("");
        Matcher bareReference = BARE_REFERENCE.matcher(unlinked);
        if (bareReference.find()) {
            findings.add("cite issues and pull requests as full Markdown links, not " + bareReference.group());
        }
        if (BARE_GITHUB_URL.matcher(unlinked).find()) {
            findings.add("cite issues and pull requests as full Markdown links, not a bare URL");
        }
    }

    private static void checkDashes(Findings findings, List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).indexOf(EM_DASH) >= 0) {
                findings.add("line " + (i + 1) + " holds an em dash; use a comma or a full stop");
                return;
            }
        }
    }

    /** {@code body} with the lines inside ``` fences blanked, so code never counts as a heading or a placeholder. */
    private static List<String> withoutCodeBlocks(List<String> body) {
        List<String> prose = new ArrayList<>();
        boolean fenced = false;
        for (String line : body) {
            boolean fence = line.strip().startsWith("```");
            prose.add(fenced && !fence ? "" : line);
            if (fence) {
                fenced = !fenced;
            }
        }
        return prose;
    }

    /** The lines after {@code heading} up to the next heading of {@code level} or higher. */
    private static List<String> section(List<String> lines, String heading, int level) {
        int start = lines.indexOf(heading);
        if (start < 0) {
            return List.of();
        }
        List<String> section = new ArrayList<>();
        for (String line : lines.subList(start + 1, lines.size())) {
            int depth = depth(line);
            if (depth > 0 && depth <= level) {
                break;
            }
            section.add(line);
        }
        return section;
    }

    /** The number of leading {@code #} of a heading line, or 0 for any other line. */
    private static int depth(String line) {
        int depth = line.indexOf(' ');
        return depth > 0 && line.substring(0, depth).chars().allMatch(c -> c == '#') ? depth : 0;
    }

    private static boolean hasText(List<String> lines) {
        return lines.stream().map(String::strip).anyMatch(AdrConformanceTest::isText);
    }

    /** Whether a stripped line shows the reader something: not blank and not an HTML comment. */
    private static boolean isText(String stripped) {
        return !stripped.isEmpty() && !stripped.startsWith("<!--");
    }

    /** The bullet items of {@code lines}, each with its continuation lines joined by single spaces. */
    private static List<String> bullets(List<String> lines) {
        List<String> items = new ArrayList<>();
        for (String line : lines) {
            String text = line.strip();
            if (text.startsWith("* ") || text.startsWith("- ")) {
                items.add(text.substring(2).strip());
            } else if (!text.isEmpty() && !items.isEmpty() && line.startsWith(" ")) {
                items.add(items.removeLast() + " " + text);
            }
        }
        return items;
    }

    /** The violations of one record, each prefixed with the record's file name. */
    private record Findings(String name, List<String> violations) {

        void add(String problem) {
            violations.add(name + ": " + problem);
        }
    }

    private static final String VALID = """
            ---
            status: accepted
            date: 2026-10-26
            decision-makers: [martinfrancois]
            consulted: [Maintainers]
            informed: [Future maintainers]
            ---

            # Keep the sample decision

            ## Context and Problem Statement

            A sample problem that needs a decision.

            ## Decision Drivers

            * A sample driver.

            ## Considered Options

            * Keep the sample
            * Drop the sample

            ## Decision Outcome

            Chosen option: "Keep the sample", because it is the sample.

            ### Consequences

            * Good, because the sample exists.

            ### Confirmation

            Run the sample test.

            ## Pros and Cons of the Options

            ### Keep the sample

            Keeping means the sample file stays in the repository.

            * Good, because it is simple.

            ### Drop the sample

            Dropping means the sample file is deleted.

            * Bad, because nothing is left.

            ## More Information

            See [GitHub issue 1](https://github.com/example/project/issues/1).
            """;
}
