# Bind the map to the unstripped output immediately after the linker finishes.
foreach(input NATIVE_LIBRARY NATIVE_MAP)
    if(NOT DEFINED ${input} OR NOT EXISTS "${${input}}")
        message(FATAL_ERROR "Missing link audit input: ${input}")
    endif()
endforeach()
file(SHA256 "${NATIVE_LIBRARY}" native_sha256)
file(SHA256 "${NATIVE_MAP}" map_sha256)
file(WRITE "${NATIVE_MAP}.binding" "${native_sha256}\n${map_sha256}\n")
