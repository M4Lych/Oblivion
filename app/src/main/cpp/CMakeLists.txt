cmake_minimum_required(VERSION 3.22.1)
project(oblivion_engine LANGUAGES CXX)

add_library(oblivion_engine SHARED oblivion_engine.cpp)
target_compile_features(oblivion_engine PRIVATE cxx_std_17)

find_library(log-lib log REQUIRED)
find_library(dl-lib dl REQUIRED)
target_link_libraries(oblivion_engine PRIVATE ${log-lib} ${dl-lib})
