package com.nikohalfkino.rubydung.console;

import com.nikohalfkino.rubydung.interfaces.widget.TextField;

import java.util.Collections;
import java.util.List;

/**
 * tab completion and the suggestion list for a command input, shared by the chat (".command") and the console
 *
 * Tab on the only match finishes the word, on several fills in what they have in common, and when
 * they agree on nothing more steps through them (Shift+Tab goes back). any other edit ends the stepping
 */
public final class CommandCompleter {

    /** what a suggestion box shows, see popup() */
    public static final class Popup {
        public final List<String> options;
        /** a short description per option, "" where there is none */
        public final List<String> notes;
        /** whether the options are command names (as opposed to argument values) */
        public final boolean commandNames;
        /** usage of the command being typed, null if there is none */
        public final String hint;
        /** the option Tab has stepped to, -1 for none */
        public final int selected;

        Popup(List<String> options, List<String> notes, boolean commandNames, String hint, int selected) {
            this.options = options;
            this.notes = notes;
            this.commandNames = commandNames;
            this.hint = hint;
            this.selected = selected;
        }
    }

    private final TextField input;
    private final CommandSyntax syntax;
    // text the command line starts after, "." in the chat and nothing in the console
    private final String lead;

    // the matches Tab is stepping through, which one is in the input now, and the input as it stood after
    // the last step (changing it any other way ends the stepping)
    private List<String> cycleOptions = null;
    private List<String> cycleNotes = null;
    private boolean cycleNames = false;
    private int cycleIndex = 0;
    private int cycleStart = 0;
    private String cycleText = null;

    public CommandCompleter(TextField input, CommandSyntax syntax, String lead) {
        this.input = input;
        this.syntax = syntax;
        this.lead = lead;
    }

    /** forget any stepping, call when the input is sent or replaced */
    public void reset() {
        this.cycleOptions = null;
        this.cycleNotes = null;
        this.cycleText = null;
    }

    private boolean cycling() {
        return this.cycleOptions != null && this.input.getText().equals(this.cycleText);
    }

    /** direction 1 = Tab, -1 = Shift+Tab */
    public void tab(int direction) {
        String text = this.input.getText();

        if (!text.startsWith(this.lead)) {
            return;
        }

        // already stepping through matches: next / previous one
        if (cycling()) {
            int size = this.cycleOptions.size();
            this.cycleIndex = Math.floorMod(this.cycleIndex + direction, size);

            int end = this.cycleStart + wordLength(text, this.cycleStart);
            replace(this.cycleStart, end, this.cycleOptions.get(this.cycleIndex));
            this.cycleText = this.input.getText();
            return;
        }

        reset();

        int offset = this.lead.length();
        int cursor = this.input.getCursorPosition();
        if (cursor < offset) {
            return;
        }

        CommandSyntax.Suggestion s = this.syntax.suggest(text.substring(offset), cursor - offset);
        if (s.options.isEmpty()) {
            return;
        }

        int start = s.start + offset;
        int end = s.end + offset;

        if (s.options.size() == 1) {
            // the only match: finish the word and move on to the next one
            replace(start, end, s.options.get(0) + (end >= text.length() ? " " : ""));
            return;
        }

        String common = commonPrefix(s.options);

        if (common.length() > s.prefix.length()) {
            // the matches agree on more than what is typed: fill in that much
            replace(start, end, common);
            return;
        }

        this.cycleOptions = s.options;
        this.cycleNotes = s.notes;
        this.cycleNames = s.commandNames;
        this.cycleStart = start;
        this.cycleIndex = direction > 0 ? 0 : s.options.size() - 1;

        replace(start, end, this.cycleOptions.get(this.cycleIndex));
        this.cycleText = this.input.getText();
    }

    /** what a suggestion box should show for the input as it is now, null for nothing */
    public Popup popup() {
        String text = this.input.getText();

        if (!text.startsWith(this.lead)) {
            return null;
        }

        // an empty console line would list every command all the time
        if (this.lead.isEmpty() && text.trim().isEmpty()) {
            return null;
        }

        if (cycling()) {
            return new Popup(this.cycleOptions, this.cycleNotes, this.cycleNames, null, this.cycleIndex);
        }

        int offset = this.lead.length();
        int cursor = this.input.getCursorPosition();
        if (cursor < offset) {
            return null;
        }

        CommandSyntax.Suggestion s = this.syntax.suggest(text.substring(offset), cursor - offset);
        List<String> options = s.options;

        // nothing to add when the one match is exactly what is already typed
        if (options.size() == 1 && options.get(0).equalsIgnoreCase(s.prefix)) {
            options = Collections.emptyList();
        }

        if (options.isEmpty() && s.hint == null) {
            return null;
        }

        return new Popup(options, s.notes, s.commandNames, s.hint, -1);
    }

    /** replaces [start, end) of the input with value and puts the cursor after it */
    private void replace(int start, int end, String value) {
        String text = this.input.getText();

        this.input.setText(text.substring(0, start) + value + text.substring(end));
        this.input.setCursorPosition(start + value.length());
    }

    private static int wordLength(String text, int start) {
        int end = start;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return end - start;
    }

    private static String commonPrefix(List<String> options) {
        String first = options.get(0);
        int length = first.length();

        for (String option : options) {
            int i = 0;
            while (i < length && i < option.length()
                    && Character.toLowerCase(first.charAt(i)) == Character.toLowerCase(option.charAt(i))) {
                i++;
            }
            length = i;
        }

        return first.substring(0, length);
    }
}
