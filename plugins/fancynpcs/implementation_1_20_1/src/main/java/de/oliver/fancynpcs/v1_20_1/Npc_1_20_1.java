package de.oliver.fancynpcs.v1_20_1;

import com.google.common.collect.ImmutableList;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.datafixers.util.Pair;
import de.oliver.fancylib.ReflectionUtils;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcAttribute;
import de.oliver.fancynpcs.api.NpcData;
import de.oliver.fancynpcs.api.events.NpcSpawnEvent;
import de.oliver.fancynpcs.api.utils.NpcEquipmentSlot;
import io.papermc.paper.adventure.PaperAdventure;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.Optionull;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_20_R1.CraftServer;
import org.bukkit.craftbukkit.v1_20_R1.CraftWorld;
import org.bukkit.craftbukkit.v1_20_R1.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_20_R1.inventory.CraftItemStack;
import org.bukkit.craftbukkit.v1_20_R1.util.CraftNamespacedKey;
import org.bukkit.entity.Player;
import org.lushplugins.chatcolorhandler.ModernChatColorHandler;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class Npc_1_20_1 extends Npc {

    private final String localName;
    private final UUID uuid;
    private Entity npc;
    private Display.TextDisplay sittingVehicle;

    public Npc_1_20_1(NpcData data) {
        super(data);

        this.localName = generateLocalName();
        this.uuid = UUID.randomUUID();
    }

    @Override
    public void create() {
        MinecraftServer minecraftServer = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel serverLevel = ((CraftWorld) data.getLocation().getWorld()).getHandle();
        GameProfile gameProfile = new GameProfile(uuid, localName);

        if (data.getType() == org.bukkit.entity.EntityType.PLAYER) {
            npc = new ServerPlayer(minecraftServer, serverLevel, new GameProfile(uuid, ""));
            ((ServerPlayer) npc).gameProfile = gameProfile;
        } else {
            EntityType<?> nmsType = BuiltInRegistries.ENTITY_TYPE.get(CraftNamespacedKey.toMinecraft(data.getType().getKey()));
            EntityType.EntityFactory factory = (EntityType.EntityFactory) ReflectionUtils.getValue(nmsType, MappingKeys1_20_1.ENTITY_TYPE__FACTORY.getMapping()); // EntityType.factory
            npc = factory.create(nmsType, serverLevel);
            isTeamCreated.clear();
        }
    }

    @Override
    public void spawn(Player player) {
        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();

        if (npc == null) {
            return;
        }

        if (!data.getLocation().getWorld().getName().equalsIgnoreCase(serverPlayer.level().getWorld().getName())) {
            return;
        }

        if (data.getSkinData() != null && data.getSkinData().hasTexture()) {
            String value = data.getSkinData().getTextureValue();
            String signature = data.getSkinData().getTextureSignature();

            ((ServerPlayer) npc).getGameProfile().getProperties().replaceValues(
                    "textures",
                    ImmutableList.of(new Property("textures", value, signature))
            );
        }

        NpcSpawnEvent spawnEvent = new NpcSpawnEvent(this, player);
        spawnEvent.callEvent();
        if (spawnEvent.isCancelled()) {
            return;
        }


        if (npc instanceof ServerPlayer npcPlayer) {
            EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.noneOf(ClientboundPlayerInfoUpdatePacket.Action.class);
            actions.add(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER);
            actions.add(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME);
            if (data.isShowInTab()) {
                actions.add(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED);
            }

            // The constructor ClientboundPlayerInfoUpdatePacket(actions, entries) is not available in 1.20
            ClientboundPlayerInfoUpdatePacket playerInfoPacket = new ClientboundPlayerInfoUpdatePacket(actions, List.of(npcPlayer)); // KEEP
            List<ClientboundPlayerInfoUpdatePacket.Entry> entries = List.of(getEntry(npcPlayer, serverPlayer)); // KEEP
            ReflectionUtils.setValue(playerInfoPacket, MappingKeys1_20_1.CLIENTBOUND_PLAYER_INFO_UPDATE_PACKET__ENTRIES.getMapping(), entries); // KEEP
            serverPlayer.connection.send(playerInfoPacket);

            if (data.isSpawnEntity()) {
                npc.setPos(data.getLocation().x(), data.getLocation().y(), data.getLocation().z());
                ClientboundAddPlayerPacket spawnPlayerPacket = new ClientboundAddPlayerPacket(npcPlayer); // # keep!
                serverPlayer.connection.send(spawnPlayerPacket); // # keep!
            }
        }

        ClientboundAddEntityPacket addEntityPacket = new ClientboundAddEntityPacket(npc); // # keep!
        serverPlayer.connection.send(addEntityPacket); // # keep!

        isVisibleForPlayer.put(player.getUniqueId(), true);

        int removeNpcsFromPlayerlistDelay = FancyNpcsPlugin.get().getFancyNpcConfig().getRemoveNpcsFromPlayerlistDelay();
        if (!data.isShowInTab() && removeNpcsFromPlayerlistDelay > 0) {
            FancyNpcsPlugin.get().getNpcThread().schedule(() -> {
                ClientboundPlayerInfoRemovePacket playerInfoRemovePacket = new ClientboundPlayerInfoRemovePacket(List.of(npc.getUUID()));
                serverPlayer.connection.send(playerInfoRemovePacket);
            }, removeNpcsFromPlayerlistDelay, TimeUnit.MILLISECONDS);
        }

        update(player);
    }

    @Override
    public void remove(Player player) {
        if (npc == null) {
            return;
        }

        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();

        if (npc instanceof ServerPlayer npcPlayer) {
            ClientboundPlayerInfoRemovePacket playerInfoRemovePacket = new ClientboundPlayerInfoRemovePacket(List.of((npcPlayer.getUUID())));
            serverPlayer.connection.send(playerInfoRemovePacket);
        }

        // remove entity
        ClientboundRemoveEntitiesPacket removeEntitiesPacket = new ClientboundRemoveEntitiesPacket(npc.getId());
        serverPlayer.connection.send(removeEntitiesPacket);

        // remove sitting vehicle
        if (sittingVehicle != null) {
            ClientboundRemoveEntitiesPacket removeSittingVehiclePacket = new ClientboundRemoveEntitiesPacket(sittingVehicle.getId());
            serverPlayer.connection.send(removeSittingVehiclePacket);
        }

        isVisibleForPlayer.put(serverPlayer.getUUID(), false);
    }

    @Override
    public void lookAt(Player player, Location location) {
        if (npc == null) {
            return;
        }

        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();

        npc.setRot(location.getYaw(), location.getPitch());
        npc.setYHeadRot(location.getYaw());
        npc.setXRot(location.getPitch());
        npc.setYRot(location.getYaw());

        ClientboundTeleportEntityPacket teleportEntityPacket = new ClientboundTeleportEntityPacket(npc);
        serverPlayer.connection.send(teleportEntityPacket);

        float angelMultiplier = 256f / 360f;
        ClientboundRotateHeadPacket rotateHeadPacket = new ClientboundRotateHeadPacket(npc, (byte) (location.getYaw() * angelMultiplier));
        serverPlayer.connection.send(rotateHeadPacket);
    }

    @Override
    public void update(Player player) {
        if (npc == null) {
            return;
        }

        if (!isVisibleForPlayer.getOrDefault(player.getUniqueId(), false)) {
            return;
        }

        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();

        PlayerTeam team = new PlayerTeam(new Scoreboard(), "npc-" + localName);
        team.getPlayers().clear();
        team.getPlayers().add(npc instanceof ServerPlayer npcPlayer ? npcPlayer.getGameProfile().getName() : npc.getStringUUID());
        team.setColor(PaperAdventure.asVanilla(data.getGlowingColor()));
        if (!data.isCollidable()) {
            team.setCollisionRule(Team.CollisionRule.NEVER);
        }

        net.kyori.adventure.text.Component displayName = ModernChatColorHandler.translate(data.getDisplayName(), serverPlayer.getBukkitEntity());
        Component vanillaComponent = PaperAdventure.asVanilla(displayName);
        if (!(npc instanceof ServerPlayer)) {
            npc.setCustomName(vanillaComponent);
            npc.setCustomNameVisible(true);
        } else {
            npc.setCustomName(null);
            npc.setCustomNameVisible(false);
        }

        if (data.getDisplayName().equalsIgnoreCase("<empty>")) {
            team.setNameTagVisibility(Team.Visibility.NEVER);
            npc.setCustomName(null);
            npc.setCustomNameVisible(false);
        } else {
            team.setNameTagVisibility(Team.Visibility.ALWAYS);
        }

        if (npc instanceof ServerPlayer npcPlayer) {
            team.setPlayerPrefix(vanillaComponent);
            npcPlayer.listName = vanillaComponent;

            EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.noneOf(ClientboundPlayerInfoUpdatePacket.Action.class);
            actions.add(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME);
            if (data.isShowInTab()) {
                actions.add(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED);
            }

            ClientboundPlayerInfoUpdatePacket playerInfoPacket = new ClientboundPlayerInfoUpdatePacket(actions, List.of(npcPlayer)); // KEEP
            List<ClientboundPlayerInfoUpdatePacket.Entry> entries = List.of(getEntry(npcPlayer, serverPlayer)); // KEEP
            ReflectionUtils.setValue(playerInfoPacket, MappingKeys1_20_1.CLIENTBOUND_PLAYER_INFO_UPDATE_PACKET__ENTRIES.getMapping(), entries); // KEEP
            serverPlayer.connection.send(playerInfoPacket);
        }

        boolean isTeamCreatedForPlayer = this.isTeamCreated.getOrDefault(player.getUniqueId(), false);
        serverPlayer.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, !isTeamCreatedForPlayer));
        isTeamCreated.put(player.getUniqueId(), true);

        npc.setGlowingTag(data.isGlowing());

        if (data.getEquipment() != null && data.getEquipment().size() > 0) {
            List<Pair<EquipmentSlot, ItemStack>> equipmentList = new ArrayList<>();

            for (NpcEquipmentSlot slot : data.getEquipment().keySet()) {
                equipmentList.add(new Pair<>(EquipmentSlot.byName(slot.toNmsName()), CraftItemStack.asNMSCopy(data.getEquipment().get(slot))));
            }

            ClientboundSetEquipmentPacket setEquipmentPacket = new ClientboundSetEquipmentPacket(npc.getId(), equipmentList);
            serverPlayer.connection.send(setEquipmentPacket);
        }

        if (npc instanceof ServerPlayer) {
            // Enable second layer of skin (https://wiki.vg/Entity_metadata#Player)
            npc.getEntityData().set(net.minecraft.world.entity.player.Player.DATA_PLAYER_MODE_CUSTOMISATION, (byte) (0x01 | 0x02 | 0x04 | 0x08 | 0x10 | 0x20 | 0x40));
        }

        data.applyAllAttributes(this);

        refreshEntityData(player);

        if (data.isSpawnEntity() && data.getLocation() != null) {
            move(player, true);
        }

        NpcAttribute playerPoseAttr = FancyNpcsPlugin.get().getAttributeManager().getAttributeByName(org.bukkit.entity.EntityType.PLAYER, "pose");
        if (data.getAttributes().containsKey(playerPoseAttr)) {
            String pose = data.getAttributes().get(playerPoseAttr);

            if (pose.equals("sitting")) {
                setSitting(serverPlayer);
            } else {
                if (sittingVehicle != null) {
                    ClientboundRemoveEntitiesPacket removeSittingVehiclePacket = new ClientboundRemoveEntitiesPacket(sittingVehicle.getId());
                    serverPlayer.connection.send(removeSittingVehiclePacket);
                }
            }

        }
    }

    @Override
    protected void refreshEntityData(Player player) {
        if (!isVisibleForPlayer.getOrDefault(player.getUniqueId(), false)) {
            return;
        }

        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();

        Int2ObjectMap<SynchedEntityData.DataItem<?>> itemsById = (Int2ObjectMap<SynchedEntityData.DataItem<?>>) ReflectionUtils.getValue(npc.getEntityData(), MappingKeys1_20_1.SYNCHED_ENTITY_DATA__ITEMS_BY_ID.getMapping()); // itemsById
        List<SynchedEntityData.DataValue<?>> entityData = new ArrayList<>();
        for (SynchedEntityData.DataItem<?> dataItem : itemsById.values()) {
            entityData.add(dataItem.value());
        }
        ClientboundSetEntityDataPacket setEntityDataPacket = new ClientboundSetEntityDataPacket(npc.getId(), entityData);
        serverPlayer.connection.send(setEntityDataPacket);
    }

    public void move(Player player, boolean swingArm) {
        if (npc == null) {
            return;
        }

        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();

        npc.setPosRaw(data.getLocation().x(), data.getLocation().y(), data.getLocation().z());
        npc.setRot(data.getLocation().getYaw(), data.getLocation().getPitch());
        npc.setYHeadRot(data.getLocation().getYaw());
        npc.setXRot(data.getLocation().getPitch());
        npc.setYRot(data.getLocation().getYaw());

        ClientboundTeleportEntityPacket teleportEntityPacket = new ClientboundTeleportEntityPacket(npc);
        ReflectionUtils.setValue(teleportEntityPacket, MappingKeys1_20_1.CLIENTBOUND_TELEPORT_ENTITY_PACKET__X.getMapping(), data.getLocation().x()); // 'x'
        ReflectionUtils.setValue(teleportEntityPacket, MappingKeys1_20_1.CLIENTBOUND_TELEPORT_ENTITY_PACKET__Y.getMapping(), data.getLocation().y()); // 'y'
        ReflectionUtils.setValue(teleportEntityPacket, MappingKeys1_20_1.CLIENTBOUND_TELEPORT_ENTITY_PACKET__Z.getMapping(), data.getLocation().z()); // 'z'
        serverPlayer.connection.send(teleportEntityPacket);

        float angelMultiplier = 256f / 360f;
        ClientboundRotateHeadPacket rotateHeadPacket = new ClientboundRotateHeadPacket(npc, (byte) (data.getLocation().getYaw() * angelMultiplier));
        serverPlayer.connection.send(rotateHeadPacket);

        if (swingArm && npc instanceof ServerPlayer) {
            ClientboundAnimatePacket animatePacket = new ClientboundAnimatePacket(npc, 0);
            serverPlayer.connection.send(animatePacket);
        }
    }

    private ClientboundPlayerInfoUpdatePacket.Entry getEntry(ServerPlayer npcPlayer, ServerPlayer viewer) {
        GameProfile profile = npcPlayer.getGameProfile();
        if (data.isMirrorSkin() && viewer.getGameProfile().getProperties().containsKey("textures")) {
            GameProfile newProfile = new GameProfile(profile.getId(), profile.getName());
            newProfile.getProperties().putAll(viewer.getGameProfile().getProperties());
            profile = newProfile;
        }

        return new ClientboundPlayerInfoUpdatePacket.Entry(
                npcPlayer.getUUID(),
                profile,
                data.isShowInTab(),
                npcPlayer.latency,
                npcPlayer.gameMode.getGameModeForPlayer(),
                npcPlayer.getTabListDisplayName(),
                Optionull.map(npcPlayer.getChatSession(), RemoteChatSession::asData)
        );
    }

    public void setSitting(ServerPlayer serverPlayer) {
        if (npc == null) {
            return;
        }

        if (sittingVehicle == null) {
            sittingVehicle = new Display.TextDisplay(EntityType.TEXT_DISPLAY, ((CraftWorld) data.getLocation().getWorld()).getHandle());
        }

        sittingVehicle.setPos(data.getLocation().x(), data.getLocation().y(), data.getLocation().z());

        ClientboundAddEntityPacket addEntityPacket = new ClientboundAddEntityPacket(sittingVehicle);
        serverPlayer.connection.send(addEntityPacket);

        sittingVehicle.passengers = ImmutableList.of(npc);

        ClientboundSetPassengersPacket packet = new ClientboundSetPassengersPacket(sittingVehicle);
        serverPlayer.connection.send(packet);
    }

    @Override
    public float getEyeHeight() {
        return npc.getEyeHeight();
    }

    @Override
    public int getEntityId() {
        return npc.getId();
    }

    public Entity getNpc() {
        return npc;
    }
}
