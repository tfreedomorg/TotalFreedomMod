# TotalFreedomMod r03
![Release](https://img.shields.io/github/v/release/tfreedomorg/TotalFreedomMod?include_prereleases&style=plastic)
![Version](https://img.shields.io/badge/version-26.7.1-green?style=plastic)
![License](https://img.shields.io/badge/license-TFGL%20v2.0-orange?style=plastic)
![Code size](https://img.shields.io/github/languages/code-size/tfreedomorg/TotalFreedomMod?style=plastic)

TotalFreedomMod is a PaperMC server plugin designed primarily to support the [TotalFreedom Minecraft Server](https://tfreedom.org/). However, you are more than welcome to adapt the source for your own server.

This plugin was originally coded by StevenLawson (Madgeek1450), with Jerom van der Sar (Prozza) becoming heavily involved in its development some time later. It consists of over 85 custom coded commands and a large variety of distinguishable features not included in any other plugin. The plugin has since its beginning grown immensely. Together, with the main TotalFreedom server, TotalFreedomMod has a long-standing reputation of effectiveness whilst maintaining a clear feeling of openness towards the administrators and the players themselves.

Since then, the plugin has been widely adopted by numerous servers in order to fully create the experience of a freedom server.  This repository seeks to bring that traditional server experience into the modern era by updating TotalFreedomMod to be compatible with newer versions of Minecraft servers.

As of current, TotalFreedomMod is compatible with Paper 26.1.2 (last tested on 26.1.2-61-main@8dea6f1). It may work on older versions and/or server types, however there has been no testing performed to this end.

### Hard dependencies
As of TotalFreedomMod 6.1.3, the plugin requires [JLine3](https://github.com/jline/jline3) and [Log4j2](https://github.com/apache/logging-log4j2) to compile, but they are not required at runtime.  The plugin requires no external dependencies in order to run.

### Soft dependencies
The following plugins hook into TotalFreedomMod and are only necessary for specific features of the plugin to function as intended.  However, these plugins are not required for TotalFreedomMod to run.
1. [Vault](https://github.com/MilkBowl/Vault) (supports both the original library and [VaultUnlockedAPI](https://github.com/TheNewEconomy/VaultUnlockedAPI))
2. [WorldEdit](https://github.com/EngineHub/WorldEdit) 7.3.x (also supports [FastAsyncWorldEdit](https://github.com/IntellectualSites/FastAsyncWorldEdit))
3. [LibsDisguises](https://github.com/libraryaddict/LibsDisguises) 11.0.x
4. [EssentialsX](https://github.com/EssentialsX/Essentials) 2.21.x (including EssentialsChat and EssentialsSpawn)
5. [packetevents](https://github.com/retrooper/packetevents) 2.12.x (for item/block entity client anti-crash guards and inbound packet rate limiting)

### Download
To download TotalFreedomMod, please visit our [Releases](https://github.com/tfreedomorg/TotalFreedomMod/releases).

### Contributing
Please read [Contributing](https://github.com/tfreedomorg/TotalFreedomMod/wiki/Contributing) if you are interested in further developing TotalFreedomMod.

For information on how TotalFreedomMod is licensed, please see [LICENSE.md](LICENSE.md).

You are also welcome to [join our Discord server](https://tfreedom.org/discord) for any discussion on TFM development and other TotalFreedom Organization projects.
