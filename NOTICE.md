# Third-Party Notices

Immensa includes, derives from, or interacts with the following third-party
software and assets.

## terrain-diffusion-mc

Immensa is derived from **terrain-diffusion-mc**, Copyright (c) 2025 Alexander
Goslin, licensed under the MIT License.

Upstream repository: https://github.com/xandergos/terrain-diffusion-mc

The upstream copyright notice and license text are retained in
[LICENSE.txt](LICENSE.txt). Modifications are Copyright (c) 2026 Midnight
Network, under the same MIT License.

## ONNX Runtime

The Immensa jars redistribute native libraries and Java classes from
**ONNX Runtime** 1.23.2, Copyright (c) Microsoft Corporation, licensed under
the MIT License. This includes the official Linux CUDA build and a locally
built Windows DirectML build (`libs/onnxruntime-dml.jar`).

Project repository: https://github.com/microsoft/onnxruntime

## FastNoiseLite

The file `common/src/main/java/th/in/midnight_network/immensa/pipeline/FastNoiseLite.java`
is upstream **FastNoiseLite** source code by Jordan Peck (Auburn), licensed
under the MIT License. Its file header carries the original notice:

```
MIT License

Copyright(c) 2023 Jordan Peck (jordan.me2@gmail.com)
Copyright(c) 2023 Contributors
```

Project repository: https://github.com/Auburn/FastNoiseLite

## Terrain Diffusion models

The ONNX model weights used at runtime (`xandergos/terrain-diffusion-30m-onnx`
on Hugging Face, licensed under the MIT License) are **not** redistributed in
the Immensa jars. They are downloaded automatically on first launch and stored
in the local `immensa-models` directory.

Model repository: https://huggingface.co/xandergos/terrain-diffusion-30m-onnx

## Minecraft

Minecraft is a trademark of Mojang Synergies AB / Microsoft Corporation.
Immensa is an unofficial, community-made mod and is not affiliated with,
endorsed by, or approved by Mojang or Microsoft. Immensa is likewise not
affiliated with or endorsed by the author of the original terrain-diffusion-mc
project.
