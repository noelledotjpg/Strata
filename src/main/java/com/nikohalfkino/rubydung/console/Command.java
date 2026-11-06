package com.nikohalfkino.rubydung.console;

import com.nikohalfkino.rubydung.RubyDung;

import java.util.Collections;
import java.util.List;

public interface Command {

    String name();

    String description();

    default List<String> help() {
        return Collections.singletonList(description());
    }

    /**
     * typed arguments, used by the chat to colour wrong ones red and to tab-complete values
     *
     * null (the default) reads them from a single "Usage: name <a> [b]" line of help() when there is
     * one, and otherwise doesn't check them. return an empty list for a command that takes none
     */
    default List<CommandArg> arguments() {
        return null;
    }

    /**
     * the whole argument list, as far as the words typed so far decide it, for commands whose later
     * arguments depend on an earlier one ("time set" takes a time, "time speed" a number). given holds the
     * finished words after the command name, so it is empty while the first argument is being typed.
     * the list always starts at the first argument: for given = {"set"} that is [action, time]
     *
     * the default ignores given and uses arguments(). return null to fall back to that as well
     */
    default List<CommandArg> arguments(String[] given) {
        return arguments();
    }

    void execute(RubyDung game, Console console, String[] args);
}