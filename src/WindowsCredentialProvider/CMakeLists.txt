cmake_minimum_required(VERSION 3.25)
project(WinHelloCredentialProvider LANGUAGES CXX)

if(NOT MINGW)
  message(FATAL_ERROR "Use the LLVM-MinGW toolchain in cmake/llvm-mingw-toolchain.cmake.")
endif()

add_library(WinHelloCredentialProvider SHARED CredentialProvider.cpp WinHelloCredentialProvider.def)
target_compile_features(WinHelloCredentialProvider PRIVATE cxx_std_20)
target_compile_definitions(WinHelloCredentialProvider PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN _WIN32_WINNT=0x0A00 NTDDI_VERSION=0x0A000000)
target_compile_options(WinHelloCredentialProvider PRIVATE -fno-exceptions -fno-rtti -fno-stack-protector)
target_link_options(WinHelloCredentialProvider PRIVATE -static)
target_link_libraries(WinHelloCredentialProvider PRIVATE ole32 kernel32 gdi32)
set_target_properties(WinHelloCredentialProvider PROPERTIES PREFIX "")
