package com.nikohalfkino.rubydung.level;

/**
 * hears every change made to a level that has to reach other players. The server (LAN host or dedicated) uses it to
 * broadcast, a client uses it to ask the server. Changes applied with the Level.apply...FromNetwork methods are not
 * reported, so a change that arrived from the network is never sent back.
 */
public interface LevelEditListener {

    /** a tile or its data byte changed. id and data are read back from the cell after the change, not what was asked for */
    void tileEdited(int x, int y, int z, int id, int data);

    /** a liquid tile was set through setTile (placing water). tileId is the liquid tile's id */
    void liquidPlaced(int x, int y, int z, int tileId);

    /** a cell of the liquid layer changed (flowing, placed, removed). tileId 0 means the cell is dry now */
    void liquidChanged(int x, int y, int z, int tileId, int fill);

    /** the sub-tiles of a cell changed */
    void subTilesEdited(int x, int y, int z);
}
