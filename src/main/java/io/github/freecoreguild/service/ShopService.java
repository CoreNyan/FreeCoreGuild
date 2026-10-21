package io.github.freecoreguild.service;

import io.github.freecoreguild.lang.Lang;
import io.github.freecoreguild.EconomyGateway;
import io.github.freecoreguild.api.GuildProvider.Role;
import io.github.freecoreguild.api.ShopProvider;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

/** Shop catalog, limits, payment and delivery service. */
public final class ShopService implements ShopProvider {
    private volatile ShopConfig config;
    private final PurchaseLedger ledger;
    private final PurchaseRecorder recorder;
    private final EconomyGateway economy;
    private final ToLongFunction<UUID> guildId;
    private final Clock clock;
    private final Map<PurchaseKey, Integer> memoryPurchases = new ConcurrentHashMap<>();

    public void clearAllLimits() {
        memoryPurchases.clear();
    }

    public void reload(ShopConfig newConfig) {
        if (newConfig != null) {
            this.config = newConfig;
        }
    }

    public ShopService(ShopConfig config, PurchaseLedger ledger) {
        this(config, ledger, null, null, p -> 0L, Clock.systemUTC());
    }
    public ShopService(ShopConfig config, PurchaseLedger ledger, PurchaseRecorder recorder,
                       EconomyGateway economy, ToLongFunction<UUID> guildId) {
        this(config, ledger, recorder, economy, guildId, Clock.systemUTC());
    }
    public ShopService(ShopConfig config, PurchaseLedger ledger, PurchaseRecorder recorder,
                       EconomyGateway economy, ToLongFunction<UUID> guildId, Clock clock) {
        this.config = Objects.requireNonNull(config); this.ledger = Objects.requireNonNull(ledger);
        this.recorder = recorder; this.economy = economy; this.guildId = Objects.requireNonNull(guildId);
        this.clock = Objects.requireNonNull(clock);
    }
    public Collection<ShopItem> items() { return config.items().values(); }
    public Optional<ShopItem.Offer> offer(String itemId, Role role) { return config.item(itemId).map(i -> i.offers().get(role)); }

    public LimitDecision checkLimit(UUID player, String itemId, Role role, int requestedAmount) {
        if (requestedAmount <= 0) return new LimitDecision(false, 0, 0, Lang.text("results.shop.invalid-amount"));
        ShopItem.Offer offer = offer(itemId, role).orElse(null);
        if (offer == null) return new LimitDecision(false, 0, 0, Lang.text("results.shop.missing-offer"));
        if (offer.limitType() == ShopItem.LimitType.UNLIMITED || offer.limitAmount() <= 0)
            return new LimitDecision(true, Integer.MAX_VALUE, 0, Lang.text("results.shop.unlimited"));
        Instant start = windowStart(offer.limitType());
        int bought = Math.max(0, ledger.purchased(player, itemId, start, clock.instant()));
        if (recorder == null) bought += memoryPurchases.getOrDefault(new PurchaseKey(player, itemId, start), 0);
        int remaining = Math.max(0, offer.limitAmount() - bought);
        return new LimitDecision(requestedAmount <= remaining, remaining, bought,
                requestedAmount <= remaining ? Lang.text("results.shop.allowed") : Lang.text("results.shop.limit-exceeded", "remaining", String.valueOf(remaining)));
    }

    public LimitDecision canPurchase(UUID player, String itemId, Role role, int requestedAmount) { return checkLimit(player, itemId, role, requestedAmount); }

    /** Charges, delivers and records a purchase. */
    public PurchaseResult purchase(Player player, String itemId, Role role, int amount) {
        if (player == null) return new PurchaseResult(false, Lang.text("results.invalid-player-short"));
        LimitDecision decision = checkLimit(player.getUniqueId(), itemId, role, amount);
        if (!decision.allowed()) return new PurchaseResult(false, decision.reason());
        ShopItem.Offer offer = offer(itemId, role).orElse(null);
        if (offer == null || !Double.isFinite(offer.price()) || offer.price() < 0)
            return new PurchaseResult(false, Lang.text("results.shop.invalid-price"));
        double cost = offer.price() * amount;
        if (!Double.isFinite(cost) || cost < 0) {
            return new PurchaseResult(false, Lang.text("results.shop.invalid-total-price"));
        }
        if (economy == null) {
            return new PurchaseResult(false, Lang.text("results.shop.no-economy"));
        }
        if (cost > 0 && economy.getBalance(player) < cost) {
            return new PurchaseResult(false, Lang.text("results.shop.insufficient-funds", "cost", String.valueOf((long)cost), "balance", String.valueOf((long)economy.getBalance(player))));
        }
        if (cost > 0 && !economy.withdraw(player, cost)) {
            return new PurchaseResult(false, Lang.text("results.shop.withdraw-failed"));
        }
        try {
            if (!deliver(player, offer.material(), amount)) throw new IllegalStateException("delivery failed");
            Instant start = windowStart(offer.limitType());
            long gid = guildId.applyAsLong(player.getUniqueId());
            if (recorder != null && gid > 0 && !recorder.recordPurchase(gid, player.getUniqueId(), itemId, amount, start))
                throw new IllegalStateException("record failed");
            memoryPurchases.merge(new PurchaseKey(player.getUniqueId(), itemId, start), amount, Integer::sum);
            return new PurchaseResult(true, Lang.text("results.shop.success"));
        } catch (RuntimeException ex) {
            if (cost > 0) economy.refund(player, cost);
            return new PurchaseResult(false, Lang.text("results.shop.delivery-failed"));
        }
    }
    public PurchaseResult purchase(UUID player, String itemId, Role role, int amount) {
        Player p = Bukkit.getPlayer(player); return p == null ? new PurchaseResult(false, Lang.text("results.shop.offline")) : purchase(p, itemId, role, amount);
    }
    private boolean deliver(Player player, String value, int amount) {
        if (value == null || value.isBlank()) return false;
        String v = value.trim();
        if (v.regionMatches(true, 0, "console:", 0, 8)) {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), v.substring(8).replace("{player}", player.getName()));
        }
        if (v.regionMatches(true, 0, "op:", 0, 3)) {
            String cmd = v.substring(3).replace("{player}", player.getName());
            boolean wasOp = player.isOp(); try { player.setOp(true); return player.performCommand(cmd); } finally { player.setOp(wasOp); }
        }
        Material material = Material.matchMaterial(v); if (material == null) return false;
        return player.getInventory().addItem(new ItemStack(material, amount)).isEmpty();
    }
    private Instant windowStart(ShopItem.LimitType type) {
        ZoneId zone; try { zone = ZoneId.of(config.resetTimezone()); } catch (DateTimeException ex) { zone = ZoneOffset.UTC; }
        ZonedDateTime now = clock.instant().atZone(zone); LocalDate date = now.toLocalDate();
        return switch (type) { case DAILY -> date.atStartOfDay(zone).toInstant(); case WEEKLY -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant(); case MONTHLY -> date.withDayOfMonth(1).atStartOfDay(zone).toInstant(); case UNLIMITED -> Instant.EPOCH; };
    }
    @FunctionalInterface public interface PurchaseLedger { int purchased(UUID player, String itemId, Instant fromInclusive, Instant toInclusive); }
    public record LimitDecision(boolean allowed, int remaining, int purchased, String reason) {}
    public record PurchaseResult(boolean success, String message) {}
    private record PurchaseKey(UUID player, String item, Instant window) {}
}
