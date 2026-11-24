# Native libraries

- libdatachannel: v0.24.8
- mbedtls: 3.6.2 (fixed static libraries, only headers are downloaded)

Built with:

- `NO_WEBSOCKET=ON` (no WebSocket/signaling server support)
- `NO_MEDIA=ON` (DataChannel-only wrapper)
- `NO_EXAMPLES=ON`, `NO_TESTS=ON`
- `BUILD_SHARED_LIBS=OFF`
- `USE_MBEDTLS=ON`, `USE_GNUTLS=OFF`
- bundled `libjuice` and `usrsctp` submodules

Regenerate all libraries and headers with:

```shell
./gradlew :libdatachannel:updateNativeLibs -PlibdatachannelVersion=0.24.8
```

See `libdatachannel/scripts/update-native-libs.sh` for the exact toolchain commands.
