package com.nikohalfkino.rubydung.console;

import com.nikohalfkino.rubydung.RubyDung;

public interface Command {

    String name();

    String description();

    void execute(RubyDung game, Console console, String[] args);
}
