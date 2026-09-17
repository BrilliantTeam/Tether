package dev.naspo.tether.listeners;

import dev.naspo.tether.Tether;
import dev.naspo.tether.Utils;
import dev.naspo.tether.exceptions.NoPermissionException;
import dev.naspo.tether.exceptions.leashexception.LeashErrorType;
import dev.naspo.tether.exceptions.leashexception.LeashException;
import dev.naspo.tether.services.LeashMobService;
import dev.naspo.tether.services.LeashPlayerService;
import org.bukkit.Material;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

public class PlayerInteractEntityListener implements Listener {
    private final Tether plugin;
    private final LeashMobService leashMobService;
    private final LeashPlayerService leashPlayerService;

    public PlayerInteractEntityListener(
            Tether plugin,
            LeashMobService leashMobService,
            LeashPlayerService leashPlayerService) {
        this.plugin = plugin;
        this.leashMobService = leashMobService;
        this.leashPlayerService = leashPlayerService;
    }

    // Not PlayerInteractAtEntityEvent: that one comes from a separate packet the client sends first, so vanilla
    // would still handle the click afterwards and undo what Tether did (e.g. tie the mobs back to the fence).
    // This event fires right before vanilla handles the click, and cancelling it skips vanilla.
    // Clicks already cancelled, e.g. by protection plugins (Residence protects leash hitches this way), are ignored.
    @EventHandler(ignoreCancelled = true)
    private void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        // Including Living entity to include NPCs.
        if (event.getRightClicked() instanceof LivingEntity &&
                !(event.getRightClicked() instanceof Player)) {
            handlePlayerInteractAtMob(event);
            return;
        }

        if (event.getRightClicked() instanceof LeashHitch) {
            handlePlayerInteractAtLeashHitch(event);
            return;
        }

        if (event.getRightClicked() instanceof Player) {
            handlePlayerInteractAtPlayer(event);
        }
    }

    // Using PlayerInteractEntityEvent as its more general than PlayerLeashEntityEvent.
    // It's used for handling mobs that are not leasable by default.
    private void handlePlayerInteractAtMob(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof LivingEntity entity)) return;
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;

        Player player = event.getPlayer();

        // If they are sneaking which right-clicking the mob, try leashing mobs together.
        if (player.isSneaking() && leashMobService.handleSneakInteract(player, entity)) {
            event.setCancelled(true);
            return;
        }

        // Mobs leashed by a player are left to vanilla, which unleashes them for their holder and denies others.
        if (entity.isLeashed() && entity.getLeashHolder() instanceof Player) return;

        // If they have a lead in their hand we can try to leash the mob.
        if (player.getInventory().getItemInMainHand().getType().equals(Material.LEAD)) {
            try {
                leashMobService.playerLeashMob(player, entity);
            } catch (NoPermissionException e) {
                event.setCancelled(true);
            } catch (LeashException e) {
                // Only need to explicitly handle the LAND_CLAIM_RESTRICTION LeashException type.
                if (e.getType() == LeashErrorType.LAND_CLAIM_RESTRICTION) {
                    event.setCancelled(true);
                    player.sendMessage(Utils.chatColor(Utils.getPrefix(plugin) + plugin.getConfig().getString(
                            "messages.in-claim-deny-mob")));
                }
            }
        }
    }

    private void handlePlayerInteractAtLeashHitch(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof LeashHitch)) return;
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        // Since 1.21.6 shears cut all leashes tied to the hitch, leave that to vanilla.
        if (event.getPlayer().getInventory().getItemInMainHand().getType() == Material.SHEARS) return;

        if (leashMobService.handleFenceLeashing(event.getPlayer(), event.getRightClicked().getLocation())) {
            event.setCancelled(true);
        }
    }

    private void handlePlayerInteractAtPlayer(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player)) return;
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;

        // If player leashing is disabled, return.
        if (!plugin.getConfig().getBoolean("player-leash.enabled")) return;

        Player player = event.getPlayer();

        // Try to leash the player.
        try {
            leashPlayerService.playerLeashPlayer(player, (Player) event.getRightClicked());
        } catch (NoPermissionException ignored) {
        } catch (LeashException e) {
            switch (e.getType()) {
                case TARGET_PLAYER_RIDING -> player.sendMessage(Utils.chatColor(Utils.getPrefix(plugin) +
                        plugin.getConfig().getString("messages.cannot-leash-riding-player")));
                case LAND_CLAIM_RESTRICTION -> {
                    event.setCancelled(true);
                    player.sendMessage(Utils.chatColor(Utils.getPrefix(plugin) + plugin.getConfig().getString(
                            "messages.in-claim-deny-player")));
                }
                case PREVENT_NESTING -> player.sendMessage(Utils.chatColor(Utils.getPrefix(plugin) +
                        plugin.getConfig().getString("messages.prevent-nesting")));
            }
        }
    }
}
