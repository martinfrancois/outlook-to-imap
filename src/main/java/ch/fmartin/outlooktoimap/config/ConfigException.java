package ch.fmartin.outlooktoimap.config;

import java.util.List;

/** Every problem found in the configuration file, so one run reports all of them. */
public final class ConfigException extends RuntimeException {

    private final List<String> problems;

    public ConfigException(List<String> problems) {
        super("invalid configuration:\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
