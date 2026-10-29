package com.nikohalfkino.rubydung.util;

import com.nikohalfkino.rubydung.level.Tiles.Tile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal ordered registry: entries get a sequential numeric id purely from the order they're
 * registered in, nothing else. Used by {@link Tile} so block ids are never picked or kept in
 * sync by hand - registration order literally IS the id.
 * <p>
 * Typical use:
 * <pre>
 *     private static final Registry&lt;Tile&gt; REGISTRY = new Registry&lt;&gt;();
 *     public static final Tile AIR  = REGISTRY.register(new Tile("air"));
 *     public static final Tile DIRT = REGISTRY.register(new Tile("dirt"));
 * </pre>
 *
 * @param <T> The type of entry being registered
 */
public class Registry<T> {

    private final List<T> entries = new ArrayList<>();

    /**
     * The id that will be handed out to the next registered entry. Entries typically call this
     * themselves (before being registered) to know their own id - see {@link Tile#Tile}.
     *
     * @return Next free id
     */
    public int nextId() {
        return this.entries.size();
    }

    /**
     * Register an entry under the next free id. Returns the same entry so a single expression
     * can both register it and assign it to a field.
     *
     * @param entry The entry to register
     * @return The same entry, for chaining into a field assignment
     */
    public <E extends T> E register(E entry) {
        this.entries.add(entry);
        return entry;
    }

    /**
     * Look up an entry by its id
     *
     * @param id Registry id
     * @return The entry, or null if no entry has that id
     */
    public T get(int id) {
        if (id < 0 || id >= this.entries.size()) {
            return null;
        }
        return this.entries.get(id);
    }

    /**
     * @return All registered entries, in registration order
     */
    public List<T> entries() {
        return Collections.unmodifiableList(this.entries);
    }

    /**
     * @return How many entries are currently registered
     */
    public int size() {
        return this.entries.size();
    }
}