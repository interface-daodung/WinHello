if(NOT DEFINED ENV{LLVM_MINGW_ROOT})
  message(FATAL_ERROR "Set LLVM_MINGW_ROOT to the LLVM-MinGW installation directory.")
endif()

file(TO_CMAKE_PATH "$ENV{LLVM_MINGW_ROOT}" LLVM_MINGW_ROOT)

set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_C_COMPILER "${LLVM_MINGW_ROOT}/bin/clang.exe")
set(CMAKE_CXX_COMPILER "${LLVM_MINGW_ROOT}/bin/clang++.exe")
set(CMAKE_RC_COMPILER "${LLVM_MINGW_ROOT}/bin/llvm-windres.exe")
set(CMAKE_C_COMPILER_TARGET x86_64-w64-windows-gnu)
set(CMAKE_CXX_COMPILER_TARGET x86_64-w64-windows-gnu)
