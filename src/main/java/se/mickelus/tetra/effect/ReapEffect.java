package se.mickelus.tetra.effect;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.UseAction;
import net.minecraft.potion.EffectInstance;
import net.minecraft.potion.Effects;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import se.mickelus.tetra.effect.potion.SmallStrengthPotionEffect;
import se.mickelus.tetra.effect.potion.SteeledPotionEffect;
import se.mickelus.tetra.effect.potion.StunPotionEffect;
import se.mickelus.tetra.effect.potion.UnwaveringPotionEffect;
import se.mickelus.tetra.items.modular.ItemModularHandheld;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

public class ReapEffect extends ChargedAbilityEffect {

    public static final ReapEffect instance = new ReapEffect();

    ReapEffect() {
        super(20, 0.7f, 40, 8, ItemEffect.reap, TargetRequirement.none, UseAction.SPEAR, "raised");
    }

    @Override
    public void perform(PlayerEntity attacker, Hand hand, ItemModularHandheld item, ItemStack itemStack, @Nullable LivingEntity target, @Nullable BlockPos targetPos, @Nullable Vector3d hitVec, int chargedTicks) {
        if (!attacker.world.isRemote) {
            int overchargeBonus = canOvercharge(item, itemStack) ? getOverchargeBonus(item, itemStack, chargedTicks) : 0;
            double momentumEfficiency = item.getEffectEfficiency(itemStack, ItemEffect.abilityMomentum);
            Collection<LivingEntity> momentumTargets = new LinkedList<>();

            double damageMultiplier = EffectHelper.getEffectLevel(itemStack, ItemEffect.reap) / 100d;
            double range = EffectHelper.getEffectEfficiency(itemStack, ItemEffect.reap);

            if (overchargeBonus > 0) {
                damageMultiplier += overchargeBonus * item.getEffectLevel(itemStack, ItemEffect.abilityOvercharge) / 100d;
                range += overchargeBonus * 0.5;
            }

            AtomicInteger kills = new AtomicInteger();
            AtomicInteger hits = new AtomicInteger();
            Vector3d targetVec;
            if (target != null) {
                targetVec = hitVec;
            } else {
                targetVec = Vector3d.fromPitchYaw(attacker.rotationPitch, attacker.rotationYaw)
                        .normalize()
                        .scale(range)
                        .add(attacker.getEyePosition(0));
            }

            AxisAlignedBB aoe = new AxisAlignedBB(targetVec, targetVec);
            double finalDamageMultiplier = damageMultiplier;
            attacker.world.getEntitiesWithinAABB(LivingEntity.class, aoe.grow(range, 1d, range)).stream()
                    .filter(entity -> entity != attacker)
                    .filter(entity -> !attacker.isOnSameTeam(entity))
                    .forEach(entity -> {
                        AbilityUseResult result = item.hitEntity(itemStack, attacker, entity, finalDamageMultiplier, 0.5f, 0.2f);
                        if (result != AbilityUseResult.fail) {
                            if (!entity.isAlive()) {
                                kills.incrementAndGet();
                            } else if (momentumEfficiency > 0) {
                                momentumTargets.add(entity);
                            }

                            hits.incrementAndGet();
                        }

                        if (result == AbilityUseResult.crit) {
                            attacker.getEntityWorld().playSound(attacker, entity.getPosition(), SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1, 1.3f);
                        }
                    });

            if (momentumEfficiency > 0 && kills.get() > 0) {
                int stunDuration = (int) (momentumEfficiency * kills.get() * 20);
                momentumTargets.forEach(entity -> entity.addPotionEffect(new EffectInstance(StunPotionEffect.instance, stunDuration, 0, false, false)));
            }

            applyBuff(attacker, kills.get(), hits.get(), hand, item, itemStack, chargedTicks);

            attacker.world.playSound(null, attacker.getPosX(), attacker.getPosY(), attacker.getPosZ(),
                    SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, attacker.getSoundCategory(), 1.0F, 1.0F);

            item.tickProgression(attacker, itemStack, 1 + kills.get());

            attacker.spawnSweepParticles();
        }

        attacker.addExhaustion(0.05f);
        attacker.swing(hand, false);
        attacker.getCooldownTracker().setCooldown(item, getCooldown(item, itemStack));

        item.applyDamage(2, itemStack, attacker);
    }

    private void applyBuff(PlayerEntity attacker, int kills, int hits, Hand hand, ItemModularHandheld item, ItemStack itemStack, int chargedTicks) {
        int defensiveLevel = item.getEffectLevel(itemStack, ItemEffect.abilityDefensive);
        if (defensiveLevel > 0) {
            if (hand == Hand.OFF_HAND) {
                if (hits > 0) {
                    int duration = defensiveLevel * (1 + kills * 2);
                    attacker.addPotionEffect(new EffectInstance(SteeledPotionEffect.instance, duration, hits - 1, false, true));
                }
            } else if (kills > 0) {
                int duration = (int) (item.getEffectEfficiency(itemStack, ItemEffect.abilityDefensive) * 20);
                attacker.addPotionEffect(new EffectInstance(Effects.SPEED, duration, kills - 1, false, true));
            }
        }

        if (kills > 0) {
            int overchargeLevel = item.getEffectLevel(itemStack, ItemEffect.abilityOvercharge);
            if (overchargeLevel > 0) {
                double duration = 30 * 20;

                duration *= 1 + getOverchargeBonus(item, itemStack, chargedTicks) * item.getEffectEfficiency(itemStack, ItemEffect.abilityOvercharge);

                attacker.addPotionEffect(new EffectInstance(SmallStrengthPotionEffect.instance, (int) duration, kills - 1, false, true));
            }

            int speedLevel = item.getEffectLevel(itemStack, ItemEffect.abilitySpeed);
            if (speedLevel > 0) {
                attacker.addPotionEffect(new EffectInstance(Effects.HASTE, (int) (item.getEffectEfficiency(itemStack, ItemEffect.abilitySpeed) * 20),
                        kills - 1, false, true));
            }

            int momentumLevel = item.getEffectLevel(itemStack, ItemEffect.abilityMomentum);
            if (momentumLevel > 0) {
                attacker.addPotionEffect(new EffectInstance(UnwaveringPotionEffect.instance, momentumLevel * kills * 20,
                        0, false, true));
            }
        }


    }
}