package adytzu.bvrcore;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BVR Core (doar server):
 *  - ceas real in scoreboard-ul rb_const (#now_s, #now_m, #today, #mod, #clock);
 *  - comenzi scurte pentru /trigger-urile datapack-ului reborn;
 *  - comenzi de admin (doar staff = scor rb_smask >= 1) care ruleaza functiile datapack-ului;
 *  - /admineconomy cu jurnal in fisier text, /tempban si /unban cu expirare automata.
 *
 * Toate comenzile se executa prin sursa serverului (comenzi vanilla), deci nu depind de API-uri interne.
 */
public class BVRCore implements ModInitializer {
	public static final String MOD_ID = "bvr-core";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final ZoneId ZONE = ZoneId.of("Europe/Bucharest");
	private static final String OBJECTIVE = "rb_const";
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
	private static final Pattern DURATION = Pattern.compile("(\\d+)([mhd]?)");
	private static final Path LOG_DIR = Path.of("logs", "bvr-core");
	private static final Path BAN_FILE = Path.of("config", "bvr-core-bans.txt");

	/** nume jucator -> {expirare (secunde epoch), staff, motiv} */
	private static final Map<String, String[]> TEMPBANS = new LinkedHashMap<>();
	/** setari reglabile prin /adminscoreboard */
	private static final Map<String, String> SETTINGS = new LinkedHashMap<>();

	static {
		SETTINGS.put("cooldownwither", "#cd_w");
		SETTINGS.put("cooldownwarden", "#cd_k");
		SETTINGS.put("xptax", "#xpt_tax");
		SETTINGS.put("swapprice", "#price_swap");
		SETTINGS.put("rerollprice", "#price_reroll");
	}

	private int tickCounter = 0;
	private boolean warnedMissingObjective = false;

	@Override
	public void onInitialize() {
		loadBans();
		loadReports();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
		LOGGER.info("BVR Core incarcat: comenzi scurte, admin, ceas real ({}).", ZONE);
	}

	// ------------------------------------------------------------------ tick: ceas real + expirarea ban-urilor

	private void onServerTick(MinecraftServer server) {
		tickCounter++;
		if (tickCounter < 20) {
			return; // o data pe secunda
		}
		tickCounter = 0;
		checkExpiredBans(server);

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
		setScore(server, source, "#now_s", (int) epochSeconds);
		setScore(server, source, "#now_m", (int) (epochSeconds / 60));
		setScore(server, source, "#today", (int) today.toEpochDay());
		setScore(server, source, "#mod", now.getHour() * 60 + now.getMinute());
		setScore(server, source, "#clock", 1);
		updateCounters(server);
	}

	private static void setScore(MinecraftServer server, CommandSourceStack source, String holder, int value) {
		server.getCommands().performPrefixedCommand(source, "scoreboard players set " + holder + " " + OBJECTIVE + " " + value);
	}

	// ------------------------------------------------------------------ utilitare

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

	private static CommandSourceStack serverSource(MinecraftServer server) {
		return server.createCommandSourceStack().withSuppressedOutput();
	}

	private static void run(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(serverSource(server), command);
	}

	/** Ruleaza o comanda ca jucatorul care a scris comanda scurta. */
	private static int asPlayer(CommandContext<CommandSourceStack> context, String command) {
		ServerPlayer player = context.getSource().getPlayer();
		if (player == null) {
			return 0;
		}
		run(context.getSource().getServer(), "execute as " + player.getStringUUID() + " run " + command);
		return 1;
	}

	private static String stamp() {
		return ZonedDateTime.now(ZONE).format(STAMP);
	}

	private static String clean(String text) {
		return text.replaceAll("[^\\p{L}\\p{N}_ .,:;!?()\\-]", "").trim();
	}

	private static void tell(MinecraftServer server, ServerPlayer player, String text, String color) {
		run(server, "tellraw " + player.getStringUUID() + " {\"text\":\"" + text.replace("\"", "'") + "\",\"color\":\"" + color + "\"}");
	}

	private static void appendLog(String fileName, String line) {
		try {
			Files.createDirectories(LOG_DIR);
			Files.writeString(LOG_DIR.resolve(fileName), stamp() + " " + line + System.lineSeparator(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOGGER.warn("Nu pot scrie jurnalul {}: {}", fileName, e.toString());
		}
	}

	/**
	 * Staff = scor rb_smask >= 1 (Owner, Admin, Server Engineer, Tester). rb_smask e masca persistenta pe care o
	 * calculeaza datapack-ul; rb_staff e doar comanda one-shot care se reseteaza la 0 dupa fiecare sincronizare.
	 */
	private static boolean isStaff(MinecraftServer server, ServerPlayer player) {
		Objective objective = server.getScoreboard().getObjective("rb_smask");
		if (objective == null) {
			return false;
		}
		ReadOnlyScoreInfo info = server.getScoreboard().getPlayerScoreInfo(player, objective);
		return info != null && info.value() >= 1;
	}

	/** Verifica staff-ul; daca nu e, trimite mesaj si intoarce null. */
	private static ServerPlayer staffOnly(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = context.getSource().getPlayer();
		if (player == null) {
			return null;
		}
		MinecraftServer server = context.getSource().getServer();
		if (!isStaff(server, player)) {
			tell(server, player, "Nu ai permisiunea pentru aceasta comanda. / No permission.", "red");
			return null;
		}
		return player;
	}

	/** Comanda de admin: ruleaza o functie ca staff-ul care a scris-o. */
	private static int admin(CommandContext<CommandSourceStack> context, String command) {
		ServerPlayer staff = staffOnly(context);
		if (staff == null) {
			return 0;
		}
		run(context.getSource().getServer(), "execute as " + staff.getStringUUID() + " run " + command);
		return 1;
	}

	/** Comanda de admin asupra altui jucator (nume validat). */
	private static int adminOn(CommandContext<CommandSourceStack> context, String targetName, String command) {
		ServerPlayer staff = staffOnly(context);
		if (staff == null) {
			return 0;
		}
		if (!NAME.matcher(targetName).matches()) {
			tell(context.getSource().getServer(), staff, "Nume de jucator invalid.", "red");
			return 0;
		}
		run(context.getSource().getServer(), "execute as " + targetName + " run " + command);
		return 1;
	}

	// ------------------------------------------------------------------ comenzi

	private static void registerCommands(CommandDispatcher<CommandSourceStack> d) {
		registerPlayerCommands(d);
		registerAdminCommands(d);
		registerBanCommands(d);
		registerReportCommands(d);
		registerSetAdmin(d);
		registerTeleportCommands(d);
	}

	private static void registerPlayerCommands(CommandDispatcher<CommandSourceStack> d) {
		reg(d, names("reborn"), n -> Commands.literal(n).executes(c -> asPlayer(c, "trigger reborn")));
		reg(d, names("rebornstatue", "RebornStatue"), n -> Commands.literal(n).executes(c -> asPlayer(c, "trigger reborn set 9")));
		reg(d, names("myreborn", "MyReborn"), n -> Commands.literal(n).executes(c -> asPlayer(c, "trigger reborn_info")));
		reg(d, names("shardshop", "ShardShop"), n -> Commands.literal(n).executes(c -> asPlayer(c, "trigger shardshop")));
		reg(d, names("bank"), n -> Commands.literal(n).executes(c -> asPlayer(c, "trigger bankinfo")));
		reg(d, names("missions"), n -> Commands.literal(n).executes(c -> asPlayer(c, "trigger missions set 1")));
		reg(d, names("pinned"), n -> Commands.literal(n).executes(c -> asPlayer(c, "trigger missions set 8")));
		reg(d, names("commands"), n -> Commands.literal(n).executes(c -> asPlayer(c, "trigger shardshop set 50")));
		reg(d, names("admincommands", "AdminCommands"), n -> Commands.literal(n).executes(c -> asPlayer(c, "trigger shardshop set 57")));

		reg(d, names("hud"), n -> {
			LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(n).executes(c -> asPlayer(c, "trigger hud"));
			for (String v : names("show")) {
				b.then(Commands.literal(v).executes(c -> asPlayer(c, "execute if score @s hud_off matches 1 run trigger hud")));
			}
			for (String v : names("hide")) {
				b.then(Commands.literal(v).executes(c -> asPlayer(c, "execute unless score @s hud_off matches 1 run trigger hud")));
			}
			return b;
		});

		reg(d, names("language"), n -> {
			LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(n).executes(c -> asPlayer(c, "trigger changelanguage"));
			for (String v : names("ro", "romana", "Romana")) {
				b.then(Commands.literal(v).executes(c -> asPlayer(c, "trigger changelanguage set 2")));
			}
			for (String v : names("en", "english", "English")) {
				b.then(Commands.literal(v).executes(c -> asPlayer(c, "trigger changelanguage set 3")));
			}
			return b;
		});

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
			for (String v : names("money")) {
				b.then(Commands.literal(v).then(Commands.argument("amount", IntegerArgumentType.integer(1, 1000000))
					.executes(c -> asPlayer(c, "trigger withdrawmoney set " + IntegerArgumentType.getInteger(c, "amount")))));
			}
			return b;
		});

		reg(d, names("deposit"), n -> {
			LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(n);
			for (String v : names("shards")) {
				LiteralArgumentBuilder<CommandSourceStack> shards = Commands.literal(v).executes(c -> asPlayer(c, "trigger depositshards"));
				for (String a : names("all")) {
					shards.then(Commands.literal(a).executes(c -> asPlayer(c, "trigger depositshards")));
				}
				b.then(shards);
			}
			for (String v : names("money")) {
				b.then(Commands.literal(v).executes(c -> asPlayer(c, "trigger depositmoney")));
			}
			return b;
		});
	}

	private static void registerAdminCommands(CommandDispatcher<CommandSourceStack> d) {
		// /admintest force | test on|off | debug | simwither | simwarden
		reg(d, names("admintest", "AdminTest"), n -> {
			LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(n);
			b.then(Commands.literal("force").executes(c -> admin(c, "function reborn:force")));
			b.then(Commands.literal("test")
				.then(Commands.literal("on").executes(c -> admin(c, "function reborn:test_on")))
				.then(Commands.literal("off").executes(c -> admin(c, "function reborn:test_off"))));
			b.then(Commands.literal("debug").executes(c -> admin(c, "function reborn:admin_debug")));
			b.then(Commands.literal("diag").executes(c -> admin(c, "function reborn:diag")));
			b.then(Commands.literal("simwither").executes(c -> admin(c, "function reborn:admin_sim_wither")));
			b.then(Commands.literal("simwarden").executes(c -> admin(c, "function reborn:admin_sim_warden")));
			return b;
		});

		// /adminplayer reset | resetall | reborn <n> | immortal on|off | dragon on|off
		reg(d, names("adminplayer", "AdminPlayer"), n -> {
			LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(n);
			b.then(Commands.literal("reset").executes(c -> admin(c, "function reborn:admin_reset")));
			b.then(Commands.literal("resetall").executes(c -> admin(c, "function reborn:admin_reset_all")));
			b.then(Commands.literal("reborn").then(Commands.argument("n", IntegerArgumentType.integer(0, 10))
				.executes(c -> admin(c, "function reborn:admin_reborn_set {n:" + IntegerArgumentType.getInteger(c, "n") + "}"))));
			b.then(Commands.literal("immortal")
				.then(Commands.literal("on").executes(c -> admin(c, "function reborn:admin_immortal_on")))
				.then(Commands.literal("off").executes(c -> admin(c, "function reborn:admin_immortal_off"))));
			b.then(Commands.literal("dragon")
				.then(Commands.literal("on").executes(c -> admin(c, "function reborn:admin_dragon_on")))
				.then(Commands.literal("off").executes(c -> admin(c, "function reborn:admin_dragon_off"))));
			return b;
		});

		// /adminshards add|set <n> | add|set <jucator> <n> | clear [jucator]
		reg(d, names("adminshards", "AdminShards"), n -> {
			LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(n);
			for (String op : new String[] {"add", "set"}) {
				b.then(Commands.literal(op)
					.then(Commands.argument("amount", IntegerArgumentType.integer(-100000, 100000))
						.executes(c -> admin(c, "function reborn:admin_shards_" + op + " {n:" + IntegerArgumentType.getInteger(c, "amount") + "}")))
					.then(Commands.argument("player", StringArgumentType.word())
						.then(Commands.argument("amount", IntegerArgumentType.integer(-100000, 100000))
							.executes(c -> adminOn(c, StringArgumentType.getString(c, "player"),
								"function reborn:admin_shards_" + op + " {n:" + IntegerArgumentType.getInteger(c, "amount") + "}")))));
			}
			b.then(Commands.literal("clear").executes(c -> admin(c, "function reborn:admin_shards_clear"))
				.then(Commands.argument("player", StringArgumentType.word())
					.executes(c -> adminOn(c, StringArgumentType.getString(c, "player"), "function reborn:admin_shards_clear"))));
			return b;
		});

		// /adminboss resetwk | mvpreset
		reg(d, names("adminboss", "AdminBoss"), n -> {
			LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(n);
			b.then(Commands.literal("resetwk").executes(c -> admin(c, "function reborn:admin_wk_reset")));
			b.then(Commands.literal("mvpreset").executes(c -> admin(c, "function reborn:admin_mvp_reset")));
			return b;
		});

		// /adminserver restart <min> | hudpurge | statuewipe | statuebold | date <zz.ll.aaaa>
		reg(d, names("adminserver", "AdminServer"), n -> {
			LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(n);
			b.then(Commands.literal("restart").then(Commands.argument("m", IntegerArgumentType.integer(1, 120))
				.executes(c -> admin(c, "function reborn:admin_restart {m:" + IntegerArgumentType.getInteger(c, "m") + "}"))));
			b.then(Commands.literal("hudpurge").executes(c -> admin(c, "function reborn:admin_hud_purge")));
			b.then(Commands.literal("statuewipe").executes(c -> admin(c, "function reborn:admin_statues_wipe")));
			b.then(Commands.literal("statuebold").executes(c -> admin(c, "function reborn:admin_statues_bold")));
			b.then(Commands.literal("date").then(Commands.argument("d", StringArgumentType.word()).executes(c -> {
				String date = StringArgumentType.getString(c, "d");
				if (!date.matches("[0-9.]{6,10}")) {
					return 0;
				}
				return admin(c, "function reborn:admin_date {d:\"" + date + "\"}");
			})));
			return b;
		});

		// /adminscoreboard get <setare> | set <setare> <valoare>
		reg(d, names("adminscoreboard", "AdminScoreboard"), n -> {
			LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(n);
			b.then(Commands.literal("get").then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
				ServerPlayer staff = staffOnly(c);
				if (staff == null) {
					return 0;
				}
				String holder = SETTINGS.get(StringArgumentType.getString(c, "name").toLowerCase());
				MinecraftServer server = c.getSource().getServer();
				if (holder == null) {
					tell(server, staff, "Setari: " + String.join(", ", SETTINGS.keySet()), "gray");
					return 0;
				}
				run(server, "tellraw " + staff.getStringUUID() + " [{\"text\":\"" + holder + " = \",\"color\":\"gray\"},{\"score\":{\"name\":\"" + holder
					+ "\",\"objective\":\"" + OBJECTIVE + "\"},\"color\":\"green\"}]");
				return 1;
			})));
			b.then(Commands.literal("set").then(Commands.argument("name", StringArgumentType.word())
				.then(Commands.argument("value", IntegerArgumentType.integer(0, 1000000)).executes(c -> {
					ServerPlayer staff = staffOnly(c);
					if (staff == null) {
						return 0;
					}
					String key = StringArgumentType.getString(c, "name").toLowerCase();
					String holder = SETTINGS.get(key);
					MinecraftServer server = c.getSource().getServer();
					if (holder == null) {
						tell(server, staff, "Setari: " + String.join(", ", SETTINGS.keySet()), "gray");
						return 0;
					}
					int value = IntegerArgumentType.getInteger(c, "value");
					run(server, "scoreboard players set " + holder + " " + OBJECTIVE + " " + value);
					appendLog("admin.log", staff.getScoreboardName() + " a setat " + key + " (" + holder + ") = " + value);
					tell(server, staff, key + " = " + value, "green");
					return 1;
				}))));
			return b;
		});

		// /admineconomy add|remove|set <jucator> <suma> | log
		reg(d, names("admineconomy", "AdminEconomy"), n -> {
			LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(n);
			for (String op : new String[] {"add", "remove", "set"}) {
				b.then(Commands.literal(op).then(Commands.argument("player", StringArgumentType.word())
					.then(Commands.argument("amount", IntegerArgumentType.integer(0, 100000000)).executes(c -> {
						ServerPlayer staff = staffOnly(c);
						if (staff == null) {
							return 0;
						}
						MinecraftServer server = c.getSource().getServer();
						String target = StringArgumentType.getString(c, "player");
						int amount = IntegerArgumentType.getInteger(c, "amount");
						if (!NAME.matcher(target).matches()) {
							tell(server, staff, "Nume de jucator invalid.", "red");
							return 0;
						}
						String at = stamp();
						run(server, "function reborn:eco_admin {op:\"" + op + "\",t:\"" + target + "\",s:\"" + staff.getScoreboardName()
							+ "\",n:" + amount + ",at:\"" + at + "\"}");
						appendLog("economy.log", staff.getScoreboardName() + " " + op + " " + amount + " -> " + target);
						return 1;
					}))));
			}
			b.then(Commands.literal("log").executes(c -> admin(c, "function reborn:eco_log_show")));
			return b;
		});
	}

	// ------------------------------------------------------------------ /tempban, /unban

	private static void registerBanCommands(CommandDispatcher<CommandSourceStack> d) {
		reg(d, names("tempban", "TempBan"), n -> Commands.literal(n)
			.then(Commands.argument("player", StringArgumentType.word())
				.then(Commands.argument("duration", StringArgumentType.word())
					.then(Commands.argument("reason", StringArgumentType.greedyString()).executes(c -> {
						ServerPlayer staff = staffOnly(c);
						if (staff == null) {
							return 0;
						}
						MinecraftServer server = c.getSource().getServer();
						String target = StringArgumentType.getString(c, "player");
						String durationText = StringArgumentType.getString(c, "duration");
						String reason = clean(StringArgumentType.getString(c, "reason"));
						if (!NAME.matcher(target).matches() || reason.isEmpty()) {
							tell(server, staff, "Folosire: /tempban <jucator> <durata: 0, 30m, 12h, 1d> <motiv>", "red");
							return 0;
						}
						long seconds = parseDuration(durationText);
						if (seconds < 0) {
							tell(server, staff, "Durata invalida. Exemple: 0 (permanent), 30m, 12h, 1d.", "red");
							return 0;
						}
						String expiryText = "permanent";
						if (seconds > 0) {
							long expiry = Instant.now().getEpochSecond() + seconds;
							TEMPBANS.put(target.toLowerCase(), new String[] {target, Long.toString(expiry), staff.getScoreboardName(), reason});
							saveBans();
							expiryText = durationText;
						} else {
							TEMPBANS.remove(target.toLowerCase());
							saveBans();
						}
						run(server, "ban " + target + " " + reason);
						appendLog("bans.log", staff.getScoreboardName() + " TEMPBAN " + target + " durata=" + expiryText + " motiv=" + reason);
						tell(server, staff, "Ban aplicat: " + target + " (" + expiryText + ").", "green");
						staffChat(server, line("[BAN] ", "red", staff.getScoreboardName() + " a banat " + target + " (" + expiryText + "): " + reason, "gray"));
						return 1;
					})))));

		reg(d, names("unban", "UnBan"), n -> Commands.literal(n)
			.then(Commands.argument("player", StringArgumentType.word())
				.then(Commands.argument("reason", StringArgumentType.greedyString()).executes(c -> {
					ServerPlayer staff = staffOnly(c);
					if (staff == null) {
						return 0;
					}
					MinecraftServer server = c.getSource().getServer();
					String target = StringArgumentType.getString(c, "player");
					String reason = clean(StringArgumentType.getString(c, "reason"));
					if (!NAME.matcher(target).matches() || reason.isEmpty()) {
						tell(server, staff, "Folosire: /unban <jucator> <motiv>", "red");
						return 0;
					}
					TEMPBANS.remove(target.toLowerCase());
					saveBans();
					run(server, "pardon " + target);
					appendLog("bans.log", staff.getScoreboardName() + " UNBAN " + target + " motiv=" + reason);
					tell(server, staff, "Ban scos: " + target + ".", "green");
					staffChat(server, line("[UNBAN] ", "green", staff.getScoreboardName() + " a scos banul lui " + target + ": " + reason, "gray"));
					return 1;
				}))));
	}

	/** 0 = permanent, -1 = invalid, altfel secunde. Fara unitate = zile. */
	private static long parseDuration(String text) {
		if (text.equals("0")) {
			return 0;
		}
		Matcher m = DURATION.matcher(text.toLowerCase());
		if (!m.matches()) {
			return -1;
		}
		long value = Long.parseLong(m.group(1));
		if (value <= 0 || value > 100000) {
			return -1;
		}
		String unit = m.group(2);
		return switch (unit) {
			case "m" -> value * 60;
			case "h" -> value * 3600;
			default -> value * 86400;
		};
	}

	private static void checkExpiredBans(MinecraftServer server) {
		if (TEMPBANS.isEmpty()) {
			return;
		}
		long now = Instant.now().getEpochSecond();
		List<String> expired = new ArrayList<>();
		for (Map.Entry<String, String[]> e : TEMPBANS.entrySet()) {
			if (now >= Long.parseLong(e.getValue()[1])) {
				expired.add(e.getKey());
			}
		}
		for (String key : expired) {
			String[] entry = TEMPBANS.remove(key);
			run(server, "pardon " + entry[0]);
			appendLog("bans.log", "AUTO-UNBAN " + entry[0] + " (expirat; banat de " + entry[2] + ", motiv=" + entry[3] + ")");
			staffChat(server, line("[UNBAN] ", "green", "Banul lui " + entry[0] + " a expirat.", "gray"));
		}
		if (!expired.isEmpty()) {
			saveBans();
		}
	}

	private static void loadBans() {
		TEMPBANS.clear();
		try {
			if (Files.exists(BAN_FILE)) {
				for (String line : Files.readAllLines(BAN_FILE, StandardCharsets.UTF_8)) {
					String[] p = line.split("\t", 4);
					if (p.length == 4) {
						TEMPBANS.put(p[0].toLowerCase(), new String[] {p[0], p[1], p[2], p[3]});
					}
				}
			}
		} catch (IOException e) {
			LOGGER.warn("Nu pot citi {}: {}", BAN_FILE, e.toString());
		}
	}

	private static void saveBans() {
		try {
			Files.createDirectories(BAN_FILE.getParent());
			List<String> lines = new ArrayList<>();
			for (String[] e : TEMPBANS.values()) {
				lines.add(e[0] + "\t" + e[1] + "\t" + e[2] + "\t" + e[3]);
			}
			Files.write(BAN_FILE, lines, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("Nu pot scrie {}: {}", BAN_FILE, e.toString());
		}
	}

	// ------------------------------------------------------------------ chat staff

	private static String esc(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static String line(String prefix, String prefixColor, String text, String color) {
		return "[{\"text\":\"" + esc(prefix) + "\",\"color\":\"" + prefixColor + "\",\"bold\":true},{\"text\":\"" + esc(text) + "\",\"color\":\"" + color + "\"}]";
	}

	/** Mesaj in chat pentru tot staff-ul online (scor rb_smask >= 1). */
	private static void staffChat(MinecraftServer server, String json) {
		run(server, "tellraw @a[scores={rb_smask=1..}] " + json);
	}

	// ------------------------------------------------------------------ ticket-uri (/report, /acceptreport, /closereport, /r)

	private static final class Report {
		String name;
		String type;
		String text;
		String time;
		String staff = "";
	}

	/** nume jucator (litere mici) -> ticket */
	private static final Map<String, Report> REPORTS = new LinkedHashMap<>();
	/** nume (litere mici) -> numele partenerului din conversatia privata */
	private static final Map<String, String> PARTNER = new LinkedHashMap<>();
	private static final Path REPORT_FILE = Path.of("config", "bvr-core-reports.txt");

	private static void updateCounters(MinecraftServer server) {
		int pending = 0;
		for (Report r : REPORTS.values()) {
			if (r.staff.isEmpty()) {
				pending++;
			}
		}
		CommandSourceStack source = serverSource(server);
		setScore(server, source, "#rep_open", pending);
		setScore(server, source, "#flag_open", 0);
	}

	private static void registerReportCommands(CommandDispatcher<CommandSourceStack> d) {
		reg(d, names("report"), n -> {
			LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(n);
			for (String type : new String[] {"cheating", "stuck", "other"}) {
				b.then(Commands.literal(type).executes(c -> openReport(c, type, ""))
					.then(Commands.argument("message", StringArgumentType.greedyString())
						.executes(c -> openReport(c, type, StringArgumentType.getString(c, "message")))));
			}
			return b;
		});

		reg(d, names("acceptreport", "AcceptReport", "ar", "AR"), n -> Commands.literal(n)
			.then(Commands.argument("player", StringArgumentType.word()).executes(c -> {
				ServerPlayer staff = staffOnly(c);
				if (staff == null) {
					return 0;
				}
				MinecraftServer server = c.getSource().getServer();
				String target = StringArgumentType.getString(c, "player");
				Report r = REPORTS.get(target.toLowerCase());
				if (r == null) {
					tell(server, staff, "Nu exista un ticket deschis de acest jucator.", "red");
					return 0;
				}
				String staffName = staff.getScoreboardName();
				r.staff = staffName;
				PARTNER.put(staffName.toLowerCase(), r.name);
				PARTNER.put(r.name.toLowerCase(), staffName);
				saveReports();
				run(server, "tellraw " + r.name + " " + line("[TICKET] ", "gold", "Adminul " + staffName + " a acceptat ticketul tău. Folosește /r <mesaj> ca să scrii pe chat cu adminul. / Admin " + staffName + " accepted your ticket. Use /r <message> to chat with the admin.", "yellow"));
				run(server, "tellraw " + staff.getStringUUID() + " " + line("[TICKET] ", "gold", "Ai acceptat ticketul lui " + r.name + ". Folosește /r <mesaj> ca să scrii cu jucătorul. Pentru a închide ticketul folosește /closereport sau /cr.", "yellow"));
				staffChat(server, line("[TICKET] ", "red", staffName + " a acceptat ticketul lui " + r.name + ".", "gray"));
				appendLog("tickets.log", staffName + " ACCEPT " + r.name);
				updateCounters(server);
				return 1;
			})));

		reg(d, names("closereport", "CloseReport", "cr", "CR"), n -> Commands.literal(n).executes(c -> {
			ServerPlayer p = c.getSource().getPlayer();
			if (p == null) {
				return 0;
			}
			MinecraftServer server = c.getSource().getServer();
			String me = p.getScoreboardName();
			String partner = PARTNER.remove(me.toLowerCase());
			if (partner != null) {
				PARTNER.remove(partner.toLowerCase());
			}
			Report r = REPORTS.remove(me.toLowerCase());
			if (r == null && partner != null) {
				r = REPORTS.remove(partner.toLowerCase());
			}
			if (r == null && partner == null) {
				tell(server, p, "Nu ai niciun ticket. / You have no ticket.", "red");
				return 0;
			}
			saveReports();
			String who = r != null ? r.name : me;
			if (partner != null) {
				run(server, "tellraw " + partner + " " + line("[Ticket] ", "gold", "Ticketul a fost închis. / The ticket was closed.", "gray"));
			}
			tell(server, p, "Ticket inchis. / Ticket closed.", "green");
			staffChat(server, line("[TICKET] ", "red", me + " a inchis ticketul lui " + who + ".", "gray"));
			appendLog("tickets.log", me + " CLOSE " + who);
			updateCounters(server);
			return 1;
		}));

		reg(d, names("reports", "Reports"), n -> Commands.literal(n).executes(c -> {
			ServerPlayer staff = staffOnly(c);
			if (staff == null) {
				return 0;
			}
			MinecraftServer server = c.getSource().getServer();
			if (REPORTS.isEmpty()) {
				tell(server, staff, "Nu exista tickete deschise.", "gray");
				return 1;
			}
			for (Report r : REPORTS.values()) {
				String state = r.staff.isEmpty() ? "nepreluat" : "preluat de " + r.staff;
				run(server, "tellraw " + staff.getStringUUID() + " " + line("[TICKET] ", "red", r.name + " (" + r.type + ", " + state + ", " + r.time + ")" + (r.text.isEmpty() ? "" : ": " + r.text), "gray"));
			}
			return 1;
		}));

		// /r <mesaj>: chat privat intre staff si jucator dupa /acceptreport
		reg(d, names("r"), n -> Commands.literal(n)
			.then(Commands.argument("message", StringArgumentType.greedyString()).executes(c -> {
				ServerPlayer p = c.getSource().getPlayer();
				if (p == null) {
					return 0;
				}
				MinecraftServer server = c.getSource().getServer();
				String me = p.getScoreboardName();
				String partner = PARTNER.get(me.toLowerCase());
				if (partner == null) {
					tell(server, p, "Nu ai un ticket activ. / You have no active ticket.", "red");
					return 0;
				}
				String message = clean(StringArgumentType.getString(c, "message"));
				if (message.isEmpty()) {
					return 0;
				}
				String json = "[{\"text\":\"[Ticket] \",\"color\":\"gold\"},{\"text\":\"" + esc(me) + ": \",\"color\":\"white\"},{\"text\":\"" + esc(message) + "\",\"color\":\"yellow\"}]";
				run(server, "tellraw " + p.getStringUUID() + " " + json);
				run(server, "tellraw " + partner + " " + json);
				appendLog("tickets.log", me + " -> " + partner + ": " + message);
				return 1;
			})));
	}

	private static int openReport(CommandContext<CommandSourceStack> context, String type, String message) {
		ServerPlayer p = context.getSource().getPlayer();
		if (p == null) {
			return 0;
		}
		MinecraftServer server = context.getSource().getServer();
		String text = clean(message);
		if (text.length() > 120) {
			text = text.substring(0, 120);
		}
		Report r = new Report();
		r.name = p.getScoreboardName();
		r.type = type;
		r.text = text;
		r.time = stamp();
		REPORTS.put(r.name.toLowerCase(), r);
		saveReports();
		tell(server, p, "Ticket trimis (" + type + "). Un admin te va contacta. / Ticket sent. An admin will contact you.", "green");
		staffChat(server, line("[TICKET] ", "red", r.name + " a deschis un ticket (" + type + ")" + (text.isEmpty() ? "" : ": " + text) + ". Acceptă cu /ar " + r.name, "gray"));
		appendLog("tickets.log", r.name + " OPEN type=" + type + " text=" + text);
		updateCounters(server);
		return 1;
	}

	private static void loadReports() {
		REPORTS.clear();
		try {
			if (Files.exists(REPORT_FILE)) {
				for (String lineText : Files.readAllLines(REPORT_FILE, StandardCharsets.UTF_8)) {
					String[] p = lineText.split("\t", 5);
					if (p.length == 5) {
						Report r = new Report();
						r.name = p[0];
						r.type = p[1];
						r.time = p[2];
						r.staff = p[3];
						r.text = p[4];
						REPORTS.put(r.name.toLowerCase(), r);
					}
				}
			}
		} catch (IOException e) {
			LOGGER.warn("Nu pot citi {}: {}", REPORT_FILE, e.toString());
		}
	}

	private static void saveReports() {
		try {
			Files.createDirectories(REPORT_FILE.getParent());
			List<String> lines = new ArrayList<>();
			for (Report r : REPORTS.values()) {
				lines.add(r.name + "\t" + r.type + "\t" + r.time + "\t" + r.staff + "\t" + r.text);
			}
			Files.write(REPORT_FILE, lines, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("Nu pot scrie {}: {}", REPORT_FILE, e.toString());
		}
	}

	// ------------------------------------------------------------------ /setadmin <grad> <jucator>

	private static boolean isOwner(MinecraftServer server, ServerPlayer player) {
		Objective objective = server.getScoreboard().getObjective("rb_smask");
		if (objective == null) {
			return false;
		}
		ReadOnlyScoreInfo info = server.getScoreboard().getPlayerScoreInfo(player, objective);
		return info != null && (info.value() & 1) == 1;
	}

	private static void registerSetAdmin(CommandDispatcher<CommandSourceStack> d) {
		reg(d, names("setadmin", "SetAdmin"), n -> {
			LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(n);
			String[][] ranks = {
				{"owner", "1"}, {"admin", "2"}, {"engineer", "3"}, {"serverengineer", "3"}, {"se", "3"},
				{"tester", "4"}, {"none", "0"}
			};
			for (String[] rank : ranks) {
				b.then(Commands.literal(rank[0]).then(Commands.argument("player", StringArgumentType.word())
					.executes(c -> setAdmin(c, rank[0], Integer.parseInt(rank[1]), StringArgumentType.getString(c, "player")))));
			}
			return b;
		});
	}

	private static int setAdmin(CommandContext<CommandSourceStack> context, String rankName, int code, String target) {
		MinecraftServer server = context.getSource().getServer();
		ServerPlayer caller = context.getSource().getPlayer();
		if (caller != null && !isOwner(server, caller)) {
			tell(server, caller, "Doar Owner-ul poate folosi /setadmin.", "red");
			return 0;
		}
		String name = clean(target);
		if (name.isEmpty() || name.contains(" ")) {
			return 0;
		}
		if (code == 0) {
			run(server, "scoreboard players set " + name + " rb_so 0");
			run(server, "scoreboard players set " + name + " rb_sa 0");
			run(server, "scoreboard players set " + name + " rb_sse 0");
			run(server, "scoreboard players set " + name + " rb_st 0");
			run(server, "scoreboard players set " + name + " rb_staff 0");
			run(server, "scoreboard players set " + name + " rb_smask 0");
		} else {
			// team_sync citeste rb_staff (1 Owner, 2 Admin, 3 Server Engineer, 4 Tester) si aprinde steagul gradului
			run(server, "scoreboard players set " + name + " rb_staff " + code);
		}
		String by = caller != null ? caller.getScoreboardName() : "Consola";
		String text = code == 0
			? by + " a scos toate gradele lui " + name + "."
			: by + " i-a dat lui " + name + " gradul " + rankName + ".";
		staffChat(server, line("[STAFF] ", "gold", text, "gray"));
		if (caller != null) {
			tell(server, caller, text + " (se aplică în câteva secunde)", "green");
		}
		appendLog("staff.log", text);
		return 1;
	}

	// ------------------------------------------------------------------ /goto, /gethere (doar staff)

	private static void registerTeleportCommands(CommandDispatcher<CommandSourceStack> d) {
		reg(d, names("goto", "GoTo"), n -> Commands.literal(n)
			.then(Commands.argument("player", StringArgumentType.word())
				.executes(c -> teleportStaff(c, StringArgumentType.getString(c, "player"), true))));
		reg(d, names("gethere", "GetHere"), n -> Commands.literal(n)
			.then(Commands.argument("player", StringArgumentType.word())
				.executes(c -> teleportStaff(c, StringArgumentType.getString(c, "player"), false))));
	}

	/** toTarget = true: staff-ul merge la jucator (/goto); false: jucatorul vine la staff (/gethere). */
	private static int teleportStaff(CommandContext<CommandSourceStack> context, String targetName, boolean toTarget) {
		ServerPlayer staff = staffOnly(context);
		if (staff == null) {
			return 0;
		}
		MinecraftServer server = context.getSource().getServer();
		String target = clean(targetName);
		String me = staff.getScoreboardName();
		if (target.isEmpty() || target.contains(" ")) {
			return 0;
		}
		if (target.equalsIgnoreCase(me)) {
			tell(server, staff, "Nu poți folosi comanda pe tine însuți.", "red");
			return 0;
		}
		String who = staff.getStringUUID();
		if (toTarget) {
			run(server, "execute if entity " + target + " run tp " + me + " " + target);
			run(server, "execute if entity " + target + " run tellraw " + who + " " + line("[TP] ", "aqua", "Te-ai teleportat la " + target + ".", "gray"));
		} else {
			run(server, "execute if entity " + target + " run tp " + target + " " + me);
			run(server, "execute if entity " + target + " run tellraw " + who + " " + line("[TP] ", "aqua", target + " a fost adus la tine.", "gray"));
		}
		run(server, "execute unless entity " + target + " run tellraw " + who + " " + line("[TP] ", "red", "Jucătorul " + target + " nu este online.", "gray"));
		appendLog("tp.log", me + (toTarget ? " GOTO " : " GETHERE ") + target);
		return 1;
	}
}
