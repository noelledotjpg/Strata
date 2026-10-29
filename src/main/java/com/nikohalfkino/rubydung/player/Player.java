package com.nikohalfkino.rubydung.player;

import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.phys.AABB;
import com.nikohalfkino.rubydung.util.KeyBinding;
import com.nikohalfkino.rubydung.util.KeyMap;

import java.util.List;

public class Player {

    private static final double EYE_HEIGHT = 1.62D;

    private final Level level;

    public double x, y, z;
    public double prevX, prevY, prevZ;
    public double motionX, motionY, motionZ;
    public float xRotation, yRotation;

    private boolean onGround;

    public AABB boundingBox;

    public boolean noClip = false;

    public boolean flying = false;

    private static final float BASE_FLY_SPEED = 0.2F;

    private static final float BASE_GROUND_SPEED = 0.02F;
    private static final float BASE_AIR_SPEED = 0.005F;

    public float flySpeedMultiplier = 1.0F;

    public Player(Level level) {
        this.level = level;

        resetPosition();
    }

    private void setPosition(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;

        float width = 0.3F;
        float height = 1.8F;
        float feetY = y - (float) EYE_HEIGHT;

        this.boundingBox = new AABB(x - width, feetY,
                z - width, x + width,
                feetY + height, z + width);
    }

    private void resetPosition() {
        float x = (float) Math.random() * this.level.width;
        float z = (float) Math.random() * this.level.height;
        float y = findSpawnHeight((int) x, (int) z);

        setPosition(x, y, z);
    }

    private float findSpawnHeight(int x, int z) {
        int groundY = this.level.depth - 1;
        while (groundY > 0 && !this.level.isSolidTile(x, groundY, z)) {
            groundY--;
        }

        return groundY + 1 + (float) EYE_HEIGHT + 0.4f;
    }

    public void setPositionAndRotation(double x, double y, double z, float yRotation, float xRotation) {
        setPosition((float) x, (float) y, (float) z);

        this.yRotation = yRotation;
        this.xRotation = xRotation;

        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;
    }

    public void turn(float x, float y) {
        this.yRotation += x * 0.15F;
        this.xRotation -= y * 0.15F;

        this.xRotation = Math.max(-90.0F, this.xRotation);
        this.xRotation = Math.min(90.0F, this.xRotation);
    }

    public void adjustFlySpeed(int direction) {
        if (direction > 0) {
            this.flySpeedMultiplier *= 1.15F;
        } else if (direction < 0) {
            this.flySpeedMultiplier /= 1.15F;
        }
        this.flySpeedMultiplier = Math.max(0.1F, Math.min(20.0F, this.flySpeedMultiplier));
    }

    public void tick() {
        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;

        if (!(this.noClip || this.flying)) {
            this.flySpeedMultiplier = 1.0F;
        }

        float currentFlySpeed = BASE_FLY_SPEED * this.flySpeedMultiplier;
        float forward = 0.0F;
        float vertical = 0.0F;

        if (KeyMap.isDown(KeyMap.R)) {
            resetPosition();
        }

        if (KeyBinding.FORWARD.isDown() || KeyMap.isDown(KeyMap.UP)) {
            forward--;
        }
        if (KeyBinding.BACK.isDown() || KeyMap.isDown(KeyMap.DOWN)) {
            forward++;
        }
        if (KeyBinding.LEFT.isDown() || KeyMap.isDown(KeyMap.LEFT)) {
            vertical--;
        }
        if (KeyBinding.RIGHT.isDown() || KeyMap.isDown(KeyMap.RIGHT)) {
            vertical++;
        }

        boolean ascendHeld = KeyBinding.JUMP.isDown() || KeyMap.isDown(KeyMap.LWIN) || KeyMap.isDown(KeyMap.RWIN);
        boolean descendHeld = KeyBinding.SNEAK.isDown() || KeyMap.isDown(KeyMap.RSHIFT);

        if (this.noClip) {
            flyRelative(vertical, forward, currentFlySpeed);

            float ySpeed = 0.0F;
            if (ascendHeld) {
                ySpeed += currentFlySpeed;
            }
            if (descendHeld) {
                ySpeed -= currentFlySpeed;
            }
            this.motionY = ySpeed;

            move(this.motionX, this.motionY, this.motionZ);
            return;
        }

        if (this.flying) {
            moveRelative(vertical, forward, currentFlySpeed * 0.2F);

            if (ascendHeld) {
                this.motionY += currentFlySpeed * 0.2F;
            }
            if (descendHeld) {
                this.motionY -= currentFlySpeed * 0.2F;
            }

            move(this.motionX, this.motionY, this.motionZ);

            this.motionX *= 0.8F;
            this.motionY *= 0.8F;
            this.motionZ *= 0.8F;
            return;
        }

        if (ascendHeld) {
            if (this.onGround) {
                this.motionY = 0.14F;
            }
        }

        float walkSpeed = this.onGround ? BASE_GROUND_SPEED : BASE_AIR_SPEED;

        moveRelative(vertical, forward, walkSpeed);

        this.motionY -= 0.006D;

        move(this.motionX, this.motionY, this.motionZ);

        this.motionX *= 0.91F;
        this.motionY *= 0.98F;
        this.motionZ *= 0.91F;

        if (this.onGround) {
            this.motionX *= 0.8F;
            this.motionZ *= 0.8F;
        }
    }

    public void move(double x, double y, double z) {
        if (this.noClip) {
            this.boundingBox.move(x, y, z);
            this.onGround = false;

            this.x = (this.boundingBox.minX + this.boundingBox.maxX) / 2.0D;
            this.y = this.boundingBox.minY + EYE_HEIGHT;
            this.z = (this.boundingBox.minZ + this.boundingBox.maxZ) / 2.0D;
            return;
        }

        double prevX = x;
        double prevY = y;
        double prevZ = z;

        List<AABB> aABBs = this.level.getCubes(this.boundingBox.expand(x, y, z));

        for (AABB abb : aABBs) {
            y = abb.clipYCollide(this.boundingBox, y);
        }
        this.boundingBox.move(0.0F, y, 0.0F);

        for (AABB aABB : aABBs) {
            x = aABB.clipXCollide(this.boundingBox, x);
        }
        this.boundingBox.move(x, 0.0F, 0.0F);

        for (AABB aABB : aABBs) {
            z = aABB.clipZCollide(this.boundingBox, z);
        }
        this.boundingBox.move(0.0F, 0.0F, z);

        this.onGround = prevY != y && prevY < 0.0F;

        if (prevX != x) this.motionX = 0.0D;
        if (prevY != y) this.motionY = 0.0D;
        if (prevZ != z) this.motionZ = 0.0D;

        this.x = (this.boundingBox.minX + this.boundingBox.maxX) / 2.0D;
        this.y = this.boundingBox.minY + EYE_HEIGHT;
        this.z = (this.boundingBox.minZ + this.boundingBox.maxZ) / 2.0D;
    }


    private void moveRelative(float x, float z, float speed) {
        float distance = x * x + z * z;

        if (distance < 0.01F)
            return;

        distance = speed / (float) Math.sqrt(distance);
        x *= distance;
        z *= distance;

        double sin = Math.sin(Math.toRadians(this.yRotation));
        double cos = Math.cos(Math.toRadians(this.yRotation));

        this.motionX += x * cos - z * sin;
        this.motionZ += z * cos + x * sin;
    }

    private void flyRelative(float x, float z, float speed) {
        float distance = x * x + z * z;

        if (distance < 0.01F) {
            this.motionX = 0.0D;
            this.motionZ = 0.0D;
            return;
        }

        distance = speed / (float) Math.sqrt(distance);
        x *= distance;
        z *= distance;

        double sin = Math.sin(Math.toRadians(this.yRotation));
        double cos = Math.cos(Math.toRadians(this.yRotation));

        this.motionX = x * cos - z * sin;
        this.motionZ = z * cos + x * sin;
    }
}