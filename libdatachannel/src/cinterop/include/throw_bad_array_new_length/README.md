# Workaround

A compatibility shim for linking GCC 14-compiled C++ static libraries against Ubuntu's GCC 13 MinGW-w64 libstdc++

# Building

```shell
apt update
apt install -y gcc-mingw-w64-x86-64-posix g++-mingw-w64-x86-64-posix binutils-mingw-w64-x86-64 mingw-w64-x86-64-dev mingw-w64-tools
```

Building with `make`, make sure you have installed `x86_64-w64-mingw32-gcc` at least version `14`



