cmake_minimum_required(VERSION 3.18)

project(fcitx5-android VERSION ${VERSION_NAME})

# For reproducible build
add_link_options("LINKER:--hash-style=gnu,--build-id=none")

# prefab dependency
find_package(fcitx5 REQUIRED CONFIG)
get_target_property(FCITX5_CMAKE_MODULES fcitx5::cmake INTERFACE_INCLUDE_DIRECTORIES)
set(CMAKE_MODULE_PATH ${FCITX5_CMAKE_MODULES} ${CMAKE_MODULE_PATH})

find_package(Fcitx5Core MODULE)
find_package(Fcitx5Module MODULE)
find_package(ECM MODULE)

include("${FCITX_INSTALL_CMAKECONFIG_DIR}/Fcitx5Utils/Fcitx5CompilerSettings.cmake")

add_subdirectory(po)
add_subdirectory(androidfrontend)
add_subdirectory(androidnotification)

# fcitx5-rime addon, built from source with prebuilt static librime
# (adapted from plugin/rime/src/main/cpp/CMakeLists.txt)
set(RIME_PLUGIN_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../plugin/rime/src/main/cpp")
set(Rime_VERSION "1.12.0")
set(Rime_dependencies
        log # glog links to android log
        "${PREBUILT_DIR}/glog/${ANDROID_ABI}/lib/libglog.a"
        "${PREBUILT_DIR}/leveldb/${ANDROID_ABI}/lib/libleveldb.a"
        "${PREBUILT_DIR}/lua/${ANDROID_ABI}/lib/liblua_static.a"
        "${PREBUILT_DIR}/marisa/${ANDROID_ABI}/lib/libmarisa.a"
        "${PREBUILT_DIR}/opencc/${ANDROID_ABI}/lib/libopencc.a"
        "${PREBUILT_DIR}/yaml-cpp/${ANDROID_ABI}/lib/libyaml-cpp.a"
        )

add_library(Rime_static STATIC IMPORTED)
set_target_properties(Rime_static PROPERTIES
        IMPORTED_LOCATION "${PREBUILT_DIR}/librime/${ANDROID_ABI}/lib/librime.a"
        INTERFACE_INCLUDE_DIRECTORIES "${PREBUILT_DIR}/librime/${ANDROID_ABI}/include"
        INTERFACE_LINK_LIBRARIES "${Rime_dependencies}"
        )

# https://github.com/rime/librime/blob/1.9.0/src/rime_api.h#L663
# https://stackoverflow.com/questions/805555/ld-linker-question-the-whole-archive-option
# https://cmake.org/cmake/help/latest/manual/cmake-generator-expressions.7.html#link-features
set(RIME_TARGET $<LINK_LIBRARY:WHOLE_ARCHIVE,Rime_static>)
set(RIME_DATA_DIR "${CMAKE_INSTALL_DATADIR}/rime-data")
add_subdirectory("${RIME_PLUGIN_CPP_DIR}/fcitx5-rime" fcitx5-rime)

# install rime data (same list as plugin/rime prebuilt-assets)
install(FILES
        "${RIME_PLUGIN_CPP_DIR}/default.yaml"
        "${RIME_PLUGIN_CPP_DIR}/rime-essay/essay.txt"
        "${RIME_PLUGIN_CPP_DIR}/rime-prelude/key_bindings.yaml"
        "${RIME_PLUGIN_CPP_DIR}/rime-prelude/punctuation.yaml"
        "${RIME_PLUGIN_CPP_DIR}/rime-prelude/symbols.yaml"
        "${RIME_PLUGIN_CPP_DIR}/rime-luna-pinyin/luna_pinyin_fluency.schema.yaml"
        "${RIME_PLUGIN_CPP_DIR}/rime-luna-pinyin/luna_pinyin_simp.schema.yaml"
        "${RIME_PLUGIN_CPP_DIR}/rime-luna-pinyin/luna_pinyin_tw.schema.yaml"
        "${RIME_PLUGIN_CPP_DIR}/rime-luna-pinyin/luna_pinyin.dict.yaml"
        "${RIME_PLUGIN_CPP_DIR}/rime-luna-pinyin/luna_pinyin.schema.yaml"
        "${RIME_PLUGIN_CPP_DIR}/rime-luna-pinyin/luna_quanpin.schema.yaml"
        "${RIME_PLUGIN_CPP_DIR}/rime-luna-pinyin/pinyin.yaml"
        "${RIME_PLUGIN_CPP_DIR}/rime-stroke/stroke.dict.yaml"
        "${RIME_PLUGIN_CPP_DIR}/rime-stroke/stroke.schema.yaml"
        DESTINATION "${FCITX_INSTALL_DATADIR}/rime-data"
        COMPONENT prebuilt-assets
        )

# prebuilt libuv
set(libuv_DIR "${PREBUILT_DIR}/libuv/${ANDROID_ABI}/lib/cmake/libuv")
find_package(libuv)


add_library(native-lib SHARED native-lib.cpp androidaddonloader/androidaddonloader.cpp)
target_link_libraries(native-lib
        log
        libuv::uv_a
        Fcitx5::Utils
        Fcitx5::Config
        Fcitx5::Core
        Fcitx5::Module::QuickPhrase
        Fcitx5::Module::Clipboard
        )

# copy module libraries from dependency lib
add_custom_target(copy-fcitx5-modules
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
        $<TARGET_PROPERTY:fcitx5::clipboard,IMPORTED_LOCATION>
        $<TARGET_PROPERTY:fcitx5::quickphrase,IMPORTED_LOCATION>
        ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
        COMMENT "Copying fcitx5 module libraries to :app"
        )

# install prebuilt assets
install(DIRECTORY "${PREBUILT_DIR}/opencc/data/" DESTINATION "${FCITX_INSTALL_DATADIR}/opencc" COMPONENT prebuilt-assets)
