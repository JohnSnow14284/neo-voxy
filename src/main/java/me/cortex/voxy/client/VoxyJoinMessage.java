package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

//Prints the build, its maintainer, the group and the fork's repository to chat on world join. Held back
//a short while: sent straight from LoggingIn the lines land before the chat is up and get swallowed by
//the join sequence. The persisted joinMessageShown flag makes it a one-time notice per installation.
public final class VoxyJoinMessage {
    public static final VoxyJoinMessage INSTANCE = new VoxyJoinMessage();

    private static final String REPO = "https://github.com/NHblock714/voxy";
    private static final String MAINTAINER = "NHblock";
    private static final String QQ_GROUP = "1098491849";
    private static final int DELAY_TICKS = 20;

    //Counts down to the send; negative means nothing is queued
    private int pending = -1;

    private VoxyJoinMessage() {}

    @SubscribeEvent
    public void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        this.pending = VoxyConfig.CONFIG.showJoinMessage && !VoxyConfig.CONFIG.joinMessageShown
                ? DELAY_TICKS : -1;
    }

    @SubscribeEvent
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        this.pending = -1;
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        if (this.pending < 0 || this.pending-- > 0) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(header(), false);
            player.displayClientMessage(credits(), false);
            player.displayClientMessage(repo(), false);
            VoxyConfig.CONFIG.joinMessageShown = true;
            VoxyConfig.CONFIG.save();
        }
    }

    private static Component header() {
        return Component.translatable("voxy.join.header",
                        Component.literal(displayName()).withStyle(ChatFormatting.WHITE),
                        Component.literal(version()).withStyle(ChatFormatting.GRAY))
                .withStyle(ChatFormatting.AQUA);
    }

    private static Component credits() {
        var group = Component.literal(QQ_GROUP).withStyle(style -> style
                .withColor(ChatFormatting.WHITE)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, QQ_GROUP))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("voxy.join.copyGroup"))));
        return Component.translatable("voxy.join.credits",
                        Component.literal(MAINTAINER).withStyle(ChatFormatting.WHITE), group)
                .withStyle(ChatFormatting.GRAY);
    }

    private static Component repo() {
        var link = Component.literal(REPO).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, REPO))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("voxy.join.openRepo"))));
        return Component.translatable("voxy.join.repo", link).withStyle(ChatFormatting.GRAY);
    }

    //ModList is populated long before a world loads, so the clean values are available here; the
    //MOD_VERSION constant carries a commit suffix that is not worth showing.
    private static String version() {
        return ModList.get().getModContainerById("voxy")
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    private static String displayName() {
        return me.cortex.voxy.commonImpl.VoxyCommon.displayName();
    }
}
