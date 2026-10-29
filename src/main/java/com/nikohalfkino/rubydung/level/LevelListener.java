package com.nikohalfkino.rubydung.level;

public interface LevelListener {
    void lightColumnChanged(int x, int z, int minY, int maxY);

    void tileChanged(int x, int y, int z);

    void allChanged();
}