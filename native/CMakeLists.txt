cmake_minimum_required(VERSION 3.18)
# 必须同时声明 CXX：源文件是 .c，但链接必须用 C++ 驱动（见下面的 LINKER_LANGUAGE CXX）。
# 只声明 C 的话 CMake 不知道 C++ 链接器是谁，生成的 ninja 链接规则会退化成
# `cmd.exe /C "cd . && cd ."`（空操作），构建「成功」却不出产物——这个坑极易误判为构建通过。
project(direct_rime C CXX)

#
# 直连 librime 的桥接层 —— 标准 CMake 构建。
#
# 为什么还要有这份 CMakeLists（native/build.sh 已经能出 .so）：
#   build.sh 是「绕开 Gradle 的验证用短路径」，用 NDK clang 直接编，够证明
#   「直连能否链接」，但它是孤立产物，进不了 APK。要真正接进应用（这是去掉
#   fcitx5 的必经一步），必须走 CMake —— 因为主仓库的 native 构建就是 CMake
#   （app/src/main/cpp/CMakeLists.txt 用 add_library + target_link_libraries），
#   只有同构才能把 direct_rime 作为 target 并进去、由 Gradle 自动打进 APK。
#
# 两个必须保留的口径（照抄 build.sh 里踩出来的结论，别改）：
#   1. 源文件按 .c 走 C 编译器编译（jni.h 的 C 风格 (*env)->Foo(env, ...) 写法），
#      但链接必须用 C++ 驱动 —— librime 依赖 C++ 运行时，用 C 驱动会报
#        undefined symbol: std::__ndk1::ios_base::init(void*)
#      所以下面设 LINKER_LANGUAGE CXX。这是本文件最关键的一行。
#   2. 必须补 -llog -landroid：glog 的 AlsoErrorWrite 调 __android_log_write，
#      缺了会报 undefined symbol: __android_log_write。
#
# 用法见 native/build-cmake.sh。
#

set(CMAKE_C_STANDARD 11)

# PREBUILT_DIR 由外部传入（主仓库构建时由 NativeBaseConventionPlugin 注入同名变量，
# 见 build-logic/convention/src/main/kotlin/NativeBaseConventionPlugin.kt:30，口径一致）
if(NOT PREBUILT_DIR)
    message(FATAL_ERROR "PREBUILT_DIR 未设置。示例：-DPREBUILT_DIR=<...>/prebuilt")
endif()
# 目录布局是 prebuilt/<库名>/<abi>/lib/*.a —— ABI 层在库名「之后」，
# 与主仓库 app/src/main/cpp/CMakeLists.txt:39 的
#   "${PREBUILT_DIR}/librime/${ANDROID_ABI}/lib/librime.a" 同构，不要写成 prebuilt/<abi>/<库名>。
set(PRE "${PREBUILT_DIR}")
set(ABI "${ANDROID_ABI}")

set(DIRECT_RIME_SOURCES
        rime_bridge.c
        keymap.c
        )

add_library(direct_rime SHARED ${DIRECT_RIME_SOURCES})

target_include_directories(direct_rime PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${PRE}/librime/${ABI}/include
        ${PRE}/boost/${ABI}/include
        )

# ---- 预编译静态库。顺序照抄 build.sh 里验证过能连接的次序（GNU ld 单次遍历语义）----
set(DIRECT_RIME_STATIC_LIBS
        ${PRE}/librime/${ABI}/lib/librime.a
        ${PRE}/glog/${ABI}/lib/libglog.a
        ${PRE}/leveldb/${ABI}/lib/libleveldb.a
        ${PRE}/marisa/${ABI}/lib/libmarisa.a
        ${PRE}/opencc/${ABI}/lib/libopencc.a
        ${PRE}/yaml-cpp/${ABI}/lib/libyaml-cpp.a
        ${PRE}/lua/${ABI}/lib/liblua_static.a
        ${PRE}/zstd/${ABI}/lib/libzstd.a
        ${PRE}/boost/${ABI}/lib/libboost_container.a
        ${PRE}/boost/${ABI}/lib/libboost_iostreams.a
        ${PRE}/boost/${ABI}/lib/libboost_random.a
        ${PRE}/libiconv/${ABI}/lib/libiconv.a
        ${PRE}/libintl-lite/${ABI}/lib/libintl.a
        )

# 缺文件要在配置期就炸，而不是等到链接期报一堆 undefined symbol 再去猜
foreach(_lib IN LISTS DIRECT_RIME_STATIC_LIBS)
    if(NOT EXISTS "${_lib}")
        message(FATAL_ERROR "预编译库不存在：${_lib}（检查 PREBUILT_DIR 与 ANDROID_ABI）")
    endif()
endforeach()

# 关键：C 源文件 + C++ 驱动链接
set_target_properties(direct_rime PROPERTIES LINKER_LANGUAGE CXX)

# AGP 只自动打包它从 android_gradle_build.json 认识的 target，add_subdirectory 进来的
# 外部 target 不会被自动编译进 APK。这里把输出目录直接设成 AGP 的 obj 目录
# （该变量由 AGP 传入，见 app/.cxx/.../CMakeCache.txt 的 CMAKE_LIBRARY_OUTPUT_DIRECTORY），
# 这样产物落进 AGP 打包时扫描的目录。
# 注意：不能用 add_custom_command(TARGET direct_rime POST_BUILD ...) 从父目录复制 ——
# 主仓库 lib/fcitx5 的 HookAddCustomCommand.cmake 会拦截并报
#   "TARGET 'direct_rime' was not created in this directory"。
if(CMAKE_LIBRARY_OUTPUT_DIRECTORY)
    set_target_properties(direct_rime PROPERTIES
            LIBRARY_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
endif()

# STATIC_LIBCXX=1：静态链入 libc++，产物不再 NEEDED libc++_shared.so。
# 代价约 +8 MB（6.9 MB → 15.1 MB），收益是只给 .so 也能加载，不必确认 APK 里
# 有没有随附 libc++_shared.so。接进 APK 时推荐打开，因为 APK 打不打这个运行时
# 取决于打包配置，而它是 dlopen 失败的硬门槛。
if(STATIC_LIBCXX)
    target_link_options(direct_rime PRIVATE -static-libstdc++)
endif()

target_link_libraries(direct_rime
        ${DIRECT_RIME_STATIC_LIBS}
        log
        android
        )
