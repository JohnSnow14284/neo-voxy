# Voxy NeoForge 1.21.1

# Voxy NeoForge 1.21.1 非官方移植版

An unofficial NeoForge 1.21.1 port of **Voxy**, the high-performance Level of Detail terrain renderer for Minecraft.

这是 **Voxy** 的非官方 NeoForge 1.21.1 移植版本,原地址https://github.com/JohnSnow14284/1.21.1-Neo-Voxy
通过yarnobachmann的分支进行修改，但其基于的版本过于旧，性能表现很差，因此我结合原作者cortex的最新版（主要）和m3t4f1v3的分支版本进行修改，成功“套壳”到1.21.1Neoforge，且无需信雅互联和Fabricapi。

> All original Voxy credit belongs to [MCRcortex](https://github.com/MCRcortex), the creator of Voxy.
> Voxy 原作者为 [MCRcortex](https://github.com/MCRcortex)，本项目仅为非官方 NeoForge 移植与兼容性维护版本。

---

## Status / 当前状态

This port is currently **alpha** software. It builds for Minecraft 1.21.1 and NeoForge 21.1.x, but it is still being tested and stabilized.

当前版本仍属于 **alpha 测试阶段**。它可以在 Minecraft 1.21.1 与 NeoForge 21.1.x 环境下构建和运行，但仍建议在新存档或备份存档中测试后再加入长期整合包。

---

## What Works / 已实现功能

* Distant Level of Detail terrain rendering
  远距离 LOD 地形渲染

* Sodium-based rendering integration
  基于 Sodium 的渲染集成

* Sodium 0.8.12 video settings UI integration
  已集成到 Sodium 0.8.12 视频设置界面

* Client configuration through Sodium/Voxy settings
  可通过 Sodium / Voxy 设置界面调整配置

* FakeSight-style extended chunk request support, Thanks for song_5007.
  集成 FakeSight 风格的扩展区块请求功能，感谢大佬song_5007。

---


## Requirements / 运行需求

| Requirement | Version                                        |
| ----------- | ---------------------------------------------- |
| Minecraft   | 1.21.1                                         |
| NeoForge    | 21.1.x                                         |
| Java        | 21                                             |
| Sodium      | mc1.21.1-0.8.12-alpha.4-neoforge or compatible |

| 需求        | 版本                                     |
| --------- | -------------------------------------- |
| Minecraft | 1.21.1                                 |
| NeoForge  | 21.1.x                                 |
| Java      | 21                                     |
| Sodium    | mc1.21.1-0.8.12-alpha.4-neoforge 或兼容版本 |

This version no longer requires Forgified Fabric API as a mandatory dependency.

当前版本不再强制要求 Forgified Fabric API 作为前置依赖。

Recommended optional mods:

推荐可选 Mod：

| Mod                    | Why                                        |
| ---------------------- | ------------------------------------------ |
| Lithium                | General game performance improvements      |
| Iris                   | Shader testing, if supported by your setup |
| Reese's Sodium Options | Optional Sodium settings UI enhancement    |

| Mod                    | 作用                |
| ---------------------- | ----------------- |
| Lithium                | 提升游戏整体性能          |
| Iris                   | 用于测试光影兼容性         |
| Reese's Sodium Options | 可选的 Sodium 设置界面增强 |

---

## Installation / 安装方式

1. Install Minecraft 1.21.1 with NeoForge 21.1.x.
   安装 Minecraft 1.21.1 与 NeoForge 21.1.x。

2. Install the required NeoForge build of Sodium.
   安装 NeoForge 版本的 Sodium。

3. Download the latest `voxy-*.jar` from this repository's GitHub Releases page.
   从本仓库 GitHub Releases 页面下载最新的 `voxy-*.jar`。

4. Place the Voxy jar in your `mods` folder.
   将 Voxy jar 放入 `mods` 文件夹。

5. Start the game and check that Voxy appears in the mod list.
   启动游戏，并确认 Mod 列表中出现 Voxy。

6. Open Sodium video settings and configure Voxy.
   打开 Sodium 视频设置页面，并调整 Voxy 配置。

---

## Configuration / 配置说明

Voxy options can be accessed from Sodium's video settings screen.

Voxy 配置可以从 Sodium 视频设置界面进入。

Recommended first-test settings:

建议首次测试设置：

```text
Voxy Render Distance: Start low, then increase gradually
Extended Request Distance: 32 or 48
Shaders: Disabled for first test
Vanilla Render Distance: 8 to 16
```

中文建议：

```text
Voxy 渲染距离：先设置较低，再逐渐提高
扩展区块请求距离：建议先使用 32 或 48
光影：首次测试建议关闭
原版渲染距离：建议 8 到 16
```

If you encounter rendering issues, test with shaders disabled first.

如果遇到渲染异常，请先关闭光影进行测试。

---

## Troubleshooting / 常见问题

### The Game Crashes on Startup / 游戏启动崩溃

* Confirm you are using Minecraft 1.21.1.
  确认你使用的是 Minecraft 1.21.1。

* Confirm you are using NeoForge 21.1.x.
  确认你使用的是 NeoForge 21.1.x。

* Confirm Sodium is the NeoForge build and compatible with this port.
  确认 Sodium 是 NeoForge 版本，并且与本移植版兼容。

* Remove shader packs and other rendering overhaul mods for the first test.
  首次测试时建议移除光影和其他大型渲染修改 Mod。

* Check `latest.log` for dependency or mixin errors.
  查看 `latest.log` 中是否存在依赖缺失或 mixin 报错。

---

### Distant Terrain Does Not Render / 远景地形不渲染

* Confirm Voxy is enabled in its configuration screen.
  确认 Voxy 配置中已启用渲染。

* Confirm Sodium is installed and active.
  确认 Sodium 已安装并正常运行。

* Lower Voxy render distance temporarily.
  暂时降低 Voxy 渲染距离。

* Lower Extended Request Distance if using FakeSight integration.
  如果启用了 FakeSight 扩展请求，请适当降低请求距离。

* Test in a fresh world or a copied save.
  建议在新世界或备份存档中测试。

---



### Shader Issues / 光影问题

Shader support is experimental during the port.

光影支持仍处于测试阶段。

If a shader pack breaks Voxy rendering, test without the shader pack and report:

如果某个光影导致 Voxy 渲染异常，请先关闭光影测试，并反馈：

```text
Minecraft version
NeoForge version
Sodium version
Iris version
Voxy version
Shader pack name
latest.log
```

---

## Building From Source / 从源码构建

Clone the repository and run Gradle:

克隆仓库并运行 Gradle：

```bash
git clone https://github.com/yarnobachmann/Voxy-Neoforge.-1.21.1.git
cd Voxy-Neoforge.-1.21.1
./gradlew build
```

On Windows:

Windows 下：

```powershell
git clone https://github.com/yarnobachmann/Voxy-Neoforge.-1.21.1.git
cd Voxy-Neoforge.-1.21.1
.\gradlew.bat build
```

The compiled jar will be written to:

构建完成后的 jar 位于：

```text
build/libs/
```

---


## Credits / 鸣谢

* [MCRcortex](https://github.com/MCRcortex) - Original Voxy author
  Voxy 原作者
* [m3t4f1v3](https://github.com/m3t4f1v3) [yarnobachmann](https://github.com/yarnobachmann)- Forked Voxy author
  Voxy 分支作者

* [Original Voxy repository](https://github.com/MCRcortex/voxy)
  原版 Voxy 仓库
  
* NeoForge contributors
  NeoForge 贡献者

* Sodium contributors
  Sodium 贡献者

* Iris contributors
  Iris 贡献者
  
* FakeSight contributors
  FakeSight 贡献者

* The Minecraft modding community
  Minecraft Mod 开发社区

---

## License / 许可证

See [LICENSE.md](LICENSE.md).

请查看 [LICENSE.md](LICENSE.md)。

This is an unofficial port and is not affiliated with Mojang, Microsoft, NeoForge, Sodium, Iris, or the original Voxy project.

这是一个非官方移植版本，与 Mojang、Microsoft、NeoForge、Sodium、Iris 或原版 Voxy 项目无官方关联。

