package de.schematiclive;

import java.lang.reflect.Method;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Clientseitig: liest deine Position und reicht sie lokal an den Schematic-Loader im Browser weiter. */
public class SchematicLiveClient implements ClientModInitializer {
    private static LiveServer server;
    private static Method reducedInfo;
    private static boolean reducedLookedUp;
    private static int tick;

    @Override
    public void onInitializeClient() {
        server = new LiveServer(FabricLoader.getInstance().getConfigDir().resolve("schematic-live-code.txt"));
        server.start();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++tick % 2 != 0) return; // ca. 10 Mal pro Sekunde
            if (client.player == null || client.level == null || reducedDebugInfo(client)) {
                server.publishOut();
                return;
            }
            float yaw = (float) (((client.player.getYRot() % 360.0) + 540.0) % 360.0 - 180.0);
            String dim = client.level.dimension().toString().replaceAll(".*/\\s*([^\\]]+)\\].*", "$1");
            server.publishPos(client.player.getX(), client.player.getY(), client.player.getZ(),
                    yaw, client.player.getXRot(), dim);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("liveloader")
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(Component.literal(
                                    "Schematic Live – Code: " + server.code() + "  (Port " + server.port() + ")"));
                            return 1;
                        })
                        .then(ClientCommands.literal("newcode").executes(ctx -> {
                            server.newCode();
                            ctx.getSource().sendFeedback(Component.literal("Neuer Code: " + server.code()));
                            return 1;
                        }))));
    }

    /** Respektiert "reduzierte Debug-Infos": Versteckt der Server F3-Koordinaten, sendet die Mod nichts. */
    private static boolean reducedDebugInfo(Minecraft mc) {
        try {
            if (!reducedLookedUp) {
                reducedLookedUp = true;
                reducedInfo = Minecraft.class.getMethod("showOnlyReducedInfo");
            }
            return reducedInfo != null && (boolean) reducedInfo.invoke(mc);
        } catch (Throwable t) {
            return false;
        }
    }
}
