package org.asf.centuria.launcher.updater;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

public class WineInstallation {

	public WineInstallation(String path, String name, String display, boolean isUserPicked, boolean isAuto) {
		this.name = name;
		this.path = path;
		this.display = display;
		this.isUserPicked = isUserPicked;
		this.isAuto = isAuto;
	}

	public boolean isProton;
	public String path;
	public String name;
	public String display;

	public boolean isUserPicked;
	public boolean isAuto;

	public String toString() {
		return display;
	}

	public static WineInstallation[] findAllWineInstallations() {
		String[] preferredProton = new String[] { "Proton 11.0", "Proton 8.0", "Proton 9.0 (Beta)" };
		if (System.getenv("EMUFERAL_WINEDETECT_PROTONSEARCH_PREFERREDVERSIONS") != null) {
			ArrayList<String> newVers = new ArrayList<String>();
			for (String ver : System.getenv("EMUFERAL_WINEDETECT_PROTONSEARCH_PREFERREDVERSIONS").split(":"))
				newVers.add(ver);
			newVers.addAll(Arrays.asList(preferredProton));
			preferredProton = newVers.toArray(t -> new String[t]);
		}
		ArrayList<WineInstallation> installs = new ArrayList<WineInstallation>();
		ArrayList<String> protonVersions = new ArrayList<String>();

		// On linux or mac
		if (LauncherUpdaterMain.os != 1) {
			// Find system proton versions in reverse order from:
			// /usr/share/steam/compatibilitytools.d/
			// /usr/local/share/steam/compatibilitytools.d/
			// env var STEAM_EXTRA_COMPAT_TOOLS_PATHS
			// ~/.local/share/Steam/compatibilitytools.d/
			// ~/.steam/root/compatibilitytools.d/ (steam install folder symlink)

			// Emuferal env vars
			ArrayList<WineInstallation> newInstalls = new ArrayList<WineInstallation>(); // Final order list
			if (System.getenv("EMUFERAL_WINEDETECT_PROTONSEARCH_PATHS") != null) {
				// Emuferal env vars take priority
				for (String pth : System.getenv("EMUFERAL_WINEDETECT_PROTONSEARCH_PATHS").split(":")) {
					File path = new File(pth);
					if (path.exists())
						findProtonVersion("Added Proton: ", path, newInstalls, protonVersions);
				}
			}

			// Find default proton versions
			File steamappsCommonDefault = new File(System.getProperty("user.home") + "/.steam/steam/steamapps/common");
			if (steamappsCommonDefault.exists()) {
				findProtonVersions("User Proton: ", steamappsCommonDefault, installs, protonVersions);
			}
			steamappsCommonDefault = new File(System.getProperty("user.home") + "/.steam/root/steamapps/common");
			if (steamappsCommonDefault.exists()) {
				findProtonVersions("User Proton: ", steamappsCommonDefault, installs, protonVersions);
			}
			steamappsCommonDefault = new File(System.getProperty("user.home") + "/.local/share/Steam/steamapps/common");
			if (steamappsCommonDefault.exists()) {
				findProtonVersions("User Proton: ", steamappsCommonDefault, installs, protonVersions);
			}
			steamappsCommonDefault = new File(System.getProperty("user.home") + "/.local/share/steam/steamapps/common");
			if (steamappsCommonDefault.exists()) {
				findProtonVersions("User Proton: ", steamappsCommonDefault, installs, protonVersions);
			}

			// Find user provided protons
			File userProvidedProton = new File(System.getProperty("user.home") + "/.steam/root/compatibilitytools.d");
			if (userProvidedProton.exists()) {
				findProtonVersions("User Proton: ", userProvidedProton, installs, protonVersions);
			}
			userProvidedProton = new File(System.getProperty("user.home") + "/.local/share/Steam/compatibilitytools.d");
			if (userProvidedProton.exists()) {
				findProtonVersions("User Proton: ", userProvidedProton, installs, protonVersions);
			}

			// Environment
			if (System.getenv("STEAM_EXTRA_COMPAT_TOOLS_PATHS") != null) {
				for (String pth : System.getenv("STEAM_EXTRA_COMPAT_TOOLS_PATHS").split(":")) {
					File path = new File(pth);
					if (path.exists())
						findProtonVersion("System Proton: ", path, installs, protonVersions);
				}
			}

			// System root proton versions
			File systemProvidedProton = new File("/usr/local/share/steam/compatibilitytools.d");
			if (systemProvidedProton.exists()) {
				findProtonVersions("System Proton: ", systemProvidedProton, installs, protonVersions);
			}
			systemProvidedProton = new File("/usr/share/steam/compatibilitytools.d");
			if (systemProvidedProton.exists()) {
				findProtonVersions("System Proton: ", systemProvidedProton, installs, protonVersions);
			}

			// Reorder to use most supported
			for (String name : preferredProton) {
				if (installs.stream().anyMatch(t -> t.name.equalsIgnoreCase(name))) {
					WineInstallation install = installs.stream().filter(t -> t.name.equalsIgnoreCase(name)).findFirst()
							.get();
					newInstalls.add(install);
					installs.remove(install);
				}
			}
			newInstalls.addAll(installs);
			installs = newInstalls;
		}

		// Emuferal env vars
		if (System.getenv("EMUFERAL_WINEDETECT_WINESEARCH_PATHS") != null) {
			for (String pth : System.getenv("EMUFERAL_WINEDETECT_WINESEARCH_PATHS").split(":")) {
				File path = new File(pth);
				if (path.exists())
					findWine("System wine (" + path.getAbsolutePath() + ")", path, null, installs, false);
			}
		}

		// Find user wine
		findWine("User Wine (flatpak)", new File(
				System.getProperty("user.home") + "/.local/share/flatpak/app/org.winehq.Wine/current/active/files/bin"),
				null, installs, false);
		findWine("User CrossOver",
				new File(System.getProperty("user.home")
						+ "/Applications/CrossOver.app/Contents/SharedSupport/CrossOver/CrossOver-Hosted Application"),
				null, installs, false);

		// Find system wine
		findWine("System Wine (/usr/bin)", new File("/usr/bin"), null, installs, false);
		findWine("System Wine (/usr/local/bin)", new File("/usr/local/bin"), null, installs, false);
		findWine("System Wine (/opt/bin)", new File("/opt/bin"), null, installs, false);
		findWine("System Wine (/opt/homebrew/bin)", new File("/opt/homebrew/bin"), null, installs, false);
		findWine("System Wine (flatpak)", new File("/var/lib/flatpak/app/org.winehq.Wine/current/active/files/bin"),
				null, installs, false);

		// Crossover
		findWine("System CrossOver",
				new File("/Applications/CrossOver.app/Contents/SharedSupport/CrossOver/CrossOver-Hosted Application"),
				null, installs, false);

		// Bundled wine
		findWine("Bundled Wine (builtin)", new File("syslibs/bin"), "syslibs/bin", installs, false);
		findWine("Bundled Wine (builtin)", new File("installerdata/syslibs/bin"), "syslibs/bin", installs, false);
		findWine("Bundled Wine (builtin)", new File("installerdata/Contents/Resources/syslibs/bin"), "syslibs/bin",
				installs, false);

		// Return
		return installs.toArray(t -> new WineInstallation[t]);
	}

	public static class WineSearchResult {
		public WineSearchResultStatus status;

		public String wineName;
		public String wineVersion;

		public File wineBinary;
		public File winePath;

		public WineInstallation installation;
	}

	public static enum WineSearchResultStatus {
		SUCCESS, INVALID, QUERY_FAILED
	}

	public static WineSearchResult findWineInstallation(File winePath, String namePrefix) {
		// Search
		if (winePath.getPath().replace("\\", "/").endsWith("/wine") || (winePath.exists() && winePath.isFile())) {
			// Selected wine binary
			winePath = winePath.getAbsoluteFile().getParentFile();
		}

		// Search for wineserver binary
		File wineBinary = new File(winePath, "bin/wineserver");
		String wineName = winePath.getName();
		boolean proton = false;
		if (!wineBinary.exists()) {
			// Try as binary path
			wineBinary = new File(winePath, "wineserver");

			// Try as proton
			if (!wineBinary.exists()) {
				// Try proton 8
				wineBinary = new File(winePath, "dist/bin/wineserver");
				if (wineBinary.exists() && new File(winePath, "proton").exists()
						&& new File(winePath, "toolmanifest.vdf").exists())
					proton = true;

				// Try as proton 9
				if (!wineBinary.exists()) {
					// Try proton 9
					wineBinary = new File(winePath, "files/bin/wineserver");
					if (wineBinary.exists() && new File(winePath, "proton").exists()
							&& new File(winePath, "toolmanifest.vdf").exists())
						proton = true;

					// Check
					if (!wineBinary.exists()) {
						// Try subfolder

						// Try as wine in subfolder
						File[] subfolders = winePath.listFiles(t -> t.isDirectory());
						if (subfolders.length >= 1) {
							// Search
							for (File winePotential : subfolders) {
								wineName = winePotential.getName();
								wineBinary = new File(winePotential, "wineserver");
								if (wineBinary.exists())
									break;
								wineBinary = new File(winePotential, "bin/wineserver");
								if (wineBinary.exists())
									break;
								wineBinary = new File(winePotential, "files/bin/wineserver");
								if (wineBinary.exists())
									break;
								if (wineBinary.exists() && new File(winePotential, "proton").exists()
										&& new File(winePotential, "toolmanifest.vdf").exists())
									proton = true;
								wineBinary = new File(winePotential, "dist/bin/wineserver");
								if (wineBinary.exists())
									break;
								if (wineBinary.exists() && new File(winePotential, "proton").exists()
										&& new File(winePotential, "toolmanifest.vdf").exists())
									proton = true;
							}
						}

						// Invalid
						if (!wineBinary.exists()) {
							WineSearchResult res = new WineSearchResult();
							res.status = WineSearchResultStatus.INVALID;
							return res;
						}
					}
				}
			}
		}

		// Get version
		String wineVersion = WineInstallation.getWineVersion(wineBinary);
		if (wineVersion == null) {
			WineSearchResult res = new WineSearchResult();
			res.status = WineSearchResultStatus.QUERY_FAILED;
			return res;
		}

		// Return
		winePath = wineBinary.getParentFile();
		WineSearchResult res = new WineSearchResult();
		res.status = WineSearchResultStatus.SUCCESS;
		res.wineBinary = wineBinary;
		res.winePath = winePath;
		res.wineName = wineName;
		res.wineVersion = wineVersion;
		res.installation = new WineInstallation(winePath.getAbsolutePath(), namePrefix + wineName,
				namePrefix + wineName + ": " + wineVersion, true, false);
		res.installation.isProton = proton;
		return res;
	}

	public static String getWineVersion(File wineBin) {
		ProcessBuilder proc = new ProcessBuilder(wineBin.getAbsolutePath(), "--version");
		try {
			Process inst = proc.start();
			String res = new String(inst.getInputStream().readAllBytes(), "UTF-8").replace("\n", "").replace("\r", "");
			if (res.isEmpty())
				res = new String(inst.getErrorStream().readAllBytes(), "UTF-8").replace("\n", "").replace("\r", "");
			inst.waitFor();
			return res;
		} catch (IOException | InterruptedException e) {
			return null;
		}
	}

	private static void findProtonVersions(String pref, File source, ArrayList<WineInstallation> installs,
			ArrayList<String> protonVersions) {
		// Find remaining versions
		for (File game : source.listFiles(t -> t.isDirectory())) {
			File wineBinary = new File(game, "dist/bin/wineserver");
			if (!wineBinary.exists()) {
				wineBinary = new File(game, "files/bin/wineserver");
			}

			// Check
			if (wineBinary.exists()) {
				findProtonVersion(pref, game, installs, protonVersions);
			}
		}
	}

	private static void findProtonVersion(String pref, File source, ArrayList<WineInstallation> installs,
			ArrayList<String> protonVersions) {
		File wineBinary = new File(source, "dist/bin/wineserver");
		if (!wineBinary.exists()) {
			wineBinary = new File(source, "files/bin/wineserver");
		}

		// Check
		if (wineBinary.exists() && !protonVersions.contains(source.getName())) {
			// Found proton
			findWine(pref + source.getName(), wineBinary.getParentFile(), null, installs, true);
			protonVersions.add(source.getName());
		} else if (!wineBinary.exists()) {
			findProtonVersions(pref, source, installs, protonVersions);
		}
	}

	private static void findWine(String name, File source, String path, ArrayList<WineInstallation> installs,
			boolean isProton) {
		File wineBinary = new File(source, "wineserver");
		if (wineBinary.exists()) {
			// Set path
			path = path == null ? source.getAbsolutePath() : path;

			// Check already present
			String pathF = path;
			if (installs.stream().anyMatch(t -> t.path.equals(pathF)))
				return;

			// Check
			String wineVersion = getWineVersion(wineBinary);
			if (wineVersion != null) {
				// Add
				WineInstallation install = new WineInstallation(path, name, name + ": " + wineVersion, false, false);
				install.isProton = isProton;
				installs.add(install);
			}
		}
	}
}
