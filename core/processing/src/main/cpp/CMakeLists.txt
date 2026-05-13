cmake_minimum_required(VERSION 3.22.1)
project(imageshare_native)

option(IMAGESHARE_BUILD_JPEG "Build native JPEG encoder via libjpeg-turbo" ON)
option(IMAGESHARE_BUILD_AVIF "Build native AVIF encoder via libavif" ON)

if(IMAGESHARE_BUILD_AVIF)
    set(AVIF_PREBUILT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/prebuilts-avif/${ANDROID_ABI}")
    set(AVIF_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include-avif")

    if(NOT EXISTS "${AVIF_PREBUILT_DIR}/libavif.a")
        message(FATAL_ERROR
            "libavif prebuilt missing for ${ANDROID_ABI}. "
            "Place libavif.a and libaom.a at ${AVIF_PREBUILT_DIR}/ "
            "and avif/avif.h at ${AVIF_INCLUDE_DIR}/avif/. "
            "See prebuilts-avif/README.md for sourcing instructions."
        )
    endif()

    if(NOT EXISTS "${AVIF_PREBUILT_DIR}/libaom.a")
        message(FATAL_ERROR
            "libaom prebuilt missing for ${ANDROID_ABI}. "
            "Place libaom.a at ${AVIF_PREBUILT_DIR}/libaom.a. "
            "See prebuilts-avif/README.md for sourcing instructions."
        )
    endif()

    if(NOT EXISTS "${AVIF_INCLUDE_DIR}/avif/avif.h")
        message(FATAL_ERROR
            "avif.h missing. Place the libavif header at "
            "${AVIF_INCLUDE_DIR}/avif/avif.h. See prebuilts-avif/README.md for sourcing instructions."
        )
    endif()

    add_library(avif STATIC IMPORTED)
    set_target_properties(avif PROPERTIES IMPORTED_LOCATION "${AVIF_PREBUILT_DIR}/libavif.a")

    add_library(aom STATIC IMPORTED)
    set_target_properties(aom PROPERTIES IMPORTED_LOCATION "${AVIF_PREBUILT_DIR}/libaom.a")

    add_library(imageshare-avif SHARED
        imageshare_avif.cpp
    )

    target_include_directories(imageshare-avif PRIVATE ${AVIF_INCLUDE_DIR})
    target_link_libraries(imageshare-avif
        avif
        aom
        log
        jnigraphics
    )

    target_compile_options(imageshare-avif PRIVATE -fvisibility=hidden -O2)
    target_link_options(imageshare-avif PRIVATE
        "-Wl,-z,max-page-size=16384"
    )
endif()

if(IMAGESHARE_BUILD_JPEG)
    set(PREBUILT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/prebuilts/${ANDROID_ABI}")
    set(INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include")

    if(NOT EXISTS "${PREBUILT_DIR}/libturbojpeg.a")
        message(FATAL_ERROR
            "libturbojpeg prebuilt missing for ${ANDROID_ABI}. "
            "Place libturbojpeg.a at ${PREBUILT_DIR}/libturbojpeg.a "
            "and turbojpeg.h at ${INCLUDE_DIR}/turbojpeg.h. "
            "See prebuilts/README.md for sourcing instructions."
        )
    endif()

    if(NOT EXISTS "${INCLUDE_DIR}/turbojpeg.h")
        message(FATAL_ERROR
            "turbojpeg.h missing. Place the libjpeg-turbo 3.0.4 header at "
            "${INCLUDE_DIR}/turbojpeg.h. See prebuilts/README.md for sourcing instructions."
        )
    endif()

    add_library(turbojpeg STATIC IMPORTED)
    set_target_properties(turbojpeg PROPERTIES IMPORTED_LOCATION "${PREBUILT_DIR}/libturbojpeg.a")

    add_library(imageshare-jpeg SHARED
        imageshare_jpeg.cpp
    )

    target_include_directories(imageshare-jpeg PRIVATE ${INCLUDE_DIR})
    target_link_libraries(imageshare-jpeg
        turbojpeg
        log
        jnigraphics
    )

    target_compile_options(imageshare-jpeg PRIVATE -fvisibility=hidden -O2)
    target_link_options(imageshare-jpeg PRIVATE
        "-Wl,-z,max-page-size=16384"
    )
endif()
