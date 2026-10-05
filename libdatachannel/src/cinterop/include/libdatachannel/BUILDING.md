# Original project

https://github.com/paullouisageneau/libdatachannel

# libdatachannnel

```shell
cmake -B build-win -DCMAKE_SYSTEM_NAME=Windows -DCMAKE_SYSTEM_PROCESSOR=x86_64 -DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc-posix -DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++-posix -DCMAKE_RC_COMPILER=x86_64-w64-mingw32-windres -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DUSE_MBEDTLS=ON -DUSE_GNUTLS=OFF -DNO_MEDIA=ON -DNO_WEBSOCKET=ON -DUSE_SYSTEM_JUICE=OFF -DUSE_SYSTEM_USRSCTP=OFF   -DNO_EXAMPLES=ON   -DNO_TESTS=ON   -DPKG_CONFIG_PATH=   -DPKG_CONFIG_LIBDIR=   -DCMAKE_DISABLE_FIND_PACKAGE_PkgConfig=ON   -DMbedTLS_INCLUDE_DIR=/home/rtakland/source/mbedtls-3.6.2/include   -DMbedTLS_LIBRARY=/home/rtakland/source/mbedtls-3.6.2/build-win/library/libmbedtls.a   -DMbedX509_LIBRARY=/home/rtakland/source/mbedtls-3.6.2/build-win/library/libmbedx509.a   -DMbedCrypto_LIBRARY=/home/rtakland/source/mbedtls-3.6.2/build-win/library/libmbedcrypto.a   -Dplog_INCLUDE_DIR=/home/rtakland/source/libdatachannel-old/deps/plog/include
```

```shell
cmake -B build-dc-macos-arm64 \
  -DCMAKE_SYSTEM_NAME=Darwin \
  -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DCMAKE_C_COMPILER=arm64-apple-darwin25.1-clang \
  -DCMAKE_CXX_COMPILER=arm64-apple-darwin25.1-clang++ \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DUSE_MBEDTLS=ON \
  -DUSE_GNUTLS=OFF \
  -DNO_MEDIA=ON \
  -DNO_WEBSOCKET=ON \
  -DUSE_SYSTEM_JUICE=OFF \
  -DUSE_SYSTEM_USRSCTP=OFF \
  -DNO_EXAMPLES=ON \
  -DNO_TESTS=ON \
  -DCMAKE_DISABLE_FIND_PACKAGE_PkgConfig=ON \
  -DMbedTLS_INCLUDE_DIR=/home/rtakland/source/mbedtls-3.6.2/include \
  -DMbedTLS_LIBRARY=/home/rtakland/source/mbedtls-3.6.2/build-macos-arm64/library/libmbedtls.a \
  -DMbedX509_LIBRARY=/home/rtakland/source/mbedtls-3.6.2/build-macos-arm64/library/libmbedx509.a \
  -DMbedCrypto_LIBRARY=/home/rtakland/source/mbedtls-3.6.2/build-macos-arm64/library/libmbedcrypto.a

cmake --build build-dc-macos-arm64 -j$(nproc)
```

```shell
cmake -B build-dc-macos-x64 \
  -DCMAKE_SYSTEM_NAME=Darwin \
  -DCMAKE_OSX_ARCHITECTURES=x86_64 \
  -DCMAKE_C_COMPILER=x86_64-apple-darwin25.1-clang \
  -DCMAKE_CXX_COMPILER=x86_64-apple-darwin25.1-clang++ \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DUSE_MBEDTLS=ON \
  -DUSE_GNUTLS=OFF \
  -DNO_MEDIA=ON \
  -DNO_WEBSOCKET=ON \
  -DUSE_SYSTEM_JUICE=OFF \
  -DUSE_SYSTEM_USRSCTP=OFF \
  -DNO_EXAMPLES=ON \
  -DNO_TESTS=ON \
  -DCMAKE_DISABLE_FIND_PACKAGE_PkgConfig=ON \
  -DMbedTLS_INCLUDE_DIR=/home/rtakland/source/mbedtls-3.6.2/include \
  -DMbedTLS_LIBRARY=/home/rtakland/source/mbedtls-3.6.2/build-macos-x64/library/libmbedtls.a \
  -DMbedX509_LIBRARY=/home/rtakland/source/mbedtls-3.6.2/build-macos-x64/library/libmbedx509.a \
  -DMbedCrypto_LIBRARY=/home/rtakland/source/mbedtls-3.6.2/build-macos-x64/library/libmbedcrypto.a

cmake --build build-dc-macos-x64 -j$(nproc)
```

# mbedtls

```shell
cmake -B build-win   -DCMAKE_SYSTEM_NAME=Windows   -DCMAKE_SYSTEM_PROCESSOR=x86_64   -DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc-posix   -DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++-posix   -DCMAKE_RC_COMPILER=x86_64-w64-mingw32-windres   -DCMAKE_BUILD_TYPE=Release   -DENABLE_TESTING=OFF   -DENABLE_PROGRAMS=OFF   -DUSE_SHARED_MBEDTLS_LIBRARY=OFF   -DMBEDTLS_FATAL_WARNINGS=OFF
```

```shell
cmake -B build-macos-arm64 \
  -DCMAKE_SYSTEM_NAME=Darwin \
  -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DCMAKE_C_COMPILER=arm64-apple-darwin25.1-clang \
  -DCMAKE_CXX_COMPILER=arm64-apple-darwin25.1-clang++ \
  -DENABLE_TESTING=OFF \
  -DENABLE_PROGRAMS=OFF \
  -DUSE_SHARED_MBEDTLS_LIBRARY=OFF \
  -DMBEDTLS_FATAL_WARNINGS=OFF \
  -DCMAKE_BUILD_TYPE=Release

cmake --build build-macos-arm64 -j$(nproc)
```

```shell
cmake -B build-macos-x64 \
  -DCMAKE_SYSTEM_NAME=Darwin \
  -DCMAKE_OSX_ARCHITECTURES=x86_64 \
  -DCMAKE_C_COMPILER=x86_64-apple-darwin25.1-clang \
  -DCMAKE_CXX_COMPILER=x86_64-apple-darwin25.1-clang++ \
  -DENABLE_TESTING=OFF \
  -DENABLE_PROGRAMS=OFF \
  -DUSE_SHARED_MBEDTLS_LIBRARY=OFF \
  -DMBEDTLS_FATAL_WARNINGS=OFF \
  -DCMAKE_BUILD_TYPE=Release

cmake --build build-macos-x64 -j$(nproc)
```