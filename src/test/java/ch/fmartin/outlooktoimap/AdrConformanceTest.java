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

/** Keeps every decision record under docs/adr in the MADR 4 shape that AGENTS.md describes. */
class AdrConformanceTest {

    private static final Path ADR_DIRECTORY = Path.of("docs", "adr");
    private static final String TEMPLATE = "adr-template.md";

    private static final Pattern FILE_NAME = Pattern.compile("^(\\d{4})-[a-z0-9]+(?:-[a-z0-9]+)*\\.md$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern SUPERSEDED = Pattern.compile("^superseded by \\[ADR \\d{4}]\\(([^)]+\\.md)\\)$");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^]]+]\\([^)]+\\)");
    private static final Pattern BARE_REFERENCE = Pattern.compile("(?<![\\w/])#\\d+\\b");
    private static final Pattern BARE_GITHUB_URL = Pattern.compile("https?://github\\.com/[^\\s)]+/(?:issues|pull)/\\d+");
    // A Maven or shell expression such as ${session.rootDirectory} is content, not a template placeholder.
    private static final Pattern PLACEHOLDER = Pattern.compile("(?<!\\$)\\{[^}]*}");

    private static final List<String> FRONT_MATTER_KEYS = List.of("status", "date", "decision-makers", "consulted", "informed");
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
        assertViolation(directory, VALID.replace("consulted: [Maintainers]", "consulted: []"), "key consulted");
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
    void reportsAnOutcomeWithoutAChosenOption(@TempDir Path directory) throws IOException {
        assertViolation(directory, VALID.replace("Chosen option: \"Keep the sample\", because it is the sample.", "We keep the sample."),
                "must state the chosen option");
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
            checkRecord(record, violations);
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

    private static void checkRecord(Path record, List<String> violations) {
        String name = record.getFileName().toString();
        List<String> lines;
        try {
            lines = Files.readAllLines(record);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int end = lines.isEmpty() || !lines.getFirst().equals("---") ? -1 : lines.subList(1, lines.size()).indexOf("---") + 1;
        if (end <= 0) {
            violations.add(name + ": expected YAML front matter between two --- lines at the top");
            return;
        }
        Map<String, Object> metadata;
        try {
            metadata = YAML.readValue(String.join("\n", lines.subList(1, end)), new TypeReference<>() {});
        } catch (JacksonException e) {
            violations.add(name + ": front matter is not valid YAML: " + e.getOriginalMessage());
            return;
        }
        List<String> body = lines.subList(end + 1, lines.size());
        checkMetadata(name, metadata == null ? Map.of() : metadata, record.getParent(), violations);
        checkHeadings(name, body, violations);
        checkDecisionOutcome(name, body, violations);
        checkOptions(name, body, violations);
        checkPlaceholders(name, body, violations);
        checkReferences(name, lines, violations);
    }

    private static void checkMetadata(String name, Map<String, Object> metadata, Path directory, List<String> violations) {
        for (String key : FRONT_MATTER_KEYS) {
            Object value = metadata.get(key);
            boolean blank = value == null || value.toString().isBlank() || value instanceof List<?> list && list.isEmpty();
            if (blank) {
                violations.add(name + ": expected filled front matter key " + key);
            }
        }
        if (metadata.get("status") instanceof String status && !status.equals("accepted")) {
            Matcher superseded = SUPERSEDED.matcher(status);
            if (!superseded.matches()) {
                violations.add(name + ": expected status accepted or a superseded-by link, found " + status);
            } else if (!Files.isRegularFile(directory.resolve(superseded.group(1)))) {
                violations.add(name + ": superseding record does not exist: " + superseded.group(1));
            }
        }
        Object date = metadata.get("date");
        if (date != null && !DATE.matcher(date.toString()).matches()) {
            violations.add(name + ": expected date as YYYY-MM-DD, found " + date);
        }
    }

    private static void checkHeadings(String name, List<String> body, List<String> violations) {
        long titles = body.stream().filter(line -> line.startsWith("# ")).count();
        if (titles != 1) {
            violations.add(name + ": expected exactly one title heading, found " + titles);
        }
        int previous = -1;
        for (String heading : REQUIRED_HEADINGS) {
            int index = body.indexOf(heading);
            if (index < 0) {
                violations.add(name + ": expected heading " + heading);
                continue;
            }
            if (index <= previous) {
                violations.add(name + ": heading is out of order: " + heading);
            }
            previous = Math.max(previous, index);
        }
        int moreInformation = body.indexOf(MORE_INFORMATION);
        if (moreInformation >= 0 && moreInformation <= previous) {
            violations.add(name + ": heading is out of order: " + MORE_INFORMATION);
        }
    }

    private static void checkDecisionOutcome(String name, List<String> body, List<String> violations) {
        if (section(body, "## Decision Outcome", 3).stream().noneMatch(line -> line.contains("Chosen option:"))) {
            violations.add(name + ": Decision Outcome must state the chosen option");
        }
    }

    private static void checkOptions(String name, List<String> body, List<String> violations) {
        List<String> prosAndCons = section(body, "## Pros and Cons of the Options", 2);
        List<String> options = prosAndCons.stream().filter(line -> line.startsWith("### ")).toList();
        if (options.isEmpty()) {
            violations.add(name + ": Pros and Cons of the Options has no option subsection");
        }
        for (String option : options) {
            String first = section(prosAndCons, option, 3).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("<!--"))
                    .findFirst()
                    .orElse("");
            if (first.isEmpty() || first.startsWith("* ") || first.startsWith("- ")) {
                violations.add(name + ": option '" + option.substring(4) + "' must open with a sentence saying what it is");
            }
        }
    }

    private static void checkPlaceholders(String name, List<String> body, List<String> violations) {
        for (int i = 0; i < body.size(); i++) {
            if (PLACEHOLDER.matcher(body.get(i)).find()) {
                violations.add(name + ": body line " + (i + 1) + " still holds a template placeholder");
                return;
            }
        }
    }

    private static void checkReferences(String name, List<String> lines, List<String> violations) {
        String text = MARKDOWN_LINK.matcher(String.join("\n", lines)).replaceAll("");
        Matcher bareReference = BARE_REFERENCE.matcher(text);
        if (bareReference.find()) {
            violations.add(name + ": cite issues and pull requests as full Markdown links, not " + bareReference.group());
        }
        if (BARE_GITHUB_URL.matcher(text).find()) {
            violations.add(name + ": cite issues and pull requests as full Markdown links, not a bare URL");
        }
    }

    /** The lines after {@code heading} up to the next heading of {@code level} or higher. */
    private static List<String> section(List<String> lines, String heading, int level) {
        int start = lines.indexOf(heading);
        if (start < 0) {
            return List.of();
        }
        List<String> section = new ArrayList<>();
        for (String line : lines.subList(start + 1, lines.size())) {
            int depth = line.indexOf(' ');
            if (depth > 0 && depth <= level && line.substring(0, depth).chars().allMatch(c -> c == '#')) {
                break;
            }
            section.add(line);
        }
        return section;
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
