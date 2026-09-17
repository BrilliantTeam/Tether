package dev.naspo.tether.services;

import dev.naspo.tether.Tether;
import dev.naspo.tether.exceptions.NoPermissionException;
import dev.naspo.tether.exceptions.leashexception.LeashErrorType;
import dev.naspo.tether.exceptions.leashexception.LeashException;
import io.papermc.paper.entity.Leashable;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// Responsible for logic related to leashing mobs.
public class LeashMobService {
    // How far a leash reaches before it snaps (12 blocks since 1.21.6, 10 before).
    private static final double LEASH_LENGTH = 12;

    private final Tether plugin;
    private final ClaimCheckService claimCheckService;

    public LeashMobService(Tether plugin, ClaimCheckService claimCheckService) {
        this.plugin = plugin;
        this.claimCheckService = claimCheckService;
    }

    /**
     * Have the player leash a mob if they are allowed.
     * Checks things like current land claims, player permissions, and more.
     *
     * @param player The player to be the leash holder.
     * @param entity The non-player LivingEntity to be leashed. (Not `Mob` because NPCs are supported).
     * @throws InvalidParameterException if the LivingEntity passed in is a Player.
     * @throws NoPermissionException     if the player does not have permission.
     * @throws LeashException            when the leash operation fails for a given reason (LeashErrorType).
     */
    public void playerLeashMob(Player player, LivingEntity entity) throws InvalidParameterException,
            NoPermissionException, LeashException {
        checkCanLeash(player, entity);

        // Leashing the mob.
        // The actual leashing process has to run in a scheduler with a slight delay,
        // due to the way the event works.
        entity.getScheduler().runDelayed(plugin, task -> {
            // Vanilla leashes mobs it supports itself (we let PlayerLeashEntityEvent through for those),
            // which also takes the lead and fires vanilla's side effects, such as the
            // husbandry/leash_all_frog_variants advancement criteria. Nothing left to do here.
            if (entity.isLeashed() && entity.getLeashHolder() instanceof Player) return;

            // Vanilla wouldn't leash this mob, so Tether does it, and takes the lead itself.
            ItemStack held = player.getInventory().getItemInMainHand();
            if (held.getType() != Material.LEAD) return;
            // Since 1.21.6 a lead also takes a mob off a fence or another mob, which drops the lead that tied it there.
            boolean wasLeashed = entity.isLeashed();
            if (!leashTo(player, entity, player)) return;
            if (wasLeashed) entity.getWorld().dropItemNaturally(entity.getLocation(), new ItemStack(Material.LEAD));
            player.getInventory().setItemInMainHand(held.subtract());
        }, null, 1L);
    }

    /**
     * Checks whether Tether allows this player to leash this entity.
     *
     * @throws InvalidParameterException if the LivingEntity passed in is a Player.
     * @throws LeashException            when the leash is not allowed (LeashErrorType).
     */
    public void checkCanLeash(Player player, LivingEntity entity) throws InvalidParameterException, LeashException {
        if (entity instanceof Player) throw new InvalidParameterException();

        // Blacklist/whitelist check.
        if (isEntityRestricted(entity)) throw new LeashException(LeashErrorType.MOB_RESTRICTED);

        // Claim checks.
        if (!claimCheckService.canLeashMob(entity, player))
            throw new LeashException(LeashErrorType.LAND_CLAIM_RESTRICTION);

        // If the entity is a Citizens NPC, check if it can be leashed.
        if (entity.hasMetadata("NPC")) {
            net.citizensnpcs.api.npc.NPC npc = CitizensAPI.getNPCRegistry().getNPC(entity);
            // If the NPC cannot be leashed, return.
            if (npc.data().get(NPC.Metadata.LEASH_PROTECTED, true)) {
                throw new LeashException(LeashErrorType.NPC_UNLEASHABLE);
            }
        }
    }

    /**
     * Deals with leashing mobs to and from a fence.
     *
     * @param player   The player that right-clicked the fence or leash hitch.
     * @param location The location of the fence or leash hitch.
     * @return Whether any leash was moved. The click is then Tether's, and vanilla must not handle it as well.
     */
    public boolean handleFenceLeashing(Player player, Location location) {
        // Leashing mobs to a fence:
        List<Leashable> heldMobs = getMobsLeashedByPlayer(player);
        if (!heldMobs.isEmpty()) {
            return transferMobsFromPlayerToFence(player, heldMobs, location);
        }

        // Transfer mobs from fence to player (like vanilla, not while sneaking):
        return !player.isSneaking() && transferMobsFromFenceToPlayer(player, location);
    }

    /**
     * Deals with sneak-interaction, specifically looks for leashing mobs together and will
     * do so if applicable.
     *
     * @param player The player who sneak-interacted with an entity.
     * @param entity The LivingEntity that was sneak-interacted with. (Not `Mob` because NPCs are supported).
     * @return Whether any mob was leashed to the entity. The click is then Tether's, and vanilla must not
     * also leash or unleash the entity.
     */
    public boolean handleSneakInteract(Player player, LivingEntity entity) {
        if (entity instanceof Player) return false;

        boolean leashed = false;
        for (Leashable mob : getMobsLeashedByPlayer(player)) {
            if (!mob.equals(entity)) leashed |= leashTo(player, mob, entity);
        }
        return leashed;
    }

    // Checks the whitelist or blacklist to see whether the entity is restricted from being leashed or not.
    public boolean isEntityRestricted(Entity entity) {
        // Use whitelist check.
        // If whitelist is set to be used over blacklist, check the whitelist only, else use blacklist.
        if (plugin.getConfig().getBoolean("use-whitelist-over-blacklist")) {
            // Whitelist check.
            // Getting whitelist values and converting all to uppercase.
            List<String> whitelist = plugin.getConfig().getStringList("whitelisted-mobs")
                    .stream().map(String::toUpperCase).collect(Collectors.toList());

            if (!whitelist.contains(entity.getType().name())) {
                return true;
            }
        } else {
            // Blacklist check.
            // Getting blacklist values and converting all to uppercase.
            List<String> blacklist = plugin.getConfig().getStringList("blacklisted-mobs")
                    .stream().map(String::toUpperCase).collect(Collectors.toList());

            if (blacklist.contains(entity.getType().name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves the entity's leash to the holder if PlayerLeashEntityEvent, the same event vanilla fires, allows it.
     * That way protection plugins (and Tether's own checks, see PlayerLeashEntityListener) apply to Tether too.
     */
    private boolean leashTo(Player player, Entity entity, Entity holder) {
        return entity instanceof Leashable leashable
                && new PlayerLeashEntityEvent(entity, holder, player, EquipmentSlot.HAND).callEvent()
                && leashable.setLeashHolder(holder);
    }

    // Also includes boats, which can be leashed since 1.21.6.
    private List<Leashable> getMobsLeashedByPlayer(Player player) {
        List<Leashable> leashedMobs = new ArrayList<>();
        for (Entity entity : player.getNearbyEntities(LEASH_LENGTH, LEASH_LENGTH, LEASH_LENGTH)) {
            if (entity instanceof Leashable mob && mob.isLeashed() && mob.getLeashHolder().equals(player)) {
                leashedMobs.add(mob);
            }
        }
        return leashedMobs;
    }

    /**
     * @param location The location of the fence or leash hitch.
     * @return The leash hitch on that fence, or null if there is none.
     */
    private LeashHitch getLeashHitch(Location location) {
        for (Entity entity : location.getWorld().getNearbyEntities(location, 1, 1, 1)) {
            if (entity instanceof LeashHitch leashHitch) {
                return leashHitch;
            }
        }
        return null;
    }

    /**
     * @param location The location of the fence or leash hitch.
     * @return The list of mobs leashed to that fence.
     */
    private List<Leashable> getMobsLeashedToFence(Location location) {
        List<Leashable> leashedMobs = new ArrayList<>();

        // If there is a leash hitch, find all entities leashed to it.
        LeashHitch leashHitch = getLeashHitch(location);
        if (leashHitch != null) {
            for (Entity entity : leashHitch.getNearbyEntities(LEASH_LENGTH, LEASH_LENGTH, LEASH_LENGTH)) {
                if (entity instanceof Leashable mob && mob.isLeashed() && mob.getLeashHolder().equals(leashHitch)) {
                    leashedMobs.add(mob);
                }
            }
        }
        return leashedMobs;
    }

    private boolean transferMobsFromFenceToPlayer(Player player, Location fenceLocation) {
        boolean moved = false;
        for (Leashable mob : getMobsLeashedToFence(fenceLocation)) {
            moved |= leashTo(player, mob, player);
        }
        return moved;
    }

    private boolean transferMobsFromPlayerToFence(Player player, List<Leashable> leashedMobs, Location fenceLocation) {
        // If there is no leash hitch on the fence we have to create one.
        LeashHitch leashHitch = getLeashHitch(fenceLocation);
        boolean created = leashHitch == null;
        if (created) {
            // The location that the hitch should be. Cloning as to not modify the fenceLocation value.
            // 0.5 is added to properly visually align the hitch.
            Location hitchLocation = fenceLocation.clone().add(0.5, 0.5, 0.5);
            leashHitch = (LeashHitch) fenceLocation.getWorld().spawnEntity(hitchLocation, EntityType.LEASH_KNOT);
        }

        boolean moved = false;
        for (Leashable mob : leashedMobs) {
            moved |= leashTo(player, mob, leashHitch);
        }
        // Like vanilla, don't leave an empty hitch behind if no leash was allowed.
        if (created && !moved) leashHitch.remove();
        return moved;
    }
}
