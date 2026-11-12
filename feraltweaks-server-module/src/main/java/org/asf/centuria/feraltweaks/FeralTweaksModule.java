package org.asf.centuria.feraltweaks;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.asf.centuria.feraltweaks.handlers.ChatMessageHandler;
import org.asf.centuria.feraltweaks.handlers.CommandHandlers;
import org.asf.centuria.feraltweaks.handlers.DisconnectHandler;
import org.asf.centuria.feraltweaks.handlers.handshake.GameHandshakeHandler;
import org.asf.centuria.feraltweaks.handlers.handshake.ChatHandshakeHandler;
import org.asf.centuria.feraltweaks.managers.PlayerNameManager;
import org.asf.centuria.feraltweaks.managers.ScheduledMaintenanceManager;
import org.asf.centuria.feraltweaks.networking.chat.MarkConvoReadPacket;
import org.asf.centuria.feraltweaks.networking.chat.SubscribeTypingStatusPacket;
import org.asf.centuria.feraltweaks.networking.chat.TypingStatusPacket;
import org.asf.centuria.feraltweaks.networking.game.FtModPacket;
import org.asf.centuria.feraltweaks.networking.game.YesNoPopupPacket;
import org.asf.centuria.feraltweaks.networking.http.DataProcessor;
import org.asf.centuria.modules.ICenturiaModule;
import org.asf.centuria.modules.ModuleManager;
import org.asf.centuria.modules.eventbus.EventBus;
import org.asf.centuria.modules.eventbus.EventListener;
import org.asf.centuria.modules.events.accounts.MiscModerationEvent;
import org.asf.centuria.modules.events.servers.APIServerStartupEvent;
import org.asf.centuria.modules.events.servers.ChatServerStartupEvent;
import org.asf.centuria.modules.events.servers.GameServerStartupEvent;

/**
 * 
 * FeralTweaks Server Module
 * 
 * @author Sky Swimmer
 *
 */
public class FeralTweaksModule implements ICenturiaModule {

	/**
	 * FeralTweaks Protocol Version
	 */
	public static int FT_VERSION = 2;

	public boolean enableByDefault;
	public boolean requireManagedSaveData;
	public boolean preventNonFTClients;

	public String ftDataPath;
	public String ftUserPatchesPath;
	public String ftCachePath;
	public String upstreamServerJsonURL;

	public String ftUnsupportedErrorMessage;
	public String ftOutdatedErrorMessage;
	public String modDataVersion;

	private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

	public HashMap<String, Boolean> replicatingObjects = new HashMap<String, Boolean>();

	private Logger logger = LogManager.getLogger("FeralTweaks");

	@Override
	public String id() {
		return "feraltweaks";
	}

	@Override
	public String version() {
		return "beta-1.0.0-b3";
	}

	@Override
	public void init() {
		// Check config
		File configFile = new File("feraltweaks.conf");
		if (!configFile.exists()) {
			// Write config
			try {
				Files.writeString(configFile.toPath(), "" //
						+ "enable-by-default=false\n" //
						+ "prevent-non-ft-clients=true\n" //
						+ "data-path=feraltweaks/content\n" //
						+ "userpatch-path=feraltweaks/userpatches\n" //
						+ "cache-path=feraltweaks/cache\n" //
						+ "upstream-server-json-source=https://emuferal.ddns.net:6970/\n" //
						+ "error-unauthorized=\\nFeralTweaks is presently not enabled on your account!\\n\\nPlease uninstall the client modding project, contact the server administrator if you believe this is an error.\n" //
						+ "error-outdated=Incompatible client!\\nYour client is currently out of date, restart the game to update the client mods.\n" //
						+ "mod-data-version=1\n"

				);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		// Read config
		HashMap<String, String> properties = new HashMap<String, String>();
		try {
			for (String line : Files.readAllLines(configFile.toPath())) {
				if (line.startsWith("#") || line.isBlank())
					continue;
				String key = line;
				String value = "";
				if (key.contains("=")) {
					value = key.substring(key.indexOf("=") + 1);
					key = key.substring(0, key.indexOf("="));
					if (key.startsWith("set-replication-for:")) {
						replicatingObjects.put(key.substring("set-replication-for:".length()),
								value.equalsIgnoreCase("enabled"));
					}
				}
				properties.put(key, value);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		// Load config
		enableByDefault = properties.getOrDefault("enable-by-default", "false").equalsIgnoreCase("true");
		preventNonFTClients = properties.getOrDefault("prevent-non-ft-clients", "false").equalsIgnoreCase("true");
		ftDataPath = properties.getOrDefault("data-path", "feraltweaks/content");
		ftUserPatchesPath = properties.getOrDefault("userpatch-path", "feraltweaks/userpatches");
		ftCachePath = properties.getOrDefault("cache-path", "feraltweaks/cache");
		upstreamServerJsonURL = properties.getOrDefault("upstream-server-json-source",
				"https://emuferal.ddns.net:6970/");
		ftOutdatedErrorMessage = properties.getOrDefault("error-outdated",
				"\nIncompatible client!\nYour client is currently out of date, restart the game to update the client mods.")
				.replaceAll("\\\\n", "\n");
		ftUnsupportedErrorMessage = properties.getOrDefault("error-unauthorized",
				"FeralTweaks is presently not enabled on your account!\\n\\nPlease uninstall the client modding project, contact the server administrator if you believe this is an error.")
				.replaceAll("\\\\n", "\n");
		modDataVersion = properties.getOrDefault("mod-data-version", "1");
		requireManagedSaveData = properties.getOrDefault("require-managed-saves", "false").equalsIgnoreCase("true");

		// Create data folders
		if (!new File(ftCachePath + "/moduledata").exists())
			new File(ftCachePath + "/moduledata").mkdirs();
		if (!new File(ftDataPath + "/feraltweaks/chartpatches").exists())
			new File(ftDataPath + "/feraltweaks/chartpatches").mkdirs();
		if (!new File(ftDataPath + "/clientmods/assemblies").exists())
			new File(ftDataPath + "/clientmods/assemblies").mkdirs();
		if (!new File(ftDataPath + "/clientmods/assets").exists())
			new File(ftDataPath + "/clientmods/assets").mkdirs();
		if (!new File(ftUserPatchesPath + "/feraltweaks/chartpatches").exists())
			new File(ftUserPatchesPath + "/feraltweaks/chartpatches").mkdirs();
		if (!new File(ftUserPatchesPath + "/clientmods/assemblies").exists())
			new File(ftUserPatchesPath + "/clientmods/assemblies").mkdirs();
		if (!new File(ftUserPatchesPath + "/clientmods/assets").exists())
			new File(ftUserPatchesPath + "/clientmods/assets").mkdirs();

		// Update module caches
		HashMap<String, File> moduleSources = new HashMap<String, File>();
		logger.info("Preparing contentserver... Finding module packages...");
		for (ICenturiaModule module : ModuleManager.getInstance().getAllModules()) {
			URL loc = module.getClass().getProtectionDomain().getCodeSource().getLocation();
			String f;
			try {
				f = URLDecoder.decode(loc.getFile(), "UTF-8");
			} catch (UnsupportedEncodingException e) {
				logger.warn("Unable to load module source " + module.id() + ": " + loc.getFile()
						+ ", please report this as a bug!");
				continue;
			}
			File file = new File(f);
			if (!file.exists())
				logger.warn(
						"Unable to load module source " + module.id() + ": " + file + ", please report this as a bug!");
			if (!moduleSources.containsKey(module.id())) {
				logger.info("Found module: " + module.id() + ": " + file.getPath());
				moduleSources.put(module.id(), file);
			}
		}

		// Load content
		try {
			logger.info("Checking for content updates of modules...");
			HashMap<String, String> hashesCurrent = new HashMap<String, String>();
			HashMap<String, String> hashesLast = new HashMap<String, String>();
			File previouslyInstalled = new File(ftCachePath, "modulehashes.list");
			if (previouslyInstalled.exists()) {
				loadHashList(Files.readString(previouslyInstalled.toPath()), hashesLast);
			}
			for (String module : moduleSources.keySet()) {
				File path = moduleSources.get(module);
				if (!path.isDirectory()) {
					// Get hash
					FileInputStream fIn = new FileInputStream(path);
					hashesCurrent.put(module, sha256Hash(fIn));
					fIn.close();
				} else {
					// Add
					hashesCurrent.put(module, "debugging, reset");
				}
			}

			// Check for changes
			boolean changed = false;
			for (String module : hashesCurrent.keySet()) {
				File path = moduleSources.get(module);
				String last = hashesLast.get(module);
				String current = hashesCurrent.get(module);
				if (last == null) {
					// New
					if (!path.isDirectory()) {
						logger.info("Detected newly installed module: " + module + "!");
					} else {
						logger.info("Detected debug module: " + module + ", reinstalling!");
					}
					changed = true;
				} else if (!current.equals(last)) {
					// Update
					if (!path.isDirectory()) {
						logger.info("Detected changes to module: " + module + "!");
					} else {
						logger.info("Detected debug module: " + module + ", reinstalling!");
					}
					changed = true;
				}
			}

			// Find removed
			for (String module : hashesLast.keySet()) {
				if (!moduleSources.containsKey(module)) {
					logger.info("Detected removed module: " + module + "!");
					changed = true;
				}
			}

			// Add directories
			for (String module : moduleSources.keySet()) {
				File path = moduleSources.get(module);
				if (path.isDirectory()) {
					hashesCurrent.put(module, "debug");
				}
			}

			// Check result
			if (changed) {
				// Reinstall
				logger.info("Reinstalling content...");
				logger.info("Deleting current cache...");
				File cacheDir = new File(ftCachePath + "/moduledata");
				deleteDir(cacheDir);
				cacheDir.mkdirs();
				logger.info("Installing content packs into cache...");
				for (ICenturiaModule module : ModuleManager.getInstance().getAllModules()) {
					File source = moduleSources.get(module.id());
					logger.info("Installing content of " + module.id() + "...");
					if (source.isDirectory()) {
						// Copy direct
						File content = new File(new File(source, "servercontent"), "feraltweaks");
						if (content.exists()) {
							copyDir(content, cacheDir, "");
						}
					} else {
						// Open archive
						FileInputStream fIn = new FileInputStream(source);
						ZipInputStream archive = new ZipInputStream(fIn);
						while (true) {
							ZipEntry ent = archive.getNextEntry();
							if (ent == null)
								break; // End

							// Check entry
							String path = ent.getName().replace("\\", "/");
							while (path.startsWith("/"))
								path = path.substring(1);
							while (path.endsWith("/"))
								path = path.substring(0, path.length() - 1);
							while (path.contains("//"))
								path = path.replace("//", "/");
							if (path.startsWith("servercontent/feraltweaks/")) {
								// Found entry
								File out = new File(cacheDir, path.substring("servercontent/feraltweaks/".length()));
								if (ent.isDirectory()) {
									// Create
									out.mkdirs();
								} else {
									// Install
									logger.info("Installing: " + path.substring("servercontent/feraltweaks/".length()));
									FileOutputStream fO = new FileOutputStream(out);
									archive.transferTo(fO);
									fO.close();
								}
							}

							// Close
							archive.closeEntry();
						}
						archive.close();
						fIn.close();
					}
				}

				// Write
				logger.info("Writing updated list of modules...");
				FileOutputStream fO = new FileOutputStream(previouslyInstalled);
				for (String module : hashesCurrent.keySet()) {
					String hash = hashesCurrent.get(module);
					writeHashToList(fO, module, hash);
				}
				fO.close();
				logger.info("Done!");
			}
			logger.info("Content initialized!");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		// Init managers
		ScheduledMaintenanceManager.initMaintenanceManager();
		PlayerNameManager.getInstance().initPlayerNameManager();

		// Bind events
		EventBus.getInstance().addAllEventsFromReceiver(new CommandHandlers());
		EventBus.getInstance().addAllEventsFromReceiver(new ChatMessageHandler());
		EventBus.getInstance().addAllEventsFromReceiver(new DisconnectHandler());
		EventBus.getInstance().addAllEventsFromReceiver(new ChatHandshakeHandler());
		EventBus.getInstance().addAllEventsFromReceiver(new GameHandshakeHandler());
	}

	@EventListener
	public void gameServerStartup(GameServerStartupEvent event) {
		event.registerPacket(new YesNoPopupPacket());
		event.registerPacket(new FtModPacket());
	}

	@EventListener
	public void apiStartup(APIServerStartupEvent event) {
		// Register custom processors
		event.getServer().registerHandler(new DataProcessor());
	}

	@EventListener
	public void chatStartup(ChatServerStartupEvent event) {
		// Register custom chat packets
		event.registerPacket(new MarkConvoReadPacket());
		event.registerPacket(new TypingStatusPacket());
		event.registerPacket(new SubscribeTypingStatusPacket());
	}

	@EventListener
	public void miscModeration(MiscModerationEvent event) {
		// Check type
		if (event.getModerationEventID() == "permissions.update" && event.getTarget() != null) {
			// Update target's display
			PlayerNameManager.getInstance().updateAllPlayers(event.getTarget());
		}
	}

	private void loadHashList(String hashes, HashMap<String, String> build) {
		for (String line : hashes.replace("\r", "").split("\n")) {
			if (line.isEmpty() || !line.contains(": "))
				continue;
			String name = line.substring(0, line.indexOf(": ")).replace(";sp;", " ").replace(";cl;", ":")
					.replace(";sl;", ";");
			String hash = line.substring(line.indexOf(": ") + 2);
			build.put(name, hash);
		}
	}

	private String sha256Hash(InputStream stream) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			while (true) {
				byte[] data = new byte[20480];
				int i = stream.read(data);
				if (i <= 0)
					break;
				digest.update(data, 0, i);
			}
			return bytesToHex(digest.digest()).toLowerCase();
		} catch (NoSuchAlgorithmException | IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void writeHashToList(FileOutputStream fO, String key, String hash) throws IOException {
		fO.write((key.replace(";", ";sl;").replace(":", ";cl;").replace(" ", ";sp;") + ": " + hash + "\n")
				.getBytes("UTF-8"));
		fO.flush();
	}

	private String bytesToHex(byte[] bytes) {
		char[] hexChars = new char[bytes.length * 2];
		for (int j = 0; j < bytes.length; j++) {
			int v = bytes[j] & 0xFF;
			hexChars[j * 2] = HEX_ARRAY[v >>> 4];
			hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
		}
		return new String(hexChars);
	}

	private void deleteDir(File dir) {
		if (Files.isSymbolicLink(dir.toPath())) {
			// DO NOT RECURSE
			dir.delete();
			return;
		}
		for (File subDir : dir.listFiles(t -> t.isDirectory())) {
			deleteDir(subDir);
		}
		for (File file : dir.listFiles(t -> !t.isDirectory())) {
			file.delete();
		}
		dir.delete();
	}

	private void copyDir(File dir, File output, String prefix) throws IOException {
		output.mkdirs();
		for (File subDir : dir.listFiles(t -> t.isDirectory())) {
			copyDir(subDir, new File(output, subDir.getName()), prefix + subDir.getName() + "/");
		}
		for (File file : dir.listFiles(t -> !t.isDirectory())) {
			logger.info("Installing: " + prefix + file.getName());
			File outputF = new File(output, file.getName());
			if (outputF.exists())
				outputF.delete();
			Files.copy(file.toPath(), outputF.toPath());
		}
	}

}
