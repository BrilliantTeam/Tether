package dev.naspo.tether.listeners;

import dev.naspo.tether.exceptions.leashexception.LeashException;
import dev.naspo.tether.services.LeashMobService;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerLeashEntityEvent;

import java.security.InvalidParameterException;

public class PlayerLeashEntityListener implements Listener {
    private final LeashMobService leashMobService;

    public PlayerLeashEntityListener(LeashMobService leashMobService) {
        this.leashMobService = leashMobService;
    }

    // This event only fires for mobs vanilla can leash on its own. Let vanilla handle those, so its side effects
    // still happen (lead consumption, and advancement criteria such as husbandry/leash_all_frog_variants, which
    // vanilla only awards when the interaction actually consumes the action). Tether's own leashing takes over
    // only for the mobs vanilla refuses, and is cancelled here whenever Tether disallows the leash.
    @EventHandler
    private void onPlayerLeashEntity(PlayerLeashEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) return;

        try {
            leashMobService.checkCanLeash(event.getPlayer(), entity);
        } catch (LeashException | InvalidParameterException e) {
            event.setCancelled(true);
        }
    }
}
