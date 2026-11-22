package rw.lag;

import org.bukkit.entity.*;

enum Group {
    HOSTILE, ANIMAL, WATER, AMBIENT, VILLAGER, ITEM, XP, VEHICLE, TNT, OTHER;

    static Group of(Entity e) {
        if (e instanceof Item) return ITEM;
        if (e instanceof ExperienceOrb) return XP;
        if (e instanceof TNTPrimed) return TNT;
        if (e instanceof Vehicle && !(e instanceof LivingEntity)) return VEHICLE;
        if (e instanceof Enemy) return HOSTILE;
        if (e instanceof Villager || e instanceof WanderingTrader) return VILLAGER;
        if (e instanceof WaterMob) return WATER;
        if (e instanceof Ambient) return AMBIENT;
        if (e instanceof Animals) return ANIMAL;
        return OTHER;
    }

    boolean isMob() { return this == HOSTILE || this == ANIMAL || this == WATER || this == AMBIENT || this == VILLAGER; }
}
