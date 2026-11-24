# kotlin-webrtc

A Kotlin native WebRTC DataChannel wrapper
of [libdatachannel](https://github.com/paullouisageneau/libdatachannel) based on kotlin cinterop

# Supported platforms

|         | x64 | arm64 |
|---------|:---:|:-----:|
| Linux   | ✔  |  ✔   |
| macOS   |  -  |  ✔   |
| Windows | ✔  |   -   |

# Document

[Basic Usage](docs/README.md)

# Updating libdatachannel

The libdatachannel headers and static `.a` files are **generated, not tracked by git**.
They are created automatically before cinterop when they are missing or when the configured
version changes. To update them explicitly:

```shell
./gradlew :libdatachannel:updateNativeLibs -PlibdatachannelVersion=0.24.8
```

Useful properties:

| Property                   | Description                                               |
|----------------------------|-----------------------------------------------------------|
| `libdatachannelVersion`    | Version to download and build (default: `0.24.8`)         |
| `libdatachannelTargets`    | Comma-separated targets; default auto-detects the host    |
| `libdatachannelSource`     | Use an existing libdatachannel checkout (with submodules) |
| `mbedtlsSource`            | Use existing mbedtls 3.6.2 headers (no download)          |
| `libdatachannelAutoUpdate` | Set to `false` to disable automatic preparation           |

The default targets are `linuxX64,linuxArm64,mingwX64` on non-macOS hosts and `macosArm64`
on macOS. The explicit task defaults to all four. On macOS the library is built natively
with `xcrun`/Xcode; on other hosts the macOS target is cross-built in the
`osxcross-env:latest` Docker image. Set `MACOS_MODE=native` or `MACOS_MODE=docker` to
override the automatic choice. Linux arm64 uses `aarch64-linux-gnu-*`. Windows x64
uses Kotlin/Native's bundled LLVM + MinGW 9 sysroot (`$KONAN_HOME/dependencies`) so the
`.a` files are ABI-compatible with what Kotlin/Native consumers link against.

The build always uses `NO_WEBSOCKET=ON` and `NO_MEDIA=ON`, and reuses the fixed mbedtls
3.6.2 static libraries with the per-target options they were built with. Generated
`include/libdatachannel/include/rtc` and `libs/*/*.a` are git-ignored; the upstream
`LICENSE`/`BUILDING.md` stay tracked, and the Windows `libthrow_bad_array_new_length.a`
workaround remains tracked.

Release publishing builds the Linux/MinGW libraries on Ubuntu and the macOS library on
macOS, then runs cinterop and `publishAllPublicationsToMavenRepository` on macOS.
Kotlin/Native requires a macOS host to produce Apple klibs with cinterop dependencies,
so the publish job cannot run on Linux.

# Open Source

`webrtc-kotlin` is open source under [Apache-2.0](LICENSE)