package com.nikohalfkino.rubydung.player;

import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.phys.AABB;
import com.nikohalfkino.rubydung.util.KeyBinding;
import com.nikohalfkino.rubydung.util.KeyMap;

import java.util.List;

public class Player {

    public static final double EYE_HEIGHT = 1.62D;

    // highest ledge walked up without jumping (slabs are 0.5, full blocks 1.0)
    private static final double STEP_HEIGHT = 0.6D;

    private final Level level;

    public double x, y, z;
    public double prevX, prevY, prevZ;
    public double motionX, motionY, motionZ;
    public float xRotation, yRotation;

    private boolean onGround;

    public AABB boundingBox;

    public boolean noClip = false;

    public boolean flying = false;

    // way the body (player sprite) faces, same degrees as yRotation. in first person it's the look direction, otherwise it turns toward where the player walks, or the aim while breaking/placing
    // independent of where the camera looks
    public float bodyYaw, prevBodyYaw;

    // set by PlayerCamera: the body copies yRotation (first person) instead of turning on its own
    public boolean bodyFollowsLook = true;

    // set by PlayerCamera in freecam: movement keys are ignored (gravity and collisions still run)
    public boolean controlsLocked = false;

    private static final float BODY_TURN_PER_TICK = 40.0F;
    private static final double BODY_MOVE_EPSILON_SQ = 1.0E-5D;

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

    // random columns tried for a dry spawn before giving up and taking the first one
    private static final int SPAWN_TRIES = 256;

    private void resetPosition() {
        int bestX = 0, bestZ = 0, bestGround = -1;

        for (int i = 0; i < SPAWN_TRIES; i++) {
            int x = (int) (Math.random() * this.level.width);
            int z = (int) (Math.random() * this.level.height);
            int ground = findGround(x, z);

            if (i == 0) {
                bestX = x;
                bestZ = z;
                bestGround = ground;   // fallback if nothing dry turns up
            }
            if (ground >= 0 && isDry(x, ground, z)) {
                bestX = x;
                bestZ = z;
                bestGround = ground;
                break;
            }
        }

        float y = (bestGround < 0 ? 0 : bestGround) + 1 + (float) EYE_HEIGHT + 0.4f;
        setPosition(bestX + 0.5F, y, bestZ + 0.5F);
    }

    /** y of the highest solid cell in the column, -1 if there is none */
    private int findGround(int x, int z) {
        for (int y = this.level.depth - 1; y > 0; y--) {
            if (this.level.isSolidTile(x, y, z)) {
                return y;
            }
        }
        return this.level.isSolidTile(x, 0, z) ? 0 : -1;
    }

    // liquids live in their own layer, so isSolidTile knows nothing about them: a column under the sea has a solid
    // sea floor and would otherwise be a fine spawn. The ground cell and the three cells of the body above it must be dry.
    private boolean isDry(int x, int groundY, int z) {
        for (int y = groundY; y <= groundY + 3 && y < this.level.depth; y++) {
            if (this.level.getLiquids().has(x, y, z)) {
                return false;
            }
        }
        return true;
    }

    public void setPositionAndRotation(double x, double y, double z, float yRotation, float xRotation) {
        setPosition((float) x, (float) y, (float) z);

        this.yRotation = yRotation;
        this.xRotation = xRotation;
        this.bodyYaw = yRotation;
        this.prevBodyYaw = yRotation;

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
        tickMovement();
        updateBody();
    }

    /** body yaw for rendering, blended between the last two ticks (short way round) */
    public float getBodyYaw(float partialTicks) {
        return this.prevBodyYaw + wrapDegrees(this.bodyYaw - this.prevBodyYaw) * partialTicks;
    }

    private void updateBody() {
        this.prevBodyYaw = this.bodyYaw;

        if (this.bodyFollowsLook) {
            this.bodyYaw = this.yRotation;
            this.prevBodyYaw = this.yRotation;
            return;
        }

        float target;
        double dx = this.x - this.prevX;
        double dz = this.z - this.prevZ;

        if (!this.controlsLocked && (KeyBinding.BREAK_BLOCK.isDown() || KeyBinding.PLACE_BLOCK.isDown())) {
            target = this.yRotation; // working on a block: face it
        } else if (dx * dx + dz * dz > BODY_MOVE_EPSILON_SQ) {
            // Same convention as the look direction: forward is (sin yaw, -cos yaw).
            target = (float) Math.toDegrees(Math.atan2(dx, -dz));
        } else {
            return;
        }

        float diff = wrapDegrees(target - this.bodyYaw);
        diff = Math.max(-BODY_TURN_PER_TICK, Math.min(BODY_TURN_PER_TICK, diff));
        this.bodyYaw += diff;
    }

    private static float wrapDegrees(float degrees) {
        degrees %= 360.0F;
        if (degrees >= 180.0F) {
            degrees -= 360.0F;
        }
        if (degrees < -180.0F) {
            degrees += 360.0F;
        }
        return degrees;
    }

    private void tickMovement() {
        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;

        if (!(this.noClip || this.flying)) {
            this.flySpeedMultiplier = 1.0F;
        }

        float currentFlySpeed = BASE_FLY_SPEED * this.flySpeedMultiplier;
        float forward = 0.0F;
        float vertical = 0.0F;

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

        if (this.controlsLocked) {
            forward = 0.0F;
            vertical = 0.0F;
            ascendHeld = false;
            descendHeld = false;
        }

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

        boolean wasOnGround = this.onGround;
        AABB startBox = this.boundingBox.clone();

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

        // Blocked sideways while standing on the ground (or just having landed): try the same
        // horizontal move again from STEP_HEIGHT higher and, if that gets further, drop back down
        // onto whatever is there. This is what lets the player walk up slabs without jumping.
        boolean stepped = false;
        boolean landed = prevY < 0.0D && prevY != y;
        boolean blocked = prevX != x || prevZ != z;
        if (blocked && (wasOnGround || landed) && !this.flying && y <= 0.0D) {
            double stepX = prevX;
            double stepZ = prevZ;
            AABB stepBox = startBox.clone();
            List<AABB> stepCubes = this.level.getCubes(startBox.expand(prevX, STEP_HEIGHT, prevZ));

            double up = STEP_HEIGHT;
            for (AABB abb : stepCubes) {
                up = abb.clipYCollide(stepBox, up);
            }
            stepBox.move(0.0D, up, 0.0D);

            for (AABB abb : stepCubes) {
                stepX = abb.clipXCollide(stepBox, stepX);
            }
            stepBox.move(stepX, 0.0D, 0.0D);

            for (AABB abb : stepCubes) {
                stepZ = abb.clipZCollide(stepBox, stepZ);
            }
            stepBox.move(0.0D, 0.0D, stepZ);

            // Back down by what was climbed, so the player ends up standing on the ledge.
            double down = -up;
            for (AABB abb : stepCubes) {
                down = abb.clipYCollide(stepBox, down);
            }
            stepBox.move(0.0D, down, 0.0D);

            double normalDist = x * x + z * z;
            double stepDist = stepX * stepX + stepZ * stepZ;
            double climbed = stepBox.minY - startBox.minY;

            if (stepDist > normalDist + 1.0E-9D && climbed > 1.0E-6D) {
                this.boundingBox = stepBox;
                x = stepX;
                z = stepZ;
                y = climbed;
                stepped = true;
                this.motionY = 0.0D;
            }
        }

        this.onGround = stepped || (prevY != y && prevY < 0.0F);

        if (prevX != x) this.motionX = 0.0D;
        if (prevY != y && !stepped) this.motionY = 0.0D;
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