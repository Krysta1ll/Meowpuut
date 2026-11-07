cmake_minimum_required(VERSION 3.22.1)
project(MeowpuutAndroid LANGUAGES CXX)

add_subdirectory(../../../../Core core)
set_target_properties(MeowpuutCore PROPERTIES POSITION_INDEPENDENT_CODE ON)
add_library(meowpuut SHARED Bridge.cpp)
target_link_libraries(meowpuut PRIVATE MeowpuutCore android)
