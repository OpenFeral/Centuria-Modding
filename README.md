 [![FTL Loader Build (Win64)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/win64-build.yml/badge.svg)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/win64-build.yml) [![FTL Loader Build (OSX)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/osx-build.yml/badge.svg)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/osx-build.yml) [![FTL Loader Build (Android)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/android-arm64-build.yml/badge.svg)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/android-arm64-build.yml) [![FT CLI Build (Win64)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/ftl-cli-win64-build.yml/badge.svg)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/ftl-cli-win64-build.yml) [![FT CLI Build (Linux)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/ftl-cli-linux-build.yml/badge.svg)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/ftl-cli-linux-build.yml) [![FT CLI Build (OSX)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/ftl-cli-win64-build.yml/badge.svg)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/ftl-cli-osx-build.yml) 

 [![FeralTweaks Build](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/ft-basemod-build.yml/badge.svg)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/ft-basemod-build.yml) [![FeralTweaks Server Build](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/ft-server-build.yml/badge.svg)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/ft-server-build.yml) [![DiscordRPC Build](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/discordrpc-build.yml/badge.svg)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/discordrpc-build.yml) [![EmuFeral Content Build](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/emuferal-content-build.yml/badge.svg)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/emuferal-content-build.yml) [![FeralTweaks Server Build](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/ft-server-build.yml/badge.svg)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/ft-server-build.yml) [![Launcher Build](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/launcher-build.yml/badge.svg)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/launcher-build.yml) [![Android Launcher Build](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/android-launcher-build.yml/badge.svg)](https://github.com/SkySwimmer/Centuria-Modding/actions/workflows/android-launcher-build.yml)

 ![Launcher version](https://img.shields.io/badge/Launcher%20Version-1.0.0.A16-gold.svg) ![EmuFeral Content version](https://img.shields.io/badge/EmuFeral%20Content%20Version-Beta%201.8-gold.svg) ![Bootstrap version](https://img.shields.io/badge/FTL%20Bootstrap%20Version-v1.0.0--alpha--a6-blueviolet.svg) ![Loader version](https://img.shields.io/badge/FTL%20Loader%20Version-v1.0.0--alpha--a6-darkorchid.svg) ![FT version](https://img.shields.io/badge/FeralTweaks%20Version-beta--1.0.0--b6-cyan.svg) ![FeralTweaks Server version](https://img.shields.io/badge/FeralTWeaks%20Server%20Version-beta--1.0.0--b5-cyan.svg) ![DisordRPC version](https://img.shields.io/badge/DiscordRPC%20Mod%20Version-1.0.0.0-a8b3de.svg)
 
 ![License: GPL v2](https://img.shields.io/badge/License-GPL%20v2-blue.svg) ![Copyright](https://img.shields.io/badge/Copyright-Copyright(c)%202025%20Nexus%20Emulation-blue.svg)

# The Centuria Modding Project
This is the repository for the official [Centuria](https://github.com/CPeers1/Centuria) modding project. This repository contains client modding code and server modules designed to patch up and modify parts of the original game.

Centuria is a work-in-progress server emulator for the now-defunct MMORPG Fer.al. The main server project can be found [here](https://github.com/CPeers1/Centuria). Centuria is developed by a group of developers from the Fer.ever discord. The software was originally released by the AerialWorks Software Foundation (SkySwimmer's small organization) but is now owned [Owenvii](https://github.com/CPeers1).

Note that this project is a HEAVY WIP and subject to change. Currently the android mod is not yet functional, only the desktop mod, launcher and server module PARTIALLY work.

<br/>

# Building the client mods
The project is split up into multiple parts, `feraltweaks`, `feraltweaks-bootstrap` (FTL modloader), the launcher and the server modules needed for the mod handshake and mod download system.

<br/>

## Setup
First, set up dependencies:

```
mkdir deps
git clone https://github.com/SkySwimmer/ScaffoldSharp deps/ScaffoldSharp
```

ScaffoldSharp is needed as it is a framework used by the modloader and its mods. Its a modularity framework developed by us in-house, used primarily for its dynamic instance managing.

## Building the Desktop client mod (feraltweaks)
Feraltweaks is a FTL mod, in order to build it you will need .NET cli installed.
You will also need to have a original fer.al client in order to mod it.

1. Firstly, you need a Fer.al client, you can download it yourself from the EmuFeral servers or by using the one downloaded by the EmuFeral launcher.

2. If you do not have a Fer.al client, you can download it from https://emuferal.ddns.net/feraldownloads/win64/b444802d2ab386d50f57f641ff74422471910210fc9ef1faf3631404a8401630.7z

3. After downloading, extract the `.7z` file and the client should be in the folder named `build`.

4. After that, you will need to install the FeralTweaks loader, you can download it from the [github workflows](https://github.com/SkySwimmer/Centuria-Modding/actions/), select your platform and select the latest build.

5. Extract the zip in the fer.al client, after extracting you should have the Fer.al exe, FeralTweaks folder and Fer.al_Data folder and some other files in the same folder.

6. Run the client, it should generate the assemblies for the game, note that first startup **always takes a very very long time**.

6. After the first run, FTL will have generated the proxy assemblies in `FeralTweaks/cache/assemblies`. These are the client assemblies, which are needed to build the mod, copy the contents of that folder to `feraltweaks/lib/feral` (you may need to create this folder)

7. Create the folder `run` in `feraltweaks`, and copy your client to it (make sure to include FeralTweaks while copying), it should end up looking like this:<br/>
-- feraltweaks<br/>
--- lib<br/>
---- feral<br/>
----- Assembly-CSharp.dll<br/>
----- Assembly-CSharp-firstpass.dll<br/>
----- etc...<br/>
--- run<br/>
---- CoreCLR<br/>
---- Fer.al_Data<br/>
---- FeralTweaks<br/>
---- Fer.al.exe<br/>
---- winhttp.dll<br/>
---- etc...<br/>

8. Run the following command in the root of the project
```
dotnet build
```

9. You can find the built mod at `feraltweaks/run/FeralTweaks/mods/feraltweaks`

## Using the Desktop mod
After putting the mod folder in the `FeralTweaks/mods` folder of your client (you may need to create this directory), it should load automatically as long as you have FTL installed. Note that the client mod doesn't have content, content is streamed (or will stream) from the  server.

You can however change client-specific options in `FeralTweaks/config/feraltweaks/settings.props` which contains some settings you may find useful. Note that client settings are overriden by the server when playing on a feral-tweaks enabled Centuria server.

<br/>

# List of server modules
Here is the list of server modules that are a part of the project:
 - [feraltweaks-server-module](https://github.com/SkySwimmer/Centuria-Modding/tree/main/feraltweaks-server-module): core server logic required for some features of the client mod. This module provides required handshake logic for chat and game bindings and provides a way for servers to keep client mods up-to-date.
 - [gcs-for-feral](https://github.com/SkySwimmer/Centuria-Modules): group chats for Fer.al, technical side functions like DM conversations which is why thet are vanilla-compatible. GCs are created via commands and work on both vanilla and modded clients, modded clients distinguish GCs from DMs and separate them into two tabs.

<br/>

# Building the server modules
Each server module project is built with Gradle, you will need Java 17 on your device for this.


<br/>


## Building on Windows
On windows, run the following commands in cmd or powershell (inside a module subdirectory):

Set up a local server to build against:
```powershell
.\createlocalserver.bat
```

Set up a development environment (optional):
```powershell
.\gradlew eclipse createEclipseLaunches
```

Build the project:
```powershell
.\gradlew build
```

<br/>

## Building on Linux and OSX
On linux, in bash or your favorite shell, run the following commands in a module subdirectory: (note that this requires bash to be installed on OSX, most linux distros have bash pre-installed)

Configure permissions:
```bash
chmod +x createlocalserver.sh
chmod +x gradlew
```

Set up a local server to build against:
```bash
./createlocalserver.sh
```

Set up a development environment (optional):
```bash
./gradlew eclipse createEclipseLaunches
```

Build the project:
```bash
./gradlew build
```

<br/>

## Installing the modules on a Centuria server
After building, modules will be placed in `build/libs` (of the module subdirectory), simply copy the jar file into the `modules` folder of a Centuria server.

### Exception to this build directory
Apart from `centuria-discord`, all modules build in `build/libs`, however the Discord bot module has more dependencies. After building, you should copy the contents of `build/moduledata` to the server directory. This directory includes all dependencies of the module.
