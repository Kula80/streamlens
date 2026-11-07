cmake_minimum_required(VERSION 3.20)
project(StreamLensWindows CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# Static C runtime: the camera DLL is loaded into OBS, Zoom, etc., so it mustn't depend on a runtime installer.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
add_compile_definitions(UNICODE _UNICODE WIN32 _CRT_SECURE_NO_WARNINGS)

set(FFMPEG_DIR "" CACHE PATH "FFmpeg shared developer build (folder with include/, lib/, bin/)")
set(WEBVIEW2_DIR "" CACHE PATH "Microsoft.Web.WebView2 NuGet package, unpacked")
if(NOT FFMPEG_DIR OR NOT WEBVIEW2_DIR)
    message(FATAL_ERROR "Set FFMPEG_DIR and WEBVIEW2_DIR (see build.cmd)")
endif()

# --- StreamLens Camera (DirectShow virtual camera, from softcam) ---------------------------------
file(GLOB BASECLASSES camera/baseclasses/*.cpp)
add_library(baseclasses STATIC ${BASECLASSES})
target_include_directories(baseclasses PUBLIC camera/baseclasses)

file(GLOB SOFTCAMCORE camera/softcamcore/*.cpp)
add_library(softcamcore STATIC ${SOFTCAMCORE})
target_include_directories(softcamcore PUBLIC camera camera/baseclasses)
target_link_libraries(softcamcore PUBLIC baseclasses)

add_library(StreamLensCamera SHARED camera/streamlenscam/softcam.cpp camera/streamlenscam/softcam.def)
target_include_directories(StreamLensCamera PUBLIC camera/streamlenscam)
target_link_libraries(StreamLensCamera PRIVATE softcamcore strmiids winmm ole32 oleaut32 uuid advapi32 user32)

# --- Engine: receiver, right-back screen, camera feed ----------------------------------------------
add_library(engine STATIC receiver/Engine.cpp receiver/Receiver.cpp receiver/Slate.cpp)
target_include_directories(engine PUBLIC receiver ${FFMPEG_DIR}/include)
target_link_directories(engine PUBLIC ${FFMPEG_DIR}/lib)
target_link_libraries(engine PUBLIC StreamLensCamera avformat avcodec avutil swscale gdiplus shell32 ole32)

# --- StreamLens.exe: the app with a window ----------------------------------------------------------
add_executable(StreamLens WIN32 receiver/App.cpp app/streamlens.rc)
target_include_directories(StreamLens PRIVATE ${WEBVIEW2_DIR}/build/native/include)
target_link_libraries(StreamLens PRIVATE engine ${WEBVIEW2_DIR}/build/native/x64/WebView2LoaderStatic.lib
    iphlpapi ws2_32 oleaut32 shlwapi version)

# --- streamlens-cli.exe: same engine, prints to a console (tests, troubleshooting) -----------------
add_executable(streamlens-cli receiver/main.cpp)
target_link_libraries(streamlens-cli PRIVATE engine)

# The app needs FFmpeg's DLLs and its page next to it.
add_custom_command(TARGET StreamLens POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${FFMPEG_DIR}/bin/avformat-63.dll ${FFMPEG_DIR}/bin/avcodec-63.dll ${FFMPEG_DIR}/bin/avutil-61.dll
        ${FFMPEG_DIR}/bin/swscale-10.dll ${FFMPEG_DIR}/bin/swresample-7.dll
        $<TARGET_FILE_DIR:StreamLens>
    COMMAND ${CMAKE_COMMAND} -E copy_directory ${CMAKE_SOURCE_DIR}/app/ui $<TARGET_FILE_DIR:StreamLens>/ui)
