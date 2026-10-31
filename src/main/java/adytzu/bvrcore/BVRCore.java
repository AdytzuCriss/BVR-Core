package adytzu.bvrcore;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * BVR Core, etapa 1 (doar pe server):
 *  1) ceas real scris in scoreboard-ul "rb_const" (folosit de datapack-ul reborn);
 *  2) comenzi scurte care ruleaza /trigger-urile existente ale datapack-ului.
 *
 * Nu sunt folosite mixin-uri. Comenzile se executa prin sursa serverului ("execute as <uuid> run ..."),
 * deci nu depind de permisiunile jucatorului si nu afiseaza mesajele "Triggered [...]".
 */
public class BVRCore implements ModInitializer {
	public static final String MOD_ID = "bvr-core";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Fusul orar folosit pentru data si ora (Romania). */
	private static final ZoneId ZONE = ZoneId.of("Europe/Bucharest");
	/** Obiectivul scoreboard creat de datapack-ul reborn (load.mcfunction). */
	private static final String OBJECTIVE = "rb_const";

	private int tickCounter = 0;
	private boolean warnedMissingObjective = false;

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
		LOGGER.info("BVR Core incarcat: comenzi scurte + ceas real ({}).", ZONE);
	}

	// ------------------------------------------------------------------ ceas real

	private void onServerTick(MinecraftServer server) {
		tickCounter++;
		if (tickCounter < 20) {
			return; // o data pe secunda
		}
		tickCounter = 0;

		if (server.getScoreboard().getObjective(OBJECTIVE) == null) {
			if (!warnedMissingObjective) {
				LOGGER.warn("Obiectivul '{}' nu exista inca (datapack-ul reborn nu e incarcat). Ceasul nu se scrie.", OBJECTIVE);
				warnedMissingObjective = true;
			}
			return;
		}
		warnedMissingObjective = false;

		ZonedDateTime now = ZonedDateTime.now(ZONE);
		long epochSeconds = now.toEpochSecond();
		LocalDate today = now.toLocalDate();

		CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
		setScore(server, source, "#now_s", (int) epochSeconds);                 // secunde de la 1970 (UTC)
		setScore(server, source, "#now_m", (int) (epochSeconds / 60));          // minute de la 1970 (UTC)
		setScore(server, source, "#today", (int) today.toEpochDay());           // numarul zilei locale (zile de la 1970)
		setScore(server, source, "#mod", now.getHour() * 60 + now.getMinute()); // minute de la miezul noptii (ora locala)
		setScore(server, source, "#clock", 1);                                  // semn ca modul ruleaza
	}

	private static void setScore(MinecraftServer server, CommandSourceStack source, String holder, int value) {
		server.getCommands().performPrefixedCommand(source, "scoreboard players set " + holder + " " + OBJECTIVE + " " + value);
	}

	// ------------------------------------------------------------------ comenzi scurte

	/** Numele comenzii cu litere mici si cu prima litera mare, plus variantele date (ex. CamelCase). */
	private static Set<String> names(String lower, String... extra) {
		Set<String> set = new LinkedHashSet<>();
		set.add(lower);
		set.add(Character.toUpperCase(lower.charAt(0)) + lower.substring(1));
		for (String e : extra) {
			set.add(e);
		}
		return set;
	}

	private static void reg(CommandDispatcher<CommandSourceStack> dispatcher, Set<String> names,
							Function<String, LiteralArgumentBuilder<CommandSourceStack>> factory) {
		for (String name : names) {
			dispatcher.register(factory.apply(name));
		}
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> d) {
		// /reborn -> /trigger reborn (cartea Reborn)
		reg(d, names("reborn"), n -> Commands.literal(n)
			.executes(c -> asPlayer(c, "trigger reborn")));

		// /rebornstatue -> meniul statuilor
		reg(d, names("rebornstatue", "RebornStatue"), n -> Commands.literal(n)
			.executes(c -> asPlayer(c, "trigger reborn set 9")));

		// /myreborn -> Reborn-urile si efectele tale
		reg(d, names("myreborn", "MyReborn"), n -> Commands.literal(n)
			.executes(c -> asPlayer(c, "trigger reborn_info")));

		// /shardshop -> Shard Shop
		reg(d, names("shardshop", "ShardShop"), n -> Commands.literal(n)
			.executes(c -> asPlayer(c, "trigger shardshop")));

		// /hud (comuta) | /hud show | /hud hide
		reg(d, names("hud"), n -> {
			LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(n)
				.executes(c -> asPlayer(c, "trigger hud"));
			for (String v : names("show")) {
				b.then(Commands.literal(v).executes(c -> asPlayer(c, "execute if score @s hud_off matches 1 run trigger hud")));
			}
			for (String v : names("hide")) {
				b.then(Commands.literal(v).executes(c -> asPlayer(c, "execute unless score @s hud_off matches 1 run trigger hud")));
			}
			return b;
		});

		// /language | /language ro | /language en
		reg(d, names("language"), n -> {
			LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(n)
				.executes(c -> asPlayer(c, "trigger changelanguage"));
			for (String v : names("ro", "romana", "Romana")) {
				b.then(Commands.literal(v).executes(c -> asPlayer(c, "trigger changelanguage set 2")));
			}
			for (String v : names("en", "english", "English")) {
				b.then(Commands.literal(v).executes(c -> asPlayer(c, "trigger changelanguage set 3")));
			}
			return b;
		});

		// /withdraw shards <n> | /withdraw levels <n>
		reg(d, names("withdraw"), n -> {
			LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(n);
			for (String v : names("shards")) {
				b.then(Commands.literal(v).then(Commands.argument("amount", IntegerArgumentType.integer(1, 256))
					.executes(c -> asPlayer(c, "trigger withdrawshards set " + IntegerArgumentType.getInteger(c, "amount")))));
			}
			for (String v : names("levels")) {
				b.then(Commands.literal(v).then(Commands.argument("amount", IntegerArgumentType.integer(1, 65535))
					.executes(c -> asPlayer(c, "trigger withdrawlevels set " + IntegerArgumentType.getInteger(c, "amount")))));
			}
			return b;
		});

		// /deposit shards [all]  (momentan depune toate shard-urile din inventar)
		reg(d, names("deposit"), n -> {
			LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(n);
			for (String v : names("shards")) {
				LiteralArgumentBuilder<CommandSourceStack> shards = Commands.literal(v)
					.executes(c -> asPlayer(c, "trigger depositshards"));
				for (String a : names("all")) {
					shards.then(Commands.literal(a).executes(c -> asPlayer(c, "trigger depositshards")));
				}
				b.then(shards);
			}
			return b;
		});
	}

	/** Ruleaza o comanda ca jucatorul care a scris comanda scurta, prin sursa serverului. */
	private static int asPlayer(CommandContext<CommandSourceStack> context, String command) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			return 0; // rulata din consola
		}
		MinecraftServer server = source.getServer();
		CommandSourceStack serverSource = server.createCommandSourceStack().withSuppressedOutput();
		server.getCommands().performPrefixedCommand(serverSource, "execute as " + player.getStringUUID() + " run " + command);
		return 1;
	}
}
