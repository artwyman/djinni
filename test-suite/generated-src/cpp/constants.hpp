// AUTOGENERATED FILE - DO NOT MODIFY!
// This file generated by Djinni from constants.djinni

#pragma once

#include <cstdint>
#include <experimental/optional>
#include <string>
#include <utility>

struct Constants final {

    static bool const BOOL_CONSTANT;

    static int8_t const I8_CONSTANT;

    static int16_t const I16_CONSTANT;

    static int32_t const I32_CONSTANT;

    static int64_t const I64_CONSTANT;

    static double const F64_CONSTANT;

    static std::string const STRING_CONSTANT;

    static std::experimental::optional<int32_t> const OPTIONAL_INTEGER_CONSTANT;

    static Constants const OBJECT_CONSTANT;
    int32_t some_integer;
    std::string some_string;

    Constants(int32_t some_integer,
              std::string some_string)
    : some_integer(std::move(some_integer))
    , some_string(std::move(some_string))
    {}
};
