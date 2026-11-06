package com.nikohalfkino.rubydung.console;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * colours and completes a command line (no leading "." or prompt), no drawing in here
 *
 * arguments come from Command.arguments() when a command declares them. otherwise a single
 * "Usage: name <a> [b|c] [rest...]" line in help() is read, and when help() doesn't have exactly
 * that shape the arguments aren't checked at all rather than guessed at
 */
public final class CommandSyntax {

    /** colour value meaning "leave it the input's normal colour" */
    public static final int NORMAL = -1;

    public static final int COMMAND = 0xFFD866;
    public static final int COMMAND_PARTIAL = 0xBFAE70;
    public static final int ARG = 0xE8E8E8;
    public static final int CHOICE = 0x7FD7FF;
    public static final int NUMBER = 0xFFA860;
    public static final int SKIPPED = 0x8899AA;
    public static final int DIM = 0x888888;
    public static final int ERROR = 0xFF5555;

    private final Function<String, Command> find;
    private final Supplier<List<Command>> all;

    // null list = nothing declared and nothing readable, don't check the arguments
    private final Map<Command, List<CommandArg>> specs = new IdentityHashMap<>();
    private final Map<Command, Boolean> resolved = new IdentityHashMap<>();

    public CommandSyntax(Function<String, Command> find, Supplier<List<Command>> all) {
        this.find = find;
        this.all = all;
    }

    // tokens

    private static final class Token {
        final int start;
        final int end;
        final String text;

        Token(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }

        boolean touches(int cursor) {
            return this.start <= cursor && cursor <= this.end;
        }
    }

    private static List<Token> tokenize(String line) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;

        while (i < line.length()) {
            if (Character.isWhitespace(line.charAt(i))) {
                i++;
                continue;
            }

            int start = i;
            while (i < line.length() && !Character.isWhitespace(line.charAt(i))) {
                i++;
            }

            tokens.add(new Token(start, i, line.substring(start, i)));
        }

        return tokens;
    }

    // arguments of a command

    private List<CommandArg> argsOf(Command command) {
        if (!this.resolved.containsKey(command)) {
            List<CommandArg> declared;

            try {
                declared = command.arguments();
                if (declared == null) {
                    declared = parseUsage(command);
                }
            } catch (RuntimeException e) {
                declared = null;
            }

            this.specs.put(command, declared);
            this.resolved.put(command, Boolean.TRUE);
        }

        return this.specs.get(command);
    }

    /**
     * the arguments for the word at tokens[index], which for a command with subcommands depends on the
     * words before it. a command that doesn't say falls back to its fixed list (argsOf)
     */
    private List<CommandArg> specFor(Command command, List<Token> tokens, int index) {
        String[] given = new String[Math.max(0, index - 1)];
        for (int i = 1; i < index && i < tokens.size(); i++) {
            given[i - 1] = tokens.get(i).text;
        }

        List<CommandArg> dynamic;
        try {
            dynamic = command.arguments(given);
        } catch (RuntimeException e) {
            dynamic = null;
        }

        return dynamic != null ? dynamic : argsOf(command);
    }

    private static CommandArg argAt(List<CommandArg> spec, int index) {
        if (index < spec.size()) {
            return spec.get(index);
        }

        CommandArg last = spec.isEmpty() ? null : spec.get(spec.size() - 1);
        return last != null && last.isRest() ? last : null;
    }

    private static String usageLine(Command command) {
        for (String line : command.help()) {
            String t = line.trim();
            if (t.regionMatches(true, 0, "usage:", 0, 6)) {
                return t;
            }
        }

        return command.description();
    }

    // reads "Usage: name <a> [b|c] [rest...]", null on anything else
    private static List<CommandArg> parseUsage(Command command) {
        String usage = null;

        for (String line : command.help()) {
            String t = line.trim();
            if (t.regionMatches(true, 0, "usage:", 0, 6)) {
                if (usage != null) {
                    return null;   // several forms of the command, too ambiguous to check
                }
                usage = t.substring(6).trim();
            }
        }

        if (usage == null || usage.isEmpty()) {
            return null;
        }

        String[] parts = usage.split("\\s+");
        if (!parts[0].equalsIgnoreCase(command.name())) {
            return null;
        }

        List<CommandArg> out = new ArrayList<>();

        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            boolean optional = p.length() > 2 && p.startsWith("[") && p.endsWith("]");
            boolean required = p.length() > 2 && p.startsWith("<") && p.endsWith(">");

            if (!optional && !required) {
                return null;
            }

            String inner = p.substring(1, p.length() - 1);
            boolean rest = inner.endsWith("...");
            if (rest) {
                inner = inner.substring(0, inner.length() - 3);
            }

            if (inner.isEmpty()) {
                return null;
            }

            String[] alternatives = inner.split("\\|");
            boolean fixed = alternatives.length >= 2;

            for (String alternative : alternatives) {
                if (!alternative.matches("[a-z]+")) {
                    fixed = false;
                }
            }

            // a fixed set is only trusted to be exhaustive when the usage line says so, and a
            // command may still take "-" for "skip", so that is let through
            CommandArg arg = fixed
                    ? CommandArg.choice(inner, alternatives).skippable()
                    : CommandArg.word(inner);

            if (optional) {
                arg.optional();
            }
            if (rest) {
                arg.rest();
            }

            out.add(arg);
        }

        return out;
    }

    // highlighting

    /** a colour for every character of line (NORMAL where it should keep the input's colour) */
    public int[] highlight(String line, int cursor) {
        int[] colors = new int[line.length()];
        Arrays.fill(colors, NORMAL);

        List<Token> tokens = tokenize(line);
        if (tokens.isEmpty()) {
            return colors;
        }

        Token first = tokens.get(0);
        Command command = this.find.apply(first.text);

        if (command != null) {
            paint(colors, first, COMMAND);
        } else if (first.touches(cursor) && !matchingCommands(first.text).isEmpty()) {
            paint(colors, first, COMMAND_PARTIAL);
        } else {
            paint(colors, first, ERROR);
        }

        if (command == null) {
            for (int i = 1; i < tokens.size(); i++) {
                paint(colors, tokens.get(i), DIM);
            }
            return colors;
        }

        for (int i = 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            List<CommandArg> spec = specFor(command, tokens, i);

            // "<command> help" is handled by the console itself, whatever the command takes
            if (i == 1 && tokens.size() == 2 && token.text.equalsIgnoreCase("help")) {
                paint(colors, token, CHOICE);
                continue;
            }

            if (spec == null) {
                paint(colors, token, ARG);
                continue;
            }

            CommandArg arg = argAt(spec, i - 1);

            if (arg == null) {
                paint(colors, token, ERROR);   // more arguments than the command takes
            } else if (arg.accepts(token.text)) {
                paint(colors, token, colorOf(arg, token.text));
            } else if (token.touches(cursor) && arg.couldStart(token.text)) {
                paint(colors, token, colorOf(arg, token.text));   // still typing it
            } else {
                paint(colors, token, ERROR);
            }
        }

        return colors;
    }

    private static int colorOf(CommandArg arg, String text) {
        if (arg.isSkippable() && text.equals("-")) {
            return SKIPPED;
        }

        switch (arg.kind()) {
            case CHOICE:
                if (arg.isNumeric(text)) {
                    return NUMBER;
                }
                return arg.isChoice(text) ? CHOICE : ARG;
            case INTEGER:
            case NUMBER:
                return NUMBER;
            default:
                return ARG;
        }
    }

    private static void paint(int[] colors, Token token, int color) {
        for (int i = token.start; i < token.end; i++) {
            colors[i] = color;
        }
    }

    // suggestions

    public static final class Suggestion {
        /** the word being completed, [start, end) in the line */
        public final int start;
        public final int end;
        /** what has been typed of it up to the cursor */
        public final String prefix;
        public final List<String> options;
        /** a short description per option, "" where there is none */
        public final List<String> notes;
        /** usage of the command being typed, null if there is none to show */
        public final String hint;
        /** whether the options are command names (as opposed to argument values) */
        public final boolean commandNames;

        Suggestion(int start, int end, String prefix, List<String> options, List<String> notes,
                   String hint, boolean commandNames) {
            this.start = start;
            this.end = end;
            this.prefix = prefix;
            this.options = options;
            this.notes = notes;
            this.hint = hint;
            this.commandNames = commandNames;
        }
    }

    private List<Command> matchingCommands(String prefix) {
        String lower = prefix.toLowerCase();
        List<Command> out = new ArrayList<>();

        for (Command command : this.all.get()) {
            if (command.name().toLowerCase().startsWith(lower)) {
                out.add(command);
            }
        }

        return out;
    }

    /** what could be typed at the cursor, options empty when nothing is known */
    public Suggestion suggest(String line, int cursor) {
        cursor = Math.max(0, Math.min(line.length(), cursor));

        List<Token> tokens = tokenize(line);

        Token current = null;
        int index = 0;

        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.touches(cursor)) {
                current = t;
                index = i;
                break;
            }
            if (t.end < cursor) {
                index = i + 1;
            }
        }

        int start = current != null ? current.start : cursor;
        int end = current != null ? current.end : cursor;
        String prefix = line.substring(start, cursor);

        List<String> options = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String hint = null;

        if (index == 0) {
            List<Command> matches = matchingCommands(prefix);

            for (Command command : matches) {
                options.add(command.name());
                notes.add(command.description());
            }

            if (matches.size() == 1) {
                hint = usageLine(matches.get(0));
            }

            return new Suggestion(start, end, prefix, options, notes, hint, true);
        }

        Command command = this.find.apply(tokens.get(0).text);

        if (command == null) {
            return new Suggestion(start, end, prefix, Collections.<String>emptyList(),
                    Collections.<String>emptyList(), null, false);
        }

        hint = usageLine(command);

        List<CommandArg> spec = specFor(command, tokens, index);
        CommandArg arg = spec == null ? null : argAt(spec, index - 1);

        if (arg != null) {
            for (String option : arg.suggestions(prefix)) {
                options.add(option);
                notes.add(arg.noteFor(option));
            }
        }

        return new Suggestion(start, end, prefix, options, notes, hint, false);
    }
}
