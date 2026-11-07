cmake_minimum_required(VERSION 3.20)
project(StreamLensWindows CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# Static C runtime: the camera DLL is loaded into OBS, Zoom, etc., so it mustn't depend on a runtime installer.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
add_compile_definitions(UNICODE _UNICODE WIN32 _CRT_SECURE_NO_WARNINGS)

set(FFMPEG_DIR "" CACHE PATH "FFmpeg shared developer build (folder with include/, lib/, bin/)")
if(NOT FFMPEG_DIR)
    message(FATAL_ERROR "Set FFMPEG_DIR to the FFmpeg shared developer build")
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

# --- StreamLens receiver ---------------------------------------------------------------------------
add_executable(StreamLens receiver/main.cpp receiver/Receiver.cpp)
target_include_directories(StreamLens PRIVATE ${FFMPEG_DIR}/include)
target_link_directories(StreamLens PRIVATE ${FFMPEG_DIR}/lib)
target_link_libraries(StreamLens PRIVATE StreamLensCamera avformat avcodec avutil swscale)

# The receiver needs FFmpeg's DLLs next to it.
add_custom_command(TARGET StreamLens POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${FFMPEG_DIR}/bin/avformat-63.dll ${FFMPEG_DIR}/bin/avcodec-63.dll ${FFMPEG_DIR}/bin/avutil-61.dll
        ${FFMPEG_DIR}/bin/swscale-10.dll ${FFMPEG_DIR}/bin/swresample-7.dll
        $<TARGET_FILE_DIR:StreamLens>)
