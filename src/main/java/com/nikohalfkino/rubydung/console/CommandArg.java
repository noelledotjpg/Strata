package com.nikohalfkino.rubydung.console;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * one argument of a command, what the chat colours and completes against
 *
 * built with the static factories and chained: CommandArg.choice("type", "flat", "natural").optional().skippable()
 */
public final class CommandArg {

    public enum Kind { WORD, INTEGER, NUMBER, CHOICE }

    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)");
    private static final Pattern PARTIAL_INTEGER = Pattern.compile("[+-]?\\d*");
    private static final Pattern PARTIAL_NUMBER = Pattern.compile("[+-]?\\d*\\.?\\d*");

    private final String name;
    private final Kind kind;
    private final List<String> choices;

    private boolean optional;
    private boolean skippable;
    private boolean rest;
    private boolean alsoNumber;
    private boolean loose;
    // short descriptions shown next to the choices in the suggestion box, keyed by lower-case choice
    private final Map<String, String> notes = new HashMap<>();

    private CommandArg(String name, Kind kind, List<String> choices) {
        this.name = name;
        this.kind = kind;
        this.choices = choices;
    }

    /** anything goes */
    public static CommandArg word(String name) {
        return new CommandArg(name, Kind.WORD, Collections.<String>emptyList());
    }

    public static CommandArg integer(String name) {
        return new CommandArg(name, Kind.INTEGER, Collections.<String>emptyList());
    }

    public static CommandArg number(String name) {
        return new CommandArg(name, Kind.NUMBER, Collections.<String>emptyList());
    }

    /** one of these, any case */
    public static CommandArg choice(String name, String... choices) {
        return new CommandArg(name, Kind.CHOICE,
                Collections.unmodifiableList(new ArrayList<>(Arrays.asList(choices))));
    }

    public CommandArg optional() {
        this.optional = true;
        return this;
    }

    /** a lone "-" is also fine (the command treats it as "leave this one out") */
    public CommandArg skippable() {
        this.skippable = true;
        return this;
    }

    /** takes every remaining word, so the command may be given any number of these */
    public CommandArg rest() {
        this.rest = true;
        return this;
    }

    /** a choice argument that also takes any number (\"time set noon\" or \"time set 0.3\") */
    public CommandArg orNumber() {
        this.alsoNumber = true;
        return this;
    }

    /** a choice argument that only suggests its choices and lets any other word through unchecked */
    public CommandArg loose() {
        this.loose = true;
        return this;
    }

    /** describes one of the choices in the suggestion box */
    public CommandArg note(String choice, String text) {
        this.notes.put(choice.toLowerCase(), text);
        return this;
    }

    /** the description of a choice, \"\" if it has none */
    public String noteFor(String choice) {
        String note = this.notes.get(choice.toLowerCase());
        return note == null ? "" : note;
    }

    /** whether the word is one of the fixed choices */
    public boolean isChoice(String token) {
        for (String choice : this.choices) {
            if (choice.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    /** whether the word is a number this argument takes in place of a choice */
    public boolean isNumeric(String token) {
        return this.alsoNumber && NUMBER.matcher(token).matches();
    }

    public String name() {
        return this.name;
    }

    public Kind kind() {
        return this.kind;
    }

    public boolean isOptional() {
        return this.optional;
    }

    public boolean isSkippable() {
        return this.skippable;
    }

    public boolean isRest() {
        return this.rest;
    }

    public List<String> choices() {
        return this.choices;
    }

    /** "[name]" or "<name>" */
    public String label() {
        return this.optional ? "[" + this.name + "]" : "<" + this.name + ">";
    }

    /** whether a finished word is a valid value */
    public boolean accepts(String token) {
        if (this.skippable && token.equals("-")) {
            return true;
        }

        switch (this.kind) {
            case INTEGER:
                return INTEGER.matcher(token).matches();
            case NUMBER:
                return NUMBER.matcher(token).matches();
            case CHOICE:
                return this.loose || isChoice(token) || isNumeric(token);
            default:
                return true;
        }
    }

    /** whether a word still being typed could turn into a valid value (so "fl" isn't red yet for flat) */
    public boolean couldStart(String prefix) {
        if (prefix.isEmpty() || accepts(prefix)) {
            return true;
        }

        if (this.skippable && "-".startsWith(prefix)) {
            return true;
        }

        switch (this.kind) {
            case INTEGER:
                return PARTIAL_INTEGER.matcher(prefix).matches();
            case NUMBER:
                return PARTIAL_NUMBER.matcher(prefix).matches();
            case CHOICE:
                return !suggestions(prefix).isEmpty()
                        || (this.alsoNumber && PARTIAL_NUMBER.matcher(prefix).matches());
            default:
                return true;
        }
    }

    /** the fixed values that start with prefix, empty for free-form arguments */
    public List<String> suggestions(String prefix) {
        List<String> out = new ArrayList<>();

        if (this.kind == Kind.CHOICE) {
            String lower = prefix.toLowerCase();
            for (String choice : this.choices) {
                if (choice.toLowerCase().startsWith(lower)) {
                    out.add(choice);
                }
            }
        }

        return out;
    }
}
