# The inherited ndkports AARs contain 4 KB ELF LOAD segments. Build the same
# upstream versions here so the NDK can link them for 16 KB pages. Android
# SONAME handling follows AOSP ndkports (Apache-2.0); see docs/PROVENANCE.md.
include(ExternalProject)
find_program(NETWORK_MAKE NAMES gmake make REQUIRED)
find_package(Perl REQUIRED)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a" OR CMAKE_HOST_WIN32)
    message(FATAL_ERROR "Native network libraries require ARM64 Android and a Linux/macOS build host")
endif()

get_filename_component(NETWORK_TOOLCHAIN_BIN "${CMAKE_C_COMPILER}" DIRECTORY)
string(REGEX REPLACE "^android-" "" NETWORK_ANDROID_API "${ANDROID_PLATFORM}")
set(NETWORK_ROOT "${CMAKE_CURRENT_BINARY_DIR}/native-network")
set(NETWORK_PREFIX "${NETWORK_ROOT}/install")
set(NETWORK_LINK_FLAGS "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
# Imported include directories must exist during CMake generation. Their
# contents are supplied by the ordered OpenSSL/curl installation steps.
file(MAKE_DIRECTORY "${NETWORK_PREFIX}/include")

ExternalProject_Add(vapor_openssl_build
    PREFIX "${NETWORK_ROOT}/openssl"
    URL https://github.com/openssl/openssl/releases/download/OpenSSL_1_1_1q/openssl-1.1.1q.tar.gz
    URL_HASH SHA256=d7939ce614029cdff0b6c20f0e2e5703158a489a72b2507b8bd51bf8c8fd10ca
    INSTALL_DIR "${NETWORK_PREFIX}"
    CONFIGURE_COMMAND "${CMAKE_COMMAND}" -E env
        "ANDROID_NDK_HOME=${ANDROID_NDK}"
        "PATH=${NETWORK_TOOLCHAIN_BIN}:$ENV{PATH}"
        "LDFLAGS=${NETWORK_LINK_FLAGS}"
        "${PERL_EXECUTABLE}" <SOURCE_DIR>/Configure android-arm64
        "-D__ANDROID_API__=${NETWORK_ANDROID_API}"
        --prefix=<INSTALL_DIR> --openssldir=/system/etc/security
        no-sctp no-tests shared
    BUILD_COMMAND "${CMAKE_COMMAND}" -E env
        "PATH=${NETWORK_TOOLCHAIN_BIN}:$ENV{PATH}"
        "${NETWORK_MAKE}" -j2 SHLIB_EXT=.so build_libs
    INSTALL_COMMAND "${CMAKE_COMMAND}" -E env
        "PATH=${NETWORK_TOOLCHAIN_BIN}:$ENV{PATH}"
        "${NETWORK_MAKE}" SHLIB_EXT=.so install_dev
    BUILD_BYPRODUCTS "${NETWORK_PREFIX}/lib/libcrypto.so" "${NETWORK_PREFIX}/lib/libssl.so"
    LOG_DOWNLOAD ON LOG_CONFIGURE ON LOG_BUILD ON LOG_INSTALL ON
)

ExternalProject_Add(vapor_curl_build
    PREFIX "${NETWORK_ROOT}/curl"
    URL https://curl.se/download/curl-7.85.0.tar.gz
    URL_HASH SHA256=78a06f918bd5fde3c4573ef4f9806f56372b32ec1829c9ec474799eeee641c27
    INSTALL_DIR "${NETWORK_PREFIX}"
    DEPENDS vapor_openssl_build
    CONFIGURE_COMMAND "${CMAKE_COMMAND}" -E env
        "PATH=${NETWORK_TOOLCHAIN_BIN}:$ENV{PATH}"
        "CC=${NETWORK_TOOLCHAIN_BIN}/aarch64-linux-android${NETWORK_ANDROID_API}-clang"
        "AR=${CMAKE_AR}" "RANLIB=${CMAKE_RANLIB}" "STRIP=${CMAKE_STRIP}"
        "NM=${CMAKE_NM}" "LD=${CMAKE_LINKER}"
        "CFLAGS=-O2 -fPIC" "LDFLAGS=${NETWORK_LINK_FLAGS}"
        # Do not discover build-host TLS/HTTP libraries through pkg-config.
        "PKG_CONFIG_LIBDIR=${NETWORK_PREFIX}/lib/pkgconfig"
        <SOURCE_DIR>/configure --host=aarch64-linux-android
        --prefix=<INSTALL_DIR> --disable-static --enable-shared
        --disable-ntlm-wb --enable-ipv6 --with-zlib
        --with-ca-path=/system/etc/security/cacerts --without-ca-bundle
        --with-ssl=<INSTALL_DIR>
    BUILD_COMMAND "${NETWORK_MAKE}" -j2 -C lib
    INSTALL_COMMAND "${NETWORK_MAKE}" -C lib install
        COMMAND "${NETWORK_MAKE}" -C include install
    BUILD_BYPRODUCTS "${NETWORK_PREFIX}/lib/libcurl.so"
    LOG_DOWNLOAD ON LOG_CONFIGURE ON LOG_BUILD ON LOG_INSTALL ON
)

foreach(module crypto ssl curl)
    add_library(vapor_${module} SHARED IMPORTED GLOBAL)
    set_target_properties(vapor_${module} PROPERTIES
        IMPORTED_LOCATION "${NETWORK_PREFIX}/lib/lib${module}.so"
        IMPORTED_SONAME "lib${module}.so"
        INTERFACE_INCLUDE_DIRECTORIES "${NETWORK_PREFIX}/include")
endforeach()
add_dependencies(vapor_crypto vapor_openssl_build)
add_dependencies(vapor_ssl vapor_openssl_build)
add_dependencies(vapor_curl vapor_curl_build)
set_target_properties(vapor_ssl PROPERTIES INTERFACE_LINK_LIBRARIES vapor_crypto)
set_target_properties(vapor_curl PROPERTIES INTERFACE_LINK_LIBRARIES "vapor_ssl;vapor_crypto")
