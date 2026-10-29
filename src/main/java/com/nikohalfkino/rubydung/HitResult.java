package com.nikohalfkino.rubydung;

public class HitResult {

    public int x;
    public int y;
    public int z;

    public int type;
    public int face;

    public HitResult(int x, int y, int z, int type, int face) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.type = type;
        this.face = face;
    }
}
