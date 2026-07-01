# Voxy NeoForge 1.21.1 非官方移植版

An unofficial NeoForge 1.21.1 port of **Voxy**, the high-performance Level of Detail terrain renderer for Minecraft.

这是 **Voxy** 的非官方 NeoForge 1.21.1 移植版本,原地址https://github.com/JohnSnow14284/1.21.1-Neo-Voxy

通过yarnobachmann的分支进行修改，但其基于的版本过于旧，性能表现很差，因此我结合原作者cortex的最新版（主要）和m3t4f1v3的分支版本进行修改，成功“套壳”到1.21.1Neoforge，且无需信雅互联和Fabricapi。

> All original Voxy credit belongs to [MCRcortex](https://github.com/MCRcortex), the creator of Voxy.
> Voxy 原作者为 [MCRcortex](https://github.com/MCRcortex)，本项目仅为非官方 NeoForge 移植与兼容性维护版本。

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

* FakeSight-style extended chunk request support, Thanks for song_5007
  集成 FakeSight 风格的扩展区块请求功能，感谢大佬song_5007
  
* 已初步兼容Domum Ornamentum，修复模拟殖民地Domum Ornamentum方块颜色消失问题

---
## Pre / 计划实现

*兼容voxy worldgen

*兼容Continuity

---


## Requirements / 运行需求

| Requirement | Version                                        |
| ----------- | ---------------------------------------------- |
| Minecraft   | 1.21.1                                         |
| NeoForge    | 21.1.x                                         |
| Java        | 21                                             |
| Sodium      | mc1.21.1-0.8.12-alpha.4-neoforge or compatible |


This version no longer requires Forgified Fabric API as a mandatory dependency.

当前版本不再强制要求 Forgified Fabric API 作为前置依赖。

Recommended optional mods:

推荐可选 Mod：

| Mod                    | Why                                        |
| ---------------------- | ------------------------------------------ |
| Lithium                | General game performance improvements      |
| Iris                   | Shader testing, if supported by your setup |
| Reese's Sodium Options | Optional Sodium settings UI enhancement    |

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

