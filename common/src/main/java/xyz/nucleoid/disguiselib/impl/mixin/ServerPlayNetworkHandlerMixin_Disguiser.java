package xyz.nucleoid.disguiselib.impl.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Entry;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.nucleoid.disguiselib.api.DisguiseUtils;
import xyz.nucleoid.disguiselib.api.EntityDisguise;
import xyz.nucleoid.disguiselib.impl.mixin.accessor.*;
import xyz.nucleoid.disguiselib.impl.packets.FakePackets;

import java.util.*;

import static net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket.BRAND;
import static xyz.nucleoid.disguiselib.impl.DisguiseLib.DISGUISE_TEAM;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin_Disguiser {
    @Shadow public ServerPlayerEntity player;
    @Unique
    private boolean disguiselib$skipCheck;
    @Unique
    private final Set<Packet<?>> disguiselib$q = new HashSet<>();
    @Unique
    private int disguiselib$qTimer;
    @Unique
    private boolean disguiselib$sentTeamPacket;

    @Shadow public abstract void sendPacket(Packet<?> packet);

    /**
     * Checks the packet that was sent. If the entity in the packet is disguised, the
     * entity type / id in the packet will be changed.
     *
     * As minecraft client doesn't allow moving if you send it an entity with the same
     * id as player, we send the disguised player another entity, so they will see their
     * own disguise.
     *
     * @param packet packet being sent
     */
    @Inject(
            method = "sendPacket(Lnet/minecraft/network/Packet;Lnet/minecraft/network/PacketCallbacks;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/ClientConnection;send(Lnet/minecraft/network/Packet;Lnet/minecraft/network/PacketCallbacks;)V"
            ),
            cancellable = true
    )
    private void disguiseEntity(Packet<?> packet, PacketCallbacks callbacks, CallbackInfo ci) {
        if (!this.disguiselib$skipCheck) {
            World world = this.player.getEntityWorld();
            Entity entity = null;
            if (packet instanceof PlayerSpawnS2CPacket) {
                entity = world.getEntityById(((PlayerSpawnS2CPacketAccessor) packet).getId());
            } else if (packet instanceof EntitySpawnS2CPacket) {
                entity = world.getEntityById(((EntitySpawnS2CPacketAccessor) packet).getEntityId());
            } else if (packet instanceof EntitiesDestroyS2CPacket && ((EntitiesDestroyS2CPacketAccessor) packet).getEntityIds().getInt(0) == this.player.getId()) {
                ci.cancel();
                return;
            } else if(packet instanceof EntityTrackerUpdateS2CPacket) {
                // an ugly fix for #6
                int entityId = ((EntityTrackerUpdateS2CPacketAccessor) packet).getEntityId();
                if(entityId == this.player.getId() && ((EntityDisguise) this.player).isDisguised()) {
                    List<DataTracker.Entry<?>> trackedValues = this.player.getDataTracker().getAllEntries();
                    if(((EntityDisguise) this.player).getDisguiseType() != EntityType.PLAYER) {
                        Byte flags = this.player.getDataTracker().get(EntityAccessor.getFLAGS());

                        boolean removed = trackedValues.removeIf(entry -> entry.get().equals(flags));
                        if(removed) {
                            DataTracker.Entry<Byte> fakeInvisibleFlag = new DataTracker.Entry<>(EntityAccessor.getFLAGS(), (byte) (flags | 1 << 5));
                            trackedValues.add(fakeInvisibleFlag);
                        }
                    }
                    ((EntityTrackerUpdateS2CPacketAccessor) packet).setTrackedValues(trackedValues);
                } else if(!((EntityDisguise) this.player).hasTrueSight()) {
                    // Fixing "wrong data" client issue (#1)
                    // Just prevents the client from spamming the log
                    Entity original = world.getEntityById(entityId);

                    // Only change the content if entity is disguised
                    if(original != null && ((EntityDisguise) original).isDisguised()) {
                        Entity disguised = ((EntityDisguise) original).getDisguiseEntity();
                        if(disguised != null) {
                            ((DisguiseUtils) original).updateTrackedData();
                            List<DataTracker.Entry<?>> trackedValues = disguised.getDataTracker().getAllEntries();
                            ((EntityTrackerUpdateS2CPacketAccessor) packet).setTrackedValues(trackedValues);
                        }
                    }
                }
                return;
            } else if(packet instanceof EntityAttributesS2CPacket && !((EntityDisguise) this.player).hasTrueSight()) {
                // Fixing #2
                // Another client spam
                // Entity attributes "cannot" be sent for non-living entities
                Entity original = world.getEntityById(((EntityAttributesS2CPacketAccessor) packet).getEntityId());
                EntityDisguise entityDisguise = (EntityDisguise) original;

                if(original != null && entityDisguise.isDisguised() && !((DisguiseUtils) original).disguiseAlive()) {
                    ci.cancel();
                    return;
                }
            } else if(packet instanceof EntityVelocityUpdateS2CPacket velocityPacket) {
                int id = velocityPacket.getId();
                if(id != this.player.getId()) {

                    Entity entity1 = world.getEntityById(id);
                    if(entity1 != null && ((EntityDisguise) entity1).isDisguised()) {
                        // Cancels some client predictions
                        ci.cancel();
                    }
                }
            }

            if(entity != null) {
                disguiselib$sendFakePacket(entity, ci);
            }
        }
    }

    /**
     * Sends fake packet instead of the real one.
     *
     * @param entity the entity that is disguised and needs to have a custom packet sent.
     */
    @Unique
    private void disguiselib$sendFakePacket(Entity entity, CallbackInfo ci) {
        EntityDisguise disguise = (EntityDisguise) entity;
        GameProfile profile = disguise.getGameProfile();
        Entity disguiseEntity = disguise.getDisguiseEntity();

        Packet<?> spawnPacket;
        if(((EntityDisguise) this.player).hasTrueSight() || !disguise.isDisguised())
            spawnPacket = entity.createSpawnPacket();
        else
            spawnPacket = FakePackets.universalSpawnPacket(entity);

        this.disguiselib$skipCheck = true;
        if (disguise.getDisguiseType() == EntityType.PLAYER) {
            PlayerListS2CPacket packet = new PlayerListS2CPacket(PlayerListS2CPacket.Action.ADD_PLAYER);
            //noinspection ConstantConditions
            PlayerListS2CPacketAccessor listS2CPacketAccessor = (PlayerListS2CPacketAccessor) packet;

            // Arrays.asList is used as it returns mutable map, otherwise
            // this packet can cause some issues with other mods.
            listS2CPacketAccessor.setEntries(Arrays.asList(new Entry(profile, 0, GameMode.SURVIVAL, Text.literal(profile.getName()), null)));

            this.sendPacket(packet);

            if (!(entity instanceof PlayerEntity)) {
                packet = new PlayerListS2CPacket(PlayerListS2CPacket.Action.REMOVE_PLAYER);
                //noinspection ConstantConditions
                listS2CPacketAccessor = (PlayerListS2CPacketAccessor) packet;
                listS2CPacketAccessor.setEntries(Arrays.asList(new Entry(profile, 0, GameMode.SURVIVAL, Text.literal(profile.getName()), null)));

                this.disguiselib$q.add(packet);
                this.disguiselib$qTimer = 50;
            }
        }
        if (entity.getId() == this.player.getId()) {
            // We must treat disguised player differently
            // Why, I hear you ask ..?
            // Well, sending spawn packet of the new entity makes the player not being able to move :(
            if (disguise.getDisguiseType() != EntityType.PLAYER && disguise.isDisguised()) {
                if (disguiseEntity != null) {
                    if (spawnPacket instanceof EntitySpawnS2CPacket) {
                        ((EntitySpawnS2CPacketAccessor) spawnPacket).setEntityId(disguiseEntity.getId());
                        ((EntitySpawnS2CPacketAccessor) spawnPacket).setUuid(disguiseEntity.getUuid());
                    }
                    disguiseEntity.startRiding(this.player, true);
                    this.sendPacket(spawnPacket);

                    TeamS2CPacket joinTeamPacket = TeamS2CPacket.changePlayerTeam(DISGUISE_TEAM, this.player.getGameProfile().getName(), TeamS2CPacket.Operation.ADD); // join team
                    this.sendPacket(joinTeamPacket);
                }
            }
            ci.cancel();
        } else if(disguise.isDisguised()) {
            //this.player.getX()
            //ArmorStandEntity fakeStand = new ArmorStandEntity(this.player.world, );
            //fakeStand.startRiding(fakeStand, true);
            //new EntitySpawnS2CPacket(fakeStand);
            this.sendPacket(spawnPacket);
            ci.cancel();
        }


        this.disguiselib$skipCheck = false;
    }


    @Inject(
        method = "onPlayerMove(Lnet/minecraft/network/packet/c2s/play/PlayerMoveC2SPacket;)V",
        at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",
                shift = At.Shift.AFTER
        )
    )
    private void disguiselib$moveDisguiseEntity(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        if(((EntityDisguise) this.player).isDisguised() && ((EntityDisguise) this.player).getDisguiseType() != EntityType.PLAYER) {
            // Moving disguise for the disguised player
            EntityPositionS2CPacket s2CPacket = new EntityPositionS2CPacket(this.player);
            EntitySetHeadYawS2CPacket headYawS2CPacket = new EntitySetHeadYawS2CPacket(this.player, (byte)((int)(this.player.getHeadYaw() * 256.0F / 360.0F)));

            //noinspection ConstantConditions
            ((EntityPositionS2CPacketAccessor) s2CPacket).setEntityId(((EntityDisguise) this.player).getDisguiseEntity().getId());
            //noinspection ConstantConditions
            ((EntitySetHeadYawS2CPacketAccessor) headYawS2CPacket).setEntityId(((EntityDisguise) this.player).getDisguiseEntity().getId());
            this.sendPacket(s2CPacket);
            this.sendPacket(headYawS2CPacket);
        }
    }


    @Inject(method = "onPlayerMove(Lnet/minecraft/network/packet/c2s/play/PlayerMoveC2SPacket;)V", at = @At("RETURN"))
    private void removeFromTablist(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        if(!this.disguiselib$q.isEmpty() && --this.disguiselib$qTimer <= 0) {
            // fixme - non-living disguised as player still not showing up
            // fixme - player sometimes gets removed from tablist :(
            this.disguiselib$skipCheck = true;
            this.disguiselib$q.forEach(this::sendPacket);
            this.disguiselib$q.clear();
            this.disguiselib$skipCheck = false;
        }
    }



    @Inject(method = "onCustomPayload(Lnet/minecraft/network/packet/c2s/play/CustomPayloadC2SPacket;)V", at = @At("TAIL"))
    private void onClientBrand(CustomPayloadC2SPacket packet, CallbackInfo ci) {
        if (!this.disguiselib$sentTeamPacket && packet.getChannel().equals(BRAND)) {
            // Disabling collisions with the disguised entity itself
            TeamS2CPacket addTeamPacket = TeamS2CPacket.updateTeam(DISGUISE_TEAM, true); // create team
            this.disguiselib$sentTeamPacket = true;
            this.sendPacket(addTeamPacket);

            if (((EntityDisguise) this.player).isDisguised()) {
                // Send join team packet to prevent "sliding"
                TeamS2CPacket joinTeamPacket = TeamS2CPacket.changePlayerTeam(DISGUISE_TEAM, this.player.getGameProfile().getName(), TeamS2CPacket.Operation.ADD); // join team
                this.sendPacket(joinTeamPacket);
            }
        }
    }
}
