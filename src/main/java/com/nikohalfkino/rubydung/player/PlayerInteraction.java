package com.nikohalfkino.rubydung.player;

import com.nikohalfkino.rubydung.HitResult;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.Tiles.RotatableTile;
import com.nikohalfkino.rubydung.level.Tiles.Tile;
import com.nikohalfkino.rubydung.phys.AABB;
import com.nikohalfkino.rubydung.util.KeyBinding;

import java.util.List;

public class PlayerInteraction {

    private final Level level;
    private final Player player;

    private static final int REPEAT_INTERVAL_TICKS = 8;
    private int breakCooldown = 0;
    private int placeCooldown = 0;

    private boolean breakSuppressedUntilRelease = false;
    private boolean placeSuppressedUntilRelease = false;

    private HitResult hitResult;

    private Tile selectedTile = Tile.GRASS;

    public PlayerInteraction(Level level, Player player) {
        this.level = level;
        this.player = player;
    }

    public HitResult getHitResult() {
        return this.hitResult;
    }

    public void setHitResult(HitResult hitResult) {
        this.hitResult = hitResult;
    }

    public Tile getSelectedTile() {
        return this.selectedTile;
    }

    public void setSelectedTile(Tile tile) {
        this.selectedTile = tile;
    }

    private int getPlacementDirection() {
        int rot = (int) Math.floor((this.player.yRotation * 4.0F / 360.0F) + 0.5D) & 3;
        return (4 - rot) & 3;
    }

    public void handleClick(int button, boolean pressed) {
        if (!pressed || this.hitResult == null) {
            return;
        }

        if (button == 0) {
            breakTargetedTile();
        } else if (button == 1) {
            placeTargetedTile();
        }
    }

    private void breakTargetedTile() {
        this.level.setTile(this.hitResult.x, this.hitResult.y, this.hitResult.z, 0);
    }

    public void pickTargetedTile() {
        if (this.hitResult == null) {
            return;
        }

        int tileId = this.level.getTile(this.hitResult.x, this.hitResult.y, this.hitResult.z);
        if (tileId > 0) {
            for (Tile tile : Tile.getTiles()) {
                if (tile.id == tileId) {
                    this.selectedTile = tile;
                    break;
                }
            }
        }
    }

    private void placeTargetedTile() {
        int x = this.hitResult.x;
        int y = this.hitResult.y;
        int z = this.hitResult.z;

        if (this.hitResult.face == 0) y--;
        if (this.hitResult.face == 1) y++;
        if (this.hitResult.face == 2) z--;
        if (this.hitResult.face == 3) z++;
        if (this.hitResult.face == 4) x--;
        if (this.hitResult.face == 5) x++;

        AABB placedTileBox = this.selectedTile.getAABB(x, y, z);
        if (placedTileBox == null) {
            placedTileBox = new AABB(x, y, z, x + 1, y + 1, z + 1);
        }

        if (!placedTileBox.intersects(this.player.boundingBox)) {
            int axis = 0;

            if (this.selectedTile instanceof RotatableTile) {
                int hitFace = this.hitResult.face;
                if (hitFace == 0 || hitFace == 1) axis = 0;
                if (hitFace == 2 || hitFace == 3) axis = 1;
                if (hitFace == 4 || hitFace == 5) axis = 2;
            }

            this.level.setTile(x, y, z, this.selectedTile.id, axis);
        }
    }

    public void suppressHeldButtonsUntilReleased() {
        if (KeyBinding.BREAK_BLOCK.isDown()) {
            this.breakSuppressedUntilRelease = true;
        }
        if (KeyBinding.PLACE_BLOCK.isDown()) {
            this.placeSuppressedUntilRelease = true;
        }
    }

    public void tick() {
        if (KeyBinding.BREAK_BLOCK.isDown() && this.hitResult != null) {
            if (this.breakSuppressedUntilRelease) {
                breakCooldown = 0;
            } else if (breakCooldown == 0) {
                breakTargetedTile();
                breakCooldown = REPEAT_INTERVAL_TICKS;
            }
            breakCooldown--;
        } else {
            breakCooldown = 0;
            this.breakSuppressedUntilRelease = false;
        }

        if (KeyBinding.PLACE_BLOCK.isDown() && this.hitResult != null) {
            if (this.placeSuppressedUntilRelease) {
                placeCooldown = 0;
            } else if (placeCooldown == 0) {
                placeTargetedTile();
                placeCooldown = REPEAT_INTERVAL_TICKS;
            }
            placeCooldown--;
        } else {
            placeCooldown = 0;
            this.placeSuppressedUntilRelease = false;
        }
    }

    public void cycleSelectedTile(int direction) {
        List<Tile> tiles = Tile.getCycleOrder();
        int size = tiles.size();
        int index = tiles.indexOf(this.selectedTile);

        index = (index + direction + size) % size;

        this.selectedTile = tiles.get(index);
    }
}