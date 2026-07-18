# Immensa

Immensa is a Minecraft world-generation mod for Fabric and NeoForge that integrates [Terrain Diffusion](https://github.com/xandergos/terrain-diffusion) to generate realistic, continental-scale terrain. The peer-reviewed Terrain Diffusion research behind the original project was accepted to SIGGRAPH 2026.

This mod is based on Terrain Diffusion MC by Alexander Goslin (https://github.com/xandergos/terrain-diffusion-mc), used under the MIT License. It has been substantially modified and is not an official release of the original project.

## Which version should I use?

Use the single `-universal` jar for your loader from the Releases page of the
Immensa GitHub repository. It detects the operating system at startup: Windows loads DirectML, while Linux loads
CUDA when the required NVIDIA CUDA/cuDNN libraries are installed. Either platform falls back
to ONNX Runtime CPU in `inference.device=auto` mode when its GPU provider is unavailable.
Legacy platform-specific CUDA, DirectML and CPU builds remain available to source builders.

## Requirements

- Minecraft 1.21.1, running on Java 21 or higher
- One of the following mod loaders:
  - [Fabric](https://fabricmc.net/) Loader 0.19.3 or higher, with the [Fabric API](https://modrinth.com/mod/fabric-api) mod for Minecraft 1.21.1 installed
  - [NeoForge](https://neoforged.net/) 21.1.240 or higher
- Windows with a GPU OR Linux with an NVIDIA GPU is strongly recommended. CPU inference works but is very slow.
- VRAM (GPU RAM) needed: 1.5GB
- RAM needed: 2.5GB (May need to increase Minecraft's RAM allocation)

## Usage

**Linux GPU users:** First see [CUDA_INSTALL.md](CUDA_INSTALL.md).

1. Download the Immensa jar for your loader (`immensa-fabric-<version>.jar` or `immensa-neoforge-<version>.jar`) from the Releases page of the Immensa GitHub repository and place it in your Minecraft `mods/` folder. Make sure the Minecraft version matches.
2. Launch Minecraft, at least once online to download the models (~2.5GB).
3. Create a world, and select the **Terrain Diffusion** world type. Click **Customize** to set the `World Scale` (see [Per-world settings](#per-world-settings) below).
4. The mod will search for a land spawn point near the world origin automatically. If the area around (0, 0) is entirely ocean, it may take a moment to find land. Use `/immensa-explore` (see below) to scout the world further.

## Exploring the World

The mod includes a built-in terrain explorer web UI. Run the `/immensa-explore` command in-game; it will print a clickable link (e.g. `http://localhost:19801`) that opens an interactive map in your browser. Click the map on the left to open a "detailed view". Click the detailed view to get coordinates in the bottom left. You can also filter for certain climates.

Use the explorer to scout continents, mountains, islands, and other interesting terrain before venturing out in Minecraft.

## Configuration

Edit `config/immensa.properties` (created automatically on first launch):

```
# Immensa configuration

# Inference device: "cpu", "gpu", or "auto" (try GPU first then fall back to CPU).
# The universal jar uses DirectML on Windows, CUDA on Linux, and otherwise CPU.
# Use "gpu" instead of "auto" only when startup should fail if GPU setup is broken.
inference.device=auto

# Offload inactive models from VRAM between pipeline stages.
# Keeps peak VRAM to ~1.5-2 GB. Set to false if you have ~2.5+ GB free for slightly
# faster generation.
inference.offload_models=true

# Validate SHA-256 for pre-existing files in .minecraft/immensa-models.
# Set to false if you want to provide custom models/config files without hash checks.
validate_model=true

# Port for the local terrain explorer web UI (/immensa-explore).
explorer.port=19801

# Spawn search: coarse-pixel region sizes for finding a land spawn near (0, 0).
# Starts at initial_size x initial_size and expands by 8 each step up to max_size x max_size.
# Each coarse pixel covers a large area (hundreds of blocks), so 16–128 is typically sufficient.
spawn_search.initial_size=16
spawn_search.max_size=128

# Generate climate-aware rivers and bounded lakes.
hydrology.enabled=true
hydrology.context_padding=128
hydrology.min_catchment_km2=8.0
hydrology.scale_density=true
hydrology.river_width_multiplier=2.15
hydrology.normal_river_min_width_blocks=12
hydrology.normal_river_max_width_blocks=32
hydrology.great_rivers=true
hydrology.great_river_catchment_multiplier=6.0
hydrology.great_river_min_width_blocks=64
hydrology.great_river_max_width_blocks=120
hydrology.min_lake_area_km2=0.12
hydrology.max_lake_depth_m=300.0
hydrology.rain_lake_rise_blocks=1

# Multi-scale frozen-ocean pack ice and floes.
polar_ice.enabled=true
polar_ice.frozen_ocean_coverage=0.86
polar_ice.cold_ocean_coverage=0.34
polar_ice.max_thickness_blocks=3
polar_ice.offshore_reach_meters=850

# Dynamic geological stone/deepslate transition.
geology.dynamic_strata=true
geology.strata_relief_blocks=28
geology.strata_blend_blocks=10

# True 3D cliff ledges and undercuts.
terrain.cliff_overhangs=true
terrain.cliff_overhang_strength=1.0

# Carve deterministic, multi-scale caves into Terrain Diffusion terrain.
caves.enabled=true
caves.abundance=1.0
caves.tunnels=true
caves.massive_caverns=true
caves.surface_entrances=true
caves.drain_base_fluids=true
caves.deep_lava_pools=true

# CPU post-processing overlaps the next GPU inference. By default the mod uses
# half of the available processors, with a safe cap.
# performance.postprocess_threads=8
performance.inference_queue_size=32
performance.cache_tiles=96
performance.persistent_tile_cache=true
performance.prefetch_radius=1
performance.prefetch_queue_limit=4
```

### Rivers and lakes

Hydrology is derived deterministically from the generated elevation and biome maps.
Cold high terrain contributes glacier melt, tributaries merge through downhill flow
accumulation, and arid terrain loses water with distance. River width and depth follow
catchment size; gentle valleys meander, while deltas, estuaries, wetlands, floodplains,
waterfalls and occasional oxbows are classified from flow and slope. Small tributaries and noisy
terrain pits are pruned so the remaining main channels are wider and deeper. Closed
depressions become lakes at their spill elevation only when they meet the configured
minimum area and the deepest point is no more than 300 real-world meters below the
surface. River and lake shores receive greener biome variants. Underwater clay, sand,
mud and gravel use smooth world-seeded sediment patches rather than repeating coordinate
hash stripes.

Rare ocean-bound trunks become a separate great-river class after roughly six normal
river catchments merge. Great rivers widen smoothly toward 64–120 blocks, cut deeper
navigable channels, use long broad meanders, open equally broad estuaries, and build
wide alluvial floodplains. Ordinary rivers retain their existing size, so these waterways
read as exceptional continental features instead of covering every valley.

`hydrology.context_padding` controls how much neighboring terrain participates in a
tile's drainage calculation. Increasing it improves long-range catchments but costs
additional inference time. `hydrology.min_catchment_km2` controls river frequency;
`hydrology.river_width_multiplier` changes only visible wetted width. Ordinary retained
rivers now form broad 12–32 block channels, with separate minimum and maximum controls;
the normal-width cap prevents them from competing visually with rare great rivers.
Lower catchment values create more
streams. `hydrology.min_lake_area_km2` rejects small enclosed
ponds. When a chunk is first generated during rain, lake water rises by
`hydrology.rain_lake_rise_blocks` relative to its normal surface.

`hydrology.great_river_catchment_multiplier` controls how rarely great rivers begin.
Their minimum and maximum block widths can be changed independently. The maximum is
clamped to 120 blocks and is also limited by drainage context, preventing neighboring
tiles from disagreeing about a river bank.

Frozen oceans and frozen rivers receive a separate polar ice pass after biome features.
Shallow coasts form broad 38–48 block snow-covered slabs and shore-fast packed ice.
With increasing seabed depth the plates progressively fragment through medium pack into
sparse 8–12 block floes. Warped nested plate boundaries and long curving rifts avoid a
uniform fish-scale crack grid. A kilometre-scale noise-shaped pack front fades to open
water around `polar_ice.offshore_reach_meters`, so ice does not repeat across the entire
ocean. Cold oceans use a shorter, lighter marginal field. Because all geometry uses
absolute world coordinates, cracks continue cleanly through chunk and terrain-tile borders.

The stone/deepslate contact is geological rather than fixed to one Y level. Kilometer-wide
crustal provinces raise and lower the boundary, regional folds bend it further, and a
three-dimensional mixed band produces deepslate tongues and stone windows around the
contact. Volcanic and granitic provinces tend to lift deepslate upward, while limestone
and sedimentary regions retain deeper stone. Very deep terrain still becomes reliably
deepslate, preserving familiar mining progression without the artificial flat stripe.

`hydrology.scale_density=true` compensates for how much more physical land is shown
per Minecraft block at each world scale. With the default threshold, scale 1 uses an
effective 32 km² visible-stream catchment, scale 2 uses 8 km², and finer scales reduce
the physical threshold proportionally while preserving similar on-screen spacing.
Channel width is calculated in physical metres and converted to blocks, so scale
1 keeps narrow tributaries while major downstream rivers can still become broad.

### Caves

Terrain Diffusion adds caves directly to its density field instead of depending only
on biome decoration. Low-frequency three-dimensional noise creates large, flattened
chambers and intersecting noise sheets create connected winding tunnels. Surface cave
mouths are restricted to genuine hillsides: they open horizontally through a protected
roof, rise gently into the slope, and widen into an offset rear chamber. Flat terrain is
never punched from above. Ordinary cave noise is excluded from the first 23 blocks below
the surface, preventing enormous chambers from hiding beneath a thin terrain skin; only
the sparse downhill-facing mouths may cross that shell. Seeded multi-vault chambers create isolated cathedral-scale underground spaces
alongside the noise caves. A protected shallow band prevents random holes
across the landscape, while the bottom bedrock band is never carved. Vanilla cave and
canyon carvers remain enabled for small-scale variety. When Still Life is installed,
its cave biomes now also appear inside high mountain caves rather than only below Y=48.

Underground biomes form broad world-seed-deterministic provinces. Limestone and dry
sedimentary rock favour dripstone caves, wet forest and jungle catchments favour lush
caves, and rare regions below Y=8 with substantial overburden become deep dark. Tall
mountains do not become deep dark solely because they have a large surface-to-cave depth.
Terrain Diffusion also supplies six original provinces: limestone cathedrals with dense
dripstone, crystal grottos with frequent geodes, flooded subterranean wetlands, basalt and
magma-lined volcanic chambers, sparse-sculk echoing abysses, and cool alpine galleries
inside giant mountains. With Still Life installed,
the remaining provinces include frozen, glowing, mushroom, scorched, haunted, infested,
pale and barren caves. Their biome feature lists supply dripstone, sculk, vines, mushrooms,
icicles, monoliths, cobwebs and other decorations.

`caves.abundance` controls how much underground volume is open. The default `1.0` is
balanced; use `0.7` for rarer caves or `1.3` for a denser network. The three cave families
can be disabled independently. Cave layout is world-seed deterministic and safe under
multi-threaded chunk generation.

Winding tunnels are clustered into broad regional networks instead of filling every cave
layer. They peak around the middle and lower underground, vary from survival-sized passages
to occasional broad trunks, and rapidly become rarer with altitude. Giant mountains may
contain an unusual ascending branch, but do not repeat worm tunnels through their full height.
Thick mountain interiors use a separate alpine system: long gently sloping fault galleries,
large asymmetric vaults, connected side chambers and occasional tall tectonic fractures.
These systems create useful high-elevation exploration while leaving roughly half of mountain
provinces solid and keeping a protected roof below the summit surface.

`caves.drain_base_fluids=true` prevents Minecraft's base noise pass from filling
every underground void below sea level with water and every void below Y=-54 with
lava. It does not remove oceans, Terrain Diffusion rivers/lakes, or the smaller
springs and pools placed later by biome features.

Near bedrock, the cave density blends into a broad and rough noise-shaped floor
instead of stopping at one flat Y level. `caves.deep_lava_pools=true` retains only
sparse smooth-edged portions of Minecraft's lowest lava layer, producing occasional
deep basins without restoring the world-spanning lava floor.

Ancient Cities receive a dedicated structure-aware cavern during placement. Rounded
clearance volumes follow the actual city pieces and blend together across nearby streets
and plazas; unused corners of the city's outer bounding rectangle remain natural rock.
The roof varies smoothly at two scales and gives every structure piece safe clearance
without producing one enormous flat ceiling. Short deepslate foundations are repaired
only directly beneath city pieces, preventing lava or void breakthroughs without making
a rectangular platform. This applies only to Ancient Cities in newly generated Terrain
Diffusion chunks, including packs that replace the vanilla `minecraft:ancient_city`
definition.

Trial Chambers are checked against the generated underground volume before their pieces
are committed. Terrain Diffusion tests the full chamber footprint, tries nearby vertical
levels, and requires strong rock support and roof cover. A small cave intersection is kept
as a natural entrance, but candidates hanging in megacaverns or above deep lava lakes are
discarded. Trial Chambers have internal corridors and doors; vanilla does not guarantee a
surface entrance. This placement check applies to newly generated chunks only.

### Performance and concurrency

Terrain generation is automatically pipelined. InfiniteTensor cache mutation and
ONNX inference remain on one ordered thread for deterministic, race-free generation,
while completed tiles move to a bounded CPU pool for biome classification and
hydrology. This lets the GPU immediately begin the next tile while multiple CPU cores
finish previous tiles. Concurrent requests for the same tile share one future. Visible
gameplay uses the high-priority FIFO lane; bounded predictive prefetch uses a lower lane.
Biome and noise chunk-status futures are delayed until that shared tile future completes,
so Minecraft and C2ME workers are released during inference instead of blocking in a
heightmap lookup. Cache reads have a dedicated executor; optional compressed writes are
best-effort and cannot stall gameplay or inference. Scale-up samples only the requested
crop, avoiding full-size temporary elevation and climate images.

- `performance.postprocess_threads` overrides the automatic worker count. Half the
  available processors is normally fastest; reduce it if Minecraft needs more CPU.
- `performance.inference_queue_size` bounds pending GPU work and applies backpressure
  instead of allowing chunk requests to consume unlimited memory.
- `performance.cache_tiles` controls the final terrain-tile cache. Raising it trades
  RAM for fewer repeat queries.
- `performance.persistent_tile_cache` stores versioned compressed results under the
  game directory so revisiting or restarting does not repeat model inference.
- `performance.prefetch_radius` and `performance.prefetch_queue_limit` warm adjacent
  regions only while gameplay generation is not backed up.

The regional post-process adds multi-scale continental/range relief, rainfall-driven
hydraulic erosion, thermal relaxation, prevailing winds, rain shadows, maritime climate,
ecotones, geological provinces, variable soil and stable-ground metadata. Coast types
select beaches, snowy beaches, stony shores or mangrove wetlands instead of applying sand
uniformly. Large-footprint settlements are rejected on steep, flooded or unstable candidate
sites; temples, portals, monuments, mineshafts and other structures retain their own placement
rules so custom structure packs are not made artificially rare.
Rare strongly convergent plate boundaries rise into supermassifs with broad forested
foothills, long connected alpine spines, steep faces and several aggressive 7-9 km summit
crowns. Nested shorter-scale tectonic ridges can now drive exceptional summits beyond 10 km;
at world scale 2 those peaks can approach Y650–700 instead of ending with ordinary uplands
near Y300. Extreme height is smoothly compressed into each world's vertical range, preserving
sharp summit silhouettes and sky headroom instead of cutting peaks flat at the build limit.
Selected rocky headlands now rise directly from the sea as tall coastal cliffs, while other
coasts remain beaches, deltas or wetlands. Inland fault provinces form long stacked
escarpments: exposed rock walls separated by broad natural benches and plateaus instead of
uniform smooth hills or artificial block-by-block stair noise. Genuinely steep faces are
marked during regional generation and receive a final three-dimensional rock pass: coherent
projecting caps and shallow undercuts create real overhang silhouettes while normal slopes,
structure placement surfaces and gentle coasts remain unchanged.

Run `./gradlew hydrologyBenchmark` to measure the CPU stage on your machine. A native
Rust/JNI hydrology backend is intentionally not required: model execution already uses
native ONNX Runtime, and the optimized Java hydrology avoids per-pixel object allocation
while eliminating JNI array-copy overhead.

### Generation ordering

Terrain Diffusion keeps custom passes aligned with Minecraft's chunk-status pipeline.
AI density terrain and caves are generated before surface rules. After every vanilla and
modded carver is complete, base aquifer fluids are normalized and deterministic cave pools,
rivers and lakes are placed, allowing later structures and biome features to see the final
terrain. Structures, ores, springs and biome decoration then run normally. At the end of
the FEATURES stage—but before lighting—Ancient City fluids/foundations are repaired and
surface-cave vegetation is applied only to natural cave blocks outside expanded structure
piece boxes. This prevents late springs from flooding cities, structures from erasing cave
decoration, and cave decoration from repainting modded structure floors.

### Structure-pack compatibility

Terrain Diffusion supplies additive biome-tag bridges for vanilla replacement structures,
Still Life, Epic Structures, Better Archaeology, Hopo's structure packs, Moog's structures,
and the Nova/Dungeons & Taverns tag conventions. The bridge maps each pack's vanilla or
legacy biome categories onto the surface and cave biomes that this generator actually emits.
Biome-location searches use Minecraft's normal implementation, so stronghold rings and
commands that locate biomes or structures work with the custom biome source. These changes
apply to newly generated chunks; existing chunks are never retroactively regenerated.

### Per-world settings

For Terrain Diffusion worlds, click **Customize** in world creation and set:

- `World Scale` (integer `1..6`)

This value is saved with the world save and affects:

- how many real-world meters each block represents (`scale=1` => `30m/block`, `scale=2` => `15m/block`, etc.)
- world max height for newly created worlds (assumes tallest point is 10000 real-world meters)
- 2 is recommended for a good balance of scale and playability. Use 1 for smaller, more compressed worlds.
- Lower values put more stress on the GPU (Terrain Diffusion runs more often), while higher values put more stress on the CPU (larger world height). Most modern GPUs will be bottlenecked by the CPU around scale 2 or 3.

## Common Issues

**A dynamic link library (DLL) initialization routine failed**

This can happen for some older Java versions. Please update to the most recent version of Java 21 or higher. The [latest Microsoft OpenJDK 21](https://learn.microsoft.com/en-us/java/openjdk/download) version is known to work.

**LoadLibrary failed with error 126** *(CUDA build only)*

This is typically due to an improper CUDA or cuDNN installation. See [CUDA_INSTALL.md](CUDA_INSTALL.md) for troubleshooting steps.

**java.lang.IllegalStateException: Failed to load immensa models**

This typically indicates an "out of memory" error (the logs should show this as well).
Immensa's models take up about 2.5GB of RAM, so make sure to allocate enough RAM to account for this.

**If your issue is still not resolved, please open an issue on the Immensa GitHub repository.**

## Building from Source

An internet connection is required during the build to fetch the pinned model manifest metadata from Hugging Face.

**Requirements:** JDK 21 to build. (The Fabric dev client launches on a Java 25
toolchain — Gradle provisions it automatically — because C2ME's OpenCL module requires
Java 25 at runtime; the mod's own bytecode stays Java 21.)

The project is a multiloader build with three modules:

- `common/` — all mod code, resources and tests, compiled once against official Mojang
  mappings. Shared code lives here; the loader modules contain only loader-facing glue.
- `fabric/` — the Fabric loader module (Fabric Loom).
- `neoforge/` — the NeoForge loader module (ModDevGradle).

Build both loaders (universal variant, default):
```
./gradlew build
```

Or build one loader at a time:
```
./gradlew :fabric:build
./gradlew :neoforge:build
```

Output jars:

- `fabric/build/libs/immensa-fabric-<version>.jar`
- `neoforge/build/libs/immensa-neoforge-<version>.jar`

`<version>` is `1.0.0-<variant>+1.21.1`, e.g. `1.0.0-universal+1.21.1`. The mod id and
registry/datapack namespace is `immensa` on both loaders, so worlds and datapacks behave
the same on either loader.

The default build creates one universal jar per loader. It combines the official Linux
CUDA/CPU runtime with the matching Windows DirectML natives from `libs/onnxruntime-dml.jar`
without putting duplicate ONNX Runtime Java classes on Fabric's classpath.

Platform-specific builds are still available, using the same variant flags as before
(filename infix `-windows`, `-cuda` or `-cpu` instead of `-universal`):

Windows DirectML only:
```
./gradlew build -PuseDml=true
```

CUDA only:
```
./gradlew build -PuseCuda=true
```

CPU (also handles macOS/CoreML automatically):
```
./gradlew build -PuseCpu=true
```

Build all variants:
```
./gradlew buildAll
```

The root project also provides `buildCuda`, `buildDml` and `buildCpu` convenience tasks,
which build the matching variant for both loaders.

### Building onnxruntime with DirectML

**Requirements**

- [Windows 10 SDK (10.0.17134.0)](https://developer.microsoft.com/en-us/windows/downloads/sdk-archive/index-legacy) — for Windows 10 version 1803 or newer
- Visual Studio 2017 toolchain — install *Desktop development with C++* from the VS Installer
- Visual Studio 2022 toolchain — same as above
- Python 3.10+: [https://python.org/](https://python.org/)
- CMake 3.28 or higher

Keep both VS toolchains up to date. Full details at the [ONNX Runtime build docs](https://onnxruntime.ai/docs/build/inferencing.html) and the [DirectML EP requirements](https://onnxruntime.ai/docs/execution-providers/DirectML-ExecutionProvider.html#build).

**Steps**

Run all commands from the **Developer Command Prompt for VS 2022**.

```
git clone --recursive https://github.com/Microsoft/onnxruntime.git
cd onnxruntime
.\build.bat --config RelWithDebInfo --build_shared_lib --parallel --compile_no_warning_as_error --skip_submodule_sync --use_dml --build_java --build
```

The built jar appears in `java/build/`. Rename it to `onnxruntime-dml.jar` and place it in `libs/` in this repository.

## Note For Mod Developers

While modifying the AI terrain itself is quite complex, the integration with Minecraft biomes is extremely simple. The model outputs elevation + 4 climate variables, and this is converted to Minecraft biomes with hand-written rules. This is the most immediate way to improve the quality of the terrain and is relatively easy, but takes time to get realistic. The entire biome classifier is only 250 lines (`common/src/main/java/th/in/midnight_network/immensa/pipeline/BiomeClassifier.java`).

The terrain diversity far outpaces the biome diversity and there is a real opportunity to close that gap.

## Relationship to upstream

Immensa is a fork of terrain-diffusion-mc (https://github.com/xandergos/terrain-diffusion-mc) by Alexander Goslin. Relative to the original project, this fork has been substantially modified: it adds multiloader support (Fabric and NeoForge sharing one `common` module), renames the mod id and namespace to `immensa`, renames the configuration and data files, and continues feature development. Immensa is maintained by Midnight Network and is not affiliated with, endorsed by, or an official release of the original project. Bug reports and contributions for Immensa belong on the Immensa GitHub repository, not the upstream project.

## License

Immensa is licensed under the MIT License; see [LICENSE.txt](LICENSE.txt). The original
copyright (© 2025 Alexander Goslin) is retained, and modifications are © 2026 Midnight
Network. Third-party components and assets — including ONNX Runtime, FastNoiseLite and
the Terrain Diffusion model weights — are listed in [NOTICE.md](NOTICE.md).
