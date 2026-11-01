package org.asf.centuria.launcher.updater;

import java.awt.EventQueue;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import javax.swing.JProgressBar;
import java.awt.Dimension;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.filechooser.FileSystemView;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.awt.Color;

public class LauncherUpdaterMain {

	///
	///
	/// THIS CODE IS A FUCKING MESS ;v;
	///
	/// Itll be fixed in a later version, right now we just need something that
	/// works, we apologize about the mess ;v;
	///
	///

	private static boolean installerMode;

	private static JFrame frmCenturiaLauncher;
	private static JLabel lblNewLabel;
	private static BackgroundPanel panel_1;

	private static String[] args;

	public static int os = 0;

	// List of files installed to the target
	// Only includes root-level files in the target folder
	// Used to know what to files to delete during uninstallation
	// Any files created by the installer MUST be added here, otherwise theyre
	// invalid
	private static final String[] installTargetFiles = new String[] { // FIXME: also use installerdata contents from
																		// target-saved installer under
																		// centuria-installer as part of the deletion
																		// process guidance
			// Installation json
			"installation.json",

			// FT Launcher folder
			"launcher",

			// Installer
			"centuria-installer",

			// Launcher binaries
			"centuria-launcher",

			// Version info
			"currentversion.info",

			// Settings
			"shortcuts.json",
			"wine.json",

			// Caches
			"banner.image",
			"launcher.zip"
	};

	/**
	 * Launch the application.
	 * 
	 * @throws IOException
	 * @throws JsonSyntaxException
	 */
	public static void main(String[] args) throws JsonSyntaxException, IOException {
		LauncherUpdaterMain.args = args;
		if (new File("installerdata").exists()) {
			installerMode = true;

			// Check arguments
			if (args.length >= 2 && args[0].equals("--wait-process") && args[1].matches("[0-9]+")) {
				// Check pid
				Optional<ProcessHandle> handle = ProcessHandle.of(Long.parseLong(args[1]));
				if (handle.isPresent()) {
					ProcessHandle proc = handle.get();
					if (proc.isAlive()) {
						// Wait for exit
						proc.onExit().join();
					}
				}
			}

			// Check OS
			if (System.getProperty("os.name").toLowerCase().contains("darwin")
					|| System.getProperty("os.name").toLowerCase().contains("mac")) {
				// Check package
				if (!new File("installerdata", "Contents/MacOS").exists()) {
					// Error
					JOptionPane.showMessageDialog(null,
							"This installer is not compatible with MacOS, please download the macos-specific installer.",
							"Installer Error", JOptionPane.ERROR_MESSAGE);
					System.exit(1);
					return;
				}
			}

			// Detect OS
			if (System.getProperty("os.name").toLowerCase().contains("darwin")
					|| System.getProperty("os.name").toLowerCase().contains("mac")) {
				os = 0; // MacOS
			} else if (System.getProperty("os.name").toLowerCase().contains("win")) {
				os = 1; // Windows
			} else {
				os = 2; // Linux
			}

			// Handle arguments
			boolean launch = false;
			int operation = -1;
			String installPath = null;
			for (int i = 0; i < args.length; i++) {
				if (args[i].startsWith("--")) {
					String opt = args[i].substring(2);
					String val = null;
					if (opt.contains("=")) {
						val = opt.substring(opt.indexOf("=") + 1);
						opt = opt.substring(0, opt.indexOf("="));
					}

					// Handle argument
					switch (opt) {

						case "help": {
							System.out.println("Arguments:");
							System.out.println(" --install                     -  selects the install operation");
							System.out.println(" --uninstall                   -  selects the uninstall operation");
							System.out.println(
									" --launch-on-complete          -  enables launching of the installed launcher when installation completes");
							System.out.println(" --installation-path \"<path>\"  -  defines the installation path");
							System.exit(0);
						}

						case "install":
							operation = 0;
							break;
						case "uninstall":
							operation = 1;
							break;
						case "launch-on-complete":
							launch = true;
							break;

						case "installation-path": {
							// Retrieve argument if needed
							if (val == null) {
								if (i + 1 < args.length)
									val = args[i + 1];
								else
									break;
								i++;
							}

							// Set path
							installPath = val;
							if (!new File(installPath).exists()) {
								System.err.println("Error: installation folder does not exist");
								System.exit(1);
							}
							break;
						}

					}
				}
			}

			// Perform installer operation if needed
			if (operation != -1) {
				// Contact server
				String launcherVersion = null;
				String launcherDir = null;
				String launcherURL = null;
				String dataUrl = null;
				String srvName = null;
				File instDir;
				boolean osxUseWineMethod = false;
				try {
					// Read server info
					String dirName;
					String url;
					try {
						JsonObject conf = JsonParser.parseString(Files.readString(Path.of("server.json")))
								.getAsJsonObject();
						srvName = conf.get("serverName").getAsString();
						dirName = conf.get("launcherDirName").getAsString();
						url = conf.get("serverConfig").getAsString();
						dataUrl = url;
						launcherDir = dirName;
					} catch (Exception e) {
						JOptionPane.showMessageDialog(null,
								"Invalid " + (installerMode ? "installer" : "launcher") + " configuration.",
								(installerMode ? "Installer" : "Launcher") + " Error", JOptionPane.ERROR_MESSAGE);
						System.exit(1);
						return;
					}

					// Download data
					InputStream strm = new URL(url).openStream();
					String data = new String(strm.readAllBytes(), "UTF-8");
					strm.close();
					JsonObject info = JsonParser.parseString(data).getAsJsonObject();
					JsonObject launcher = info.get("launcher").getAsJsonObject();
					url = launcher.get("url").getAsString();
					launcherVersion = launcher.get("version").getAsString();
					if (launcher.has("osxUseWineMethod"))
						osxUseWineMethod = launcher.get("osxUseWineMethod").getAsBoolean();
					else
						osxUseWineMethod = false;
					launcherURL = url;
				} catch (Exception e) {
					if (operation == 0) {
						System.err.println("Error: failed to contact launcher servers.");
						System.exit(1);
						return;
					}
				}

				// Build folder path
				boolean inHome = false;
				if (System.getenv("LOCALAPPDATA") == null) {
					instDir = new File(System.getProperty("user.home") + "/.local/share");
					if (os == 0) {
						inHome = true;
						instDir = new File(System.getProperty("user.home"));
					} else {
						// Try to create
						instDir.mkdirs();
					}
				} else {
					instDir = new File(System.getenv("LOCALAPPDATA"));
				}
				if (new File("installation.json").exists())
					instDir = new File(JsonParser.parseString(Files.readString(Path.of("installation.json")))
							.getAsJsonObject().get("installationDirectory").getAsString());
				else {
					// Check appdata
					if (System.getenv("LOCALAPPDATA") == null) {
						// Check OSX
						if (os == 0 || !inHome) {
							instDir = new File(instDir, launcherDir);
						} else {
							instDir = new File(instDir, "." + launcherDir);
						}
					} else
						instDir = new File(instDir, launcherDir);
				}
				if (!instDir.exists())
					instDir.mkdirs();

				// Check redirect
				File installDirFile = new File(instDir, "installation.json");
				if (new File(instDir, "installation.json").exists())
					instDir = new File(
							JsonParser.parseString(Files.readString(new File(instDir, "installation.json").toPath()))
									.getAsJsonObject().get("installationDirectory").getAsString());

				// Perform operation
				if (operation == 0) {
					// Install
					if (installPath != null)
						instDir = new File(installPath);
					performInstallLauncher(osxUseWineMethod, instDir, null, launcherVersion, srvName, launcherURL,
							dataUrl, launch, installDirFile, launcherDir);
				} else if (operation == 1) {
					// Uninstall
					uninstallLauncher(instDir, null, srvName, launcherDir, installDirFile);
				}

				// Exit
				System.exit(0);
			}
		}
		EventQueue.invokeLater(new Runnable() {
			public void run() {
				try {
					new LauncherUpdaterMain();
					frmCenturiaLauncher.setVisible(true);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}

	/**
	 * Create the application.
	 */
	public LauncherUpdaterMain() {
		initialize();
	}

	/**
	 * Initialize the contents of the frame.
	 */
	private void initialize() {
		try {
			try {
				UIManager.setLookAndFeel("com.sun.java.swing.plaf.gtk.GTKLookAndFeel");
			} catch (ClassNotFoundException | InstantiationException | IllegalAccessException
					| UnsupportedLookAndFeelException e1) {
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
			}
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException
				| UnsupportedLookAndFeelException e1) {
		}

		// Detect OS
		if (System.getProperty("os.name").toLowerCase().contains("darwin")
				|| System.getProperty("os.name").toLowerCase().contains("mac")) {
			os = 0; // MacOS
		} else if (System.getProperty("os.name").toLowerCase().contains("win")) {
			os = 1; // Windows
		} else {
			os = 2; // Linux
		}

		frmCenturiaLauncher = new JFrame();
		frmCenturiaLauncher.setResizable(false);
		frmCenturiaLauncher.setBounds(100, 100, 555, 492);
		frmCenturiaLauncher.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frmCenturiaLauncher.setLocationRelativeTo(null);
		try {
			InputStream strmi = getClass().getClassLoader().getResourceAsStream("emulogo_purple.png");
			frmCenturiaLauncher.setIconImage(ImageIO.read(strmi));
			strmi.close();
		} catch (IOException e1) {
		}
		try {
			frmCenturiaLauncher.setIconImage(ImageIO.read(new File("icon.png")));
		} catch (IOException e1) {
		}

		JPanel panel = new JPanel();
		frmCenturiaLauncher.getContentPane().add(panel, BorderLayout.SOUTH);
		panel.setLayout(new BorderLayout(0, 0));

		JProgressBar progressBar = new JProgressBar();
		progressBar.setPreferredSize(new Dimension(146, 10));
		panel.add(progressBar, BorderLayout.NORTH);

		panel_1 = new BackgroundPanel();
		panel_1.setForeground(Color.WHITE);
		frmCenturiaLauncher.getContentPane().add(panel_1, BorderLayout.CENTER);
		panel_1.setLayout(new BorderLayout(0, 0));

		lblNewLabel = new JLabel("New label");
		lblNewLabel.setPreferredSize(new Dimension(46, 20));
		panel_1.add(lblNewLabel, BorderLayout.SOUTH);

		try {
			// Read server info
			String dirName;
			String url;
			String launcherDir;
			String dataUrl;
			String srvName;
			try {
				JsonObject conf = JsonParser.parseString(Files.readString(Path.of("server.json"))).getAsJsonObject();
				srvName = conf.get("serverName").getAsString();
				dirName = conf.get("launcherDirName").getAsString();
				url = conf.get("serverConfig").getAsString();
				dataUrl = url;
				launcherDir = dirName;
			} catch (Exception e) {
				JOptionPane.showMessageDialog(null,
						"Invalid " + (installerMode ? "installer" : "launcher") + " configuration.",
						(installerMode ? "Installer" : "Launcher") + " Error", JOptionPane.ERROR_MESSAGE);
				System.exit(1);
				return;
			}

			// Build folder path
			File instDir;
			File instDirInitial;
			boolean inHome = false;
			if (System.getenv("LOCALAPPDATA") == null) {
				instDir = new File(System.getProperty("user.home") + "/.local/share");
				if (os == 0) {
					inHome = true;
					instDir = new File(System.getProperty("user.home"));
				} else {
					// Try to create
					instDir.mkdirs();
				}
			} else {
				instDir = new File(System.getenv("LOCALAPPDATA"));
			}
			if (new File("installation.json").exists())
				instDir = new File(JsonParser.parseString(Files.readString(Path.of("installation.json")))
						.getAsJsonObject().get("installationDirectory").getAsString());
			else {
				// Check appdata
				if (System.getenv("LOCALAPPDATA") == null) {
					// Check OSX
					if (os == 0 || !inHome) {
						instDir = new File(instDir, launcherDir);
					} else {
						instDir = new File(instDir, "." + launcherDir);
					}
				} else
					instDir = new File(instDir, launcherDir);
			}
			if (!instDir.exists())
				instDir.mkdirs();

			// Check redirect
			File installDirFile = new File(instDir, "installation.json");
			instDirInitial = instDir;
			if (installDirFile.exists())
				instDir = new File(JsonParser.parseString(Files.readString(installDirFile.toPath())).getAsJsonObject()
						.get("installationDirectory").getAsString());

			// Set title
			if (!installerMode)
				frmCenturiaLauncher.setTitle(srvName + " Launcher");
			else
				frmCenturiaLauncher.setTitle(srvName + " Installer");

			// Contact server
			String launcherVersion = null;
			String launcherURL = null;
			boolean connection = false;
			boolean osxUseWineMethod = false;
			try {
				// Download data
				InputStream strm = new URL(url).openStream();
				String data = new String(strm.readAllBytes(), "UTF-8");
				strm.close();
				JsonObject info = JsonParser.parseString(data).getAsJsonObject();
				JsonObject launcher = info.get("launcher").getAsJsonObject();
				String splash = launcher.get("splash").getAsString();
				url = launcher.get("url").getAsString();
				String version = launcher.get("version").getAsString();
				if (launcher.has("osxUseWineMethod"))
					osxUseWineMethod = launcher.get("osxUseWineMethod").getAsBoolean();
				else
					osxUseWineMethod = false;

				// Handle relative paths for banner
				JsonObject server = info.get("server").getAsJsonObject();
				JsonObject hosts = server.get("hosts").getAsJsonObject();
				String api = hosts.get("api").getAsString();
				if (!api.endsWith("/"))
					api += "/";
				String apiData = api + "data/";
				if (hosts.has("launcherDataSource")) {
					apiData = hosts.get("launcherDataSource").getAsString();
					if (!apiData.endsWith("/"))
						apiData += "/";
				}
				splash = processRelative(apiData, splash);
				url = processRelative(apiData, url);

				// Download splash and set image
				try {
					InputStream strmi = new URL(splash).openStream();
					FileOutputStream bannerO = new FileOutputStream(new File(instDirInitial, "banner.image"));
					strmi.transferTo(bannerO);
					strmi.close();
					bannerO.close();
				} catch (IOException e) {
				}
				BufferedImage img = ImageIO.read(new File(instDirInitial, "banner.image"));
				panel_1.setImage(img);
				launcherVersion = version;
				launcherURL = url;
				connection = true;
			} catch (Exception e) {
				if (!installerMode || !new File(instDirInitial, "banner.image").exists()) {
					JOptionPane.showMessageDialog(null,
							"Could not connect with the launcher servers, please check your internet connection.\n\nIf you are connected, please wait a few minutes and try again.\nIf the issue remains and you are connected to the internet, please contact support.",
							(installerMode ? "Installer" : "Launcher") + " Error", JOptionPane.ERROR_MESSAGE);
					System.exit(1);
					return;
				} else {
					// Download splash and set image
					BufferedImage img = ImageIO.read(new File(instDirInitial, "banner.image"));
					panel_1.setImage(img);
					launcherURL = url;
				}
			}

			// Start launcher
			boolean osxUseWineMethodF = osxUseWineMethod;
			if (!installerMode) {
				// Start launcher
				startLauncher(instDir, instDirInitial, progressBar, launcherVersion, srvName, launcherURL, dataUrl,
						args, osxUseWineMethodF);
			} else {
				// Start installer
				startInstaller(instDir, instDirInitial, progressBar, launcherVersion, srvName, launcherURL, dataUrl,
						args, osxUseWineMethodF, connection, launcherDir, installDirFile);
			}
		} catch (Exception e) {
			String stackTrace = "";
			for (StackTraceElement ele : e.getStackTrace())
				stackTrace += "\n     At: " + ele;
			JOptionPane.showMessageDialog(frmCenturiaLauncher,
					"An error occured while running the " + (installerMode ? "installer" : "launcher")
							+ ".\nUnable to continue, the " + (installerMode ? "installer" : "launcher")
							+ " will now close.\n\nError details: " + e + stackTrace
							+ "\nPlease report this error to the server operators.",
					(installerMode ? "Installer" : "Launcher") + " Error", JOptionPane.ERROR_MESSAGE);
			System.exit(1);
		}
	}

	private class Container<T> {
		public T value;
	}

	private static void moveDir(File dir, File output, JProgressBar progressBar) throws IOException {
		if (!dir.exists())
			return;
		if (Files.isSymbolicLink(dir.toPath()) || dir.isFile()) {
			// Skip symlink
			try {
				// Try direct move
				dir.renameTo(output);
			} catch (Exception e) {
				// Other strategy, delete source after copy
				Files.copy(dir.toPath(), new File(output, output.getName()).toPath());
				output.delete();
			}
			if (progressBar != null) {
				try {
					SwingUtilities.invokeAndWait(() -> {
						progressBar.setValue(progressBar.getValue() + 1);
						progressBar.repaint();
					});
				} catch (InvocationTargetException | InterruptedException e) {
				}
			}
			return;
		}
		output.mkdirs();
		for (File subDir : dir.listFiles(t -> t.isDirectory())) {
			moveDir(subDir, new File(output, subDir.getName()), progressBar);
			if (progressBar != null) {
				try {
					SwingUtilities.invokeAndWait(() -> {
						progressBar.setValue(progressBar.getValue() + 1);
						progressBar.repaint();
					});
				} catch (InvocationTargetException | InterruptedException e) {
				}
			}
		}
		for (File file : dir.listFiles(t -> !t.isDirectory())) {
			File outputF = new File(output, file.getName());
			if (outputF.exists())
				outputF.delete();
			try {
				// Try direct move
				file.renameTo(outputF);
			} catch (Exception e) {
				// Other strategy, delete source after copy
				Files.copy(file.toPath(), new File(output, file.getName()).toPath());
				file.delete();
			}
			if (progressBar != null) {
				try {
					SwingUtilities.invokeAndWait(() -> {
						progressBar.setValue(progressBar.getValue() + 1);
						progressBar.repaint();
					});
				} catch (InvocationTargetException | InterruptedException e) {
				}
			}
		}
		if (progressBar != null) {
			try {
				SwingUtilities.invokeAndWait(() -> {
					progressBar.setValue(progressBar.getValue() + 1);
					progressBar.repaint();
				});
			} catch (InvocationTargetException | InterruptedException e) {
			}
		}

		// Delete if possible
		File[] res = dir.listFiles();
		if (res.length == 0)
			dir.delete();
	}

	private static void deleteDir(File dir, JProgressBar progressBar) {
		if (Files.isSymbolicLink(dir.toPath())) {
			// Skip symlink
			if (progressBar != null) {
				try {
					SwingUtilities.invokeAndWait(() -> {
						progressBar.setValue(progressBar.getValue() + 1);
						progressBar.repaint();
					});
				} catch (InvocationTargetException | InterruptedException e) {
				}
			}
			dir.delete();
			return;
		}
		if (!dir.exists() || dir.listFiles() == null || dir.isFile()) {
			if (progressBar != null) {
				try {
					SwingUtilities.invokeAndWait(() -> {
						progressBar.setValue(progressBar.getValue() + 1);
						progressBar.repaint();
					});
				} catch (InvocationTargetException | InterruptedException e) {
				}
			}
			dir.delete();
			return;
		}
		for (File subDir : dir.listFiles(t -> t.isDirectory())) {
			deleteDir(subDir, progressBar);
			if (progressBar != null) {
				try {
					SwingUtilities.invokeAndWait(() -> {
						progressBar.setValue(progressBar.getValue() + 1);
						progressBar.repaint();
					});
				} catch (InvocationTargetException | InterruptedException e) {
				}
			}
		}
		for (File file : dir.listFiles(t -> !t.isDirectory())) {
			file.delete();
			if (progressBar != null) {
				try {
					SwingUtilities.invokeAndWait(() -> {
						progressBar.setValue(progressBar.getValue() + 1);
						progressBar.repaint();
					});
				} catch (InvocationTargetException | InterruptedException e) {
				}
			}
		}
		dir.delete();
		if (progressBar != null) {
			try {
				SwingUtilities.invokeAndWait(() -> {
					progressBar.setValue(progressBar.getValue() + 1);
					progressBar.repaint();
				});
			} catch (InvocationTargetException | InterruptedException e) {
			}
		}
	}

	private static int countDir(File dir) {
		if (!dir.exists() || dir.isFile() || dir.listFiles() == null) {
			return 1;
		}
		if (Files.isSymbolicLink(dir.toPath())) {
			// Skip symlink
			return 1;
		}

		int i = 0;
		for (File subDir : dir.listFiles(t -> t.isDirectory())) {
			i += countDir(subDir);
		}
		var listFiles = dir.listFiles(t -> !t.isDirectory());
		for (int j = 0; j < listFiles.length; j++) {
			i++;
		}
		i++;
		return i;
	}

	private void startLauncher(File instDir, File instDirInitial, JProgressBar progressBar, String launcherVersion,
			String srvName, String launcherURL, String dataUrl, String[] args, boolean osxUseWineMethod) {
		File dir = instDir;
		Thread th = new Thread(() -> {
			// Set progress bar status
			try {
				log("Checking launcher files...");
				SwingUtilities.invokeAndWait(() -> {
					progressBar.setMaximum(100);
					progressBar.setValue(0);
				});

				// Check version file
				File verFile = new File(dir, "currentversion.info");
				String currentVersion = "";
				boolean isNew = !verFile.exists();
				if (!isNew)
					currentVersion = Files.readString(verFile.toPath());

				// Check updates
				log("Checking for updates...");
				SwingUtilities.invokeAndWait(() -> {
					progressBar.setMaximum(100);
					progressBar.setValue(0);
				});
				if (!currentVersion.equals(launcherVersion)) {
					// Update label
					log("Updating launcher...");

					// Download zip
					File tmpOut = new File(dir, "launcher.zip");
					downloadFile(launcherURL, tmpOut, progressBar);

					// Extract zip
					try {
						log("Extracting launcher update...");
						SwingUtilities.invokeAndWait(() -> {
							progressBar.setMaximum(100);
							progressBar.setValue(0);
						});
					} catch (InvocationTargetException | InterruptedException e) {
					}
					unZip(tmpOut, new File(dir, "launcher"), progressBar);
				}

				// Prepare to start launcher
				try {
					log("Starting...");
					SwingUtilities.invokeAndWait(() -> {
						progressBar.setMaximum(100);
						progressBar.setValue(0);
					});
				} catch (InvocationTargetException | InterruptedException e) {
				}
				Thread.sleep(1000);

				// Start launcher, compute arguments
				JsonObject startupInfo = JsonParser
						.parseString(Files.readString(new File(new File(dir, "launcher"), "startup.json").toPath()))
						.getAsJsonObject();
				ArrayList<String> cmd = new ArrayList<String>();
				cmd.add(startupInfo.get("executable").getAsString()
						.replace("$<dir>", new File(dir, "launcher").getAbsolutePath())
						.replace("$<jvm>", new File(ProcessHandle.current().info().command().get()).getAbsolutePath()));
				for (JsonElement ele : startupInfo.get("arguments").getAsJsonArray())
					cmd.add(ele.getAsString().replace("$<dir>", new File(dir, "launcher").getAbsolutePath())
							.replace("$<jvm>", ProcessHandle.current().info().command().get())
							.replace("$<pathsep>", File.pathSeparator).replace("$<server>", srvName)
							.replace("$<data-url>", dataUrl));
				for (String arg : args)
					cmd.add(arg);

				// Detect OS
				int os;
				if (System.getProperty("os.name").toLowerCase().contains("darwin")
						|| System.getProperty("os.name").toLowerCase().contains("mac"))
					os = 0; // MacOS
				else if (System.getProperty("os.name").toLowerCase().contains("win"))
					os = 1; // Windows
				else
					os = 2; // Linux

				// Wine setup
				boolean useWine = os == 2 || (os == 0 && osxUseWineMethod);
				if (useWine) {
					System.out.println("[LAUNCHER] [UPDATER] " + "Setting up wine environment...");
					System.out.println("[LAUNCHER] [UPDATER] " + "");
					System.out.println("[LAUNCHER] [UPDATER] " + "Supported environment variables:");
					System.out.println("[LAUNCHER] [UPDATER] "
							+ " - EMUFERAL_WINEDETECT_DISABLE - set to true to disable this mechanism");
					System.out.println("[LAUNCHER] [UPDATER] "
							+ " - EMUFERAL_WINEDETECT_PREFER_PROTON - controls if proton should be preferred over wine (default: false)");
					System.out.println("[LAUNCHER] [UPDATER] "
							+ " - EMUFERAL_WINEDETECT_WINESEARCH_PATHS - additional priority search paths for wine binary sources, separated by ':', expects a `wineserver` file to exist at the target path");
					System.out.println("[LAUNCHER] [UPDATER] "
							+ " - EMUFERAL_WINEDETECT_PROTONSEARCH_PATHS - additional priority search paths for proton sources, separated by ':', expects a proton game folder or a folder containing proton versions");
					System.out.println("[LAUNCHER] [UPDATER] "
							+ " - EMUFERAL_WINEDETECT_PROTONSEARCH_PREFERREDVERSIONS - additional preferred proton versions to use aside from Proton 11.0, Proton 8.0 and Proton 9.0, separated by ':', expects game folder names");
					System.out.println("[LAUNCHER] [UPDATER] "
							+ " - EMUFERAL_WINEDETECT_SELECTED_WINE - path to the wine version to select, this will use a user-selected wine installation and implies EMUFERAL_WINEDETECT_USE_BUNDLED=false");
					System.out.println("[LAUNCHER] [UPDATER] "
							+ " - EMUFERAL_WINEDETECT_USE_AUTO - controls if automatic wine detection mode should be used (default: false)");
					System.out.println("[LAUNCHER] [UPDATER] "
							+ " - EMUFERAL_WINEDETECT_USE_BUNDLED - controls if the bundled wine should be used (default: false) [macOS only]");
					System.out.println("[LAUNCHER] [UPDATER] "
							+ "Note: environment variables override the launcher completely, and prevent manual configuration from the launcher");
					System.out.println("[LAUNCHER] [UPDATER] "
							+ "Note: if a variable is unspecified, the user-configured settings will be used");
					System.out.println("[LAUNCHER] [UPDATER] " + "");
					if (System.getenv("EMUFERAL_WINEDETECT_DISABLE") != null
							&& System.getenv("EMUFERAL_WINEDETECT_DISABLE").equalsIgnoreCase("true")) {
						// Disabled
						System.out.println("[LAUNCHER] [UPDATER] "
								+ "Wine support system disabled! Depending on the system to provide wine versions for the PATH.");
						useWine = false;
					} else {
						System.out.println("[LAUNCHER] [UPDATER] " + "Finding wine installations...");
					}
				}
				boolean supportBundledWine = new File("syslibs/bin/wine").exists();
				WineInstallation[] allWineInstalls = useWine ? WineInstallation.findAllWineInstallations()
						: new WineInstallation[0];
				if (useWine) {
					// Log all wine installs
					for (WineInstallation install : allWineInstalls) {
						System.out.println("[LAUNCHER] [UPDATER] " + "Found " + (install.isProton ? "proton" : "wine")
								+ " installation: " + install.display + ": " + install.path);
					}
				}

				// Wine default settings
				boolean preferProtonEnabled = false;
				boolean useBundled = supportBundledWine;
				WineInstallation selectedWine = null;

				// Load wine preferences
				File wineProperties = new File(dir, "wine.json");
				if (wineProperties.exists() && useWine) {
					System.out.println("[LAUNCHER] [UPDATER] " + "Loading wine settings...");

					// Load settings
					JsonObject wineSettings = JsonParser.parseString(Files.readString(wineProperties.toPath()))
							.getAsJsonObject();

					// Load states
					useBundled = wineSettings.get("useBundled").getAsBoolean();
					preferProtonEnabled = wineSettings.get("preferProton").getAsBoolean();
					System.out.println("[LAUNCHER] [UPDATER] " + "Use bundled wine: " + useBundled);
					System.out.println("[LAUNCHER] [UPDATER] " + "Prefer proton: " + preferProtonEnabled);
					if (!supportBundledWine)
						useBundled = false;

					// Load if on auto
					boolean auto = wineSettings.get("useAuto").getAsBoolean();

					// Handle auto
					if (!useBundled) {
						if (auto) {
							// Set
							selectedWine = new WineInstallation("<auto>", "<auto>", "", false, true);
							System.out.println("[LAUNCHER] [UPDATER] " + "Automatic mode: enabled");
						} else {
							// Get path
							String path = wineSettings.get("path").getAsString();
							if (!wineSettings.has("name"))
								wineSettings.addProperty("name", "<unknownname>");
							String name = wineSettings.get("name").getAsString();
							String display = wineSettings.get("display").getAsString();
							boolean userPicked = wineSettings.get("userPicked").getAsBoolean();
							selectedWine = new WineInstallation(path, name, display, userPicked, false);
							System.out.println("[LAUNCHER] [UPDATER] " + "Automatic mode: disabled");
							System.out.println("[LAUNCHER] [UPDATER] " + "Current wine profile: " + display);
							System.out.println("[LAUNCHER] [UPDATER] " + "Current wine path: " + path);
							System.out.println("[LAUNCHER] [UPDATER] " + "Was added by user: " + userPicked);
						}
					}
				}

				// Check overrides
				if (System.getenv("EMUFERAL_WINEDETECT_PREFER_PROTON") != null) {
					preferProtonEnabled = System.getenv("EMUFERAL_WINEDETECT_PREFER_PROTON").equalsIgnoreCase("true");
					System.out
							.println("[LAUNCHER] [UPDATER] " + "Override! Prefer proton mode: " + preferProtonEnabled);
				}
				if (System.getenv("EMUFERAL_WINEDETECT_USE_BUNDLED") != null
						&& !System.getenv("EMUFERAL_WINEDETECT_USE_BUNDLED").equalsIgnoreCase("true")) {
					useBundled = false;
					System.out.println("[LAUNCHER] [UPDATER] " + "Override! Disabled bundled wine!");
				}

				// Check pre-specified
				if (System.getenv("EMUFERAL_WINEDETECT_USE_BUNDLED") != null
						&& System.getenv("EMUFERAL_WINEDETECT_USE_BUNDLED").equalsIgnoreCase("true")
						&& supportBundledWine) {
					// Use auto
					System.out.println("[LAUNCHER] [UPDATER] " + "Override! Using bundled wine!");
					useBundled = true;
				} else if (System.getenv("EMUFERAL_WINEDETECT_USE_AUTO") != null
						&& System.getenv("EMUFERAL_WINEDETECT_USE_AUTO").equalsIgnoreCase("true")) {
					// Use auto
					System.out.println("[LAUNCHER] [UPDATER] " + "Override! Using automatic wine detection!");
					selectedWine = new WineInstallation("<auto>", "<auto>", "", false, true);
					useBundled = false;
				} else if (System.getenv("EMUFERAL_WINEDETECT_SELECTED_WINE") != null) {
					// Load
					String pathFragment = System.getenv("EMUFERAL_WINEDETECT_SELECTED_WINE");
					System.out.println("[LAUNCHER] [UPDATER] " + "Override! Trying to select user-specified wine: "
							+ pathFragment + "...");
					File path = new File(pathFragment);
					if (path.exists()) {
						// Get search result
						WineInstallation.WineSearchResult res = WineInstallation.findWineInstallation(path,
								"User-selected wine: ");
						if (res.status == WineInstallation.WineSearchResultStatus.SUCCESS) {
							// Disable bundled
							useBundled = false;

							// Select
							System.out.println("[LAUNCHER] [UPDATER] " + "Selected wine installation: "
									+ res.installation.display);
							selectedWine = res.installation;
							selectedWine.isUserPicked = true;
						} else {
							// Invalid
							System.err.println("[LAUNCHER] [UPDATER] " + "Error: invalid wine installation: "
									+ pathFragment + ": got error code: "
									+ res.status);
						}
					} else {
						// Invalid
						System.err.println("[LAUNCHER] [UPDATER] " + "Error: invalid wine installation: " + pathFragment
								+ ": path does not exist");
					}
				}

				// Check support for bundled wine
				if (!supportBundledWine && useBundled) {
					// Disable bundled
					useBundled = false;
				}

				// Build path extensions
				String pathExtensions = "";
				if (useWine) {
					log("Selecting wine installation...");

					// Check bundled support
					if (supportBundledWine && useBundled) {
						// Use bundled
						log("Using bundled wine!");
						selectedWine = new WineInstallation("syslibs/bin", "<bundled>", "Bundled wine", false, false);
					} else {
						// Check auto
						if (selectedWine == null || selectedWine.isAuto || !new File(selectedWine.path).exists()) {
							// Find first automatic entry
							log("Selecting wine using automatic detection system...");
							selectedWine = null;

							// Check proton preference
							if (preferProtonEnabled) {
								// First try proton
								System.out.println(
										"[LAUNCHER] [UPDATER] " + "Attempting to select proton installation...");
								Optional<WineInstallation> proton = Stream.of(allWineInstalls).filter(t -> t.isProton)
										.findFirst();
								if (proton.isPresent()) {
									// Use proton
									selectedWine = proton.get();
									System.out.println(
											"[LAUNCHER] [UPDATER] " + "Selected proton: " + selectedWine.display + "!");
								} else {
									System.out.println(
											"[LAUNCHER] [UPDATER] " + "Could not find a usable proton installation!");
								}
							}

							// Find wine packages
							if (selectedWine == null || selectedWine.isAuto) {
								System.out
										.println("[LAUNCHER] [UPDATER] " + "Attempting to select wine installation...");
								Optional<WineInstallation> wine = Stream.of(allWineInstalls).filter(t -> !t.isProton)
										.findFirst();
								if (wine.isPresent()) {
									// Use proton
									selectedWine = wine.get();
									System.out.println(
											"[LAUNCHER] [UPDATER] " + "Found wine: " + selectedWine.display + "!");
								} else {
									System.out.println(
											"[LAUNCHER] [UPDATER] " + "Could not find a usable wine installation!");
								}
							}

							// Use default
							if (selectedWine == null || selectedWine.isAuto) {
								System.out.println("[LAUNCHER] [UPDATER] "
										+ "Attempting to find wine installations using default listing...");
								Optional<WineInstallation> wine = Stream.of(allWineInstalls).findFirst();
								if (wine.isPresent()) {
									// Use proton
									selectedWine = wine.get();
									System.out.println(
											"[LAUNCHER] [UPDATER] " + "Found wine: " + selectedWine.display + "!");
								} else {
									selectedWine = null;
									System.out.println(
											"[LAUNCHER] [UPDATER] " + "Could not find compatible wine installations!");
								}
							}
						} else {
							System.out.println("[LAUNCHER] [UPDATER] " + "Using user-selected wine: "
									+ selectedWine.display + "!");
						}
					}
				}

				// Check
				if (selectedWine != null) {
					System.out.println("[LAUNCHER] [UPDATER] " + "Wine binaries path: " + selectedWine.path);
					File wine = new File(selectedWine.path).getAbsoluteFile();
					pathExtensions = wine.getAbsolutePath() + ":";
				}
				log("Starting launcher!");

				// Start process
				ProcessBuilder builder = new ProcessBuilder(cmd.toArray(t -> new String[t]));
				builder.directory(new File(dir, "launcher"));
				builder.environment().put("CENTURIA_LAUNCHER_PATH",
						(os == 0 || os == 2 ? new File("launcher.sh").getAbsolutePath()
								: new File("launcher.bat").getAbsolutePath()));
				builder.environment().put("PATH", pathExtensions + System.getenv("PATH"));
				builder.inheritIO();
				Process proc = builder.start();

				// Mark done
				if (!currentVersion.equals(launcherVersion))
					Files.writeString(verFile.toPath(), launcherVersion);
				SwingUtilities.invokeAndWait(() -> {
					frmCenturiaLauncher.setVisible(false);
				});
				int exitCode = proc.waitFor();
				if (exitCode == 237) {
					// Reset
					log("Checking launcher files...");
					SwingUtilities.invokeAndWait(() -> {
						progressBar.setMaximum(100);
						progressBar.setValue(0);
					});

					// Read server info
					String url;
					String dataUrl2;
					String srvName2;
					try {
						JsonObject conf = JsonParser.parseString(Files.readString(Path.of("server.json")))
								.getAsJsonObject();
						srvName2 = conf.get("serverName").getAsString();
						url = conf.get("serverConfig").getAsString();
						dataUrl2 = url;
					} catch (Exception e) {
						JOptionPane.showMessageDialog(null,
								"Invalid " + (installerMode ? "installer" : "launcher") + " configuration.",
								(installerMode ? "Installer" : "Launcher") + " Error", JOptionPane.ERROR_MESSAGE);
						System.exit(1);
						return;
					}

					// Set title
					if (!installerMode)
						frmCenturiaLauncher.setTitle(srvName + " Launcher");
					else
						frmCenturiaLauncher.setTitle(srvName + " Installer");

					// Contact server
					String launcherVersion2;
					String launcherURL2;
					try {
						// Download data
						InputStream strm = new URL(url).openStream();
						String data = new String(strm.readAllBytes(), "UTF-8");
						strm.close();
						JsonObject info = JsonParser.parseString(data).getAsJsonObject();
						JsonObject launcher = info.get("launcher").getAsJsonObject();
						String splash = launcher.get("splash").getAsString();
						url = launcher.get("url").getAsString();
						String version = launcher.get("version").getAsString();

						// Handle relative paths for banner
						JsonObject server = info.get("server").getAsJsonObject();
						JsonObject hosts = server.get("hosts").getAsJsonObject();
						String api = hosts.get("api").getAsString();
						if (!api.endsWith("/"))
							api += "/";
						String apiData = api + "data/";
						if (hosts.has("launcherDataSource")) {
							apiData = hosts.get("launcherDataSource").getAsString();
							if (!apiData.endsWith("/"))
								apiData += "/";
						}
						splash = processRelative(apiData, splash);
						launcherURL2 = processRelative(apiData, url);

						// Download splash and set image
						try {
							InputStream strmi = new URL(splash).openStream();
							FileOutputStream bannerO = new FileOutputStream(new File(instDirInitial, "banner.image"));
							strmi.transferTo(bannerO);
							strmi.close();
							bannerO.close();
						} catch (IOException e) {
						}
						BufferedImage img = ImageIO.read(new File(instDirInitial, "banner.image"));
						panel_1.setImage(img);
						launcherVersion2 = version;

						// Launch again
						SwingUtilities.invokeAndWait(() -> {
							frmCenturiaLauncher.setVisible(true);
						});
						startLauncher(instDir, instDirInitial, progressBar, launcherVersion2, srvName2, launcherURL2,
								dataUrl2, args, launcher.get("osxUseWineMethod").getAsBoolean());
					} catch (Exception e) {
						JOptionPane.showMessageDialog(null,
								"Could not connect with the launcher servers, please check your internet connection.\n\nIf you are connected, please wait a few minutes and try again.\nIf the issue remains and you are connected to the internet, please contact support.",
								(installerMode ? "Installer" : "Launcher") + " Error", JOptionPane.ERROR_MESSAGE);
						System.exit(1);
						return;
					}
					return;
				}
				SwingUtilities.invokeAndWait(() -> {
					frmCenturiaLauncher.dispose();
				});
				System.exit(proc.exitValue());
			} catch (Exception e) {
				try {
					SwingUtilities.invokeAndWait(() -> {
						String stackTrace = "";
						for (StackTraceElement ele : e.getStackTrace())
							stackTrace += "\n     At: " + ele;
						JOptionPane.showMessageDialog(frmCenturiaLauncher,
								"An error occured while running the launcher.\nUnable to continue, the launcher will now close.\n\nError details: "
										+ e + stackTrace + "\nPlease report this error to the server operators.",
								"Launcher Error", JOptionPane.ERROR_MESSAGE);
						System.exit(1);
					});
				} catch (InvocationTargetException | InterruptedException e1) {
				}
			}
		}, "Launcher Thread");
		th.setDaemon(true);
		th.start();
	}

	private void startInstaller(File instDir, File instDirInitial, JProgressBar progressBar, String launcherVersion,
			String srvName, String launcherURL, String dataUrl, String[] args, boolean osxUseWineMethodF,
			boolean connection, String launcherDir, File installDirFile) {
		File dir = instDir;

		// Installer mode, ask which operation to perform
		boolean connectionF = connection;
		String launcherVersionF = launcherVersion;
		String launcherURLF = launcherURL;
		Thread th = new Thread(() -> {
			// Check command line
			int operation = -1;
			String selectedInstallPath = null;
			for (int i = 0; i < args.length; i++) {
				if (args[i].startsWith("--")) {
					String opt = args[i].substring(2);
					String val = null;
					if (opt.contains("=")) {
						val = opt.substring(opt.indexOf("=") + 1);
						opt = opt.substring(0, opt.indexOf("="));
					}

					// Handle argument
					switch (opt) {

						case "install-with-gui":
							operation = 0;
							break;
						case "uninstall-with-gui":
							operation = 1;
							break;

						case "installation-path": {
							// Retrieve argument if needed
							if (val == null) {
								if (i + 1 < args.length)
									val = args[i + 1];
								else
									break;
								i++;
							}

							// Set path
							selectedInstallPath = val;
							if (!new File(selectedInstallPath).exists()) {
								System.err.println("Error: installation folder does not exist");
								System.exit(1);
							}
							break;
						}

					}
				}
			}

			// Check operation
			if (operation == -1) {
				// Show picker
				log("Waiting for user to select installer operation...");
				SwingUtilities.invokeLater(() -> {
					showInstallerOperationPicker(osxUseWineMethodF, progressBar, srvName, dir, launcherVersionF,
							launcherURLF, dataUrl, launcherDir, connectionF, installDirFile);
				});

				// Done
				return;
			} else {
				// Handle operation
				try {
					log("Preparing installer...");
					if (operation == 0) {
						// Install
						if (!connectionF) {
							JOptionPane.showMessageDialog(null,
									"Could not connect with the launcher servers, please check your internet connection.\n\nIf you are connected, please wait a few minutes and try again.\nIf the issue remains and you are connected to the internet, please contact support.",
									(installerMode ? "Installer" : "Launcher") + " Error", JOptionPane.ERROR_MESSAGE);
							System.exit(1);
							return;
						}
						if (selectedInstallPath == null) {
							installLauncher(dir, osxUseWineMethodF, progressBar, launcherVersionF, srvName,
									launcherURLF, dataUrl, launcherDir);
						} else {
							File target = new File(selectedInstallPath);
							performInstallLauncher(osxUseWineMethodF, target, progressBar, launcherVersionF, srvName,
									launcherURLF, dataUrl, true, installDirFile, launcherDir);
						}
					} else {
						// Uninstall
						uninstallLauncher(dir, progressBar, srvName, launcherDir, installDirFile);
					}
					System.exit(0);
				} catch (Exception e) {
					try {
						SwingUtilities.invokeAndWait(() -> {
							String stackTrace = "";
							for (StackTraceElement ele : e.getStackTrace())
								stackTrace += "\n     At: " + ele;
							JOptionPane.showMessageDialog(frmCenturiaLauncher,
									"An error occured while running the installer.\nUnable to continue, the installer will now close.\n\nError details: "
											+ e + stackTrace + "\nPlease report this error to the server operators.",
									"Installer Error", JOptionPane.ERROR_MESSAGE);
							System.exit(1);
						});
					} catch (InvocationTargetException | InterruptedException e1) {
					}
				}

				// Done
				return;
			}
		}, "Installer Thread");
		th.setDaemon(true);
		th.start();
	}

	private void showInstallerOperationPicker(boolean osxUseWineMethod, JProgressBar progressBar, String srvName,
			File dir, String launcherVersionF, String launcherURLF, String dataUrl, String launcherDir,
			boolean connectionF, File installDirFile) {
		progressBar.setMaximum(100);
		progressBar.setValue(0);

		// Check state
		if (!dir.exists() && installDirFile.exists()) {
			// Warn
			if (JOptionPane.showConfirmDialog(frmCenturiaLauncher,
					"Warning! It appears the launcher is currently missing!\n\nThis could happen if it is installed on a disk not currently connected, or if files are corrupted.\n\nDo you wish to proceed to the installer? Please note that installing may not be possible.\nUninstall is possible but may not remove the game completely.",
					"Launcher not on disk", JOptionPane.OK_CANCEL_OPTION,
					JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
				System.exit(0);
			}
		}

		// Show message
		int selected;
		File verFile = new File(dir, "currentversion.info");
		if (verFile.exists() || (!dir.exists() && installDirFile.exists())) {
			selected = JOptionPane.showOptionDialog(frmCenturiaLauncher,
					"Welcome to the " + srvName + " installer!\n\nPlease select installer operation...\n ",
					srvName + " Installer", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
					new Object[] { "Modify or repair installation", "Uninstall", "Cancel" }, "Cancel");

			// Quit if cancelled
			if (selected == 2 || selected == -1)
				System.exit(0);
		} else {
			selected = JOptionPane.showOptionDialog(frmCenturiaLauncher,
					"Welcome to the " + srvName + " installer!\n\nPlease select installer operation...\n ",
					srvName + " Installer", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
					new Object[] { "Install the launcher", "Cancel" }, "Cancel");

			// Quit if cancelled
			if (selected == 1 || selected == -1)
				System.exit(0);
		}

		// Run installer
		Thread th2 = new Thread(() -> {
			try {
				// Log
				log("Processing selected operation...");
				SwingUtilities.invokeAndWait(() -> {
					progressBar.setMaximum(100);
					progressBar.setValue(0);
				});
				if (selected == 1) {
					// Warn
					Container<Boolean> res = new Container<Boolean>();
					res.value = false;
					SwingUtilities.invokeAndWait(() -> {
						if (JOptionPane.showConfirmDialog(frmCenturiaLauncher,
								"Are you sure you wish to uninstall the " + srvName + " launcher and client?",
								"Uninstall Launcher", JOptionPane.YES_NO_OPTION,
								JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) {
							// Restart
							lblNewLabel.setText(" Waiting for user to select installer operation...");
							System.out
									.println("[LAUNCHER] [UPDATER] Waiting for user to select installer operation...");

							// Reopen picker
							showInstallerOperationPicker(osxUseWineMethod, progressBar, srvName, dir, launcherVersionF,
									launcherURLF, dataUrl, launcherDir, connectionF, installDirFile);
							return;
						}
						res.value = true;
					});
					if (!res.value)
						return;
				}

				// Check relative
				try {
					if (isRelative(dir, new File(".")) && ((os == 1 && new File("launcher.bat").exists())
							|| (os != 1 && new File("launcher.sh").exists()))) {
						// Copy if needed
						log("Copying installer to temporary location...");
						File source = new File(".");
						boolean asApp = false;
						if (os == 0) {
							// Macos

							// Get parent
							File app = new File("../..");

							// Copy the actual .app
							if (app.exists() && app.getAbsoluteFile().getName().endsWith(".app")) {
								source = app;
								asApp = true;
							}
						}
						File tempDir = Files.createTempDirectory("installer-temp-").toFile();
						if (os == 0 && asApp) {
							tempDir.mkdirs();
							tempDir = new File(tempDir, "installer.app");
						}
						if (progressBar != null) {
							try {
								int c = countDir(source);
								SwingUtilities.invokeAndWait(() -> {
									progressBar.setMaximum(c);
									progressBar.setValue(0);
									progressBar.repaint();
								});
							} catch (InvocationTargetException | InterruptedException e) {
							}
						}
						copyDir(source, tempDir, progressBar);
						if (progressBar != null) {
							try {
								SwingUtilities.invokeAndWait(() -> {
									progressBar.setMaximum(100);
									progressBar.setValue(100);
									progressBar.repaint();
								});
							} catch (InvocationTargetException | InterruptedException e) {
							}
						}

						// Launch
						log("Starting installer...");
						if (os != 0 || !asApp) {
							// Start process
							ArrayList<String> command = new ArrayList<String>();
							command.add((os == 1 ? new File(tempDir, "launcher.bat").getAbsolutePath()
									: new File(tempDir, "launcher.sh").getAbsolutePath()));
							command.add("--wait-process");
							command.add(Long.toString(ProcessHandle.current().pid()));
							if (selected == 0)
								command.add("--install-with-gui");
							else if (selected == 1)
								command.add("--uninstall-with-gui");
							for (String arg : args)
								command.add(arg);
							ProcessBuilder builder = new ProcessBuilder(command.toArray(t -> new String[t]));
							builder.directory(tempDir.getAbsoluteFile());
							builder.inheritIO();
							builder.start();
							try {
								SwingUtilities.invokeAndWait(() -> {
									frmCenturiaLauncher.dispose();
								});
							} catch (InterruptedException | InvocationTargetException e) {
							}
							System.exit(0);
						} else {
							// Start OSX launcher
							ArrayList<String> command = new ArrayList<String>();
							command.add("open");
							command.add("-n");
							command.add("--args");
							command.add("--wait-process");
							command.add(Long.toString(ProcessHandle.current().pid()));
							if (selected == 0)
								command.add("--install-with-gui");
							else if (selected == 1)
								command.add("--uninstall-with-gui");
							for (String arg : args)
								command.add(arg);
							ProcessBuilder builder = new ProcessBuilder(command.toArray(t -> new String[t]));
							builder.inheritIO();
							Process proc = builder.start();
							try {
								SwingUtilities.invokeAndWait(() -> {
									frmCenturiaLauncher.dispose();
								});
							} catch (InterruptedException | InvocationTargetException e) {
							}
							System.exit(proc.exitValue());
						}

						// Exit
						return;
					}
				} catch (IOException e) {
					String stackTrace = "";
					for (StackTraceElement ele : e.getStackTrace())
						stackTrace += "\n     At: " + ele;
					JOptionPane.showMessageDialog(frmCenturiaLauncher,
							"An error occured while running the " + (installerMode ? "installer" : "launcher")
									+ ".\nUnable to continue, the " + (installerMode ? "installer" : "launcher")
									+ " will now close.\n\nError details: " + e + stackTrace
									+ "\nPlease report this error to the server operators.",
							(installerMode ? "Installer" : "Launcher") + " Error", JOptionPane.ERROR_MESSAGE);
					System.exit(1);
				}

				// Run operation
				if (selected == 0) {
					// Check state
					if (!dir.exists() && installDirFile.exists()) {
						// Warn
						if (JOptionPane.showConfirmDialog(frmCenturiaLauncher,
								"Warning! It appears the launcher is currently missing!\n\nThis could happen if it is installed on a disk not currently connected, or if files are corrupted.\nDo you wish to proceed with installation? Please beware this could have unexpected effects!",
								"Launcher not on disk", JOptionPane.YES_NO_OPTION,
								JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
							// Restart
							log("Waiting for user to select installer operation...");
							SwingUtilities.invokeLater(() -> {
								// Reopen
								showInstallerOperationPicker(osxUseWineMethod, progressBar, srvName, dir,
										launcherVersionF, launcherURLF, dataUrl, launcherDir, connectionF,
										installDirFile);
							});
							return;
						}
					}

					if (!connectionF) {
						JOptionPane.showMessageDialog(null,
								"Could not connect with the launcher servers, please check your internet connection.\n\nIf you are connected, please wait a few minutes and try again.\nIf the issue remains and you are connected to the internet, please contact support.",
								(installerMode ? "Installer" : "Launcher") + " Error", JOptionPane.ERROR_MESSAGE);
						System.exit(1);
						return;
					}
					if (!installLauncher(dir, osxUseWineMethod, progressBar, launcherVersionF, srvName, launcherURLF,
							dataUrl, launcherDir)) {
						// Restart
						log("Waiting for user to select installer operation...");
						SwingUtilities.invokeLater(() -> {
							// Reopen
							showInstallerOperationPicker(osxUseWineMethod, progressBar, srvName, dir, launcherVersionF,
									launcherURLF, dataUrl, launcherDir, connectionF, installDirFile);
						});
					}
				} else {
					// Check state
					if (!dir.exists() && installDirFile.exists()) {
						// Warn
						if (JOptionPane.showConfirmDialog(frmCenturiaLauncher,
								"Warning! It appears the launcher is currently missing!\n\nThis could happen if it is installed on a disk not currently connected, or if files are corrupted.\nDo you wish to proceed with removal? Please note that the installer wont be able to remove the entire game, and may leave files left over!",
								"Launcher not on disk", JOptionPane.YES_NO_OPTION,
								JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
							// Restart
							log("Waiting for user to select installer operation...");
							SwingUtilities.invokeLater(() -> {
								// Reopen
								showInstallerOperationPicker(osxUseWineMethod, progressBar, srvName, dir,
										launcherVersionF, launcherURLF, dataUrl, launcherDir, connectionF,
										installDirFile);
							});
							return;
						}
					}

					// Uninstall
					uninstallLauncher(dir, progressBar, srvName, launcherDir, installDirFile);
				}
			} catch (Exception e) {
				try {
					SwingUtilities.invokeAndWait(() -> {
						String stackTrace = "";
						for (StackTraceElement ele : e.getStackTrace())
							stackTrace += "\n     At: " + ele;
						JOptionPane.showMessageDialog(frmCenturiaLauncher,
								"An error occured while running the installer.\nUnable to continue, the installer will now close.\n\nError details: "
										+ e + stackTrace + "\nPlease report this error to the server operators.",
								"Installer Error", JOptionPane.ERROR_MESSAGE);
						System.exit(1);
					});
				} catch (InvocationTargetException | InterruptedException e1) {
				}
			}
		}, "Installer Thread");
		th2.setDaemon(true);
		th2.start();
	}

	private boolean isRelative(File root, File target) throws IOException {
		String canonical = target.getCanonicalPath();
		String rootCanonical = root.getCanonicalPath();
		while (rootCanonical.contains("//"))
			rootCanonical = rootCanonical.replace("//", "/");
		while (canonical.contains("//"))
			canonical = canonical.replace("//", "/");
		if (os == 1) {
			rootCanonical = rootCanonical.replace("\\", "/").toLowerCase();
			canonical = canonical.replace("\\", "/").toLowerCase();
		}
		if (!canonical.endsWith("/"))
			canonical += "/";
		if (!rootCanonical.endsWith("/"))
			rootCanonical += "/";
		return canonical.startsWith(rootCanonical);
	}

	private static boolean installLauncher(File instDir, boolean osxUseWineMethod, JProgressBar progressBar,
			String launcherVersion, String srvName, String launcherURL, String dataUrl, String launcherDir)
			throws IOException {
		// Build folder path
		boolean inHome = false;
		File instDir2;
		if (System.getenv("LOCALAPPDATA") == null) {
			instDir2 = new File(System.getProperty("user.home") + "/.local/share");
			if (os == 0) {
				inHome = true;
				instDir2 = new File(System.getProperty("user.home"));
			} else {
				// Try to create
				instDir2.mkdirs();
			}
		} else {
			instDir2 = new File(System.getenv("LOCALAPPDATA"));
		}
		if (new File("installation.json").exists())
			try {
				instDir2 = new File(JsonParser.parseString(Files.readString(Path.of("installation.json")))
						.getAsJsonObject().get("installationDirectory").getAsString());
			} catch (JsonSyntaxException | IOException e) {
				throw new RuntimeException(e);
			}
		else {
			// Check appdata
			if (System.getenv("LOCALAPPDATA") == null) {
				// Check OSX
				if (os == 0 || !inHome) {
					instDir2 = new File(instDir2, launcherDir);
				} else {
					instDir2 = new File(instDir2, "." + launcherDir);
				}
			} else
				instDir2 = new File(instDir2, launcherDir);
		}
		File installDirFile = new File(instDir2, "installation.json");

		// Load installation
		boolean lock = false;
		File targetInstall = instDir;
		if (installDirFile.exists()) {
			targetInstall = new File(JsonParser.parseString(Files.readString(installDirFile.toPath())).getAsJsonObject()
					.get("installationDirectory").getAsString());
			lock = true;
		}

		// Wine setup
		boolean useWine = os == 2 || (os == 0 && osxUseWineMethod);
		if (useWine) {
			System.out.println("[LAUNCHER] [UPDATER] " + "Setting up wine environment...");
			System.out.println("[LAUNCHER] [UPDATER] " + "");
			System.out.println("[LAUNCHER] [UPDATER] " + "Supported environment variables:");
			System.out.println("[LAUNCHER] [UPDATER] "
					+ " - EMUFERAL_WINEDETECT_DISABLE - set to true to disable this mechanism");
			System.out.println("[LAUNCHER] [UPDATER] "
					+ " - EMUFERAL_WINEDETECT_PREFER_PROTON - controls if proton should be preferred over wine (default: unspecified)");
			System.out.println("[LAUNCHER] [UPDATER] "
					+ " - EMUFERAL_WINEDETECT_WINESEARCH_PATHS - additional priority search paths for wine binary sources, separated by ':', expects a `wineserver` file to exist at the target path");
			System.out.println("[LAUNCHER] [UPDATER] "
					+ " - EMUFERAL_WINEDETECT_PROTONSEARCH_PATHS - additional priority search paths for proton sources, separated by ':', expects a proton game folder or a folder containing proton versions");
			System.out.println("[LAUNCHER] [UPDATER] "
					+ " - EMUFERAL_WINEDETECT_PROTONSEARCH_PREFERREDVERSIONS - additional preferred proton versions to use aside from Proton 11.0, Proton 8.0 and Proton 9.0, separated by ':', expects game folder names");
			System.out.println("[LAUNCHER] [UPDATER] "
					+ " - EMUFERAL_WINEDETECT_SELECTED_WINE - path to the wine version to select, this will use a user-selected wine installation and implies EMUFERAL_WINEDETECT_USE_BUNDLED=false");
			System.out.println("[LAUNCHER] [UPDATER] "
					+ " - EMUFERAL_WINEDETECT_USE_AUTO - controls if automatic wine detection mode should be used (default: unspecified)");
			System.out.println("[LAUNCHER] [UPDATER] "
					+ " - EMUFERAL_WINEDETECT_USE_BUNDLED - controls if the bundled wine should be used (default: unspecified) [macOS only]");
			System.out.println("[LAUNCHER] [UPDATER] "
					+ "Note: environment variables override the launcher completely, and prevent manual configuration from the launcher");
			System.out.println("[LAUNCHER] [UPDATER] "
					+ "Note: if a variable is unspecified, the user-configured settings will be used");
			System.out.println("[LAUNCHER] [UPDATER] " + "");
			if (System.getenv("EMUFERAL_WINEDETECT_DISABLE") != null
					&& System.getenv("EMUFERAL_WINEDETECT_DISABLE").equalsIgnoreCase("true")) {
				// Disabled
				System.out.println("[LAUNCHER] [UPDATER] "
						+ "Wine support system disabled! Depending on the system to provide wine versions for the PATH.");
				useWine = false;
			} else {
				System.out.println("[LAUNCHER] [UPDATER] " + "Finding wine installations...");
			}
		}
		boolean supportBundledWine = new File("installerdata/syslibs/bin/wine").exists()
				|| new File("installerdata/Contents/Resources/syslibs/bin/wine").exists();
		WineInstallation[] allWineInstalls = useWine ? WineInstallation.findAllWineInstallations()
				: new WineInstallation[0];
		if (useWine) {
			// Log all wine installs
			for (WineInstallation install : allWineInstalls) {
				System.out.println("[LAUNCHER] [UPDATER] " + "Found " + (install.isProton ? "proton" : "wine")
						+ " installation: " + install.display + ": " + install.path);
			}
		}

		// Wine default settings
		boolean preferProtonEnabled = false;
		boolean useBundled = supportBundledWine;
		WineInstallation selectedWine = null;

		// Load wine preferences
		File wineProperties = new File(targetInstall, "wine.json");
		if (wineProperties.exists() && useWine) {
			// Load settings
			JsonObject wineSettings = JsonParser.parseString(Files.readString(wineProperties.toPath()))
					.getAsJsonObject();

			// Load states
			useBundled = wineSettings.get("useBundled").getAsBoolean();
			preferProtonEnabled = wineSettings.get("preferProton").getAsBoolean();

			// Load if on auto
			boolean auto = wineSettings.get("useAuto").getAsBoolean();

			// Handle auto
			if (auto) {
				// Set
				selectedWine = new WineInstallation("<auto>", "<auto>", "", false, true);
			} else {
				// Get path
				String path = wineSettings.get("path").getAsString();
				if (!wineSettings.has("name"))
					wineSettings.addProperty("name", "<unknownname>");
				String name = wineSettings.get("name").getAsString();
				String display = wineSettings.get("display").getAsString();
				boolean userPicked = wineSettings.get("userPicked").getAsBoolean();
				selectedWine = new WineInstallation(path, name, display, userPicked, false);
			}
		}

		// Check overrides
		if (System.getenv("EMUFERAL_WINEDETECT_PREFER_PROTON") != null) {
			preferProtonEnabled = System.getenv("EMUFERAL_WINEDETECT_PREFER_PROTON").equalsIgnoreCase("true");
			System.out
					.println("[LAUNCHER] [UPDATER] " + "Override! Prefer proton mode: " + preferProtonEnabled);
		}
		if (System.getenv("EMUFERAL_WINEDETECT_USE_BUNDLED") != null
				&& !System.getenv("EMUFERAL_WINEDETECT_USE_BUNDLED").equalsIgnoreCase("true")) {
			useBundled = false;
			System.out.println("[LAUNCHER] [UPDATER] " + "Override! Disabled bundled wine!");
		}

		// Check pre-specified
		if (System.getenv("EMUFERAL_WINEDETECT_USE_BUNDLED") != null
				&& System.getenv("EMUFERAL_WINEDETECT_USE_BUNDLED").equalsIgnoreCase("true")
				&& supportBundledWine) {
			// Use auto
			System.out.println("[LAUNCHER] [UPDATER] " + "Override! Using bundled wine!");
			useBundled = true;
		} else if (System.getenv("EMUFERAL_WINEDETECT_USE_AUTO") != null
				&& System.getenv("EMUFERAL_WINEDETECT_USE_AUTO").equalsIgnoreCase("true")) {
			// Use auto
			System.out.println("[LAUNCHER] [UPDATER] " + "Override! Using automatic wine detection!");
			selectedWine = new WineInstallation("<auto>", "<auto>", "", false, true);
			useBundled = false;
		} else if (System.getenv("EMUFERAL_WINEDETECT_SELECTED_WINE") != null) {
			// Load
			String pathFragment = System.getenv("EMUFERAL_WINEDETECT_SELECTED_WINE");
			System.out.println("[LAUNCHER] [UPDATER] " + "Override! Trying to select user-specified wine: "
					+ pathFragment + "...");
			File path = new File(pathFragment);
			if (path.exists()) {
				// Get search result
				WineInstallation.WineSearchResult res = WineInstallation.findWineInstallation(path,
						"User-selected wine: ");
				if (res.status == WineInstallation.WineSearchResultStatus.SUCCESS) {
					// Disable bundled
					useBundled = false;

					// Select
					System.out.println("[LAUNCHER] [UPDATER] " + "Selected wine installation: "
							+ res.installation.display);
					selectedWine = res.installation;
					selectedWine.isUserPicked = true;
				} else {
					// Invalid
					System.err.println("[LAUNCHER] [UPDATER] " + "Error: invalid wine installation: "
							+ pathFragment + ": got error code: "
							+ res.status);
				}
			} else {
				// Invalid
				System.err.println("[LAUNCHER] [UPDATER] " + "Error: invalid wine installation: " + pathFragment
						+ ": path does not exist");
			}
		}

		// Check support for bundled wine
		if (!supportBundledWine && useBundled) {
			// Disable bundled
			useBundled = false;
		}

		// Default shortcut settings
		boolean createShortcutDesktop = true;
		boolean createStartMenu = true;

		// Load shortcut settings
		File shortcutsProperties = new File(targetInstall, "shortcuts.json");
		if (shortcutsProperties.exists()) {
			// Load settings
			JsonObject shortcutsSettings = JsonParser.parseString(Files.readString(shortcutsProperties.toPath()))
					.getAsJsonObject();

			// Load states
			createShortcutDesktop = shortcutsSettings.get("createShortcutDesktop").getAsBoolean();
			createStartMenu = shortcutsSettings.get("createStartMenuEntry").getAsBoolean();
		}

		// Installation options
		targetInstall.mkdirs();
		BufferedImage img = ImageIO.read(new File(instDir2, "banner.image"));
		InstallOptionsOverviewWindow overview = new InstallOptionsOverviewWindow(frmCenturiaLauncher,
				preferProtonEnabled, selectedWine, allWineInstalls, useWine, supportBundledWine, useBundled, img, lock,
				targetInstall.getCanonicalPath(), launcherDir, createShortcutDesktop, createStartMenu, os != 0);

		// Check cancel
		if (overview.getInstallPath() == null) {
			// Cancel
			return false;
		}

		// Result
		targetInstall = overview.getInstallPath();
		instDir = targetInstall;
		if (!targetInstall.mkdirs() && !targetInstall.exists()) {
			// Error
			if (installDirFile.exists()) {
				JOptionPane.showMessageDialog(frmCenturiaLauncher,
						"Error! The launcher appears to be missing and installer was unable to recreate it!\n\nThis could happen if it is installed on a disk not currently connected, or if the target folder could not be accessed.",
						"Launcher not on disk", JOptionPane.ERROR_MESSAGE);
			} else {
				JOptionPane.showMessageDialog(frmCenturiaLauncher,
						"Error! Access was denied to the target folder!\n\nThis could happen if the target folder is on a disk not currently connected, or if the target folder could not be accessed due to file permissions.",
						"Launcher not on disk", JOptionPane.ERROR_MESSAGE);
			}
			JOptionPane.showMessageDialog(frmCenturiaLauncher, "Returning to installer option selector.",
					"Installation failure", JOptionPane.INFORMATION_MESSAGE);
			return false;
		}

		// Update file paths
		wineProperties = new File(targetInstall, "wine.json");
		shortcutsProperties = new File(targetInstall, "shortcuts.json");

		// Wine settings
		if (useWine) {
			selectedWine = overview.getSelectedWine();

			// Write wine preferences
			JsonObject wineProps = new JsonObject();
			wineProps.addProperty("useBundled", overview.useBundledWine());
			wineProps.addProperty("preferProton", overview.preferProton());
			wineProps.addProperty("useAuto", selectedWine.isAuto);
			if (!selectedWine.isAuto) {
				wineProps.addProperty("name", selectedWine.name);
				wineProps.addProperty("display", selectedWine.display);
				wineProps.addProperty("path", selectedWine.path);
				wineProps.addProperty("userPicked", selectedWine.isUserPicked);
			}

			// Write
			Files.writeString(wineProperties.toPath(), wineProps.toString());
		}

		// Write shortcuts
		JsonObject shortcutProps = new JsonObject();
		shortcutProps.addProperty("createShortcutDesktop", overview.createShortcutDesktop());
		shortcutProps.addProperty("createStartMenuEntry", overview.createStartMenu());

		// Write
		Files.writeString(shortcutsProperties.toPath(), shortcutProps.toString());

		// Install
		performInstallLauncher(osxUseWineMethod, instDir, progressBar, launcherVersion, srvName, launcherURL, dataUrl,
				true, installDirFile, launcherDir);

		// Exit
		System.exit(0);
		return true;
	}

	private static void performInstallLauncher(boolean osxUseWineMethod, File instDir, JProgressBar progressBar,
			String launcherVersion, String srvName, String launcherURL, String dataUrl, boolean launchOnComplete,
			File installDirFile, String launcherDir) throws IOException {
		log("Preparing to install...");
		File instSource = new File("installerdata");

		// Load installation
		boolean targetChanged = false;
		File originalTarget = instDir;
		if (installDirFile.exists()) {
			originalTarget = new File(JsonParser.parseString(Files.readString(installDirFile.toPath()))
					.getAsJsonObject().get("installationDirectory").getAsString());
			if (!originalTarget.getAbsolutePath().equals(instDir.getAbsolutePath()))
				targetChanged = true;
		}

		// Default shortcut settings
		boolean createShortcutDesktop = true;
		boolean createStartMenu = true;

		// Load shortcut settings
		File shortcutsProperties = new File(originalTarget, "shortcuts.json");
		if (shortcutsProperties.exists()) {
			// Load settings
			JsonObject shortcutsSettings = JsonParser.parseString(Files.readString(shortcutsProperties.toPath()))
					.getAsJsonObject();

			// Load states
			createShortcutDesktop = shortcutsSettings.get("createShortcutDesktop").getAsBoolean();
			createStartMenu = shortcutsSettings.get("createStartMenuEntry").getAsBoolean();
		}

		// Check change of install path
		if (targetChanged) {
			log("Moving installation folder to new location...");

			// Collect files
			File originalTargetF = originalTarget;
			String[] targetFilesToMoveKeys = installTargetFiles;
			File[] originalFilesToMove = Stream.of(targetFilesToMoveKeys).map(t -> new File(originalTargetF, t))
					.filter(t -> t.exists()).toArray(t -> new File[t]);
			if (progressBar != null) {
				try {
					int c = 0;
					for (File f : originalFilesToMove)
						c += countDir(f);
					int cF = c;
					SwingUtilities.invokeAndWait(() -> {
						progressBar.setMaximum(cF);
						progressBar.setValue(0);
						progressBar.repaint();
					});
				} catch (InvocationTargetException | InterruptedException e) {
				}
			}

			// Move
			for (File fileToMove : originalFilesToMove) {
				// Move entry
				moveDir(fileToMove, new File(instDir, fileToMove.getName()), progressBar);
			}

			// Delete old if empty
			if (instDir.listFiles().length == 0)
				instDir.delete();

			// Mark finished
			if (progressBar != null) {
				try {
					SwingUtilities.invokeAndWait(() -> {
						progressBar.setMaximum(100);
						progressBar.setValue(100);
						progressBar.repaint();
					});
				} catch (InvocationTargetException | InterruptedException e) {
				}
			}
		}

		// Create output
		log("Creating installation directories...");
		File launcherOut = new File(instDir, "centuria-launcher");
		File installerOut = new File(instDir, "centuria-installer");
		launcherOut.mkdirs();
		installerOut.mkdirs();
		if (os == 0) {
			new File(System.getProperty("user.home") + "/Applications/" + srvName + ".app").mkdirs();
			installerOut = new File(installerOut, "installer.app");
			installerOut.mkdirs();
		}

		// Install launcher
		log("Copying base launcher...");
		if (progressBar != null) {
			try {
				int c = countDir(instSource);
				SwingUtilities.invokeAndWait(() -> {
					progressBar.setMaximum(c);
					progressBar.setValue(0);
					progressBar.repaint();
				});
			} catch (InvocationTargetException | InterruptedException e) {
			}
		}
		copyDir(instSource, launcherOut, progressBar);
		if (progressBar != null) {
			try {
				SwingUtilities.invokeAndWait(() -> {
					progressBar.setMaximum(100);
					progressBar.setValue(100);
					progressBar.repaint();
				});
			} catch (InvocationTargetException | InterruptedException e) {
			}
		}

		// Install installer
		log("Copying installer binaries...");
		if (progressBar != null) {
			try {
				int c = countDir(instSource);
				SwingUtilities.invokeAndWait(() -> {
					progressBar.setMaximum(c);
					progressBar.setValue(0);
					progressBar.repaint();
				});
			} catch (InvocationTargetException | InterruptedException e) {
			}
		}
		copyDir(instSource, installerOut, progressBar);

		// Copy server json
		File sOut = new File(installerOut, os == 0 ? "Contents/Resources/server.json" : "server.json");
		if (sOut.exists())
			sOut.delete();
		Files.copy(new File("server.json").toPath(), sOut.toPath());

		// Copy payload
		log("Copying installer payload data...");
		if (progressBar != null) {
			try {
				int c = countDir(instSource);
				SwingUtilities.invokeAndWait(() -> {
					progressBar.setMaximum(c);
					progressBar.setValue(0);
					progressBar.repaint();
				});
			} catch (InvocationTargetException | InterruptedException e) {
			}
		}
		File installerDataOut = new File(os == 0 ? new File(installerOut, "Contents/Resources") : installerOut,
				"installerdata");
		copyDir(instSource, installerDataOut, progressBar);
		if (progressBar != null) {
			try {
				SwingUtilities.invokeAndWait(() -> {
					progressBar.setMaximum(100);
					progressBar.setValue(100);
					progressBar.repaint();
				});
			} catch (InvocationTargetException | InterruptedException e) {
			}
		}

		// MacOS only
		if (os == 0 && createStartMenu) {
			// Install launcher
			log("Installing launcher application...");
			if (progressBar != null) {
				try {
					int c = countDir(instSource);
					SwingUtilities.invokeAndWait(() -> {
						progressBar.setMaximum(c);
						progressBar.setValue(0);
						progressBar.repaint();
					});
				} catch (InvocationTargetException | InterruptedException e) {
				}
			}
			File appDir = new File(System.getProperty("user.home") + "/Applications/" + srvName + ".app");
			copyDir(instSource, appDir, progressBar);
			if (progressBar != null) {
				try {
					SwingUtilities.invokeAndWait(() -> {
						progressBar.setMaximum(100);
						progressBar.setValue(100);
						progressBar.repaint();
					});
				} catch (InvocationTargetException | InterruptedException e) {
				}
			}

			// Copy server json
			sOut = new File(appDir, os == 0 ? "Contents/Resources/server.json" : "server.json");
			if (sOut.exists())
				sOut.delete();
			Files.copy(new File("server.json").toPath(), sOut.toPath());

			// Delete old folder if needed
			File oldLauncher = new File("/Applications/" + srvName + ".app");
			if (oldLauncher.exists()) {
				// Remove
				log("Removing previous launcher...");
				if (progressBar != null) {
					try {
						int c = countDir(oldLauncher);
						SwingUtilities.invokeAndWait(() -> {
							progressBar.setMaximum(c);
							progressBar.setValue(0);
							progressBar.repaint();
						});
					} catch (InvocationTargetException | InterruptedException e) {
					}
				}

				// Delete directory
				deleteDir(oldLauncher, progressBar);
				if (progressBar != null) {
					try {
						SwingUtilities.invokeAndWait(() -> {
							progressBar.setMaximum(100);
							progressBar.setValue(100);
							progressBar.repaint();
						});
					} catch (InvocationTargetException | InterruptedException e) {
					}
				}
			}
		}

		// Copy server info
		log("Copying server information...");
		sOut = new File(launcherOut, os == 0 ? "Contents/Resources/server.json" : "server.json");
		if (sOut.exists())
			sOut.delete();
		Files.copy(new File("server.json").toPath(), sOut.toPath());

		// Write installation json to launcher
		JsonObject instJsonData = new JsonObject();
		instJsonData.addProperty("installationDirectory", os == 0 ? "../../.." : "..");
		File instJson = new File(launcherOut, os == 0 ? "Contents/Resources/installation.json" : "installation.json");
		if (instJson.exists())
			instJson.delete();
		Files.writeString(instJson.toPath(), instJsonData.toString());

		// Copy runtime
		log("Copying java runtime...");
		if (progressBar != null) {
			try {
				int c = countDir(new File(os == 1 ? "win" : (os == 0 ? "osx" : "linux")));
				SwingUtilities.invokeAndWait(() -> {
					progressBar.setMaximum(c);
					progressBar.setValue(0);
					progressBar.repaint();
				});
			} catch (InvocationTargetException | InterruptedException e) {
			}
		}
		copyDir(new File(os == 1 ? "win" : (os == 0 ? "osx" : "linux")),
				new File(launcherOut, os == 1 ? "win" : (os == 0 ? "Contents/Resources/osx" : "linux")), progressBar);
		log("Copying java runtime to installer...");
		if (progressBar != null) {
			try {
				int c = countDir(new File(os == 1 ? "win" : (os == 0 ? "osx" : "linux")));
				SwingUtilities.invokeAndWait(() -> {
					progressBar.setMaximum(c);
					progressBar.setValue(0);
					progressBar.repaint();
				});
			} catch (InvocationTargetException | InterruptedException e) {
			}
		}
		copyDir(new File(os == 1 ? "win" : (os == 0 ? "osx" : "linux")),
				new File(installerOut, os == 1 ? "win" : (os == 0 ? "Contents/Resources/osx" : "linux")), progressBar);
		if (progressBar != null) {
			try {
				SwingUtilities.invokeAndWait(() -> {
					progressBar.setMaximum(100);
					progressBar.setValue(100);
					progressBar.repaint();
				});
			} catch (InvocationTargetException | InterruptedException e) {
			}
		}

		// Set perms
		if (os == 2) {
			// Linux-only
			log("Setting permissions...");
			ProcessBuilder proc = new ProcessBuilder("chmod", "+x",
					new File(launcherOut, "launcher.sh").getCanonicalPath());
			try {
				proc.start().waitFor();
			} catch (InterruptedException e1) {
			}
		}

		// Write initial version
		log("Saving initial version...");
		Files.writeString(new File(instDir, "currentversion.info").toPath(), "none");

		// Write installation directory
		JsonObject infoJson = new JsonObject();
		infoJson.addProperty("installationDirectory", instDir.getAbsolutePath());
		log("Creating installation target specifier file...");
		installDirFile.getParentFile().mkdirs();
		Files.writeString(installDirFile.toPath(), infoJson.toString());

		// Windows uninstall
		if (os == 1) {
			log("Writing uninstall information...");

			try {
				// Base key
				ProcessBuilder proc = new ProcessBuilder("reg", "add",
						"HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\" + launcherDir,
						"/f");
				proc.inheritIO();
				if (proc.start().waitFor() != 0)
					regWriteError();

				// Icon
				proc = new ProcessBuilder("reg", "add",
						"HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\" + launcherDir,
						"/v", "DisplayIcon", "/d", new File(installerOut, "icon.ico").getAbsolutePath(), "/f");
				proc.inheritIO();
				if (proc.start().waitFor() != 0)
					regWriteError();

				// Display name
				proc = new ProcessBuilder("reg", "add",
						"HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\" + launcherDir,
						"/v", "DisplayName", "/d", srvName, "/f");
				proc.inheritIO();
				if (proc.start().waitFor() != 0)
					regWriteError();

				// NoRepair
				proc = new ProcessBuilder("reg", "add",
						"HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\" + launcherDir,
						"/v", "NoRepair", "/t", "REG_DWORD", "/d", "1", "/f");
				proc.inheritIO();
				if (proc.start().waitFor() != 0)
					regWriteError();

				// Modify path
				proc = new ProcessBuilder("reg", "add",
						"HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\" + launcherDir,
						"/v", "ModifyPath", "/d",
						"\\\"" + new File(installerOut, "launcher.bat").getAbsolutePath() + "\\\"", "/f");
				proc.inheritIO();
				if (proc.start().waitFor() != 0)
					regWriteError();

				// Uninstall string
				proc = new ProcessBuilder("reg", "add",
						"HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\" + launcherDir,
						"/v", "UninstallString", "/d",
						"\\\"" + new File(installerOut, "launcher.bat").getAbsolutePath() + "\\\"", "/f");
				proc.inheritIO();
				if (proc.start().waitFor() != 0)
					regWriteError();
			} catch (Exception e) {
				regWriteError();
			}
		}

		// Windows and linux only, macos already has the app installed now
		if (os != 0 && (createShortcutDesktop || createStartMenu)) {
			log("Creating shortcuts...");
			if (os == 1) {
				// Windows

				// Build classpath
				String classPath = "";
				for (File f : new File(launcherOut, "libs").listFiles(t -> t.isFile() && t.getName().endsWith(".jar")))
					if (classPath.isEmpty())
						classPath = "libs/" + f.getName();
					else
						classPath += ";libs/" + f.getName();
				for (File f : launcherOut.listFiles(t -> t.isFile() && t.getName().endsWith(".jar")))
					if (classPath.isEmpty())
						classPath = f.getName();
					else
						classPath += ";" + f.getName();

				// Find desktop
				// Ugly but uh it works?
				File winDesktop = FileSystemView.getFileSystemView().getHomeDirectory();

				// Get JVM folder
				File jvm = new File(launcherOut, "win");
				String jvmVer = jvm.listFiles(t -> t.isDirectory() && t.getName().startsWith("java-"))[0].getName();

				// Create shortcuts
				if (createShortcutDesktop) {
					File lnk = new File(winDesktop, srvName + ".lnk");
					if (!lnk.getParentFile().exists())
						lnk.getParentFile().mkdirs();
					createWindowsShortcut(lnk, new File(launcherOut, "win/" + jvmVer + "/bin/javaw.exe"),
							"-cp '" + classPath + "' org.asf.centuria.launcher.updater.LauncherUpdaterMain",
							launcherOut, new File(launcherOut, "icon.ico"));
				}
				if (createStartMenu) {
					File lnk = new File(new File(System.getenv("APPDATA"), "Microsoft/Windows/Start Menu/Programs"),
							srvName + ".lnk");
					if (!lnk.getParentFile().exists())
						lnk.getParentFile().mkdirs();
					createWindowsShortcut(lnk, new File(launcherOut, "win/" + jvmVer + "/bin/javaw.exe"),
							"-cp '" + classPath + "' org.asf.centuria.launcher.updater.LauncherUpdaterMain",
							launcherOut, new File(launcherOut, "icon.ico"));
				}
			} else {
				// Linux
				if (createStartMenu) {
					File applicationFile = new File(
							System.getProperty("user.home") + "/.local/share/applications/" + srvName + ".desktop");
					if (!applicationFile.getParentFile().exists())
						applicationFile.getParentFile().mkdirs();

					// Generate desktop entry
					FileOutputStream sO = new FileOutputStream(applicationFile);
					sO.write("[Desktop Entry]\n".getBytes("UTF-8"));
					sO.write(("Name=" + srvName + "\n").getBytes("UTF-8"));
					sO.write(("Categories=Game;\n").getBytes("UTF-8"));
					sO.write(("Comment=Launcher for " + srvName + "\n").getBytes("UTF-8"));
					sO.write(("Exec=\"" + new File(launcherOut, "launcher.sh").getAbsolutePath() + "\"\n")
							.getBytes("UTF-8"));
					sO.write(("Icon=" + new File(launcherOut, "icon.png").getAbsolutePath() + "\n").getBytes("UTF-8"));
					sO.write(("Path=" + launcherOut.getAbsolutePath() + "\n").getBytes("UTF-8"));
					sO.write(("StartupNotify=true\n").getBytes("UTF-8"));
					sO.write(("Terminal=false\n").getBytes("UTF-8"));
					sO.write(("Type=Application\n").getBytes("UTF-8"));
					sO.write(("X-KDE-RunOnDiscreteGpu=true\n").getBytes("UTF-8"));
					sO.close();
				}
				if (createShortcutDesktop) {
					File applicationFile = new File(
							System.getProperty("user.home") + "/Desktop/" + srvName + ".desktop");
					if (!applicationFile.getParentFile().exists())
						applicationFile.getParentFile().mkdirs();

					// Generate desktop entry
					if (!createStartMenu) {
						FileOutputStream sO = new FileOutputStream(applicationFile);
						sO.write("[Desktop Entry]\n".getBytes("UTF-8"));
						sO.write(("Name=" + srvName + "\n").getBytes("UTF-8"));
						sO.write(("Categories=Game;\n").getBytes("UTF-8"));
						sO.write(("Comment=Launcher for " + srvName + "\n").getBytes("UTF-8"));
						sO.write(("Exec=\"" + new File(launcherOut, "launcher.sh").getAbsolutePath() + "\"\n")
								.getBytes("UTF-8"));
						sO.write(("Icon=" + new File(launcherOut, "icon.png").getAbsolutePath() + "\n")
								.getBytes("UTF-8"));
						sO.write(("Path=" + launcherOut.getAbsolutePath() + "\n").getBytes("UTF-8"));
						sO.write(("StartupNotify=true\n").getBytes("UTF-8"));
						sO.write(("Terminal=false\n").getBytes("UTF-8"));
						sO.write(("Type=Application\n").getBytes("UTF-8"));
						sO.write(("X-KDE-RunOnDiscreteGpu=true\n").getBytes("UTF-8"));
						sO.close();
					} else {
						// Create link
						File sourceApp = new File(
								System.getProperty("user.home") + "/.local/share/applications/" + srvName + ".desktop");
						if (applicationFile.exists())
							applicationFile.delete();
						Files.createSymbolicLink(applicationFile.toPath(), sourceApp.toPath());
					}
				}
			}
		}

		// Read server json
		JsonObject conf = JsonParser.parseString(Files.readString(Path.of("server.json"))).getAsJsonObject();
		if (conf.has("urlProtocols")) {
			// Go through protocols
			log("Setting url protocols...");
			JsonObject protocols = conf.get("urlProtocols").getAsJsonObject();
			for (String urlProt : protocols.keySet()) {
				String args = protocols.get(urlProt).getAsString();

				// Add protocol
				if (os == 2) {
					// Linux

					// Create entry
					File applicationFile = new File(System.getProperty("user.home") + "/.local/share/applications/"
							+ launcherDir + "-" + urlProt + ".desktop");
					if (!applicationFile.getParentFile().exists())
						applicationFile.getParentFile().mkdirs();
					FileOutputStream sO = new FileOutputStream(applicationFile);
					sO.write("[Desktop Entry]\n".getBytes("UTF-8"));
					sO.write(("Exec=\"" + new File(launcherOut, "launcher.sh").getAbsolutePath() + "\""
							+ (args.isEmpty() ? "" : " " + args.replace("%url%", "%u") + "\n")).getBytes("UTF-8"));
					sO.write(("Path=" + launcherOut.getAbsolutePath() + "\n").getBytes("UTF-8"));
					sO.write(("StartupNotify=false\n").getBytes("UTF-8"));
					sO.write(("NoDisplay=true\n").getBytes("UTF-8"));
					sO.write(("X-KDE-RunOnDiscreteGpu=true\n").getBytes("UTF-8"));
					sO.close();

					// Refresh database
					try {
						// Base key
						ProcessBuilder proc = new ProcessBuilder("xdg-mime", "default",
								launcherDir + "-" + urlProt + ".desktop", "x-scheme-handler/" + urlProt);
						proc.inheritIO();
						proc.start().waitFor();
					} catch (Exception e) {
					}
				} else if (os == 1) {
					// Windows

					try {
						// Base key
						ProcessBuilder proc = new ProcessBuilder("reg", "add",
								"HKEY_CURRENT_USER\\Software\\Classes\\" + urlProt, "/d",
								"URL : " + srvName + " " + urlProt, "/f");
						proc.inheritIO();
						proc.start().waitFor();

						// URL protocol
						proc = new ProcessBuilder("reg", "add", "HKEY_CURRENT_USER\\Software\\Classes\\" + urlProt,
								"/v", "URL Protocol", "/f");
						proc.inheritIO();
						proc.start().waitFor();

						// Shell command
						proc = new ProcessBuilder("reg", "add",
								"HKEY_CURRENT_USER\\Software\\Classes\\" + urlProt + "\\shell\\open\\command", "/d",
								"\\\"" + new File(launcherOut, "launcher.bat").getAbsolutePath() + "\\\""
										+ (args.isEmpty() ? ""
												: " " + args.replace("%url%", "%u").replace("\\\"", "\\\\\"")
														.replace("\"", "\\\"")),
								"/f");
						proc.inheritIO();
						proc.start().waitFor();
					} catch (Exception e) {
					}
				} else {
					// Mac
					// FIXME: support
				}
			}
		}

		// Done
		log("Installation completed!");
		if (frmCenturiaLauncher != null && !launchOnComplete) {
			JOptionPane.showMessageDialog(frmCenturiaLauncher, "Successfully installed the launcher!",
					"Installed the launcher", JOptionPane.INFORMATION_MESSAGE);
		}

		// Launch if needed
		if (launchOnComplete) {
			if (os != 0) {
				// Start process
				ProcessBuilder builder = new ProcessBuilder(
						(os == 1 ? new File(launcherOut, "launcher.bat").getAbsolutePath()
								: new File(launcherOut, "launcher.sh").getAbsolutePath()));
				builder.directory(launcherOut.getAbsoluteFile());
				builder.inheritIO();
				Process proc = builder.start();
				try {
					SwingUtilities.invokeAndWait(() -> {
						frmCenturiaLauncher.dispose();
					});
					proc.waitFor();
				} catch (InterruptedException | InvocationTargetException e) {
				}
				System.exit(proc.exitValue());
			} else {
				// Start OSX launcher
				ProcessBuilder builder = new ProcessBuilder("open", "-n",
						new File(System.getProperty("user.home") + "/Applications/" + srvName + ".app")
								.getAbsolutePath());
				builder.inheritIO();
				Process proc = builder.start();
				try {
					SwingUtilities.invokeAndWait(() -> {
						frmCenturiaLauncher.dispose();
					});
					proc.waitFor();
				} catch (InterruptedException | InvocationTargetException e) {
				}
				System.exit(proc.exitValue());
			}
		}
	}

	private static void regWriteError() {
		try {
			SwingUtilities.invokeAndWait(() -> {
				JOptionPane.showMessageDialog(frmCenturiaLauncher,
						"Failed to register the launcher to the windows program list, please contact support, an error occurred while writing the registry.",
						"Launcher Error", JOptionPane.ERROR_MESSAGE);
				System.exit(1);
			});
		} catch (InvocationTargetException | InterruptedException e) {
			;
		}
	}

	private static void createWindowsShortcut(File lnk, File executable, String args, File cwd, File icon)
			throws IOException {
		// Create temp file
		File vbs = File.createTempFile("desktopcreate-", ".vbs");
		vbs.deleteOnExit();

		// Write script
		FileOutputStream sO = new FileOutputStream(vbs);
		sO.write("Set sh = CreateObject(\"WScript.Shell\")\r\n".getBytes("UTF-8"));
		sO.write("Set sc = sh.CreateShortCut(WScript.Arguments(0))\r\n".getBytes("UTF-8"));
		sO.write("sc.IconLocation = WScript.Arguments(1)\r\n".getBytes("UTF-8"));
		sO.write("sc.TargetPath = WScript.Arguments(2)\r\n".getBytes("UTF-8"));
		sO.write("sc.Arguments = Replace(WScript.Arguments(3), \"'\", chr(34))\r\n".getBytes("UTF-8"));
		sO.write("sc.WorkingDirectory = WScript.Arguments(4)\r\n".getBytes("UTF-8"));
		sO.write("sc.Save\r\n".getBytes("UTF-8"));
		sO.close();

		// Start script
		ProcessBuilder proc = new ProcessBuilder("wscript", vbs.getCanonicalPath(), lnk.getAbsolutePath(),
				icon.getAbsolutePath(), executable.getAbsolutePath(), args, cwd.getAbsolutePath());
		try {
			proc.start().waitFor();
		} catch (InterruptedException e1) {
		}
		vbs.delete();
	}

	private static void uninstallLauncher(File instDir, JProgressBar progressBar, String srvName, String launcherDir,
			File installDirFile) {
		log("Preparing to uninstall...");

		// Check for installation data
		log("Finding launcher files...");
		File verFile = new File(instDir, "currentversion.info");
		if (!verFile.exists() && !installDirFile.exists()) {
			// Error
			if (frmCenturiaLauncher != null) {
				JOptionPane.showMessageDialog(frmCenturiaLauncher,
						"Unable to uninstall the launcher as it has not been installed yet.", "Installer Error",
						JOptionPane.ERROR_MESSAGE);
				System.exit(1);
			} else {
				System.err.println("Error: unable to uninstall the launcher as it has not been installed yet.");
				System.exit(1);
			}
		}

		// Uninstall
		log("Uninstalling launcher...");
		if (progressBar != null) {
			try {
				int c = countDir(instDir);
				SwingUtilities.invokeAndWait(() -> {
					progressBar.setMaximum(c);
					progressBar.setValue(0);
					progressBar.repaint();
				});
			} catch (InvocationTargetException | InterruptedException e) {
			}
		}

		// Delete directory
		if (instDir.exists()) {
			// Uninstall launcher

			// Collect files to delete related to the launcher, and only those related to
			// the launcher, nothing else, for older installs that still utilize an older
			// installer
			File instDirF = instDir;
			String[] keysToDelete = installTargetFiles;
			File[] filesToDelete = Stream.of(keysToDelete).map(t -> new File(instDirF, t))
					.filter(t -> t.exists()).toArray(t -> new File[t]);
			if (progressBar != null) {
				try {
					int c = 0;
					for (File f : filesToDelete)
						c += countDir(f);
					int cF = c;
					SwingUtilities.invokeAndWait(() -> {
						progressBar.setMaximum(cF);
						progressBar.setValue(0);
						progressBar.repaint();
					});
				} catch (InvocationTargetException | InterruptedException e) {
				}
			}

			// Move
			for (File file : filesToDelete) {
				// Delete entry
				deleteDir(file, progressBar);
			}

			// Delete target folder if empty
			if (new File(instDir.getAbsolutePath()).listFiles().length == 0)
				instDir.delete();
		}

		// Mark finished
		if (progressBar != null) {
			try {
				SwingUtilities.invokeAndWait(() -> {
					progressBar.setMaximum(100);
					progressBar.setValue(100);
					progressBar.repaint();
				});
			} catch (InvocationTargetException | InterruptedException e) {
			}
		}

		// Remove shortcuts
		log("Removing application shortcut files...");
		if (os == 0) {
			// Mac
			log("Removing launcher from applications...");
			File launcherFile = new File(System.getProperty("user.home") + "/Applications/" + srvName + ".app");
			if (launcherFile.exists()) {
				// Delete the launcher app
				log("Deleting launcher application...");
				if (progressBar != null) {
					try {
						int c = countDir(launcherFile);
						SwingUtilities.invokeAndWait(() -> {
							progressBar.setMaximum(c);
							progressBar.setValue(0);
							progressBar.repaint();
						});
					} catch (InvocationTargetException | InterruptedException e) {
					}
				}

				// Delete directory
				deleteDir(launcherFile, progressBar);
				if (progressBar != null) {
					try {
						SwingUtilities.invokeAndWait(() -> {
							progressBar.setMaximum(100);
							progressBar.setValue(100);
							progressBar.repaint();
						});
					} catch (InvocationTargetException | InterruptedException e) {
					}
				}
			}
		} else if (os == 1) {
			// Windows
			File winDesktop = FileSystemView.getFileSystemView().getHomeDirectory();
			File lnk = new File(winDesktop, srvName + ".lnk");
			if (lnk.exists())
				lnk.delete();
			lnk = new File(new File(System.getenv("APPDATA"), "Microsoft/Windows/Start Menu/Programs"),
					srvName + ".lnk");
			if (lnk.exists())
				lnk.delete();
		} else {
			// Linux
			File applicationFile = new File(
					System.getProperty("user.home") + "/.local/share/applications/" + srvName + ".desktop");
			if (applicationFile.exists())
				applicationFile.delete();
			applicationFile = new File(System.getProperty("user.home") + "/Desktop/" + srvName + ".desktop");
			if (applicationFile.exists())
				applicationFile.delete();
		}

		// Program entry
		if (os == 1) {
			try {
				// Base key
				ProcessBuilder proc = new ProcessBuilder("reg", "delete",
						"HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\" + launcherDir,
						"/f");
				proc.inheritIO();
				proc.start().waitFor();
			} catch (Exception e) {
			}
		}

		// Create URL protocols
		try {
			JsonObject conf = JsonParser.parseString(Files.readString(Path.of("server.json"))).getAsJsonObject();
			if (conf.has("urlProtocols")) {
				// Go through protocols
				log("Removing url protocols...");
				JsonObject protocols = conf.get("urlProtocols").getAsJsonObject();
				for (String urlProt : protocols.keySet()) {
					// Add protocol
					if (os == 2) {
						// Linux

						// Remove entry
						File applicationFile = new File(System.getProperty("user.home") + "/.local/share/applications/"
								+ launcherDir + "-" + urlProt + ".desktop");
						if (applicationFile.exists())
							applicationFile.delete();
					} else if (os == 1) {
						// Windows

						try {
							// Remove registry
							ProcessBuilder proc = new ProcessBuilder("reg", "delete",
									"HKEY_CURRENT_USER\\Software\\Classes\\" + urlProt, "/f");
							proc.inheritIO();
							proc.start().waitFor();
						} catch (Exception e) {
						}
					} else {
						// Mac
						// FIXME: support
					}
				}
			}
		} catch (IOException e) {
		}

		// Remove path file
		log("Removing installation target specifier file...");

		// Build folder path
		File installParent = installDirFile.getParentFile();
		if (installDirFile.exists())
			installDirFile.delete();
		File bannerFile = new File(instDir, "banner.image");
		if (bannerFile.exists())
			bannerFile.delete();
		bannerFile = new File(installParent, "banner.image");
		if (bannerFile.exists())
			bannerFile.delete();
		if (installParent.exists() && installParent.listFiles().length == 0) {
			installParent.delete();
		}

		// Done
		log("Uninstallation completed!");
		if (frmCenturiaLauncher != null) {
			JOptionPane.showMessageDialog(frmCenturiaLauncher, "Successfully uninstalled the launcher!",
					"Uninstalled the launcher", JOptionPane.INFORMATION_MESSAGE);
		}
		System.exit(0);
	}

	private static void copyDir(File dir, File output, JProgressBar progressBar) throws IOException {
		output.mkdirs();
		for (File subDir : dir.listFiles(t -> t.isDirectory())) {
			copyDir(subDir, new File(output, subDir.getName()), progressBar);
			if (progressBar != null) {
				try {
					SwingUtilities.invokeAndWait(() -> {
						progressBar.setValue(progressBar.getValue() + 1);
						progressBar.repaint();
					});
				} catch (InvocationTargetException | InterruptedException e) {
				}
			}
		}
		for (File file : dir.listFiles(t -> !t.isDirectory())) {
			File outputF = new File(output, file.getName());
			if (outputF.exists())
				outputF.delete();
			Files.copy(file.toPath(), outputF.toPath());
			if (progressBar != null) {
				try {
					SwingUtilities.invokeAndWait(() -> {
						progressBar.setValue(progressBar.getValue() + 1);
						progressBar.repaint();
					});
				} catch (InvocationTargetException | InterruptedException e) {
				}
			}
		}
		if (progressBar != null) {
			try {
				SwingUtilities.invokeAndWait(() -> {
					progressBar.setValue(progressBar.getValue() + 1);
					progressBar.repaint();
				});
			} catch (InvocationTargetException | InterruptedException e) {
			}
		}
	}

	private void downloadFile(String url, File outp, JProgressBar progressBar)
			throws MalformedURLException, IOException {
		URLConnection urlConnection = new URL(url).openConnection();
		try {
			SwingUtilities.invokeAndWait(() -> {
				progressBar.setMaximum(urlConnection.getContentLength() / 1000);
				progressBar.setValue(0);
			});
		} catch (InvocationTargetException | InterruptedException e) {
		}
		InputStream data = urlConnection.getInputStream();
		FileOutputStream out = new FileOutputStream(outp);
		while (true) {
			byte[] b = data.readNBytes(1000);
			if (b.length == 0)
				break;
			else {
				out.write(b);
				SwingUtilities.invokeLater(() -> {
					progressBar.setValue(progressBar.getValue() + 1);
				});
			}
		}
		out.close();
		data.close();
		SwingUtilities.invokeLater(() -> {
			progressBar.setValue(progressBar.getMaximum());
		});
	}

	private static void log(String message) {
		if (lblNewLabel != null) {
			try {
				SwingUtilities.invokeAndWait(() -> {
					lblNewLabel.setText(" " + message);
				});
			} catch (InvocationTargetException | InterruptedException e) {
			}
		}
		System.out.println("[LAUNCHER] [UPDATER] " + message);
	}

	private void unZip(File input, File output, JProgressBar bar) throws IOException {
		output.mkdirs();

		// count entries
		ZipFile archive = new ZipFile(input);
		int count = 0;
		Enumeration<? extends ZipEntry> en = archive.entries();
		while (en.hasMoreElements()) {
			en.nextElement();
			count++;
		}
		archive.close();

		// prepare and log
		archive = new ZipFile(input);
		en = archive.entries();
		try {
			int fcount = count;
			SwingUtilities.invokeAndWait(() -> {
				bar.setMaximum(fcount);
				bar.setValue(0);
			});
		} catch (InvocationTargetException | InterruptedException e) {
		}

		// extract
		while (en.hasMoreElements()) {
			ZipEntry ent = en.nextElement();
			if (ent == null)
				break;

			if (ent.isDirectory()) {
				new File(output, ent.getName()).mkdirs();
			} else {
				File out = new File(output, ent.getName());
				if (out.getParentFile() != null && !out.getParentFile().exists())
					out.getParentFile().mkdirs();
				FileOutputStream os = new FileOutputStream(out);
				InputStream is = archive.getInputStream(ent);
				is.transferTo(os);
				is.close();
				os.close();
			}

			SwingUtilities.invokeLater(() -> {
				bar.setValue(bar.getValue() + 1);
			});
		}

		// finish progress
		SwingUtilities.invokeLater(() -> {
			bar.setValue(bar.getValue() + 1);
		});
		archive.close();
	}

	private String processRelative(String apiData, String url) {
		if (!url.startsWith("http://") && !url.startsWith("https://")) {
			while (url.startsWith("/"))
				url = url.substring(1);
			url = apiData + url;
		}
		return url;
	}
}
