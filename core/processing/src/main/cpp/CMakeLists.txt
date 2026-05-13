cmake_minimum_required(VERSION 3.22.1)
project(imageshare_jpeg)

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
