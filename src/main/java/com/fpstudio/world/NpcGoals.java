package com.fpstudio.world;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/** The always-on behaviours of a world NPC. They all step aside while a script is running (the script drives the NPC then). */
final class NpcGoals {
    private NpcGoals() {}

    /** Follow the player a script told us to follow. */
    static final class Follow extends Goal {
        private final StudioNpc n;
        private int repath;

        Follow(StudioNpc n) {
            this.n = n;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override public boolean canUse() { return n.followTarget() != null && !n.frozen(); }

        @Override public boolean canContinueToUse() { return canUse(); }

        @Override public void tick() {
            Player p = n.followTarget();
            if (p == null) return;
            n.getLookControl().setLookAt(p, 30f, 30f);
            double d = n.distanceToSqr(p);
            float want = n.followDist();
            if (d > (double) (want * want)) {
                if (--repath <= 0) {
                    repath = 8;
                    n.getNavigation().moveTo(p, n.followRun() ? n.profile().runSpeedMul : 1.0);
                }
                n.setRunning(n.followRun());
            } else {
                n.getNavigation().stop();
                n.setRunning(false);
            }
        }

        @Override public void stop() { n.getNavigation().stop(); n.setRunning(false); }
    }

    /** Walk the route waypoint by waypoint. */
    static final class Route extends Goal {
        private final StudioNpc n;
        private int idx, dir = 1, waitLeft, fails;
        private boolean finished, waiting, started;

        Route(StudioNpc n) {
            this.n = n;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override public boolean canUse() {
            var p = n.profile();
            return !n.scriptBusy() && !n.frozen() && n.followTarget() == null && !finished
                    && !p.route.isEmpty() && !"none".equals(p.routeMode);
        }

        @Override public boolean canContinueToUse() { return canUse(); }

        @Override public void start() {
            var p = n.profile();
            if (idx >= p.route.size()) idx = 0;
            started = false;
            waiting = false;
        }

        private void go() {
            var p = n.profile();
            if (idx < 0 || idx >= p.route.size()) return;
            NpcProfile.Point pt = p.route.get(idx);
            boolean ok = n.getNavigation().moveTo(pt.x, pt.y, pt.z, p.routeRun ? p.runSpeedMul : 1.0);
            n.setRunning(p.routeRun);
            fails = ok ? 0 : fails + 1;
            started = true;
        }

        private void advance() {
            var p = n.profile();
            int size = p.route.size();
            switch (p.routeMode) {
                case "pingpong" -> {
                    if (size == 1) break;
                    if (idx + dir >= size || idx + dir < 0) dir = -dir;
                    idx += dir;
                }
                case "once" -> {
                    if (idx + 1 >= size) {
                        finished = true;
                        n.setRunning(false);
                        n.runScript("on_route_end", null);
                        return;
                    }
                    idx++;
                }
                default -> idx = (idx + 1) % size;
            }
            started = false;
            waiting = false;
        }

        @Override public void tick() {
            var p = n.profile();
            if (p.route.isEmpty()) return;
            if (idx >= p.route.size()) idx = 0;
            if (waiting) {
                if (--waitLeft <= 0) advance();
                return;
            }
            if (!started) { go(); }
            NpcProfile.Point pt = p.route.get(idx);
            double dx = pt.x - n.getX(), dz = pt.z - n.getZ();
            boolean near = dx * dx + dz * dz < 1.6 && Math.abs(pt.y - n.getY()) < 2.5;
            if (near || (fails >= 3)) {
                n.getNavigation().stop();
                n.setRunning(false);
                waiting = true;
                waitLeft = Math.max(1, Math.round(pt.wait * 20f));
                fails = 0;
                if (near && pt.script != null && !pt.script.isBlank()) n.runScript(pt.script, null);
            } else if (n.getNavigation().isDone()) {
                go();
            }
        }

        @Override public void stop() { n.setRunning(false); started = false; }
    }

    /** Idle wandering around the home point (radius from the profile). */
    static final class Wander extends WaterAvoidingRandomStrollGoal {
        private final StudioNpc n;

        Wander(StudioNpc n) {
            super(n, 1.0);
            this.n = n;
        }

        @Override public boolean canUse() {
            var p = n.profile();
            return p.wanderRadius > 0.5f && p.route.isEmpty() && !n.scriptBusy() && !n.frozen() && n.followTarget() == null && super.canUse();
        }

        @Override public boolean canContinueToUse() { return !n.scriptBusy() && !n.frozen() && super.canContinueToUse(); }
    }

    /** Turn the head towards the nearest player. */
    static final class LookAtPlayer extends Goal {
        private final StudioNpc n;
        private Player target;

        LookAtPlayer(StudioNpc n) {
            this.n = n;
            setFlags(EnumSet.of(Flag.LOOK));
        }

        @Override public boolean canUse() {
            var p = n.profile();
            if (!p.lookAtPlayer || n.lookOverride != null) return false;
            target = n.level().getNearestPlayer(n, Math.max(1f, p.lookRange));
            return target != null && !target.isSpectator();
        }

        @Override public boolean canContinueToUse() {
            return target != null && target.isAlive() && n.distanceToSqr(target) < (double) (n.profile().lookRange * n.profile().lookRange);
        }

        @Override public void tick() {
            if (target != null) n.getLookControl().setLookAt(target.getX(), target.getEyeY(), target.getZ(), 30f, 30f);
        }

        @Override public void stop() { target = null; }
    }
}
