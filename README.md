# Neo-Voxy

**Neo-Voxy** 是 Voxy 的 Minecraft 1.21.1 NeoForge 非官方移植与兼容性维护版本。项目在保留 Voxy 高性能远景 LOD 架构的基础上，重点改进 NeoForge、Sodium、Iris 及常见模组环境下的稳定性与视觉表现。

**Neo-Voxy** is an unofficial Minecraft 1.21.1 NeoForge port and compatibility-focused continuation of Voxy. It preserves Voxy's high-performance distant-terrain LOD architecture while improving stability and visual quality across NeoForge, Sodium, Iris, and common modded environments.

> Voxy 由 [MCRcortex](https://github.com/MCRcortex) 创建。本项目不是 Voxy 官方版本，也不代表原作者、Mojang、Microsoft、NeoForge、Sodium 或 Iris。
>
> Voxy was created by [MCRcortex](https://github.com/MCRcortex). This project is unofficial and is not endorsed by the original author, Mojang, Microsoft, NeoForge, Sodium, or Iris.

---

## 特色功能 / Highlights

- **高性能远景 LOD / High-performance distant LOD**
  在原版区块范围之外绘制低细节地形，在扩展可视距离的同时控制 CPU、内存与 GPU 开销。
  Renders reduced-detail terrain beyond the vanilla chunk range while keeping CPU, memory, and GPU overhead under control.

- **Sodium 渲染集成 / Sodium rendering integration**
  接入 Sodium 的区块渲染阶段、相机数据与配置界面，同时保留 Voxy 专用的高性能 LOD 渲染器。
  Integrates with Sodium's terrain stages, camera data, and options UI while retaining Voxy's dedicated LOD renderer.

- **原版区块与 LOD 空间淡入 / Vanilla-to-LOD spatial fade**
  在玩家移动时，通过核心片元阶段的距离渐变与抖动覆盖平滑交接原版区块和 Voxy LOD；该效果不依赖特定光影包，并提供重叠距离与淡入长度选项。
  Smoothly hands off moving vanilla chunks to Voxy LOD using distance-based dither coverage in the core fragment stage; it is shader-pack independent and exposes overlap and fade-length controls.

- **扩展区块请求 / Extended chunk requests**
  单人游戏最高可请求 127 区块；移动时保持 32 区块以优先刷新原版地形，停止后再逐级扩展远景请求。
  Singleplayer can request up to 127 chunks; it stays at 32 while moving to prioritise vanilla terrain, then gradually expands the distant request while stationary.

- **远距离玩家与乘骑物 / Far players and ridden vehicles**
  采用 SeeU 风格的轻量玩家快照渲染远距离玩家；马、船等实体仅在玩家实际乘骑时创建代理。
  Uses SeeU-style snapshots for far players and only creates horse, boat or other vehicle proxies while actually ridden.

  单人游戏自动具备服务端支持；多人游戏的远距离玩家快照要求服务端也安装本版本。若安装独立 SeeU，Voxy 的内置玩家代理会自动停用以避免重复渲染。
  Singleplayer includes the server side automatically; multiplayer far-player snapshots require this version on the server too. The built-in player proxy disables itself when standalone SeeU is installed.

- **无光影水体优化 / No-shader water improvements**
  采用直通 Alpha 烘焙、标准 `SRC_ALPHA` 合成和流体面排序，改善关闭光影时远景水体的颜色、透明度、雾中表现与岸线衔接。
  Uses straight-alpha baking, standard `SRC_ALPHA` compositing, and ordered fluid-face submission to improve distant water colour, transparency, fog blending, and shorelines without shaders.

- **模组植物兼容 / Modded plant compatibility**
  支持标准交叉面地面植物的轻量 LOD 表现，并尽量避免影响作物、藤蔓、睡莲等特殊模型。
  Supports lightweight crossed-plane LOD models for standard ground plants while avoiding unwanted changes to crops, vines, lily pads, and other special models.

- **Supplement 荧光液正式兼容 / First-class Supplement Lumisene Fluids compatibility**
  正式兼容 Supplement 模组提供的 Lumisene Fluids（荧光液），包括流体颜色、透明度和 LOD 表面渲染。
  Provides first-class support for the Lumisene Fluids feature included in the Supplement mod, including fluid colour, transparency, and LOD surface rendering.

- **Domum Ornamentum 正式兼容 / First-class Domum Ornamentum compatibility**
  支持 Domum 动态材质、颜色映射与部分复杂外形的轻量代理模型，并仅在检测到 Domum 时启用相关逻辑。
  Supports Domum dynamic materials, colour mapping, and lightweight proxy geometry for selected complex shapes, with the compatibility path enabled only when Domum is installed.

- **可调树叶 LOD / Configurable leaf LOD**
  提供性能、平衡和质量三种树叶模式。默认的平衡模式会轻微变化各方向的烘焙纹理、剔除相邻树叶之间不可见的内部面，并使用 Mip 感知透明裁剪改善远景树冠。
  Provides Fast, Balanced, and Quality foliage modes. The default Balanced mode adds subtle per-face texture variation, removes hidden internal faces between matching leaves, and uses mip-aware alpha cutout for cleaner distant canopies.

- **保存与退出稳定性 / Save and shutdown reliability**
  优化区块保存、卸载竞争与世界关闭顺序，降低退出世界时卡住或存储未完全释放的概率。
  Improves section saving, unload races, and world shutdown ordering to reduce hangs and incomplete storage cleanup when leaving a world.

---

## 兼容性 / Compatibility

| Component | Supported Version | Status | Notes |
|---|---:|---|---|
| Minecraft | 1.21.1 | Required | Current target version |
| NeoForge | 21.1.x | Required | Native NeoForge build |
| Java | 21 | Required | Java 25 may be used by CI |
| Sodium | 0.8.x | Required | Rendering and options integration |
| Iris | 1.8.x | Optional | Shader support depends on the selected shader pack |
| Lithium | 0.15.x | Optional | Recommended general performance improvement |
| Supplement | 1.21.1 builds | Supported | Includes Lumisene Fluids compatibility |
| Domum Ornamentum | 1.21.1 builds | Supported | Dynamic material and proxy-model compatibility |

Neo-Voxy 不再强制要求 Forgified Fabric API 作为独立前置；实际依赖以发布页面和模组加载器提示为准。

Neo-Voxy no longer requires Forgified Fabric API as a separate mandatory prerequisite. The release page and loader dependency report remain the final source of truth.

---

## 安装 / Installation

1. 安装 Minecraft 1.21.1、NeoForge 21.1.x、Java 21 和兼容版本的 Sodium。
   Install Minecraft 1.21.1, NeoForge 21.1.x, Java 21, and a compatible Sodium build.
2. 将 `neo-voxy-<version>.jar` 放入实例的 `mods` 文件夹。
   Place `neo-voxy-<version>.jar` in the instance's `mods` directory.
3. 首次测试建议使用新世界，或提前备份世界与旧 Voxy 缓存。
   A new test world, or a backup of the world and existing Voxy cache, is recommended for initial testing.
4. 通过 Sodium 视频设置中的 Neo-Voxy 页面调整 LOD 与扩展区块请求参数。
   Configure LOD and extended chunk request settings from the Neo-Voxy page in Sodium's video settings.

---

## 配置说明 / Configuration Notes

- 修改较大的 LOD 参数后，部分内容可能需要重新进入世界、重新加载渲染器或重建旧缓存才能完全体现。
  After major LOD setting changes, re-entering the world, reloading the renderer, or rebuilding old cache data may be required.
- Domum Ornamentum 的旧缓存可能缺少动态材质信息；测试新兼容逻辑时建议清理对应世界的旧 Voxy 缓存。
  Old Domum Ornamentum cache data may not contain dynamic material metadata; clearing the affected world's old Voxy cache is recommended when testing the new compatibility path.
- 光影兼容性取决于 Iris、Sodium、光影包及其他渲染模组的组合。
  Shader compatibility depends on the combination of Iris, Sodium, the shader pack, and other rendering mods.
- 原版区块与 LOD 边界淡入默认采用 16 方块重叠和 16 方块渐变；若光影具有特殊的时间抗锯齿，可按实际观感调整渐变长度。
  The vanilla-to-LOD boundary fade defaults to a 16-block overlap and 16-block transition; tune the fade length when a shader pack uses unusual temporal anti-aliasing.
- 修改树叶 LOD 模式会自动重载 Voxy 渲染器，不需要删除世界 LOD 缓存。
  Changing the leaf LOD mode reloads the Voxy renderer and does not require deleting the world's LOD cache.

---

## 上游同步与维护 / Upstream Sync and Maintenance

本项目会定期跟踪并持续合并 Voxy 上游的性能优化、错误修复和架构改进。上游改动会根据 Minecraft 1.21.1、NeoForge 21.1.x 以及本项目已有兼容功能重新适配与测试，而不是直接覆盖分支独有实现。

This project periodically tracks and integrates upstream Voxy performance improvements, bug fixes, and architectural changes. Upstream changes are adapted and reviewed for Minecraft 1.21.1, NeoForge 21.1.x, and this port's existing compatibility work instead of replacing branch-specific implementations wholesale.

---

## 构建 / Building

Windows PowerShell：
Windows PowerShell:

```powershell
.\gradlew clean build
```

Linux 或 GitHub Actions：
Linux or GitHub Actions:

```bash
./gradlew clean build
```

正式发布文件位于 `build/libs`：
Release files are written to `build/libs`:

```text
neo-voxy-<version>.jar
```

构建过程可能保留带 `-unoptimized` 后缀的诊断中间包，该文件不应作为正式版本发布。

The build may retain an intermediate JAR with the `-unoptimized` suffix for diagnostics. It should not be distributed.

---

## 鸣谢 / Credits

| Project or Contributor | Contribution |
|---|---|
| [MCRcortex](https://github.com/MCRcortex) | Original Voxy author |
| [m3t4f1v3](https://github.com/m3t4f1v3) | Community fork and implementation references |
| [NHblock714/voxy](https://github.com/NHblock714/voxy) | No-shader water straight-alpha baking, compositing, and fluid-face ordering reference |
| [yarnobachmann](https://github.com/yarnobachmann) | Earlier NeoForge porting work |
| Supplement contributors | Lumisene Fluids implementation and ecosystem support |
| Sodium contributors | Rendering infrastructure and performance work |
| Iris contributors | Shader integration ecosystem |
| NeoForge contributors | NeoForge platform and tooling |
| FakeSight contributors | Extended chunk request concept |
| Minecraft modding community | Testing, issue reports, and compatibility feedback |

无光影水体处理中的直通 Alpha 烘焙、`SRC_ALPHA` 合成与流体面排序方案，参考并适配自 [NHblock714/voxy](https://github.com/NHblock714/voxy)。

The straight-alpha bake, `SRC_ALPHA` composite, and fluid-face ordering used by the no-shader water path were studied and adapted from [NHblock714/voxy](https://github.com/NHblock714/voxy).

感谢所有原作者、分支维护者、测试者和问题反馈者。

Thanks to all original authors, fork maintainers, testers, and issue reporters.

---

## 许可证 / License

请查看 [LICENSE.md](LICENSE.md)。

See [LICENSE.md](LICENSE.md).
